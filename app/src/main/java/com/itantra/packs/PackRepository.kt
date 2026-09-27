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

    private companion object {
        const val TAG = "iTantra"
        const val BUILTIN = "builtin"
    }
}
