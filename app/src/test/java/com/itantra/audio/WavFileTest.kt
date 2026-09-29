package com.itantra.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavFileTest {

    private fun wav(rate: Int, channels: Int, samples: ShortArray, extraChunk: Boolean = false): File {
        val extra = if (extraChunk) 8 + 3 + 1 else 0 // odd-sized LIST chunk + pad byte
        val b = ByteBuffer.allocate(44 + extra + samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + extra + samples.size * 2).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort()).putInt(rate)
            .putInt(rate * 2 * channels).putShort((2 * channels).toShort()).putShort(16)
        if (extraChunk) b.put("LIST".toByteArray()).putInt(3).put(byteArrayOf(1, 2, 3, 0))
        b.put("data".toByteArray()).putInt(samples.size * 2)
        samples.forEach { b.putShort(it) }
        return File.createTempFile("wav", ".wav").apply { writeBytes(b.array()); deleteOnExit() }
    }

    @Test
    fun readsPcm16MonoSkippingOtherChunks() {
        val read = readPcm16MonoWav(wav(16_000, 1, shortArrayOf(0, 16384, -32768), extraChunk = true))
        assertArrayEquals(floatArrayOf(0f, 0.5f, -1f), read, 0f)
    }

    @Test
    fun rejectsOtherFormats() {
        assertThrows(IllegalArgumentException::class.java) { readPcm16MonoWav(wav(22_050, 1, shortArrayOf(1))) }
        assertThrows(IllegalArgumentException::class.java) { readPcm16MonoWav(wav(16_000, 2, shortArrayOf(1, 2))) }
    }
}
