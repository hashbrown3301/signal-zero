package com.itantra.session

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Starts long utterances after synthesizing their first chunk, then prepares at most one
 * further chunk during playback. The delegate owns voices and their native-engine locks.
 * Short utterances take the original path; source text and wire packets are unchanged.
 */
class ChunkedSpeaker(private val delegate: Speaker) : Speaker {
    override suspend fun preload(langCode: Int): Boolean = delegate.preload(langCode)

    override suspend fun prepare(text: String, langCode: Int): Prepared? {
        val chunks = speechChunks(text)
        if (chunks.size <= 1) return delegate.prepare(text, langCode)
        val first = delegate.prepare(chunks.first(), langCode) ?: return null
        return object : Prepared {
            override val synthMs = first.synthMs
            override val chunkCount = chunks.size
            override var totalSynthMs = first.totalSynthMs
                private set

            override suspend fun play() = coroutineScope {
                var current = first
                for (index in chunks.indices) {
                    val next = if (index < chunks.lastIndex) async {
                        delegate.prepare(chunks[index + 1], langCode)
                            ?: error("Voice became unavailable during playback")
                    } else null
                    current.play()
                    if (next != null) {
                        current = next.await()
                        totalSynthMs += current.totalSynthMs
                    }
                }
            }
        }
    }
}

/** Lossless text boundaries: sentence endings first, then whitespace, never inside a word. */
internal fun speechChunks(text: String, maxChars: Int = 140, minSentenceChars: Int = 40): List<String> {
    require(maxChars > 0 && minSentenceChars in 1..maxChars)
    if (text.length <= maxChars || text.isBlank()) return listOf(text)
    val endings = Regex("[.!?।॥。！？]+(?=\\s|$)").findAll(text).map { it.range.last + 1 }.toList()
    val result = mutableListOf<String>()
    var start = 0
    while (start < text.length) {
        val limit = (start + maxChars).coerceAtMost(text.length)
        if (limit == text.length) {
            result += text.substring(start)
            break
        }
        // Prefer an early complete sentence: it gives TTS useful context while starting sooner.
        var end = endings.firstOrNull { it >= start + minSentenceChars && it <= limit }
            ?: (limit downTo start + 1).firstOrNull { text[it].isWhitespace() }
            ?: (limit until text.length).firstOrNull { text[it].isWhitespace() }
            ?: text.length
        // Carry separators with the preceding chunk so joining the chunks recovers the exact input.
        while (end < text.length && text[end].isWhitespace()) end++
        result += text.substring(start, end)
        start = end
    }
    return result
}
