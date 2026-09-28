package com.itantra.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class VoiceCacheTest {

    private class Voice(val code: Int)

    private val loads = mutableListOf<Int>()
    private val released = mutableListOf<Int>()

    private fun cache(capacity: Int, installed: Set<Int> = setOf(1, 2, 6, 9)) = VoiceCache(
        capacity,
        load = { code -> if (code in installed) Voice(code).also { loads += code } else null },
        release = { released += it.code },
    )

    @Test
    fun loadsOnceAndReuses() {
        val c = cache(2)
        val first = c.get(6)
        assertSame(first, c.get(6))
        assertEquals(listOf(6), loads)
    }

    @Test
    fun evictsLeastRecentlyUsed() {
        val c = cache(2)
        c.get(1); c.get(6)
        c.get(1)          // 1 is now more recent than 6
        c.get(9)          // full: 6 goes
        assertEquals(listOf(6), released)
        assertEquals(listOf(1, 9), c.loaded())
    }

    @Test
    fun lowRamKeepsOneVoice() {
        val c = cache(1)
        c.get(1); c.get(6); c.get(9)
        assertEquals(listOf(1, 6), released)
        assertEquals(listOf(9), c.loaded())
    }

    @Test
    fun missingPackReturnsNullAndKeepsTheOthers() {
        val c = cache(2)
        c.get(1)
        assertNull(c.get(7))  // not installed
        assertEquals(listOf(1), c.loaded())
        assertEquals(emptyList<Int>(), released)
    }

    @Test
    fun evictAndClearRelease() {
        val c = cache(2)
        c.get(1); c.get(6)
        c.evict(6)
        c.clear()
        assertEquals(listOf(6, 1), released)
        assertEquals(emptyList<Int>(), c.loaded())
    }
}
