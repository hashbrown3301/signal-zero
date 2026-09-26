package com.itantra

import android.Manifest
import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.itantra.audio.AudioRecorder
import com.itantra.speech.SttEngine
import com.itantra.speech.TtsEngine
import com.itantra.speech.VadTrimmer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Hold-to-talk pipeline: record → VAD → STT → TTS → playback, with per-stage timings. */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    enum class Phase { Loading, Ready, Listening, Processing, Speaking, Error }

    data class Timings(
        val recordedSec: Float,
        val speechSec: Float,
        val vadMs: Long,
        val sttMs: Long? = null,
        val ttsMs: Long? = null,
    ) {
        /** Time from releasing the button until playback starts. */
        val totalMs: Long get() = vadMs + (sttMs ?: 0) + (ttsMs ?: 0)
    }

    data class UiState(
        val phase: Phase = Phase.Loading,
        val transcript: String = "",
        val timings: Timings? = null,
        val message: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val recorder = AudioRecorder()
    private var vad: VadTrimmer? = null
    private var stt: SttEngine? = null
    private var tts: TtsEngine? = null
    private var pressStartedAt = 0L

    init {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.Default) { loadEngines() }
                _state.update { it.copy(phase = Phase.Ready) }
            } catch (e: Exception) {
                fail("Could not load models", e)
            }
        }
    }

    private fun loadEngines() {
        val context = getApplication<Application>()
        var t = SystemClock.elapsedRealtime()
        vad = VadTrimmer(context.assets)
        Log.i(TAG, "VAD loaded in ${SystemClock.elapsedRealtime() - t} ms")
        t = SystemClock.elapsedRealtime()
        stt = SttEngine(context.assets)
        Log.i(TAG, "STT loaded in ${SystemClock.elapsedRealtime() - t} ms")
        t = SystemClock.elapsedRealtime()
        tts = TtsEngine(context)
        Log.i(TAG, "TTS loaded in ${SystemClock.elapsedRealtime() - t} ms")
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun onPressStart() {
        if (_state.value.phase != Phase.Ready) return
        try {
            recorder.start()
        } catch (e: Exception) {
            fail("Microphone unavailable", e)
            return
        }
        pressStartedAt = SystemClock.elapsedRealtime()
        _state.update { it.copy(phase = Phase.Listening, message = null) }
    }

    fun onPressEnd() {
        if (_state.value.phase != Phase.Listening) return
        val audio = recorder.stop()
        if (SystemClock.elapsedRealtime() - pressStartedAt < MIN_PRESS_MS) {
            _state.update { it.copy(phase = Phase.Ready, message = "Hold the button while you speak") }
            return
        }
        _state.update { it.copy(phase = Phase.Processing) }
        viewModelScope.launch {
            try {
                runPipeline(audio)
            } catch (e: Exception) {
                fail("Processing failed", e)
            }
        }
    }

    private suspend fun runPipeline(audio: FloatArray) {
        val vad = checkNotNull(vad)
        val stt = checkNotNull(stt)
        val tts = checkNotNull(tts)

        val trimmed = withContext(Dispatchers.Default) { vad.trim(audio) }
        var timings = Timings(
            recordedSec = audio.size / SAMPLE_RATE,
            speechSec = trimmed.speech.size / SAMPLE_RATE,
            vadMs = trimmed.millis,
        )
        if (trimmed.isEmpty) return noSpeech(timings)

        val result = withContext(Dispatchers.Default) { stt.transcribe(trimmed.speech) }
        timings = timings.copy(sttMs = result.millis)
        if (result.text.isBlank()) return noSpeech(timings)
        _state.update { it.copy(transcript = result.text, timings = timings) }

        val spoken = withContext(Dispatchers.Default) { tts.synthesize(result.text) }
        timings = timings.copy(ttsMs = spoken.millis)
        Log.i(
            TAG, "recorded %.2f s, speech %.2f s | VAD %d ms, STT %d ms, TTS %d ms, total %d ms | %s".format(
                timings.recordedSec, timings.speechSec, timings.vadMs, timings.sttMs,
                timings.ttsMs, timings.totalMs, result.text,
            )
        )
        _state.update { it.copy(phase = Phase.Speaking, timings = timings) }
        tts.play(spoken)
        _state.update { it.copy(phase = Phase.Ready) }
    }

    private fun noSpeech(timings: Timings) {
        Log.i(TAG, "No speech detected (recorded %.2f s)".format(timings.recordedSec))
        _state.update {
            it.copy(phase = Phase.Ready, transcript = "", timings = timings, message = "कुछ सुनाई नहीं दिया")
        }
    }

    private fun fail(what: String, e: Exception) {
        Log.e(TAG, what, e)
        _state.update { it.copy(phase = Phase.Error, message = "$what: ${e.message}") }
    }

    override fun onCleared() {
        if (recorder.isRecording) recorder.stop()
        vad?.release()
        stt?.release()
        tts?.release()
    }

    private companion object {
        const val TAG = "iTantra"
        const val SAMPLE_RATE = 16_000f
        const val MIN_PRESS_MS = 300
    }
}
