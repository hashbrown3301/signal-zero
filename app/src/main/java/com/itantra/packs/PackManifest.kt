package com.itantra.packs

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** `pack.json`, written by scripts/packs/build_pack.py. Unknown keys are ignored so newer packs still load. */
@Serializable
data class PackManifest(
    val format: Int,
    val id: String,
    val lang: String,
    @SerialName("packet_code") val packetCode: Int,
    val name: String,
    val native: String,
    /** "speak" (STT) or "listen" (voice). */
    val kind: String,
    val engine: PackEngine,
    val files: List<PackFile>,
    val size: Long,
    val sources: List<PackSource> = emptyList(),
    val built: String = "",
) {
    val isSpeak: Boolean get() = kind == KIND_SPEAK
    val isListen: Boolean get() = kind == KIND_LISTEN

    companion object {
        const val SUPPORTED_FORMAT = 1
        const val KIND_SPEAK = "speak"
        const val KIND_LISTEN = "listen"

        val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): PackManifest = json.decodeFromString(serializer(), text)
    }
}

@Serializable
data class PackEngine(
    /** "nemo_ctc" (STT); "piper" or "mms" (voice). */
    val type: String,
    val model: String,
    val tokens: String,
    @SerialName("sample_rate") val sampleRate: Int? = null,
    @SerialName("feature_dim") val featureDim: Int? = null,
    /** Shared data the app provides instead of the pack, e.g. "espeak-ng-data". */
    val needs: List<String> = emptyList(),
    val quant: String? = null,
)

@Serializable
data class PackFile(val path: String, val size: Long, val sha256: String)

@Serializable
data class PackSource(
    val url: String = "",
    val commit: String? = null,
    val licence: String = "",
    val note: String? = null,
)
