package com.itantra.translation

import com.itantra.comm.Language
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.FloatBuffer

class NllbTokensTest {
    @Test fun allTenLanguagesUseTheirPinnedNllbTags() {
        val tags = mapOf(
            "en" to 256047, "hi" to 256068, "mr" to 256116, "gu" to 256064,
            "bn" to 256026, "ta" to 256170, "te" to 256172, "kn" to 256083,
            "ml" to 256115, "or" to 256136,
        )
        assertEquals(10, Language.entries.size)
        Language.entries.forEach { assertEquals(tags.getValue(it.iso), NllbTokens.languageId(it)) }
    }

    @Test fun encoderUsesModernNllbSourcePrefixAndFinalEos() {
        assertArrayEquals(longArrayOf(256170, 42, 75, 2), NllbTokens.encoderInput(intArrayOf(42, 75), Language.TAMIL))
    }

    @Test fun sourceLimitIncludesLanguageAndEosWithoutSilentlyDroppingText() {
        val largest = IntArray(NllbTokens.MAX_SOURCE_TOKENS - 2) { 42 }
        assertEquals(256, NllbTokens.encoderInput(largest, Language.ENGLISH).size)
        assertThrows(IllegalArgumentException::class.java) {
            NllbTokens.encoderInput(largest + 42, Language.ENGLISH)
        }
    }

    @Test fun detokenizationKeepsUnknownAndAllSentencepieceIdsAndRemovesControlIds() {
        assertArrayEquals(longArrayOf(3, 4, 100, 256000), NllbTokens.contentIds(listOf(0, 1, 2, 3, 4, 100, 256000, 256001, 256047)))
    }

    @Test fun greedyPicksHighestScoreAndFirstTokenOnTie() {
        val logits = FloatArray(256206) { -10f }.apply {
            this[17] = 5f
            this[18] = 5f
        }
        assertEquals(17, NllbTokens.greedy(FloatBuffer.wrap(logits)))
    }

    @Test fun greedyUsesBufferPositionWithoutMovingIt() {
        val logits = FloatArray(256207) { -10f }.apply {
            this[0] = Float.NaN
            this[101] = 5f
        }
        val buffer = FloatBuffer.wrap(logits).apply { position(1) }
        assertEquals(100, NllbTokens.greedy(buffer))
        assertEquals(1, buffer.position())
    }

    @Test fun wrongModelVocabularyFailsInsteadOfGeneratingWrongLanguage() {
        assertThrows(IllegalArgumentException::class.java) { NllbTokens.greedy(FloatBuffer.allocate(12)) }
    }

    @Test fun invalidModelScoresFailInsteadOfChoosingAnArbitraryToken() {
        val logits = FloatArray(256206).apply { this[42] = Float.NaN }
        assertThrows(IllegalStateException::class.java) { NllbTokens.greedy(FloatBuffer.wrap(logits)) }
    }
}
