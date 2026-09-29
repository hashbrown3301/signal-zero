package com.itantra.audio

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads a 16-bit PCM mono WAV as floats in [-1, 1]. Used by the debug microphone stand-in (emulator tests,
 * scripted benchmark sentences); rejects any other format rather than guessing. Plain Kotlin, tested on the PC.
 */
fun readPcm16MonoWav(file: File, expectedRate: Int = 16_000): FloatArray {
    val b = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
    require(b.remaining() >= 12 && b.getInt(0) == RIFF && b.getInt(8) == WAVE) { "${file.name}: not a WAV file" }
    var pos = 12
    var formatOk = false
    while (pos + 8 <= b.limit()) {
        val id = b.getInt(pos)
        val size = b.getInt(pos + 4)
        val body = pos + 8
        require(size >= 0 && body + size <= b.limit() || id == DATA) { "${file.name}: truncated chunk" }
        when (id) {
            FMT -> {
                val format = b.getShort(body).toInt()
                val channels = b.getShort(body + 2).toInt()
                val rate = b.getInt(body + 4)
                val bits = b.getShort(body + 14).toInt()
                require(format == 1 && channels == 1 && bits == 16 && rate == expectedRate) {
                    "${file.name}: need 16-bit PCM mono at $expectedRate Hz (got format $format, $channels ch, $bits bit, $rate Hz)"
                }
                formatOk = true
            }
            DATA -> {
                require(formatOk) { "${file.name}: data before fmt" }
                val n = (if (size < 0) b.limit() - body else minOf(size, b.limit() - body)) / 2 // streamed WAVs may lie
                return FloatArray(n) { b.getShort(body + 2 * it) / 32768f }
            }
        }
        pos = body + size + (size and 1) // chunks are word-aligned
    }
    throw IllegalArgumentException("${file.name}: no data chunk")
}

private const val RIFF = 0x46464952 // "RIFF" little-endian
private const val WAVE = 0x45564157
private const val FMT = 0x20746d66
private const val DATA = 0x61746164

/** Writes floats in [-1, 1] as a 16-bit PCM mono WAV (debug audio dumps). */
fun writePcm16MonoWav(file: File, samples: FloatArray, sampleRate: Int) {
    val data = samples.size * 2
    val b = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
    b.putInt(RIFF).putInt(36 + data).putInt(WAVE)
    b.putInt(FMT).putInt(16).putShort(1).putShort(1).putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
    b.putInt(DATA).putInt(data)
    for (s in samples) b.putShort((s.coerceIn(-1f, 1f) * 32767).toInt().toShort())
    file.writeBytes(b.array())
}
