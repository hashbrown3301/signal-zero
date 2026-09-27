package com.itantra.speech

import android.content.Context
import com.itantra.packs.PackManifest
import com.itantra.packs.PackRef
import java.io.File

/**
 * Builds speech engines from packs, driven only by pack.json: nothing here names a language.
 * Built-in packs ([PackRef.dir] == null) read from the APK's assets; installed packs from their folder.
 */
class EngineFactory(private val context: Context) {

    fun stt(ref: PackRef): SttEngine {
        val e = ref.manifest.engine
        require(ref.manifest.isSpeak) { "${ref.manifest.id} is not a speak pack" }
        require(e.type == "nemo_ctc") { "${ref.manifest.id}: unsupported STT engine ${e.type}" }
        return SttEngine(assets(ref), path(ref, e.model), path(ref, e.tokens), featureDim = e.featureDim ?: 80)
    }

    fun tts(ref: PackRef): TtsEngine {
        val e = ref.manifest.engine
        require(ref.manifest.isListen) { "${ref.manifest.id} is not a listen pack" }
        require(e.type == "piper" || e.type == "mms") { "${ref.manifest.id}: unsupported voice engine ${e.type}" }
        // Piper needs espeak-ng-data as real files; every Piper pack shares the app's copy (see scripts/packs).
        val dataDir = if (ESPEAK in e.needs) AssetCopier.copyDir(context, "tts/$ESPEAK").absolutePath else ""
        return TtsEngine(assets(ref), path(ref, e.model), path(ref, e.tokens), dataDir)
    }

    private fun assets(ref: PackRef) = if (ref.dir == null) context.assets else null

    private fun path(ref: PackRef, file: String): String = ref.dir?.let { File(it, file).absolutePath } ?: file

    private companion object {
        const val ESPEAK = "espeak-ng-data"
    }
}
