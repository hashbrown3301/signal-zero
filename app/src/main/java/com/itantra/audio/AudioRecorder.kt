package com.itantra.audio

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import kotlin.concurrent.thread

/**
 * Push-to-talk microphone capture: [start] when the button goes down, [stop] when it comes up.
 * Audio is 16 kHz mono, returned as floats in [-1, 1] – the format sherpa-onnx expects.
 */
class AudioRecorder(val sampleRate: Int = 16_000) {

    private val chunks = mutableListOf<ShortArray>()
    private var record: AudioRecord? = null
    private var reader: Thread? = null

    @Volatile
    private var running = false

    val isRecording: Boolean get() = running

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start() {
        check(record == null) { "Already recording" }
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = AudioRecord(
            // VOICE_RECOGNITION skips the call-oriented processing on most devices.
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, sampleRate / 2 * BYTES_PER_SAMPLE),
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            error("AudioRecord failed to initialise")
        }

        synchronized(chunks) { chunks.clear() }
        running = true
        rec.startRecording()
        record = rec
        reader = thread(name = "AudioRecorder") {
            // 20 ms reads: stop() waits for at most one read, so small chunks = faster release.
            val chunk = ShortArray(sampleRate / 50)
            while (running) {
                val n = rec.read(chunk, 0, chunk.size)
                if (n > 0) synchronized(chunks) { chunks.add(chunk.copyOf(n)) }
            }
        }
    }

    /** Stops capture and returns everything recorded since [start]. */
    fun stop(): FloatArray {
        val rec = record ?: return FloatArray(0)
        running = false
        reader?.join()
        rec.stop()
        rec.release()
        record = null
        reader = null

        synchronized(chunks) {
            val out = FloatArray(chunks.sumOf { it.size })
            var i = 0
            for (chunk in chunks) for (s in chunk) out[i++] = s / 32768f
            chunks.clear()
            return out
        }
    }

    private companion object {
        const val BYTES_PER_SAMPLE = 2
    }
}
