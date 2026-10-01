package com.itantra.audio

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission

/** Hold-to-talk capture: 16 kHz mono PCM, limited to 30 seconds per utterance. */
class AudioRecorder(val sampleRate: Int = 16_000) {
    private val capture = PcmCapture(sampleRate) { createSource() }

    val isRecording: Boolean get() = capture.isRecording

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start() = capture.start()

    /** Returns the complete recording, or fails instead of translating truncated audio. */
    fun stop(): FloatArray = capture.stop()

    /** Always safe after a capture error or a previous stop; releases the owned microphone. */
    fun discard() = capture.discard()

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun createSource(): PcmSource {
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        check(minBuffer > 0) { "Microphone does not support this audio format" }
        val record = AudioRecord(
            // VOICE_RECOGNITION skips the call-oriented processing on most devices.
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, sampleRate / 2 * 2),
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            error("AudioRecord failed to initialise")
        }
        return object : PcmSource {
            override fun start() = record.startRecording()
            override fun read(buffer: ShortArray) = record.read(buffer, 0, buffer.size)
            override fun stop() = record.stop()
            override fun release() = record.release()
        }
    }
}
