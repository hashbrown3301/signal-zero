package com.itantra.translation

import com.itantra.comm.Language
import com.itantra.session.Translated
import com.itantra.session.TranslationOrigin
import com.itantra.session.Translator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PhrasebookTranslatorTest {
    @get:Rule
    val folder = TemporaryFolder()

    private class FakeFallback : Translator {
        var calls = 0
        var failure: Throwable? = null
        var lastInput: Triple<String, Language, Language>? = null
        val output = Translated("Model result", 37, cached = true)
        override suspend fun translate(text: String, source: Language, target: Language): Translated {
            calls++
            lastInput = Triple(text, source, target)
            failure?.let { throw it }
            return output
        }
    }

    private fun store(): PhrasebookStore = PhrasebookStore(folder.root.resolve("phrases.json"))

    @Test
    fun reviewedHitBypassesUnavailableNativeFallbackAndIdentifiesItsOrigin() = runBlocking {
        val store = store()
        store.upsert(Language.ENGLISH, Language.HINDI, "Bring 25 bottles", "२५ बोतलें लाएँ")
        val fallback = FakeFallback().apply { failure = IllegalStateException("No models or memory") }
        val result = PhrasebookTranslator(store, fallback).translate("Bring 25 bottles", Language.ENGLISH, Language.HINDI)
        assertEquals("२५ बोतलें लाएँ", result.text)
        assertEquals(TranslationOrigin.REVIEWED_PHRASE, result.origin)
        assertTrue(result.millis >= 0)
        assertFalse(result.cached)
        assertEquals(0, fallback.calls)
    }

    @Test
    fun aMissPassesTheUnmodifiedInputAndFallbackMetadataThrough() = runBlocking {
        val fallback = FakeFallback()
        val result = PhrasebookTranslator(store(), fallback).translate(" New text ", Language.ENGLISH, Language.ODIA)
        assertEquals(fallback.output, result)
        assertEquals(Triple(" New text ", Language.ENGLISH, Language.ODIA), fallback.lastInput)
        assertEquals(1, fallback.calls)
    }

    @Test
    fun directedLanguageAndCriticalExactMatchBoundariesDoNotBypassFallback() = runBlocking {
        val store = store()
        store.upsert(Language.ENGLISH, Language.HINDI, "Bring -25, not 50", "-२५ लाएँ, ५० नहीं")
        val fallback = FakeFallback()
        val translator = PhrasebookTranslator(store, fallback)
        translator.translate("Bring 25, not 50", Language.ENGLISH, Language.HINDI)
        translator.translate("bring -25, not 50", Language.ENGLISH, Language.HINDI)
        translator.translate("Bring -25, not 50", Language.ENGLISH, Language.ODIA)
        translator.translate("Bring -25, not 50", Language.HINDI, Language.ENGLISH)
        assertEquals(4, fallback.calls)
    }

    @Test
    fun agreedWhitespaceNormalizationStillReturnsExplicitReviewedText() = runBlocking {
        val store = store()
        store.upsert(Language.ENGLISH, Language.HINDI, "Bring 25", "२५ लाएँ")
        val fallback = FakeFallback()
        assertEquals("२५ लाएँ", PhrasebookTranslator(store, fallback)
            .translate("  Bring\t25\n", Language.ENGLISH, Language.HINDI).text)
        assertEquals(0, fallback.calls)
    }

    @Test
    fun editsAndDeletionTakeEffectWithoutASeparateTranslationCache() = runBlocking {
        val store = store()
        val entry = store.upsert(Language.ENGLISH, Language.HINDI, "Hello", "नमस्ते")
        val fallback = FakeFallback()
        val translator = PhrasebookTranslator(store, fallback)
        assertEquals("नमस्ते", translator.translate("Hello", Language.ENGLISH, Language.HINDI).text)
        store.upsert(Language.ENGLISH, Language.HINDI, "Hello", "नमस्कार")
        assertEquals("नमस्कार", translator.translate("Hello", Language.ENGLISH, Language.HINDI).text)
        store.remove(entry.id)
        assertEquals(fallback.output, translator.translate("Hello", Language.ENGLISH, Language.HINDI))
        assertEquals(1, fallback.calls)
    }

    @Test
    fun sameLanguageReturnsOriginalWithoutLoadingCorruptStorageOrFallback() = runBlocking {
        folder.root.resolve("phrases.json").writeText("corrupt")
        val fallback = FakeFallback()
        val result = PhrasebookTranslator(store(), fallback).translate(" Hello ", Language.ENGLISH, Language.ENGLISH)
        assertEquals(" Hello ", result.text)
        assertEquals(0L, result.millis)
        assertEquals(0, fallback.calls)
    }

    @Test
    fun corruptReviewedStorageIsReportedInsteadOfSilentlyUsingAnUnreviewedModel() = runBlocking {
        folder.root.resolve("phrases.json").writeText("corrupt")
        val fallback = FakeFallback()
        try {
            PhrasebookTranslator(store(), fallback).translate("Hello", Language.ENGLISH, Language.HINDI)
            throw AssertionError("Expected corrupt storage to be reported")
        } catch (_: PhrasebookException) {
            assertEquals(0, fallback.calls)
        }
    }

    @Test
    fun blankInputDoesNotLoadStorageOrFallback() = runBlocking {
        val fallback = FakeFallback()
        try {
            PhrasebookTranslator(store(), fallback).translate(" \n", Language.ENGLISH, Language.HINDI)
            throw AssertionError("Expected blank input to fail")
        } catch (_: IllegalArgumentException) {
            assertEquals(0, fallback.calls)
        }
    }

    @Test
    fun alreadyCancelledCallerNeverInvokesFallback() {
        val fallback = FakeFallback()
        val cancelled = Job().apply { cancel() }
        try {
            runBlocking(cancelled) {
                PhrasebookTranslator(store(), fallback).translate("Hello", Language.ENGLISH, Language.HINDI)
            }
            throw AssertionError("Expected cancellation")
        } catch (_: CancellationException) {
            assertEquals(0, fallback.calls)
        }
    }

    @Test
    fun fallbackCancellationIsPropagatedUnchanged() = runBlocking {
        val failure = CancellationException("Model cancelled")
        val fallback = FakeFallback().apply { this.failure = failure }
        try {
            PhrasebookTranslator(store(), fallback).translate("Hello", Language.ENGLISH, Language.HINDI)
            throw AssertionError("Expected cancellation")
        } catch (actual: CancellationException) {
            assertTrue(actual === failure)
        }
    }
}
