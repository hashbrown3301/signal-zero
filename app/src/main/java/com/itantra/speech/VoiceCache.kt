package com.itantra.speech

/**
 * Keeps at most [capacity] loaded voices, least recently used out first (docs/PHASE3_PLAN.md: 2 voices,
 * 1 on Android "low RAM" phones). A voice is loaded on first use by [load] (null = no listen pack installed)
 * and freed by [release] when evicted. Plain Kotlin, unit-tested on the PC; thread-safe.
 */
class VoiceCache<V : Any>(
    private val capacity: Int,
    private val load: (code: Int) -> V?,
    private val release: (V) -> Unit,
) {
    init {
        require(capacity >= 1)
    }

    private val voices = LinkedHashMap<Int, V>(capacity + 1, 0.75f, /* accessOrder = */ true)

    /** The voice for a language code, loading it (and evicting the least recently used) if needed. */
    @Synchronized
    fun get(code: Int): V? {
        voices[code]?.let { return it }
        val voice = load(code) ?: return null
        voices[code] = voice
        while (voices.size > capacity) {
            val eldest = voices.entries.first()
            voices.remove(eldest.key)
            release(eldest.value)
        }
        return voice
    }

    /** Language codes currently loaded, least recently used first. */
    @Synchronized
    fun loaded(): List<Int> = voices.keys.toList()

    /** Frees one voice, e.g. after its pack was deleted. */
    @Synchronized
    fun evict(code: Int) {
        voices.remove(code)?.let(release)
    }

    @Synchronized
    fun clear() {
        voices.values.forEach(release)
        voices.clear()
    }
}
