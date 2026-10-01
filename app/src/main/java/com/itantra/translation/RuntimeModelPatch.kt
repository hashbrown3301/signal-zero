package com.itantra.translation

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal data class ModelEdit(val offset: Long, val remove: Long, val insert: ByteArray)
internal data class ModelPatchSpec(
    val sourceSize: Long, val sourceSha256: String,
    val targetSize: Long, val targetSha256: String, val edits: List<ModelEdit>,
)

/**
 * Applies a tiny, revision-specific graph patch without loading model weights into Java's heap.
 * Hashes every source byte (including deleted ranges) and every output byte before activation.
 * The original downloaded model remains unchanged. Derived weights keep the upstream license.
 */
internal object RuntimeModelPatch {
    @Synchronized
    fun prepare(source: File, target: File, spec: ModelPatchSpec): File {
        validate(spec)
        require(source.canonicalFile != target.canonicalFile) { "Derived model must preserve the original" }
        if (target.isFile && target.length() == spec.targetSize && sha256(target) == spec.targetSha256) return target
        check(source.isFile && source.length() == spec.sourceSize) { "Translation decoder has the wrong size; reinstall its pack" }
        val parent = checkNotNull(target.parentFile)
        check(parent.mkdirs() || parent.isDirectory) { "Cannot create derived translation model directory" }
        // A process killed during first preparation can leave a temporary copy behind.
        parent.listFiles { file -> file.name.startsWith(".translation-") && file.name.endsWith(".partial") }
            ?.forEach { it.delete() }
        check(parent.usableSpace >= spec.targetSize + 64 * 1024 * 1024L) {
            "Free at least 550 MB more storage to prepare offline translation"
        }
        val temporary = File.createTempFile(".translation-", ".partial", parent)
        try {
            val sourceHash = MessageDigest.getInstance("SHA-256")
            val targetHash = MessageDigest.getInstance("SHA-256")
            source.inputStream().buffered().use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var position = 0L
                    for (edit in spec.edits) {
                        transfer(input, output, edit.offset - position, buffer, sourceHash, targetHash)
                        transfer(input, null, edit.remove, buffer, sourceHash, targetHash)
                        output.write(edit.insert)
                        targetHash.update(edit.insert)
                        position = edit.offset + edit.remove
                    }
                    transfer(input, output, spec.sourceSize - position, buffer, sourceHash, targetHash)
                    check(input.read() == -1) { "Translation decoder changed during preparation" }
                    output.fd.sync()
                }
            }
            check(sourceHash.digest().hex() == spec.sourceSha256) {
                "Translation decoder failed its SHA-256 check; reinstall the pack"
            }
            check(temporary.length() == spec.targetSize && targetHash.digest().hex() == spec.targetSha256) {
                "Derived translation decoder failed verification"
            }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            return target
        } finally {
            temporary.delete()
        }
    }

    private fun transfer(
        input: InputStream, output: OutputStream?, size: Long, buffer: ByteArray,
        sourceHash: MessageDigest, targetHash: MessageDigest,
    ) {
        var remaining = size
        while (remaining > 0) {
            val count = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
            if (count < 0) throw IOException("Translation decoder ended during preparation")
            if (count == 0) continue
            sourceHash.update(buffer, 0, count)
            if (output != null) { output.write(buffer, 0, count); targetHash.update(buffer, 0, count) }
            remaining -= count
        }
    }

    private fun validate(spec: ModelPatchSpec) {
        require(spec.sourceSize > 0 && spec.targetSize > 0)
        require(listOf(spec.sourceSha256, spec.targetSha256).all { it.matches(Regex("[0-9a-f]{64}")) })
        var end = 0L
        var size = spec.sourceSize
        for (edit in spec.edits) {
            require(edit.offset >= end && edit.remove >= 0 && edit.offset <= spec.sourceSize - edit.remove) { "Invalid model patch range" }
            end = edit.offset + edit.remove
            size = Math.addExact(Math.subtractExact(size, edit.remove), edit.insert.size.toLong())
        }
        require(size == spec.targetSize) { "Model patch size does not match its specification" }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().hex()
    }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
}
