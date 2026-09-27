package com.itantra.speech

import android.content.res.AssetManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.delay

/**
 * Offline text-to-speech with a VITS voice (Piper or MMS), plus simple speaker playback.
 * Built by [EngineFactory] from a pack: [assets] non-null means [model]/[tokens] are asset paths (built-in pack),
 * null means absolute file paths (installed pack). [dataDir] is espeak-ng-data for Piper, empty for MMS.
 */
class TtsEngine(assets: AssetManager?, model: String, tokens: String, dataDir: String, numThreads: Int = 4) {

    class Audio(val samples: FloatArray, val sampleRate: Int, val millis: Long) {
        val seconds: Float get() = samples.size / sampleRate.toFloat()
    }

    private val tts = OfflineTts(
        assets,
        OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(model = model, tokens = tokens, dataDir = dataDir),
                numThreads = numThreads,
            ),
        ),
    )

    fun synthesize(text: String, speed: Float = 1.0f): Audio {
        val start = SystemClock.elapsedRealtime()
        val audio = tts.generate(text, sid = 0, speed = speed)
        return Audio(audio.samples, audio.sampleRate, SystemClock.elapsedRealtime() - start)
    }

    /** Plays [audio] on the media stream and suspends until it has finished. */
    suspend fun play(audio: Audio) {
        if (audio.samples.isEmpty()) return
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(audio.sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(audio.samples.size * Float.SIZE_BYTES)
            .build()
        try {
            track.write(audio.samples, 0, audio.samples.size, AudioTrack.WRITE_BLOCKING)
            track.play()
            while (track.playbackHeadPosition < audio.samples.size &&
                track.playState == AudioTrack.PLAYSTATE_PLAYING
            ) {
                delay(20)
            }
        } finally {
            track.stop()
            track.release()
        }
    }

    fun release() = tts.release()

}
