package com.itantra

import android.app.ActivityManager
import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import java.io.File
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
import com.itantra.packs.CatalogEntry
import com.itantra.packs.PackDownloader
import com.itantra.packs.PackManifest
import com.itantra.packs.PackRepository
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.flow.first
import com.itantra.speech.EngineFactory
import com.itantra.speech.VoiceCache
import com.itantra.comm.Language
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

    /** A language the phone knows about (from built-in and installed packs). */
    data class LanguageOption(
        val iso: String,
        val name: String,
        val native: String,
        val code: Int,
        val hasSpeak: Boolean,
        val hasListen: Boolean,
    )

    /** A pack download in progress (or failed, with [error]). */
    data class DownloadUi(val downloaded: Long = 0, val total: Long = 0, val error: String? = null)

    data class PacksUi(
        val builtIn: List<PackManifest> = emptyList(),
        val installed: List<PackManifest> = emptyList(),
        /** Every downloadable pack (all 10 languages), from the catalogue. */
        val catalog: List<Pair<String, CatalogEntry>> = emptyList(),
        /** Downloads by pack id; at most one runs at a time. */
        val downloads: Map<String, DownloadUi> = emptyMap(),
        val busy: Boolean = false,
        /** Result of the last install/delete, e.g. "Installed Tamil (listen)" or an error. */
        val message: String? = null,
        val messageIsError: Boolean = false,
        /** Where `adb push` should put pack zips. */
        val incomingPath: String = "",
    )

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
        /** The language this phone speaks (ISO code of its speak pack), and which languages are installed. */
        val myLanguage: String = "hi",
        val languages: List<LanguageOption> = emptyList(),
        /** Language whose models are loading right now (switching languages), or null. */
        val loadingLanguage: String? = null,
        /** The "Language packs" screen is open (on top of the start screen). */
        val showPacks: Boolean = false,
        val packs: PacksUi = PacksUi(),
        val session: SessionState = SessionState(),
    )

    private val prefs = app.getSharedPreferences("itantra", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(
        UiState(
            lastPeer = prefs.getString(KEY_LAST_PEER, "").orEmpty(),
            lastBtAddress = prefs.getString(KEY_LAST_BT, "").orEmpty(),
            myLanguage = prefs.getString(KEY_MY_LANGUAGE, "hi") ?: "hi",
        )
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val factory by lazy { EngineFactory(getApplication()) }
    private val packRepo by lazy { PackRepository(getApplication()) }
    /** Guards [vad] and [stt]: held while they run and while they're released. Taken before [voiceLock] when both are needed. */
    private val engineLock = Mutex()
    /** Guards [voices]: held while a voice is looked up and synthesizes, and while voices are evicted or released. */
    private val voiceLock = Mutex()
    private var vad: VadTrimmer? = null
    private var stt: SttEngine? = null
    /**
     * Voices by packet language code, loaded when a message in that language first needs one. Keeps 2 voices,
     * 1 on Android "low RAM" phones (e.g. the A03 Core). The phone's own voice is only needed in Solo, so it isn't pinned.
     */
    private val lowRam = app.getSystemService(ActivityManager::class.java)?.isLowRamDevice == true
    private val voices = VoiceCache(
        capacity = if (lowRam) 1 else 2,
        load = { code -> loadVoice(code) },
        release = { it.release() },
    )

    private fun loadVoice(code: Int): TtsEngine? {
        val iso = Language.fromCode(code)?.iso ?: return null
        val pack = packRepo.find(iso, PackManifest.KIND_LISTEN) ?: return null
        val t = SystemClock.elapsedRealtime()
        return factory.tts(pack).also {
            Log.i(TAG, "TTS ${pack.manifest.id} loaded in ${SystemClock.elapsedRealtime() - t} ms " +
                "(voices in memory: ${voices.loaded().size + 1}, max ${if (lowRam) 1 else 2})")
        }
    }
    private var session: SessionManager? = null
    private var sessionJob: Job? = null

    init {
        viewModelScope.launch {
            refreshLanguages()
            val saved = _state.value.myLanguage
            val usable = _state.value.languages.any { it.iso == saved && it.hasSpeak }
            loadLanguage(if (usable) saved else "hi")
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

    /** Rebuilds the language list from built-in + installed packs. */
    private suspend fun refreshLanguages() {
        val packs = withContext(Dispatchers.IO) { packRepo.builtIn() + packRepo.installed() }
        val options = packs.groupBy { it.lang }.map { (iso, list) ->
            val first = list.first()
            LanguageOption(iso, first.name, first.native, first.packetCode,
                hasSpeak = list.any { it.isSpeak }, hasListen = list.any { it.isListen })
        }.sortedBy { it.code }
        _state.update { it.copy(languages = options) }
    }

    /**
     * Makes [iso] this phone's language: releases the current STT and voice, then loads that language's speak pack
     * and (if installed) listen pack. Only one STT is ever in memory (docs/PHASE3_PLAN.md).
     */
    fun selectLanguage(iso: String) {
        if (_state.value.mode != null || _state.value.loadingLanguage != null) return
        if (iso == _state.value.myLanguage && _state.value.modelsReady) return
        viewModelScope.launch { loadLanguage(iso) }
    }

    private suspend fun loadLanguage(iso: String) {
        _state.update { it.copy(loadingLanguage = iso, modelsReady = false, error = null) }
        try {
            val took = withContext(Dispatchers.Default) {
                engineLock.withLock {
                    val start = SystemClock.elapsedRealtime()
                    val context = getApplication<Application>()
                    if (vad == null) {
                        vad = VadTrimmer(context.assets)
                        Log.i(TAG, "VAD loaded in ${SystemClock.elapsedRealtime() - start} ms")
                    }
                    stt?.release()
                    stt = null
                    voiceLock.withLock { voices.clear() }

                    val speak = packRepo.find(iso, PackManifest.KIND_SPEAK) ?: error("No speak pack installed for $iso")
                    coroutineScope {
                        // Preload this language's voice so the first Solo reply is quick; it loads alongside the STT
                        // (independent native loads), so switching costs about max(STT, voice) instead of the sum.
                        val voice = async { Language.fromIso(iso)?.let { voiceLock.withLock { voices.get(it.code) } } }
                        val t = SystemClock.elapsedRealtime()
                        stt = factory.stt(speak)
                        Log.i(TAG, "STT ${speak.manifest.id} loaded in ${SystemClock.elapsedRealtime() - t} ms")
                        voice.await()
                    }
                    SystemClock.elapsedRealtime() - start
                }
            }
            Log.i(TAG, "language $iso ready in $took ms")
            prefs.edit().putString(KEY_MY_LANGUAGE, iso).apply()
            _state.update { it.copy(myLanguage = iso, modelsReady = true, loadingLanguage = null) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Could not load language $iso", e)
            _state.update { it.copy(loadingLanguage = null, error = "Could not load $iso: ${e.message}") }
            if (iso != "hi") loadLanguage("hi")  // fall back to the built-in language
        }
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
            _state.first { it.modelsReady && it.loadingLanguage == null }
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Could not start the link", e)
                _state.update { it.copy(error = "Could not start the link: ${e.message}") }
                return@launch
            }
            Log.i(TAG, "Session mode $mode over ${if (bt) "Bluetooth" else "Wi-Fi"}" +
                if (mode == Mode.JOIN) " → $peerName ($peerId)" else "")
            val language = Language.fromIso(_state.value.myLanguage) ?: Language.HINDI
            // Read the engines under their lock: a language load may have been queued behind the one that finished.
            val listener = engineLock.withLock {
                DeviceListener(AudioRecorder(), checkNotNull(vad), checkNotNull(stt), engineLock, debugFile("debug_mic.wav"))
            }
            val sm = SessionManager(
                this,
                listener,
                DeviceSpeaker(voiceLock, debugFile("tts_dump")) { code -> voices.get(code) },
                transport,
                language = language,
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

    // ---------- language packs ----------


    fun openPacks() {
        _state.update { it.copy(showPacks = true, packs = it.packs.copy(message = null)) }
        // Pick up anything sideloaded with adb since the last visit.
        packAction { repo ->
            val results = repo.installIncoming()
            val ok = results.mapNotNull { it.getOrNull() }
            val failed = results.mapNotNull { it.exceptionOrNull()?.message }
            val lines = ok.map { "Installed ${it.name} (${it.kind}) from sideload" } + failed.map { "Rejected: $it" }
            if (lines.isEmpty()) null else lines.joinToString("\n") to failed.isNotEmpty()
        }
    }

    fun closePacks() = _state.update { it.copy(showPacks = false) }

    private var downloadJob: Job? = null

    /** Downloads and installs pack [id]; progress shows on the Language packs screen. One download at a time. */
    fun downloadPack(id: String) {
        if (downloadJob?.isActive == true) return
        updateDownload(id) { DownloadUi() }
        downloadJob = viewModelScope.launch {
            val job = coroutineContext[Job]
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    packRepo.downloadAndInstall(id) { done, total ->
                        updateDownload(id) { it.copy(downloaded = done, total = total) }
                        job?.isActive == true  // false = cancel
                    }
                }
            }
            result.onSuccess { m ->
                _state.update { it.copy(packs = it.packs.copy(downloads = it.packs.downloads - id)) }
                packAction { "Installed ${m.name} (${m.kind}), ${"%.1f".format(m.size / 1e6)} MB" to false }
            }.onFailure { e ->
                if (e is PackDownloader.Cancelled || e is kotlinx.coroutines.CancellationException) {
                    _state.update { it.copy(packs = it.packs.copy(downloads = it.packs.downloads - id)) }
                } else {
                    Log.w(TAG, "download $id failed", e)
                    updateDownload(id) { it.copy(error = e.message ?: e.toString()) }
                }
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
    }

    /** Fetches the latest pack list from the internet (the bundled list works offline). */
    fun refreshCatalog() = packAction { repo ->
        repo.refreshCatalog()
        "Pack list updated" to false
    }

    private fun updateDownload(id: String, change: (DownloadUi) -> DownloadUi) = _state.update {
        it.copy(packs = it.packs.copy(downloads = it.packs.downloads + (id to change(it.packs.downloads[id] ?: DownloadUi()))))
    }

    fun importPack(uri: Uri) = packAction { repo ->
        val m = repo.installFromUri(uri)
        "Installed ${m.name} (${m.kind}), ${"%.1f".format(m.size / 1e6)} MB" to false
    }

    fun deletePack(id: String) = packAction { repo ->
        val pack = repo.installed().firstOrNull { it.id == id }
        // The running session holds this phone's STT, so its speak pack can't go until the session ends.
        if (pack != null && pack.isSpeak && pack.lang == _state.value.myLanguage && _state.value.mode != null) {
            return@packAction "End the session before deleting ${pack.name}, the language you speak" to true
        }
        if (pack?.isListen == true) voiceLock.withLock { voices.evict(pack.packetCode) }
        if (repo.delete(id)) "Deleted $id" to false else "$id was not installed" to true
    }

    /** Runs [action] off the main thread, then refreshes the pack lists; errors become the screen's message. */
    private fun packAction(action: suspend (PackRepository) -> Pair<String, Boolean>?) {
        _state.update { it.copy(packs = it.packs.copy(busy = true)) }
        viewModelScope.launch {
            val (message, isError) = withContext(Dispatchers.IO) {
                try {
                    action(packRepo) ?: (null to false)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "pack action failed", e)
                    (e.message ?: e.toString()) to true
                }
            }
            val (builtIn, installed, incoming) = withContext(Dispatchers.IO) {
                Triple(packRepo.builtIn(), packRepo.installed(), packRepo.incomingDir?.absolutePath.orEmpty())
            }
            val catalog = withContext(Dispatchers.IO) { runCatching { packRepo.catalog().sorted }.getOrDefault(emptyList()) }
            refreshLanguages()
            val mine = _state.value.myLanguage
            if (_state.value.mode == null && _state.value.loadingLanguage == null &&
                _state.value.languages.none { it.iso == mine && it.hasSpeak }
            ) loadLanguage("hi")
            _state.update {
                it.copy(
                    packs = it.packs.copy(builtIn = builtIn, installed = installed, catalog = catalog, busy = false,
                        message = message ?: it.packs.message,
                        messageIsError = if (message != null) isError else it.packs.messageIsError, incomingPath = incoming),
                )
            }
        }
    }

    fun onPressStart() = session?.pressStart() ?: false

    fun onPressEnd() = session?.pressEnd()

    /** Logs each message once per status change, so logcat shows the whole conversation. */
    private fun logNewEvents(s: SessionState, seen: Set<String>, bench: BenchmarkLog): Set<String> {
        val keys = s.messages.map { m -> "${m.id}:${m.status}" to m }
        for ((key, m) in keys) {
            if (key in seen) continue
            if (m.status in LOGGED_STATUSES) {
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

    /**
     * Debug builds only: a path in the app's external files folder, where adb can reach it. debug_mic.wav stands in
     * for the mic once (DeviceListener); a tts_dump folder receives every played utterance (DeviceSpeaker).
     */
    private fun debugFile(name: String): File? {
        val app = getApplication<Application>()
        if (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return null
        return app.getExternalFilesDir(null)?.let { File(it, name) }
    }

    override fun onCleared() {
        leaveSession()
        // A cancelled STT or synthesis call keeps running until its native call returns, so release only once it
        // has let go of the lock. viewModelScope is already cancelled, hence a scope of its own.
        CoroutineScope(Dispatchers.Default).launch {
            engineLock.withLock {
                vad?.release()
                stt?.release()
                vad = null
                stt = null
            }
            voiceLock.withLock { voices.clear() }
        }
    }

    private companion object {
        const val TAG = "iTantra"
        const val KEY_LAST_PEER = "last_peer"
        const val KEY_LAST_BT = "last_bt_address"
        const val KEY_MY_LANGUAGE = "my_language"
        // SENT is logged too, so a message that never gets an ACK (a loss) still leaves a row.
        val LOGGED_STATUSES = setOf(Status.SENT, Status.ACKED, Status.FAILED, Status.PLAYED)
    }
}
