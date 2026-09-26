package com.itantra

import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.itantra.audio.AudioRecorder
import com.itantra.comm.LocalAddress
import com.itantra.comm.TcpTransport
import com.itantra.comm.localIpv4Addresses
import com.itantra.session.DeviceListener
import com.itantra.session.DeviceSpeaker
import com.itantra.session.Direction
import com.itantra.session.SessionManager
import com.itantra.session.SessionState
import com.itantra.speech.SttEngine
import com.itantra.speech.TtsEngine
import com.itantra.speech.VadTrimmer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Loads the speech engines once, then runs one [SessionManager] at a time in the mode picked
 * on the start screen. For scripted tests a session can also start from the launch intent:
 * `am start -n com.itantra/.MainActivity --es mode host|join|solo [--es peer <ip>]`.
 */
class MainViewModel(app: Application, handle: SavedStateHandle) : AndroidViewModel(app) {

    enum class Mode { SOLO, HOST, JOIN }

    data class UiState(
        val modelsReady: Boolean = false,
        val error: String? = null,
        /** null = start screen. */
        val mode: Mode? = null,
        val peer: String = "",
        val lastPeer: String = "",
        val hostAddresses: List<LocalAddress> = emptyList(),
        val session: SessionState = SessionState(),
    )

    private val prefs = app.getSharedPreferences("itantra", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(UiState(lastPeer = prefs.getString(KEY_LAST_PEER, "").orEmpty()))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val enginesReady = CompletableDeferred<Unit>()
    private var vad: VadTrimmer? = null
    private var stt: SttEngine? = null
    private var tts: TtsEngine? = null
    private var session: SessionManager? = null
    private var sessionJob: Job? = null

    init {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.Default) { loadEngines() }
                enginesReady.complete(Unit)
                _state.update { it.copy(modelsReady = true) }
            } catch (e: Exception) {
                Log.e(TAG, "Could not load models", e)
                _state.update { it.copy(error = "Could not load models: ${e.message}") }
            }
        }
        when (handle.get<String>("mode")?.lowercase()) {
            "host" -> startSession(Mode.HOST)
            "join" -> startSession(Mode.JOIN, handle.get<String>("peer") ?: "127.0.0.1")
            "solo" -> startSession(Mode.SOLO)
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

    fun startSession(mode: Mode, peer: String = "") {
        if (_state.value.mode != null) return
        val peerIp = peer.trim()
        if (mode == Mode.JOIN) prefs.edit().putString(KEY_LAST_PEER, peerIp).apply()
        _state.update {
            it.copy(mode = mode, peer = peerIp, lastPeer = if (mode == Mode.JOIN) peerIp else it.lastPeer,
                session = SessionState())
        }
        sessionJob = viewModelScope.launch {
            enginesReady.await()
            val transport = when (mode) {
                Mode.HOST -> TcpTransport.host()
                Mode.JOIN -> TcpTransport.join(peerIp)
                Mode.SOLO -> null
            }
            Log.i(TAG, "Session mode $mode" + if (mode == Mode.JOIN) " → $peerIp" else "")
            val sm = SessionManager(
                this,
                DeviceListener(AudioRecorder(), checkNotNull(vad), checkNotNull(stt)),
                DeviceSpeaker(checkNotNull(tts)),
                transport,
                clock = SystemClock::elapsedRealtime,
            )
            session = sm
            sm.start()
            if (mode == Mode.HOST) {
                // The hotspot may be switched on after the session starts, so keep refreshing.
                launch {
                    while (true) {
                        val addresses = withContext(Dispatchers.IO) { localIpv4Addresses() }
                        _state.update { it.copy(hostAddresses = addresses) }
                        delay(3_000)
                    }
                }
            }
            var logged = emptySet<String>()
            sm.state.collect { s ->
                _state.update { it.copy(session = s) }
                logged = logNewEvents(s, logged)
            }
        }
    }

    fun leaveSession() {
        session?.close()
        session = null
        sessionJob?.cancel()
        sessionJob = null
        _state.update { it.copy(mode = null, session = SessionState(), hostAddresses = emptyList()) }
    }

    fun onPressStart() = session?.pressStart() ?: false

    fun onPressEnd() = session?.pressEnd()

    /** Logs each message once per status change, so logcat shows the whole conversation. */
    private fun logNewEvents(s: SessionState, seen: Set<String>): Set<String> {
        val keys = s.messages.map { m -> "${m.id}:${m.status}" to m }
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
                    "peerQueue=${m.peerQueueMs} rtt=${m.rttMs} e2e=${m.endToEndMs} other=${m.otherMs} | ${m.text}"
            )
        }
        return keys.map { it.first }.toSet()
    }

    override fun onCleared() {
        leaveSession()
        vad?.release()
        stt?.release()
        tts?.release()
    }

    private companion object {
        const val TAG = "iTantra"
        const val KEY_LAST_PEER = "last_peer"
    }
}
