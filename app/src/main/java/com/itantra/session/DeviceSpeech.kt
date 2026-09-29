package com.itantra.session

import android.annotation.SuppressLint
import com.itantra.audio.AudioRecorder
import com.itantra.speech.SttEngine
import com.itantra.speech.TtsEngine
import com.itantra.speech.VadTrimmer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * [Listener] backed by the phone's mic, Silero VAD and IndicConformer STT. [engineLock] is held while the native
 * engines run, so their owner can't release them mid-call (it takes the same lock before releasing).
 */
class DeviceListener(
    private val recorder: AudioRecorder,
    private val vad: VadTrimmer,
    private val stt: SttEngine,
    private val engineLock: Mutex,
) : Listener {

    @SuppressLint("MissingPermission") // the UI requests RECORD_AUDIO before the first press
    override fun start() = recorder.start()

    override suspend fun finish(): Heard = withContext(Dispatchers.Default) {
        val audio = recorder.stop()
        engineLock.withLock {
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
    }

    override fun cancel() {
        if (recorder.isRecording) recorder.stop()
    }
}

/**
 * [Speaker] backed by the installed voices; [voiceFor] returns the voice for a language code, or null.
 * [voiceLock] is held from lookup through synthesis, so the voice can't be evicted and released mid-call.
 * Playback only touches the rendered samples, so it runs outside the lock.
 */
class DeviceSpeaker(private val voiceLock: Mutex, private val voiceFor: (Int) -> TtsEngine?) : Speaker {
    override suspend fun prepare(text: String, langCode: Int): Prepared? {
        val (tts, audio) = withContext(Dispatchers.Default) {
            voiceLock.withLock {
                val tts = voiceFor(langCode) ?: return@withContext null
                tts to tts.synthesize(text)
            }
        } ?: return null
        return object : Prepared {
            override val synthMs = audio.millis
            override suspend fun play() = tts.play(audio)
        }
    }

    override suspend fun preload(langCode: Int) {
        withContext(Dispatchers.Default) { voiceLock.withLock { voiceFor(langCode) } }
    }
}
