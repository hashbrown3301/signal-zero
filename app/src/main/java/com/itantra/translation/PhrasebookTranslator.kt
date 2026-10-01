package com.itantra.translation

import com.itantra.comm.Language
import com.itantra.session.Translated
import com.itantra.session.TranslationOrigin
import com.itantra.session.Translator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Exact reviewed translations are resolved before the fallback loads models or checks memory. */
class PhrasebookTranslator(
    private val store: PhrasebookStore,
    private val fallback: Translator,
) : Translator {
    override suspend fun translate(text: String, source: Language, target: Language): Translated {
        coroutineContext.ensureActive()
        require(text.isNotBlank()) { "No text to translate" }
        if (source == target) return Translated(text, 0)
        val start = System.nanoTime()
        val reviewed = withContext(Dispatchers.IO) { store.find(text, source, target) }
        coroutineContext.ensureActive()
        if (reviewed != null) {
            return Translated(
                text = reviewed.targetText,
                millis = (System.nanoTime() - start) / 1_000_000,
                origin = TranslationOrigin.REVIEWED_PHRASE,
            )
        }
        return fallback.translate(text, source, target)
    }
}
