package com.itantra

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.itantra.audio.AudioRecorder
import com.itantra.comm.TcpTransport
import com.itantra.session.DeviceListener
import com.itantra.session.DeviceSpeaker
import com.itantra.session.Direction
import com.itantra.session.SessionManager
import com.itantra.session.SessionState
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

/**
 * Loads the speech engines once and runs a [SessionManager] in the chosen mode.
 * Mode comes from the launch intent for now: `am start -n com.itantra/.MainActivity --es mode host`
 * (host | join, with `--es peer <ip>` | solo, the default).
 */
class MainViewModel(app: Application, handle: SavedStateHandle) : AndroidViewModel(app) {

    enum class Mode { SOLO, HOST, JOIN }

    data class UiState(
        val mode: Mode,
        val loading: Boolean = true,
        val error: String? = null,
        val session: SessionState = SessionState(),
    )

    private val mode = when (handle.get<String>("mode")?.lowercase()) {
        "host" -> Mode.HOST
        "join" -> Mode.JOIN
        else -> Mode.SOLO
    }
    private val peer: String = handle.get<String>("peer") ?: "127.0.0.1"

    private val _state = MutableStateFlow(UiState(mode))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var vad: VadTrimmer? = null
    private var stt: SttEngine? = null
    private var tts: TtsEngine? = null
    private var session: SessionManager? = null

    init {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.Default) { loadEngines() }
            } catch (e: Exception) {
                Log.e(TAG, "Could not load models", e)
                _state.update { it.copy(loading = false, error = "Could not load models: ${e.message}") }
                return@launch
            }
            val transport = when (mode) {
                Mode.HOST -> TcpTransport.host()
                Mode.JOIN -> TcpTransport.join(peer)
                Mode.SOLO -> null
            }
            Log.i(TAG, "Session mode $mode" + if (mode == Mode.JOIN) " → $peer" else "")
            val sm = SessionManager(
                viewModelScope,
                DeviceListener(AudioRecorder(), checkNotNull(vad), checkNotNull(stt)),
                DeviceSpeaker(checkNotNull(tts)),
                transport,
                clock = SystemClock::elapsedRealtime,
            )
            session = sm
            sm.start()
            var logged = emptySet<String>()
            sm.state.collect { s ->
                _state.update { it.copy(loading = false, session = s) }
                logged = logNewEvents(s, logged)
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

    /** Logs each message once per status change, so logcat shows the whole conversation. */
    private fun logNewEvents(s: SessionState, seen: Set<String>): Set<String> {
        val keys = s.messages.map { m ->
            "${m.id}:${m.status}" to m
        }
        for ((key, m) in keys) {
            if (key in seen) continue
            val dir = when (m.direction) {
                Direction.OUTGOING -> "OUT"
                Direction.INCOMING -> "IN "
                Direction.LOCAL -> "LOC"
            }
            Log.i(
                TAG, "$dir #${m.seq ?: "-"} ${m.status} ${m.wireBytes ?: "-"} B | vad=${m.vadMs} stt=${m.sttMs} " +
                    "tts=${m.ttsMs} queue=${m.queueMs} ackAfter=${m.ackAfterMs} peerTts=${m.peerTtsMs} " +
                    "peerQueue=${m.peerQueueMs} | ${m.text}"
            )
        }
        return keys.map { it.first }.toSet()
    }

    fun onPressStart() = session?.pressStart() ?: false

    fun onPressEnd() = session?.pressEnd()

    override fun onCleared() {
        session?.close()
        vad?.release()
        stt?.release()
        tts?.release()
    }

    private companion object {
        const val TAG = "iTantra"
    }
}
