package com.itantra

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.itantra.audio.AudioRecorder
import com.itantra.bluetooth.Bluetooth
import com.itantra.bluetooth.BluetoothTransport
import com.itantra.comm.LinkState
import com.itantra.comm.LocalAddress
import com.itantra.comm.Transport
import com.itantra.comm.TcpTransport
import com.itantra.comm.localIpv4Addresses
import com.itantra.session.DeviceListener
import com.itantra.session.DeviceSpeaker
import com.itantra.session.Direction
import com.itantra.session.SessionManager
import com.itantra.session.SessionState
import com.itantra.session.Status
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
 * `am start -n com.itantra/.MainActivity --es mode host|join|solo [--es peer <ip>]`, or over Bluetooth
 * `--es link bt --es mode join --es peer <MAC> [--es peer_name <name>]`.
 */
class MainViewModel(app: Application, handle: SavedStateHandle) : AndroidViewModel(app) {

    enum class Mode { SOLO, HOST, JOIN }

    enum class Link { WIFI, BLUETOOTH }

    data class UiState(
        val modelsReady: Boolean = false,
        val error: String? = null,
        /** null = start screen. */
        val mode: Mode? = null,
        val link: Link = Link.WIFI,
        /** IP (Wi-Fi) or MAC address (Bluetooth) of the host when joining. */
        val peer: String = "",
        /** What to show for [peer]: the IP, or the Bluetooth device name. */
        val peerName: String = "",
        val lastPeer: String = "",
        val lastBtAddress: String = "",
        val hostAddresses: List<LocalAddress> = emptyList(),
        /** This phone's Bluetooth name, shown on the Bluetooth host card. */
        val ownBtName: String = "",
        /** Joining phone: how long the successful connect attempt took ("connecting" → "connected"). */
        val setupMs: Long? = null,
        /** When an established link dropped (elapsedRealtime); null while connected or before the first connect. */
        val linkDownSince: Long? = null,
        /** When the link last came back after a drop, and how long it had been down. */
        val reconnectedAt: Long? = null,
        val lastOutageMs: Long? = null,
        val session: SessionState = SessionState(),
    )

    private val prefs = app.getSharedPreferences("itantra", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(
        UiState(
            lastPeer = prefs.getString(KEY_LAST_PEER, "").orEmpty(),
            lastBtAddress = prefs.getString(KEY_LAST_BT, "").orEmpty(),
        )
    )
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
        val link = if (handle.get<String>("link")?.lowercase() == "bt") Link.BLUETOOTH else Link.WIFI
        when (handle.get<String>("mode")?.lowercase()) {
            "host" -> startSession(Mode.HOST, link = link)
            "join" -> {
                val peer = handle.get<String>("peer") ?: "127.0.0.1"
                startSession(Mode.JOIN, peer, link, handle.get<String>("peer_name") ?: peer)
            }
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

    /**
     * Starts a session. For [Link.WIFI] joins, [peer] is the host's IP; for [Link.BLUETOOTH] joins it's
     * the paired host's MAC address and [peerName] its device name.
     */
    fun startSession(mode: Mode, peer: String = "", link: Link = Link.WIFI, peerName: String = peer) {
        if (_state.value.mode != null) return
        val peerId = peer.trim()
        val bt = link == Link.BLUETOOTH && mode != Mode.SOLO
        if (mode == Mode.JOIN) prefs.edit().putString(if (bt) KEY_LAST_BT else KEY_LAST_PEER, peerId).apply()
        _state.update {
            it.copy(
                mode = mode,
                link = if (mode == Mode.SOLO) Link.WIFI else link,
                peer = peerId,
                peerName = peerName,
                lastPeer = if (mode == Mode.JOIN && !bt) peerId else it.lastPeer,
                lastBtAddress = if (mode == Mode.JOIN && bt) peerId else it.lastBtAddress,
                session = SessionState(),
            )
        }
        sessionJob = viewModelScope.launch {
            enginesReady.await()
            val transport = try {
                when {
                    mode == Mode.SOLO -> null
                    bt -> {
                        val adapter = Bluetooth.adapter(getApplication())
                            ?: error("This phone has no Bluetooth")
                        if (mode == Mode.HOST) BluetoothTransport.host(adapter)
                        else BluetoothTransport.join(adapter, peerId, peerName)
                    }
                    mode == Mode.HOST -> TcpTransport.host()
                    else -> TcpTransport.join(peerId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Could not start the link", e)
                _state.update { it.copy(error = "Could not start the link: ${e.message}") }
                return@launch
            }
            Log.i(TAG, "Session mode $mode over ${if (bt) "Bluetooth" else "Wi-Fi"}" +
                if (mode == Mode.JOIN) " → $peerName ($peerId)" else "")
            val sm = SessionManager(
                this,
                DeviceListener(AudioRecorder(), checkNotNull(vad), checkNotNull(stt)),
                DeviceSpeaker(checkNotNull(tts)),
                transport,
                clock = SystemClock::elapsedRealtime,
            )
            session = sm
            sm.start()
            val bench = BenchmarkLog(getApplication<Application>().filesDir, mode.name, if (bt) "bt" else "wifi")
            if (transport != null) launch { trackLink(transport, bench) }
            if (mode == Mode.HOST && bt) {
                val name = runCatching { Bluetooth.adapter(getApplication())?.name }.getOrNull().orEmpty()
                _state.update { it.copy(ownBtName = name) }
            }
            if (mode == Mode.HOST && !bt) {
                // The hotspot may be switched on after the session starts, so keep refreshing.
                launch {
                    while (true) {
                        val addresses = withContext(Dispatchers.IO) { localIpv4Addresses(wifiClientInterfaces()) }
                        _state.update { it.copy(hostAddresses = addresses) }
                        delay(3_000)
                    }
                }
            }
            var logged = emptySet<String>()
            sm.state.collect { s ->
                _state.update { it.copy(session = s) }
                logged = logNewEvents(s, logged, bench)
            }
        }
    }

    /**
     * Follows the link to record:
     * - setup time on the joining phone: start of the connect attempt that succeeded → connected. Earlier
     *   failed attempts (e.g. the host was still loading its models) are only logged as the total.
     *   A host's "listening → connected" is waiting for the other person and isn't recorded.
     * - outages: when an established link drops and how long it takes to come back (links.csv).
     */
    private suspend fun trackLink(transport: Transport, bench: BenchmarkLog) {
        var firstAttemptAt: Long? = null
        var attemptAt: Long? = null
        var everConnected = false
        var downSince: Long? = null
        transport.state.collect { link ->
            val now = SystemClock.elapsedRealtime()
            when (link) {
                is LinkState.Connecting -> {
                    if (firstAttemptAt == null) firstAttemptAt = now
                    attemptAt = now
                }
                is LinkState.Connected -> {
                    val setup = attemptAt?.let { now - it }
                    if (setup != null) {
                        Log.i(TAG, "connected to ${link.peer} in $setup ms " +
                            "(${now - firstAttemptAt!!} ms including earlier attempts)")
                    }
                    val outage = downSince?.let { now - it }
                    if (outage != null) {
                        Log.i(TAG, "reconnected to ${link.peer} after $outage ms outage")
                        withContext(Dispatchers.IO) {
                            runCatching { bench.linkEvent("reconnected", outage, "setup=${setup ?: ""} ms") }
                        }
                    }
                    everConnected = true
                    downSince = null
                    firstAttemptAt = null
                    attemptAt = null
                    _state.update {
                        it.copy(
                            setupMs = setup,
                            linkDownSince = null,
                            reconnectedAt = if (outage != null) now else it.reconnectedAt,
                            lastOutageMs = outage ?: it.lastOutageMs,
                        )
                    }
                }
                is LinkState.Disconnected -> if (everConnected && downSince == null) {
                    downSince = now
                    Log.i(TAG, "link lost: ${link.reason}")
                    withContext(Dispatchers.IO) { runCatching { bench.linkEvent("lost", null, link.reason) } }
                    _state.update { it.copy(linkDownSince = now) }
                }
                else -> Unit
            }
        }
    }

    /** Interfaces of Wi-Fi networks this phone has joined, so the hotspot can be told apart from them. */
    private fun wifiClientInterfaces(): Set<String> {
        val cm = getApplication<Application>().getSystemService(ConnectivityManager::class.java) ?: return emptySet()
        @Suppress("DEPRECATION") // allNetworks is the simplest way to see every joined network at once
        return cm.allNetworks.mapNotNull { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (isJoinedWifi(caps)) cm.getLinkProperties(network)?.interfaceName else null
        }.toSet()
    }

    /**
     * Android 15+ also lists this phone's own hotspot as a WIFI-transport network, but flagged
     * LOCAL_NETWORK and without WifiInfo (seen on the S25: swlan0). Only a network we joined
     * carries WifiInfo (SSID, BSSID…).
     */
    private fun isJoinedWifi(caps: NetworkCapabilities): Boolean {
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_LOCAL_NETWORK)
        ) return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || caps.transportInfo is WifiInfo
    }

    fun leaveSession() {
        session?.close()
        session = null
        sessionJob?.cancel()
        sessionJob = null
        _state.update {
            it.copy(
                mode = null, error = null, session = SessionState(), hostAddresses = emptyList(), setupMs = null,
                linkDownSince = null, reconnectedAt = null, lastOutageMs = null,
            )
        }
    }

    fun onPressStart() = session?.pressStart() ?: false

    fun onPressEnd() = session?.pressEnd()

    /** Logs each message once per status change, so logcat shows the whole conversation. */
    private fun logNewEvents(s: SessionState, seen: Set<String>, bench: BenchmarkLog): Set<String> {
        val keys = s.messages.map { m -> "${m.id}:${m.status}" to m }
        for ((key, m) in keys) {
            if (key in seen) continue
            if (m.status in FINAL_STATUSES) {
                viewModelScope.launch(Dispatchers.IO) {
                    runCatching { bench.append(m, _state.value.setupMs) }.onFailure { Log.w(TAG, "benchmark log failed", it) }
                }
            }
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
        const val KEY_LAST_BT = "last_bt_address"
        val FINAL_STATUSES = setOf(Status.ACKED, Status.FAILED, Status.PLAYED)
    }
}
