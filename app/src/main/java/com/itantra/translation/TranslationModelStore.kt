package com.itantra.translation

import com.itantra.packs.PackDownloader
import com.itantra.packs.PackException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream

data class ModelFile(val name: String, val url: String, val size: Long, val sha256: String)

/** The shared multilingual model is separate from per-language recognition and voice packs. */
object TranslationModelSpec {
    const val id = "nllb-200-distilled-600m-int8-v1"
    const val revision = "261c31d1a5732c67cdd16d80e8d6088507c7ccea"
    const val license = "CC-BY-NC-4.0"
    private const val BASE_URL = "https://huggingface.co/Xenova/nllb-200-distilled-600M/resolve/$revision/"

    val files = listOf(
        ModelFile(
            "encoder_model_quantized.onnx", BASE_URL + "onnx/encoder_model_quantized.onnx", 419_120_483L,
            "5cde664eacba07a62f198857ec6c06e09572b1ebb77c8137f1fa99ac604a3a28",
        ),
        ModelFile(
            "decoder_model_merged_quantized.onnx", BASE_URL + "onnx/decoder_model_merged_quantized.onnx", 475_505_771L,
            "dd66608c2a4194e78f95548fa0e64f24302303698c5b09fa8e1f9e16ec00676b",
        ),
        ModelFile(
            "sentencepiece.bpe.model", BASE_URL + "sentencepiece.bpe.model", 4_852_054L,
            "14bb8dfb35c0ffdea7bc01e56cea38b9e3d5efcdcb9c251d6b40538e1aab555a",
        ),
    )
    val totalBytes: Long = files.sumOf { it.size }
}

@Serializable
private data class InstalledModelFile(val name: String, val size: Long, val sha256: String)

@Serializable
private data class TranslationInstallation(
    val format: Int,
    val id: String,
    val revision: String,
    val files: List<InstalledModelFile>,
    val source: String = "https://huggingface.co/facebook/nllb-200-distilled-600M",
    val license: String = TranslationModelSpec.license,
    @SerialName("license_url") val licenseUrl: String = "https://creativecommons.org/licenses/by-nc/4.0/",
    val attribution: String = "NLLB Team, Meta AI; quantized ONNX conversion by Xenova.",
    @SerialName("intended_use") val intendedUse: String = "Noncommercial research/evaluation; upstream model is not released for production deployment.",
)

/**
 * Installs a checksum-pinned, shared translation model for use without any network connection.
 *
 * A ZIP has the model files at its root, optionally accompanied by model.json, LICENSE and README.md.
 * Every model is verified before activation. The active pointer is replaced atomically, so an incomplete
 * download, cancellation, corrupt import or interrupted install leaves the previous installation active.
 * [ready] validates the small manifest and file lengths; it never hashes the large model on UI refreshes.
 */
class TranslationModelStore(
    private val root: File,
    private val workDir: File,
    spec: List<ModelFile> = TranslationModelSpec.files,
) {
    private val expectedFiles = spec.map { it.copy(sha256 = it.sha256.lowercase()) }
    private val expectedByName = expectedFiles.associateBy { it.name }
    private val totalBytes = expectedFiles.sumOf { it.size }
    private val downloads = File(workDir, TranslationModelSpec.id)
    private val installation = TranslationInstallation(
        FORMAT, TranslationModelSpec.id, TranslationModelSpec.revision,
        expectedFiles.map { InstalledModelFile(it.name, it.size, it.sha256) },
    )

    init {
        require(expectedFiles.isNotEmpty() && expectedFiles.size == expectedByName.size) { "Model files must be unique" }
        require(expectedFiles.all {
            it.name.matches(SAFE_NAME) && it.name != "." && it.name != ".." && it.name !in METADATA_FILES &&
                it.size > 0 && it.sha256.matches(SHA256)
        }) { "Invalid model specification" }
        require(totalBytes > 0 && expectedFiles.all { it.size <= totalBytes }) { "Model size overflow" }
        require(root.mkdirs() || root.isDirectory) { "Cannot create translation model directory" }
        require(downloads.mkdirs() || downloads.isDirectory) { "Cannot create translation download directory" }
    }

    /** The verified model folder, or null when installation is absent or damaged. */
    val modelDir: File?
        get() = synchronized(this) { installedDir() }

    @Synchronized
    fun ready(): Boolean = installedDir() != null

    /** Download once during setup; the downloaded model is subsequently usable entirely offline. */
    @Synchronized
    fun download(progress: (Long, Long) -> Boolean) {
        if (ready()) {
            if (!progress(totalBytes, totalBytes)) throw PackDownloader.Cancelled()
            return
        }
        if (!progress(0, totalBytes)) throw PackDownloader.Cancelled()
        val downloader = PackDownloader(downloads)
        val downloaded = mutableListOf<File>()
        var completed = 0L
        for (model in expectedFiles) {
            // Completed files survive cancellation of a later file as well as .part Range downloads.
            val cached = File(downloads, model.name)
            val file = if (cached.isFile && cached.length() == model.size && PackDownloader.sha256(cached) == model.sha256) {
                cached
            } else {
                downloader.download(model.url, model.name, model.size, model.sha256) { bytes, _ ->
                    progress(completed + bytes, totalBytes)
                }
            }
            downloaded += file
            completed += model.size
            if (!progress(completed, totalBytes)) throw PackDownloader.Cancelled()
        }
        withStaging { staging ->
            downloaded.zip(expectedFiles).forEach { (source, model) ->
                val target = File(staging, model.name)
                if (!source.renameTo(target)) {
                    source.inputStream().use { copyVerified(it, target, model.size, model.sha256) }
                    source.delete()
                }
            }
            writeManifest(staging)
            activate(staging)
        }
    }

    /** Imports a pack copied from another device; no internet access is needed. */
    @Synchronized
    fun importZip(input: InputStream, shouldContinue: () -> Boolean = { true }) {
        if (!shouldContinue()) throw PackDownloader.Cancelled()
        withStaging { staging ->
            val seen = mutableSetOf<String>()
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    if (!shouldContinue()) throw PackDownloader.Cancelled()
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    if (!name.matches(SAFE_NAME) || entry.isDirectory) throw PackException("Unsafe path in translation ZIP: $name")
                    if (!seen.add(name)) throw PackException("Duplicate file in translation ZIP: $name")
                    val model = expectedByName[name]
                    val limit = model?.size ?: if (name in METADATA_FILES) MAX_METADATA_BYTES else {
                        throw PackException("Unexpected file in translation ZIP: $name")
                    }
                    if (entry.size > limit) throw PackException("$name is larger than expected")
                    copyVerified(zip, File(staging, name), limit, model?.sha256, shouldContinue)
                    zip.closeEntry()
                }
            }
            val missing = expectedByName.keys - seen
            if (missing.isNotEmpty()) throw PackException("Translation ZIP is missing ${missing.first()}")
            val suppliedManifest = File(staging, MANIFEST)
            if (suppliedManifest.exists() && !matchesManifest(suppliedManifest)) {
                throw PackException("Translation ZIP manifest does not match the supported model")
            }
            writeManifest(staging)
            if (!shouldContinue()) throw PackDownloader.Cancelled()
            activate(staging)
        }
    }

    private fun copyVerified(input: InputStream, target: File, limit: Long, expectedSha: String?, shouldContinue: () -> Boolean = { true }) {
        val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        target.outputStream().use { output ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                if (!shouldContinue()) throw PackDownloader.Cancelled()
                val n = input.read(buffer)
                if (n < 0) break
                bytes += n
                if (bytes > limit) throw PackException("${target.name} is larger than expected")
                digest.update(buffer, 0, n)
                output.write(buffer, 0, n)
            }
        }
        if (expectedSha != null) {
            if (bytes != limit) throw PackException("${target.name} has the wrong size")
            val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
            if (actualSha != expectedSha) throw PackException("${target.name} failed its SHA-256 check")
        }
    }

    private fun installedDir(): File? = runCatching {
        val generation = readActiveName() ?: return null
        val dir = File(root, generation)
        if (!dir.isDirectory || dir.canonicalFile.parentFile != root.canonicalFile) return null
        if (!matchesManifest(File(dir, MANIFEST))) return null
        if (!expectedFiles.all { model ->
            val file = File(dir, model.name)
            file.isFile && file.length() == model.size && file.canonicalFile.parentFile == dir.canonicalFile
        }) return null
        dir
    }.getOrNull()

    private fun matchesManifest(file: File): Boolean = runCatching {
        if (!file.isFile || file.length() !in 1..MAX_METADATA_BYTES) return false
        val manifest = JSON.decodeFromString(TranslationInstallation.serializer(), file.readText())
        manifest.format == FORMAT && manifest.id == installation.id && manifest.revision == installation.revision &&
            manifest.files.size == expectedFiles.size && manifest.files.associateBy { it.name } == installation.files.associateBy { it.name }
    }.getOrDefault(false)

    private fun writeManifest(staging: File) {
        File(staging, MANIFEST).writeText(JSON.encodeToString(TranslationInstallation.serializer(), installation))
    }

    private fun readActiveName(): String? = runCatching {
        val pointer = File(root, ACTIVE_POINTER)
        if (!pointer.isFile || pointer.length() !in 1..MAX_POINTER_BYTES) return null
        pointer.readText(Charsets.UTF_8).takeIf { it.matches(GENERATION_NAME) }
    }.getOrNull()

    private fun withStaging(block: (File) -> Unit) {
        val staging = File(root, GENERATION_PREFIX + UUID.randomUUID())
        if (!staging.mkdir()) throw IOException("Cannot create translation model staging directory")
        try {
            block(staging)
        } finally {
            // Never remove a directory already made active by the atomic pointer update.
            if (readActiveName() != staging.name) staging.deleteRecursively()
        }
    }

    private fun activate(staging: File) {
        // An identical, already verified pinned installation needs no replacement or additional storage.
        val current = installedDir()
        if (current != null) return
        val oldName = readActiveName()
        val pendingPointer = File(root, ".active-${UUID.randomUUID()}")
        try {
            FileOutputStream(pendingPointer).use { output ->
                output.write(staging.name.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            Files.move(
                pendingPointer.toPath(), File(root, ACTIVE_POINTER).toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            pendingPointer.delete()
        }
        if (oldName != null && oldName.matches(GENERATION_NAME) && oldName != staging.name) {
            File(root, oldName).deleteRecursively()
        }
    }

    companion object {
        private const val FORMAT = 1
        private const val MANIFEST = "model.json"
        private const val ACTIVE_POINTER = "active-model"
        private const val GENERATION_PREFIX = ".model-"
        private const val MAX_METADATA_BYTES = 65_536L
        private const val MAX_POINTER_BYTES = 128L
        private val METADATA_FILES = setOf(MANIFEST, "LICENSE", "README.md")
        private val SAFE_NAME = Regex("[A-Za-z0-9_.-]+")
        private val SHA256 = Regex("[0-9a-f]{64}")
        private val GENERATION_NAME = Regex("\\.model-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
