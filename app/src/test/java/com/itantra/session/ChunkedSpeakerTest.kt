package com.itantra.session

import com.itantra.comm.Language
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

class ChunkedSpeakerTest {
    private val longText = "This first sentence has enough context to speak clearly. " +
        "The second sentence explains the message without waiting for all the audio to be generated. " +
        "The final sentence completes the message."

    @Test
    fun chunksPreserveEveryCharacterInAllTenLanguages() {
        val phrases = mapOf(
            "hi" to "मुझे आज आपकी सहायता चाहिए। ", "en" to "Please help me find the station. ",
            "mr" to "मला आज तुमची मदत हवी आहे। ", "gu" to "મને આજે તમારી મદદ જોઈએ છે. ",
            "bn" to "আজ আমার আপনার সাহায্য দরকার। ", "ta" to "இன்று எனக்கு உங்கள் உதவி தேவை. ",
            "te" to "ఈరోజు నాకు మీ సహాయం కావాలి. ", "kn" to "ಇಂದು ನನಗೆ ನಿಮ್ಮ ಸಹಾಯ ಬೇಕು. ",
            "ml" to "ഇന്ന് എനിക്ക് നിങ്ങളുടെ സഹായം വേണം. ", "or" to "ଆଜି ମୋତେ ଆପଣଙ୍କ ସାହାଯ୍ୟ ଦରକାର। ",
        )
        for (language in Language.entries) {
            val text = phrases.getValue(language.iso).repeat(15)
            val chunks = speechChunks(text)
            assertTrue(language.iso, chunks.size > 1)
            assertEquals(language.iso, text, chunks.joinToString(""))
            assertTrue(language.iso, chunks.all { it.isNotBlank() })
        }
    }

    @Test
    fun shortTextAndLongWordsAreNeverCut() {
        assertEquals(listOf("नमस्ते"), speechChunks("नमस्ते"))
        val word = "தமிழ்".repeat(50)
        assertEquals(listOf(word), speechChunks(word))
        val text = "The measurement was 123456789.987654321 metres and must stay unchanged. ".repeat(5)
        assertTrue(speechChunks(text).any { it.contains("123456789.987654321") })
        assertEquals(text, speechChunks(text).joinToString(""))
    }

    @Test
    fun firstAudioStartsBeforeLaterSynthesisAndOnlyOneChunkIsPrefetched() = runBlocking {
        withTimeout(5_000) {
            val requested = Collections.synchronizedList(mutableListOf<String>())
            val played = Collections.synchronizedList(mutableListOf<String>())
            val firstPlaying = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            val releaseNext = CompletableDeferred<Unit>()
            val delegate = object : Speaker {
                override suspend fun prepare(text: String, langCode: Int): Prepared {
                    requested += text
                    val index = requested.size
                    if (index > 1) releaseNext.await()
                    return object : Prepared {
                        override val synthMs = 25L
                        override suspend fun play() {
                            played += text
                            if (index == 1) { firstPlaying.complete(Unit); releaseFirst.await() }
                        }
                    }
                }
            }
            val audio = ChunkedSpeaker(delegate).prepare(longText, 1)!!
            val chunks = speechChunks(longText)
            assertEquals(listOf(chunks.first()), requested.toList())
            assertEquals(25L, audio.synthMs)
            coroutineScope {
                val playback = async { audio.play() }
                firstPlaying.await()
                assertEquals(listOf(chunks.first()), played.toList())
                while (requested.size < 2) delay(5)
                assertEquals(2, requested.size)
                assertFalse(playback.isCompleted)
                releaseFirst.complete(Unit)
                releaseNext.complete(Unit)
                playback.await()
            }
            assertEquals(chunks, played.toList())
            assertEquals(chunks.size, audio.chunkCount)
            assertEquals(chunks.size * 25L, audio.totalSynthMs)
        }
    }

    @Test
    fun closingPlaybackCancelsThePrefetch() = runBlocking {
        withTimeout(5_000) {
            val prefetchStarted = CompletableDeferred<Unit>()
            val prefetchStopped = CompletableDeferred<Unit>()
            var calls = 0
            val delegate = object : Speaker {
                override suspend fun prepare(text: String, langCode: Int): Prepared {
                    if (++calls > 1) {
                        prefetchStarted.complete(Unit)
                        try { CompletableDeferred<Unit>().await() }
                        finally { prefetchStopped.complete(Unit) }
                    }
                    return object : Prepared {
                        override val synthMs = 10L
                        override suspend fun play() { CompletableDeferred<Unit>().await() }
                    }
                }
            }
            val audio = ChunkedSpeaker(delegate).prepare(longText, 1)!!
            val playback = async { audio.play() }
            prefetchStarted.await()
            playback.cancelAndJoin()
            prefetchStopped.await()
        }
    }

    @Test
    fun missingVoiceReturnsNullAndPreloadReachesTheDelegate() = runBlocking {
        val requested = mutableListOf<Int>()
        val speaker = ChunkedSpeaker(object : Speaker {
            override suspend fun prepare(text: String, langCode: Int): Prepared? = null
            override suspend fun preload(langCode: Int): Boolean { requested += langCode; return false }
        })
        assertNull(speaker.prepare(longText, 10))
        assertFalse(speaker.preload(10))
        assertEquals(listOf(10), requested)
    }
}
