package com.itantra.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.delay

/** Offline Hindi text-to-speech with a Piper VITS voice, plus simple speaker playback. */
class TtsEngine(context: Context, numThreads: Int = 4) {

    class Audio(val samples: FloatArray, val sampleRate: Int, val millis: Long) {
        val seconds: Float get() = samples.size / sampleRate.toFloat()
    }

    /** How long the one-time espeak-ng-data copy took (0 when already present). */
    val dataCopyMillis: Long

    private val tts: OfflineTts

    init {
        val start = SystemClock.elapsedRealtime()
        val dataDir = AssetCopier.copyDir(context, "tts/espeak-ng-data")
        dataCopyMillis = SystemClock.elapsedRealtime() - start

        tts = OfflineTts(
            context.assets,
            OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = "tts/$VOICE.onnx",
                        tokens = "tts/tokens.txt",
                        dataDir = dataDir.absolutePath,
                    ),
                    numThreads = numThreads,
                ),
            ),
        )
    }

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

    private companion object {
        const val VOICE = "hi_IN-priyamvada-medium"
    }
}
