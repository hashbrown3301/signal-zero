package com.itantra.session

import android.annotation.SuppressLint
import com.itantra.audio.AudioRecorder
import android.os.SystemClock
import com.itantra.audio.readPcm16MonoWav
import com.itantra.audio.writePcm16MonoWav
import com.itantra.speech.SttEngine
import com.itantra.speech.TtsEngine
import com.itantra.speech.VadTrimmer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * [Listener] backed by the phone's mic, Silero VAD and IndicConformer STT. [engineLock] is held while the native
 * engines run, so their owner can't release them mid-call (it takes the same lock before releasing).
 *
 * [debugMic] (debug builds only): if that WAV file exists when the button is released, it is used instead of what
 * the mic heard, then deleted. Lets emulators and scripted benchmark runs "speak" without a person.
 */
class DeviceListener(
    private val recorder: AudioRecorder,
    private val vad: VadTrimmer,
    private val stt: SttEngine,
    private val engineLock: Mutex,
    private val debugMic: File? = null,
) : Listener {

    @SuppressLint("MissingPermission") // the UI requests RECORD_AUDIO before the first press
    override fun start() = recorder.start()

    override suspend fun finish(): Heard = withContext(Dispatchers.Default) {
        val heard = recorder.stop()
        val audio = debugMic?.takeIf { it.isFile }?.let { wav -> readPcm16MonoWav(wav, recorder.sampleRate).also { wav.delete() } } ?: heard
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
        recorder.discard()
    }
}

/**
 * [Speaker] backed by the installed voices; [voiceFor] returns the voice for a language code, or null.
 * [voiceLock] is held from lookup through synthesis, so the voice can't be evicted and released mid-call.
 * Playback only touches the rendered samples, so it runs outside the lock.
 *
 * [debugDump] (debug builds only): if that folder exists, each played utterance is also saved there as
 * `<elapsedRealtime ms at playback start>.wav`, so a screen recording can be given the app's real audio.
 */
class DeviceSpeaker(
    private val voiceLock: Mutex,
    private val debugDump: File? = null,
    private val voiceFor: (Int) -> TtsEngine?,
) : Speaker {
    override suspend fun prepare(text: String, langCode: Int): Prepared? {
        val (tts, audio) = withContext(Dispatchers.Default) {
            voiceLock.withLock {
                val tts = voiceFor(langCode) ?: return@withContext null
                tts to tts.synthesize(text)
            }
        } ?: return null
        return object : Prepared {
            override val synthMs = audio.millis
            override suspend fun play() {
                debugDump?.takeIf { it.isDirectory }?.let { dir ->
                    runCatching { writePcm16MonoWav(File(dir, "${SystemClock.elapsedRealtime()}.wav"), audio.samples, audio.sampleRate) }
                }
                tts.play(audio)
            }
        }
    }

    override suspend fun preload(langCode: Int): Boolean =
        withContext(Dispatchers.Default) { voiceLock.withLock { voiceFor(langCode) != null } }
}
