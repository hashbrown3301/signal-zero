package com.itantra.speech

import android.content.Context
import java.io.File

/**
 * Copies an asset directory into filesDir, for native code that needs real file paths
 * (espeak-ng can't read from inside the APK). Re-copies whenever the app is reinstalled
 * or a previous copy was interrupted.
 */
object AssetCopier {

    private const val MARKER = ".copied"

    fun copyDir(context: Context, assetDir: String): File {
        val dest = File(context.filesDir, assetDir)
        val stamp = context.packageManager.getPackageInfo(context.packageName, 0)
            .lastUpdateTime.toString()
        val marker = File(dest, MARKER)
        if (marker.isFile && marker.readText() == stamp) return dest

        dest.deleteRecursively()
        copyRecursive(context, assetDir, dest)
        marker.writeText(stamp)
        return dest
    }

    private fun copyRecursive(context: Context, assetPath: String, dest: File) {
        val children = context.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            dest.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                dest.outputStream().use { input.copyTo(it) }
            }
            return
        }
        dest.mkdirs()
        for (child in children) copyRecursive(context, "$assetPath/$child", File(dest, child))
    }
}
