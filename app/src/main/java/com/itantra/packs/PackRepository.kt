package com.itantra.packs

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File

/** A pack and where its files are: [dir] is the installed pack's folder, or null for a built-in (asset) pack. */
data class PackRef(val manifest: PackManifest, val dir: File?)

/**
 * Android side of the pack manager: built-in packs (Hindi, in the APK's assets), installed packs
 * ([PackStore] under filesDir/packs), and the two ways to install without internet:
 *
 * - **sideload**: `adb push ta-listen.zip /sdcard/Android/data/com.itantra/files/incoming/` → [installIncoming]
 * - **import**: pick a zip with Android's file picker → [installFromUri]
 *
 * Blocking I/O: call from a background dispatcher.
 */
class PackRepository(private val context: Context) {

    val store = PackStore(File(context.filesDir, "packs"))

    /** Where `adb push` drops zips; the app can read it without any storage permission. */
    val incomingDir: File? get() = context.getExternalFilesDir("incoming")

    init {
        store.cleanUp()
    }

    fun builtIn(): List<PackManifest> =
        context.assets.list(BUILTIN).orEmpty().mapNotNull { id ->
            runCatching {
                context.assets.open("$BUILTIN/$id/pack.json").use { PackManifest.parse(it.readBytes().decodeToString()) }
            }.onFailure { Log.w(TAG, "bad built-in pack $id", it) }.getOrNull()
        }

    fun installed(): List<PackManifest> = store.list()

    /** The pack to use for a language: an installed pack wins over a built-in one (e.g. an updated Hindi pack). */
    fun find(lang: String, kind: String): PackRef? {
        installed().firstOrNull { it.lang == lang && it.kind == kind }?.let { return PackRef(it, store.dir(it.id)) }
        return builtIn().firstOrNull { it.lang == lang && it.kind == kind }?.let { PackRef(it, null) }
    }

    fun installFromUri(uri: Uri): PackManifest =
        context.contentResolver.openInputStream(uri)?.use { store.install(it) }
            ?: throw PackException("Couldn't open the selected file")

    /** Installs every sideloaded zip; a zip is deleted once installed and kept (for a retry) if it failed. */
    fun installIncoming(): List<Result<PackManifest>> {
        val dir = incomingDir ?: return emptyList()
        return dir.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".zip") }.sortedBy { it.name }.map { zip ->
            runCatching { zip.inputStream().use { store.install(it) } }
                .onSuccess {
                    zip.delete()
                    Log.i(TAG, "installed ${it.id} from ${zip.name}")
                }
                .onFailure { Log.w(TAG, "couldn't install ${zip.name}: ${it.message}") }
        }
    }

    fun delete(id: String): Boolean = store.delete(id).also { if (it) Log.i(TAG, "deleted pack $id") }

    // ---------- catalogue + download ----------

    private val catalogFile = File(context.filesDir, "catalog/index.json")
    private val downloader = PackDownloader(File(context.cacheDir, "downloads"))

    /** Every downloadable pack: the last copy fetched from the internet, else the one built into the APK. */
    fun catalog(): PackCatalog {
        catalogFile.takeIf { it.isFile }?.let { f ->
            runCatching { return PackCatalog.parse(f.readText()) }.onFailure { Log.w(TAG, "bad cached catalogue", it) }
        }
        return context.assets.open("catalog/index.json").use { PackCatalog.parse(it.readBytes().decodeToString()) }
    }

    /** Fetches the latest catalogue (needs internet); keeps the old one if anything goes wrong. */
    fun refreshCatalog(): PackCatalog {
        val text = java.net.URL(PackCatalog.INDEX_URL).openStream().use { it.readBytes().decodeToString() }
        val catalog = PackCatalog.parse(text)  // validate before replacing the cached copy
        catalogFile.parentFile?.mkdirs()
        catalogFile.writeText(text)
        return catalog
    }

    /**
     * Downloads pack [id] (resuming a partial download), checks the zip's SHA-256, then installs it through
     * the same checks as a sideloaded pack. [progress] returns false to cancel (the partial file is kept).
     */
    fun downloadAndInstall(id: String, progress: PackDownloader.Progress): PackManifest {
        val catalog = catalog()
        val entry = catalog.packs[id] ?: throw PackException("$id is not in the catalogue")
        val zip = downloader.download(catalog.url(id), entry.zip, entry.zipSize, entry.zipSha256, progress)
        try {
            return zip.inputStream().use { store.install(it) }.also { Log.i(TAG, "downloaded and installed $id") }
        } finally {
            zip.delete()
        }
    }

    private companion object {
        const val TAG = "iTantra"
        const val BUILTIN = "builtin"
    }
}
