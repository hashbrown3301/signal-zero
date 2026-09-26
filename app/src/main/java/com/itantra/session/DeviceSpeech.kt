package com.itantra.session

import android.annotation.SuppressLint
import com.itantra.audio.AudioRecorder
import com.itantra.speech.SttEngine
import com.itantra.speech.TtsEngine
import com.itantra.speech.VadTrimmer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** [Listener] backed by the phone's mic, Silero VAD and IndicConformer STT. */
class DeviceListener(
    private val recorder: AudioRecorder,
    private val vad: VadTrimmer,
    private val stt: SttEngine,
) : Listener {

    @SuppressLint("MissingPermission") // the UI requests RECORD_AUDIO before the first press
    override fun start() = recorder.start()

    override suspend fun finish(): Heard = withContext(Dispatchers.Default) {
        val audio = recorder.stop()
        val trimmed = vad.trim(audio)
        val recordedSec = audio.size / recorder.sampleRate.toFloat()
        val speechSec = trimmed.speech.size / recorder.sampleRate.toFloat()
        if (trimmed.isEmpty) {
            Heard("", recordedSec, speechSec, trimmed.millis, sttMs = null)
        } else {
            val result = stt.transcribe(trimmed.speech)
            Heard(result.text, recordedSec, speechSec, trimmed.millis, result.millis)
        }
    }

    override fun cancel() {
        if (recorder.isRecording) recorder.stop()
    }
}

/** [Speaker] backed by the Piper Hindi voice. */
class DeviceSpeaker(private val tts: TtsEngine) : Speaker {
    override suspend fun prepare(text: String): Prepared {
        val audio = withContext(Dispatchers.Default) { tts.synthesize(text) }
        return object : Prepared {
            override val synthMs = audio.millis
            override suspend fun play() = tts.play(audio)
        }
    }
}
