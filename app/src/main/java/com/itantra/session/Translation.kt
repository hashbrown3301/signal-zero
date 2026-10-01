package com.itantra.session

import com.itantra.comm.Language

/** Offline text translation, separate from transcription and voice synthesis. */
interface Translator {
    /** Implementations must propagate cancellation and fail when their offline model is unavailable. */
    suspend fun translate(text: String, source: Language, target: Language): Translated
}

/** [millis] is the time spent translating; [cached] identifies a reused translation. */
data class Translated(
    val text: String,
    val millis: Long,
    val cached: Boolean = false,
    val origin: TranslationOrigin = TranslationOrigin.MODEL,
)

enum class TranslationOrigin { MODEL, REVIEWED_PHRASE }
