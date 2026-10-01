package com.itantra.translation

import com.itantra.comm.Language
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Saving an entry means the user has explicitly reviewed this directional translation. */
@Serializable
data class ReviewedPhrase(
    val id: String,
    val sourceIso: String,
    val targetIso: String,
    val sourceText: String,
    val targetText: String,
    val updatedAt: Long,
)

class PhrasebookException(message: String, cause: Throwable? = null) : IOException(message, cause)

@Serializable
private data class PhrasebookDocument(val format: Int, val revision: Long, val entries: List<ReviewedPhrase>)

/**
 * A small user-owned phrasebook, independent of native models and hardware capability.
 *
 * The constructor performs no I/O. Call initial lookup/listing and mutations on an I/O dispatcher.
 * Changes become visible only after a complete validated document is atomically committed; corrupt
 * storage is reported rather than silently overwritten. One shared instance serializes all access.
 */
class PhrasebookStore(
    private val file: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Key(val sourceIso: String, val targetIso: String, val text: String)
    private data class Snapshot(val document: PhrasebookDocument, val indexed: Map<Key, ReviewedPhrase>)
    private var snapshot: Snapshot? = null

    val revision: Long
        get() = synchronized(this) { load().document.revision }

    @Synchronized
    fun list(): List<ReviewedPhrase> = load().document.entries
        .sortedWith(compareByDescending<ReviewedPhrase> { it.updatedAt }.thenBy { it.id })

    @Synchronized
    fun find(text: String, source: Language, target: Language): ReviewedPhrase? {
        if (source == target || text.length > MAX_TEXT_CHARS || text.isBlank()) return null
        return load().indexed[Key(source.iso, target.iso, normalize(text))]
    }

    /** Updates the same exact language-scoped source key, preserving its ID; otherwise adds a pair. */
    @Synchronized
    fun upsert(source: Language, target: Language, sourceText: String, targetText: String): ReviewedPhrase {
        if (source == target) throw PhrasebookException("Choose two different languages for a reviewed translation")
        validateText(sourceText)
        validateText(targetText)
        val current = load()
        val key = Key(source.iso, target.iso, normalize(sourceText))
        val previous = current.indexed[key]
        if (previous == null && current.document.entries.size >= MAX_ENTRIES) {
            throw PhrasebookException("Phrasebook is full ($MAX_ENTRIES phrases); delete a phrase before adding another")
        }
        val timestamp = clock()
        if (timestamp < 0) throw PhrasebookException("Invalid phrase update time")
        val entry = ReviewedPhrase(
            previous?.id ?: UUID.randomUUID().toString(), source.iso, target.iso,
            sourceText.trim(), targetText.trim(), maxOf(timestamp, previous?.updatedAt ?: 0),
        )
        val entries = current.document.entries.filter { it.id != entry.id } + entry
        commit(PhrasebookDocument(FORMAT, nextRevision(current.document.revision), entries))
        return entry
    }

    @Synchronized
    fun remove(id: String): Boolean {
        val current = load()
        if (current.document.entries.none { it.id == id }) return false
        commit(current.document.copy(
            revision = nextRevision(current.document.revision), entries = current.document.entries.filter { it.id != id },
        ))
        return true
    }

    private fun load(): Snapshot {
        snapshot?.let { return it }
        val document = if (!file.exists()) {
            PhrasebookDocument(FORMAT, 0, emptyList())
        } else {
            try {
                if (!file.isFile || file.length() > MAX_FILE_BYTES) throw PhrasebookException("Phrasebook file is unreadable or too large")
                val bytes = file.inputStream().buffered().use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        if (output.size() + n > MAX_FILE_BYTES) throw PhrasebookException("Phrasebook file is too large")
                        output.write(buffer, 0, n)
                    }
                    output.toByteArray()
                }
                val text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString()
                JSON.decodeFromString(PhrasebookDocument.serializer(), text)
            } catch (error: PhrasebookException) {
                throw error
            } catch (error: Exception) {
                throw PhrasebookException("Could not read the phrasebook; its saved data has been kept", error)
            }
        }
        return validate(document).also { snapshot = it }
    }

    private fun validate(document: PhrasebookDocument): Snapshot {
        if (document.format != FORMAT) throw PhrasebookException("This phrasebook format is not supported")
        if (document.revision < 0 || document.entries.size > MAX_ENTRIES) throw PhrasebookException("Invalid phrasebook revision or number of entries")
        val ids = mutableSetOf<String>()
        val indexed = mutableMapOf<Key, ReviewedPhrase>()
        for (entry in document.entries) {
            if (!entry.id.matches(SAFE_ID) || !ids.add(entry.id) || entry.updatedAt < 0) throw PhrasebookException("Invalid or duplicate phrasebook entry")
            if (Language.fromIso(entry.sourceIso) == null || Language.fromIso(entry.targetIso) == null || entry.sourceIso == entry.targetIso) {
                throw PhrasebookException("Invalid phrasebook languages")
            }
            validateText(entry.sourceText)
            validateText(entry.targetText)
            val key = Key(entry.sourceIso, entry.targetIso, normalize(entry.sourceText))
            if (indexed.put(key, entry) != null) throw PhrasebookException("Duplicate reviewed phrase for the same languages")
        }
        return Snapshot(document, indexed)
    }

    private fun commit(document: PhrasebookDocument) {
        val candidate = validate(document)
        val bytes = JSON.encodeToString(PhrasebookDocument.serializer(), document).toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_FILE_BYTES) throw PhrasebookException("Phrasebook is too large to save")
        val parent = file.absoluteFile.parentFile ?: throw PhrasebookException("Cannot locate phrasebook storage")
        var temporary: File? = null
        try {
            if (!parent.mkdirs() && !parent.isDirectory) throw IOException("Cannot create phrasebook directory")
            // One owned pending file bounds leftovers if the process dies before the finally block.
            temporary = File(parent, ".phrases-${file.name}.pending")
            if (temporary.canonicalFile.parentFile != parent.canonicalFile || temporary.isDirectory) {
                throw IOException("Unsafe phrasebook pending file")
            }
            FileOutputStream(temporary).use { output -> output.write(bytes); output.fd.sync() }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            snapshot = candidate
        } catch (error: Exception) {
            throw PhrasebookException("Could not save the phrasebook; previous phrases have been kept", error)
        } finally {
            temporary?.delete()
        }
    }

    private fun nextRevision(current: Long): Long = try {
        Math.addExact(current, 1)
    } catch (error: ArithmeticException) {
        throw PhrasebookException("Phrasebook revision limit reached", error)
    }

    private fun validateText(text: String) {
        if (text.isBlank()) throw PhrasebookException("Enter both the original phrase and its reviewed translation")
        if (text.length > MAX_TEXT_CHARS) throw PhrasebookException("Keep each phrase within $MAX_TEXT_CHARS characters")
        var index = 0
        while (index < text.length) {
            val char = text[index]
            if (char.isISOControl() && !char.isWhitespace()) throw PhrasebookException("Phrase contains unsupported control characters")
            if (Character.isHighSurrogate(char)) {
                if (index + 1 >= text.length || !Character.isLowSurrogate(text[index + 1])) throw PhrasebookException("Phrase contains invalid Unicode")
                index++
            } else if (Character.isLowSurrogate(char)) throw PhrasebookException("Phrase contains invalid Unicode")
            index++
        }
    }

    companion object {
        const val MAX_ENTRIES = 200
        const val MAX_TEXT_CHARS = 1000
        const val MAX_FILE_BYTES = 2 * 1024 * 1024
        private const val FORMAT = 1
        private val SAFE_ID = Regex("[A-Za-z0-9_-]{1,64}")
        private val JSON = Json

        /** Whitespace only: never fold case, scripts, accents, punctuation, signs or digits. */
        private fun normalize(text: String): String = buildString {
            var space = false
            for (char in text) {
                if (char.isWhitespace()) {
                    space = isNotEmpty()
                } else {
                    if (space) append(' ')
                    append(char)
                    space = false
                }
            }
        }
    }
}
