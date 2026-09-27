package com.itantra.packs

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream

class PackException(message: String) : Exception(message)

/**
 * Installed language packs: one folder per pack under [root] (`<root>/<id>/pack.json` + files).
 *
 * [install] unzips into a temporary folder, checks the manifest and every file's size and SHA-256, and only
 * then moves the pack into place, so a broken or tampered zip never replaces a working pack.
 * Plain JVM code (no Android), unit-tested on the PC.
 */
class PackStore(private val root: File) {

    init {
        root.mkdirs()
    }

    fun list(): List<PackManifest> =
        root.listFiles().orEmpty()
            .filter { it.isDirectory && !it.name.startsWith(TMP_PREFIX) }
            .mapNotNull { dir -> runCatching { read(dir) }.getOrNull() }
            .sortedWith(compareBy({ it.lang }, { it.kind }))

    fun dir(id: String): File = File(root, id)

    fun get(id: String): PackManifest? = dir(id).takeIf { it.isDirectory }?.let { runCatching { read(it) }.getOrNull() }

    fun delete(id: String): Boolean = dir(id).takeIf { it.isDirectory }?.deleteRecursively() ?: false

    /** Installs a pack zip (as built by scripts/packs/build_pack.py); replaces an installed pack with the same id. */
    fun install(zip: InputStream): PackManifest {
        val tmp = File(root, TMP_PREFIX + UUID.randomUUID())
        try {
            tmp.mkdirs()
            unzip(zip, tmp)
            // The zip holds one top-level folder named after the pack id.
            val folder = tmp.listFiles().orEmpty().singleOrNull { it.isDirectory }
                ?: throw PackException("Zip must contain exactly one pack folder")
            val manifest = read(folder)
            if (folder.name != manifest.id) throw PackException("Folder ${folder.name} doesn't match pack id ${manifest.id}")
            verify(folder, manifest)

            val target = dir(manifest.id)
            val old = File(root, TMP_PREFIX + "old-" + UUID.randomUUID())
            if (target.exists() && !target.renameTo(old)) throw PackException("Couldn't replace ${manifest.id}")
            if (!folder.renameTo(target)) {
                old.renameTo(target)
                throw PackException("Couldn't move ${manifest.id} into place")
            }
            old.deleteRecursively()
            return manifest
        } finally {
            tmp.deleteRecursively()
        }
    }

    /** Removes leftovers of an interrupted install. */
    fun cleanUp() {
        root.listFiles().orEmpty().filter { it.name.startsWith(TMP_PREFIX) }.forEach { it.deleteRecursively() }
    }

    private fun read(dir: File): PackManifest {
        val file = File(dir, "pack.json")
        if (!file.isFile) throw PackException("${dir.name}: no pack.json")
        val manifest = try {
            PackManifest.parse(file.readText())
        } catch (e: Exception) {
            throw PackException("${dir.name}: unreadable pack.json (${e.message})")
        }
        if (manifest.format > PackManifest.SUPPORTED_FORMAT) {
            throw PackException("${manifest.id}: pack format ${manifest.format} is newer than this app supports")
        }
        if (manifest.kind != PackManifest.KIND_SPEAK && manifest.kind != PackManifest.KIND_LISTEN) {
            throw PackException("${manifest.id}: unknown kind ${manifest.kind}")
        }
        return manifest
    }

    private fun verify(dir: File, manifest: PackManifest) {
        val listed = manifest.files.map { it.path }.toSet()
        val present = dir.listFiles().orEmpty().filter { it.isFile }.map { it.name }.toSet() - "pack.json"
        (present - listed).firstOrNull()?.let { throw PackException("${manifest.id}: unexpected file $it") }
        for (f in manifest.files) {
            val file = File(dir, f.path)
            if (!file.isFile) throw PackException("${manifest.id}: missing ${f.path}")
            if (file.length() != f.size) throw PackException("${manifest.id}: ${f.path} has the wrong size")
            if (sha256(file) != f.sha256.lowercase()) throw PackException("${manifest.id}: ${f.path} failed its SHA-256 check")
        }
        listOf(manifest.engine.model, manifest.engine.tokens).forEach {
            if (it !in listed) throw PackException("${manifest.id}: engine file $it isn't in the pack")
        }
    }

    private fun unzip(input: InputStream, dest: File) {
        val base = dest.canonicalFile
        ZipInputStream(input.buffered()).use { zip ->
            var total = 0L
            while (true) {
                val entry = zip.nextEntry ?: break
                val out = File(dest, entry.name).canonicalFile
                // Zip-slip guard: entries must stay inside the temporary folder.
                if (!out.path.startsWith(base.path + File.separator)) throw PackException("Unsafe path in zip: ${entry.name}")
                if (entry.isDirectory) {
                    out.mkdirs()
                    continue
                }
                out.parentFile?.mkdirs()
                out.outputStream().use { os ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = zip.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_UNPACKED_BYTES) throw PackException("Pack is larger than ${MAX_UNPACKED_BYTES / 1_000_000} MB")
                        os.write(buf, 0, n)
                    }
                }
            }
        }
    }

    companion object {
        private const val TMP_PREFIX = ".tmp-"
        private const val MAX_UNPACKED_BYTES = 600_000_000L

        fun sha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
