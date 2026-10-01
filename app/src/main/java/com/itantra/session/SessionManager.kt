package com.itantra.session

import com.itantra.comm.Language
import com.itantra.comm.LinkState
import com.itantra.comm.Packet
import com.itantra.comm.PacketCodec
import com.itantra.comm.PacketType
import com.itantra.comm.Transport
import com.itantra.translation.CriticalDetailChecker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

enum class Direction { OUTGOING, INCOMING, LOCAL }

enum class Status {
    SENT, ACKED, FAILED, QUEUED, PLAYING, PLAYED,

    /** Received, but this phone has no voice for the requested playback language: shown as text only. */
    NO_VOICE,

    /** Original text was received, but offline translation could not produce the requested language. */
    TRANSLATION_FAILED,

    /** Translated text has observable detail differences; model output waits for explicit playback consent. */
    NEEDS_REVIEW,
}

enum class InputOrigin { SPEECH, TYPED }

data class SourceDraft(
    val text: String,
    val originalText: String,
    val langCode: Int,
    val inputOrigin: InputOrigin = InputOrigin.SPEECH,
)

data class Message(
    val id: Int,
    val direction: Direction,
    val text: String,
    val status: Status,
    val seq: Int? = null,
    val wireBytes: Int? = null,
    val recordedSec: Float? = null,
    val speechSec: Float? = null,
    val vadMs: Long? = null,
    val sttMs: Long? = null,
    /** This phone's TTS time (INCOMING / LOCAL). */
    val ttsMs: Long? = null,
    /** All chunks' synthesis time, final after local playback completes. */
    val totalTtsMs: Long? = null,
    val voiceChunks: Int = 1,
    /** How long an INCOMING message waited before synthesis (talk button held, earlier playback). */
    val queueMs: Long? = null,
    /** OUTGOING: button release → ACK received, on this phone's clock. */
    val ackAfterMs: Long? = null,
    /** OUTGOING: the receiver's TTS and queue times, from its ACK. */
    val peerTtsMs: Long? = null,
    val peerQueueMs: Long? = null,
    /** OUTGOING: median PING/PONG round trip when the ACK arrived. */
    val rttMs: Long? = null,
    val error: String? = null,
    /** Packet language code (see comm.Language); null for messages from before Phase 3. */
    val langCode: Int? = null,
    /** OUTGOING: how many times the packet was sent again after a reconnect. */
    val resends: Int = 0,
    /** Translation for INCOMING / LOCAL messages; [text] always retains the original transcription. */
    val translatedText: String? = null,
    /** Language actually requested for playback, which may differ from [langCode]. */
    val outputLangCode: Int? = null,
    val translationMs: Long? = null,
    val translationError: String? = null,
    /** Unedited recognizer output when the user confirmed a corrected source. */
    val originalTranscript: String? = null,
    val inputOrigin: InputOrigin = InputOrigin.SPEECH,
    val warnings: List<String> = emptyList(),
    val translationOrigin: TranslationOrigin? = null,
) {
    /** One-way network estimate: RTT / 2. */
    val networkMs: Long? get() = rttMs?.let { it / 2 }

    /**
     * OUTGOING delivery/readiness estimate, all on this phone's clock:
     * (ACK received − release) − the ACK's trip back (RTT / 2).
     * Receipt can be text-only or translation failure; this is not proof of audible playback.
     */
    val endToEndMs: Long? get() = ackAfterMs?.let { after -> networkMs?.let { after - it } }

    /** Whatever [endToEndMs] contains beyond the named stages (recorder stop, encoding, …). */
    val otherMs: Long?
        get() = endToEndMs?.let {
            it - (vadMs ?: 0) - (sttMs ?: 0) - (networkMs ?: 0) - (peerQueueMs ?: 0) - (peerTtsMs ?: 0)
        }
}

enum class Phase { Ready, Listening, Processing, Reviewing, Maintenance }

data class SessionState(
    val phase: Phase = Phase.Ready,
    val speaking: Boolean = false,
    /** null in Solo mode. */
    val link: LinkState? = null,
    /** Kept separately because [link] moves on from Disconnected almost immediately. */
    val lastDisconnect: String? = null,
    val messages: List<Message> = emptyList(),
    val notice: String? = null,
    /** Median of the recent PING/PONG round trips; null until the first PONG. */
    val rttMs: Long? = null,
    /** True only while offline model translation runs; same-language and cached playback bypass it. */
    val translating: Boolean = false,
    val draft: SourceDraft? = null,
    val reviewBeforeSend: Boolean = false,
    /** Work waiting behind the playback worker; its active item is excluded. */
    val queuedMessages: Int = 0,
) {
    val canTalk: Boolean get() = (phase == Phase.Ready && !speaking) || phase == Phase.Listening
}

/**
 * The only place where speech meets the network.
 *
 * Talk: [pressStart]/[pressEnd] → [Listener] (VAD + STT) → TEXT packet (or local playback in Solo).
 * Receive: a non-blocking loop answers PING, matches ACKs and queues TEXT. A separate playback
 * worker waits while the talk button is held, synthesizes, sends the ACK, then plays.
 */
class SessionManager(
    parentScope: CoroutineScope,
    private val listener: Listener,
    private val speaker: Speaker,
    /** null = Solo mode (Phase 0 loop, no network). */
    private val transport: Transport?,
    /** The language this phone speaks: tags outgoing messages and picks the Solo voice. */
    private val language: Language = Language.HINDI,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val minPressMs: Long = 300,
    /** PING period while connected; 0 disables pinging. */
    private val pingIntervalMs: Long = 2_000,
    /** Once RTT is known, an idle link needs fewer keepalives than an active conversation. */
    private val idlePingIntervalMs: Long = 10_000,
    private val activeWindowMs: Long = 10_000,
    /** How long unacknowledged messages wait for the link to come back before they fail. */
    private val resendWindowMs: Long = 30_000,
    /** A healthy socket is not proof that the peer processed a message. */
    private val ackTimeoutMs: Long = 60_000,
    /** null preserves the original same-language playback path. */
    private val translator: Translator? = null,
    /** Requested listening language for incoming messages and Solo playback; null plays the original. */
    private val targetLanguage: Language? = null,
    reviewBeforeSend: Boolean = false,
    private val maxQueuedMessages: Int = 16,
) {
    init {
        require(pingIntervalMs >= 0 && idlePingIntervalMs > 0 && activeWindowMs >= 0)
        require(resendWindowMs > 0 && ackTimeoutMs > 0)
        require(maxQueuedMessages in 1..32)
    }
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)

    private val _state = MutableStateFlow(SessionState(link = transport?.state?.value, reviewBeforeSend = reviewBeforeSend))
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private sealed interface Playback {
        val messageId: Int
        val text: String

        val langCode: Int

        data class Remote(val packet: Packet, override val messageId: Int, val arrivedAt: Long) : Playback {
            override val text get() = packet.text
            override val langCode get() = packet.langCode
        }

        data class Local(override val messageId: Int, override val text: String, override val langCode: Int) : Playback

        data class Replay(
            override val messageId: Int,
            override val text: String,
            override val langCode: Int,
            val outputText: String,
            val outputLangCode: Int,
        ) : Playback
    }

    private data class Pending(val messageId: Int, val releasedAt: Long, val packet: Packet, val submittedAt: Long)

    /** Identity of a received TEXT: a resend repeats seq and timestamp. */
    private data class TextKey(val seq: Int, val timestamp: Long)

    private val talking = MutableStateFlow(false)
    private val playQueue = Channel<Playback>(maxQueuedMessages)
    private val actionLock = Any()
    private val activePlaybackIds = mutableSetOf<Int>() // queued plus the worker's active item, guarded by actionLock
    private var queuedWork = 0 // guarded by actionLock
    @Volatile private var closed = false
    private var started = false // guarded by actionLock
    private data class DraftSpeech(val heard: Heard, val releasedAt: Long)
    private var draftSpeech: DraftSpeech? = null // guarded by actionLock
    private val pending = ConcurrentHashMap<Int, Pending>()
    private var giveUp: Job? = null // only touched by the link-state collector
    /**
     * Received TEXT identities, oldest first, with their saved ACK (null while queued/in flight).
     * Keep every in-flight identity so a slow model cannot turn a resend into a duplicate playback.
     * Only completed ACK history is bounded to [SEEN_TEXTS]. Guarded by itself.
     */
    private val seen = LinkedHashMap<TextKey, Packet?>()
    private val nextId = AtomicInteger()
    private var nextSeq = 0
    private var pressedAt = 0L

    private data class Ping(val sentAt: Long, val timestamp: Long)
    private val pingSentAt = ConcurrentHashMap<Int, Ping>()
    @Volatile private var lastActivityAt = clock()
    private var nextPingSeq = 0
    /** Language code the peer's PINGs announced and whose voice was preloaded; -1 = none yet on this connection. */
    @Volatile private var preloadedLang = -1
    private val rttSamples = ArrayDeque<Long>() // guarded by itself

    fun start() {
        synchronized(actionLock) {
            if (closed || started) return
            started = true
        }
        scope.launch { playbackLoop() }
        if (transport == null && targetLanguage != null) {
            scope.launch { preloadVoice(targetLanguage.code) }
        }
        if (transport != null) {
            scope.launch {
                transport.state.collect { link ->
                    // A new connection may take a different path; start RTT fresh.
                    if (link is LinkState.Connected) {
                        giveUp?.cancel()
                        resendPending(transport)
                    } else {
                        resetRtt()
                        // Unacknowledged messages get resendWindowMs to be resent after a reconnect.
                        if (giveUp?.isActive != true) giveUp = scope.launch { delay(resendWindowMs); failPending() }
                        preloadedLang = -1 // the next peer may speak another language
                    }
                    _state.update {
                        it.copy(
                            link = link,
                            lastDisconnect = (link as? LinkState.Disconnected)?.reason ?: it.lastDisconnect,
                        )
                    }
                }
            }
            scope.launch { transport.incoming.collect(::onPacket) }
            if (pingIntervalMs > 0) scope.launch { pingLoop(transport) }
            scope.launch {
                while (true) {
                    delay(minOf(1_000, ackTimeoutMs))
                    if (transport.state.value is LinkState.Connected) {
                        val now = clock()
                        for ((seq, sent) in pending) {
                            if (now - sent.submittedAt >= ackTimeoutMs && pending.remove(seq, sent)) {
                                updateMessage(sent.messageId) {
                                    it.copy(status = Status.FAILED, error = "Delivery was not confirmed. Please try again.")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun pingLoop(transport: Transport) {
        while (true) {
            transport.state.first { it is LinkState.Connected }
            val seq = nextPingSeq++ and 0xFFFF
            val now = clock()
            val packet = Packet.ping(seq, now, language)
            pingSentAt[seq] = Ping(now, packet.timestamp)
            pingSentAt.entries.removeIf { now - it.value.sentAt > PING_TIMEOUT_MS } // lost PONGs
            try {
                transport.send(packet)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                pingSentAt.remove(seq)
            }
            val idle = _state.value.rttMs != null && clock() - lastActivityAt >= activeWindowMs
            delay(if (idle) maxOf(pingIntervalMs, idlePingIntervalMs) else pingIntervalMs)
        }
    }

    private fun onPong(p: Packet) {
        val ping = pingSentAt[p.seq] ?: return
        if (p.timestamp != ping.timestamp || !pingSentAt.remove(p.seq, ping)) return
        val rtt = clock() - ping.sentAt
        if (rtt < 0 || rtt > PING_TIMEOUT_MS) return
        val median = synchronized(rttSamples) {
            rttSamples.addLast(rtt)
            while (rttSamples.size > RTT_WINDOW) rttSamples.removeFirst()
            rttSamples.sorted()[rttSamples.size / 2]
        }
        _state.update { it.copy(rttMs = median) }
    }

    /** The link is back: send what is still unacknowledged again, unchanged. A failure leaves it for the next reconnect. */
    private suspend fun resendPending(transport: Transport) {
        for (sent in pending.values.sortedBy { it.messageId }) { // send order; seq wraps at 65536
            try {
                transport.send(sent.packet)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return
            }
            updateMessage(sent.messageId) { it.copy(resends = it.resends + 1) }
        }
    }

    /** The link stayed down for the whole window: no ACK can arrive for what is unacknowledged, so stop waiting. */
    private fun failPending() {
        for (seq in pending.keys.toList()) {
            val sent = pending.remove(seq) ?: continue // a late ACK won the race
            updateMessage(sent.messageId) { it.copy(status = Status.FAILED, error = "Link lost before delivery") }
        }
    }

    private fun resetRtt() {
        synchronized(rttSamples) { rttSamples.clear() }
        pingSentAt.clear()
        _state.update { it.copy(rttMs = null) }
    }

    /** Talk button down. Returns false if talking isn't possible right now. */
    fun pressStart(): Boolean = synchronized(actionLock) {
        val s = _state.value
        if (closed || s.phase != Phase.Ready || s.speaking || !hasDeliveryRoom()) return@synchronized false
        lastActivityAt = clock()
        try {
            listener.start()
        } catch (e: Exception) {
            _state.update { it.copy(notice = "Microphone unavailable: ${e.message}") }
            return@synchronized false
        }
        pressedAt = clock()
        talking.value = true
        _state.update { it.copy(phase = Phase.Listening, notice = null) }
        true
    }

    /** Talk button up. */
    fun pressEnd() {
        val releasedAt = synchronized(actionLock) {
            if (closed || _state.value.phase != Phase.Listening) return
            val now = clock()
            talking.value = false
            if (now - pressedAt < minPressMs) {
                try {
                    listener.cancel()
                    _state.update { it.copy(notice = "Hold the button while you speak") }
                } catch (e: Exception) {
                    _state.update { it.copy(notice = "Could not stop microphone: ${e.message ?: "capture failed"}") }
                } finally {
                    _state.update { it.copy(phase = Phase.Ready) }
                }
                return
            }
            _state.update { it.copy(phase = Phase.Processing) }
            now
        }
        scope.launch {
            try {
                handleSpeech(listener.finish(), releasedAt)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(notice = "Processing failed: ${e.message}") }
            } finally {
                finishProcessing()
            }
        }
    }

    /** Discards capture without recognition, translation, or transmission. */
    fun cancelPress(): Boolean = synchronized(actionLock) {
        if (closed || _state.value.phase != Phase.Listening) return@synchronized false
        talking.value = false
        try {
            listener.cancel()
            _state.update { it.copy(notice = "Recording discarded") }
        } catch (e: Exception) {
            _state.update { it.copy(notice = "Could not stop microphone: ${e.message ?: "capture failed"}") }
        } finally {
            _state.update { it.copy(phase = Phase.Ready) }
        }
        true
    }

    fun setReviewBeforeSend(enabled: Boolean): Boolean = synchronized(actionLock) {
        if (closed || _state.value.phase != Phase.Ready) return@synchronized false
        _state.update { it.copy(reviewBeforeSend = enabled) }
        true
    }

    /** Pauses playback before replacing offline engines without discarding retained conversation text. */
    fun beginPackMaintenance(): Boolean = synchronized(actionLock) {
        val current = _state.value
        if (closed || current.phase != Phase.Ready || current.speaking || current.translating ||
            queuedWork != 0 || activePlaybackIds.isNotEmpty()) return@synchronized false
        _state.update { it.copy(phase = Phase.Maintenance) }
        true
    }

    /** Incoming text and control packets stay live during maintenance; queued speech resumes afterward. */
    fun endPackMaintenance(): Boolean = synchronized(actionLock) {
        if (closed || _state.value.phase != Phase.Maintenance) return@synchronized false
        _state.update { it.copy(phase = Phase.Ready) }
        true
    }

    fun confirmDraft(text: String): Boolean = synchronized(actionLock) {
        val draft = _state.value.draft ?: return@synchronized false
        val saved = draftSpeech ?: return@synchronized false
        if (closed || _state.value.phase != Phase.Reviewing || !validateText(text) || !hasDeliveryRoom()) return@synchronized false
        draftSpeech = null
        _state.update { it.copy(draft = null, phase = Phase.Processing, notice = null) }
        processText(text, saved.heard, saved.releasedAt, draft.inputOrigin,
            draft.originalText.takeIf { it != text })
        true
    }

    fun discardDraft(): Boolean = synchronized(actionLock) {
        if (closed || _state.value.draft == null) return@synchronized false
        draftSpeech = null
        _state.update { it.copy(draft = null, phase = Phase.Ready, notice = "Draft discarded") }
        true
    }

    /** Typed input has already been reviewed in its editor; it does not request microphone access. */
    fun submitText(text: String): Boolean = synchronized(actionLock) {
        if (closed || _state.value.phase != Phase.Ready || _state.value.speaking || !validateText(text) || !hasDeliveryRoom()) return@synchronized false
        _state.update { it.copy(phase = Phase.Processing, notice = null) }
        processText(text, null, clock(), InputOrigin.TYPED, null)
        true
    }

    /** Re-translate/re-synthesize retained received or Solo text; never resends a packet to another phone. */
    fun retryMessage(id: Int): Boolean = synchronized(actionLock) {
        val message = _state.value.messages.firstOrNull { it.id == id } ?: return@synchronized false
        if (closed || _state.value.phase != Phase.Ready || message.direction == Direction.OUTGOING ||
            message.status !in setOf(Status.FAILED, Status.TRANSLATION_FAILED, Status.NO_VOICE)) return@synchronized false
        val lang = message.langCode ?: return@synchronized false
        if (!enqueue(Playback.Local(id, message.text, lang))) return@synchronized false
        updateMessage(id) { it.copy(status = Status.QUEUED, error = null, translationError = null,
            translatedText = null, translationMs = null, translationOrigin = null, warnings = emptyList(),
            ttsMs = null, totalTtsMs = null, queueMs = null, voiceChunks = 1) }
        true
    }

    /** Replays the retained output without recognition or another translation. */
    fun replayMessage(id: Int, allowUnsafe: Boolean = false): Boolean = synchronized(actionLock) {
        val message = _state.value.messages.firstOrNull { it.id == id } ?: return@synchronized false
        if (closed || _state.value.phase != Phase.Ready || message.direction == Direction.OUTGOING ||
            message.status !in setOf(Status.PLAYED, Status.NO_VOICE, Status.NEEDS_REVIEW, Status.FAILED)) return@synchronized false
        if (message.warnings.isNotEmpty() && message.translationOrigin != TranslationOrigin.REVIEWED_PHRASE && !allowUnsafe) {
            _state.update { it.copy(notice = "Review the detail warnings and choose Play anyway to hear this translation") }
            return@synchronized false
        }
        val lang = message.outputLangCode ?: return@synchronized false
        val text = message.translatedText ?: message.text.takeIf { message.langCode == lang } ?: return@synchronized false
        if (!enqueue(Playback.Replay(id, message.text, message.langCode ?: lang, text, lang))) return@synchronized false
        updateMessage(id) { it.copy(status = Status.QUEUED, error = null, ttsMs = null, totalTtsMs = null, voiceChunks = 1) }
        true
    }

    private fun processText(text: String, heard: Heard?, releasedAt: Long, origin: InputOrigin, original: String?) {
        scope.launch {
            try {
                handleText(text, heard, releasedAt, origin, original)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(notice = "Processing failed: ${e.message}") }
            } finally {
                finishProcessing()
            }
        }
    }

    private fun finishProcessing() = _state.update {
        if (it.phase == Phase.Processing) it.copy(phase = Phase.Ready) else it
    }

    private fun validateText(text: String): Boolean {
        val error = inputError(text)
        if (error != null) _state.update { it.copy(notice = error) }
        return error == null
    }

    private fun hasDeliveryRoom(): Boolean {
        if (pending.size < MAX_PENDING) return true
        _state.update { it.copy(notice = "Waiting for earlier deliveries. Try again when they finish.") }
        return false
    }

    private suspend fun handleSpeech(heard: Heard, releasedAt: Long) {
        if (heard.text.isBlank()) {
            _state.update { it.copy(notice = "कुछ सुनाई नहीं दिया") }
            return
        }
        if (!validateText(heard.text)) return
        synchronized(actionLock) {
            if (closed) return
            if (_state.value.reviewBeforeSend) {
                draftSpeech = DraftSpeech(heard, releasedAt)
                _state.update { it.copy(phase = Phase.Reviewing,
                    draft = SourceDraft(heard.text, heard.text, language.code), notice = null) }
                return
            }
        }
        handleText(heard.text, heard, releasedAt, InputOrigin.SPEECH, null)
    }

    private suspend fun handleText(text: String, heard: Heard?, releasedAt: Long, origin: InputOrigin, original: String?) {
        val base = Message(
            id = nextId.getAndIncrement(),
            direction = if (transport == null) Direction.LOCAL else Direction.OUTGOING,
            text = text,
            status = Status.SENT,
            recordedSec = heard?.recordedSec,
            speechSec = heard?.speechSec,
            vadMs = heard?.vadMs,
            sttMs = heard?.sttMs,
            langCode = language.code,
            originalTranscript = original,
            inputOrigin = origin,
        )
        if (transport == null) {
            addMessage(base.copy(status = Status.QUEUED))
            if (!enqueue(Playback.Local(base.id, text, language.code))) updateMessage(base.id) {
                it.copy(status = Status.FAILED, error = BUSY_NOTICE)
            }
            return
        }

        val seq = nextSeq++ and 0xFFFF
        val packet = Packet.text(seq, releasedAt, text, language)
        pending[seq] = Pending(base.id, releasedAt, packet, clock())
        addMessage(base.copy(seq = seq, wireBytes = PacketCodec.wireSize(packet)))
        try {
            transport.send(packet)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            pending.remove(seq)
            updateMessage(base.id) { it.copy(status = Status.FAILED, error = e.message ?: "Send failed") }
        }
    }

    private suspend fun onPacket(p: Packet) {
        when (p.type) {
            PacketType.TEXT -> {
                lastActivityAt = clock()
                // A resend after a reconnect may repeat a message we already have: don't play it twice.
                val key = TextKey(p.seq, p.timestamp)
                val duplicate: Boolean
                val ack: Packet?
                synchronized(seen) {
                    duplicate = key in seen
                    ack = seen[key]
                    if (!duplicate) seen[key] = null
                }
                if (duplicate) {
                    // The first ACK may have died with the link; if it isn't sent yet, it will go out on its own.
                    if (ack != null) runCatching { transport?.send(ack) }
                    return
                }
                val id = nextId.getAndIncrement()
                addMessage(
                    Message(
                        id = id,
                        direction = Direction.INCOMING,
                        text = p.text,
                        status = Status.QUEUED,
                        langCode = p.langCode,
                        seq = p.seq,
                        wireBytes = PacketCodec.wireSize(p),
                        outputLangCode = targetLanguage?.code ?: p.langCode,
                    )
                )
                val item = Playback.Remote(p, id, clock())
                val inputError = inputError(p.text)
                if (inputError != null || !enqueue(item)) {
                    updateMessage(id) { it.copy(status = Status.FAILED, error = inputError ?: BUSY_NOTICE) }
                    acknowledge(item, ttsMs = 0, queueMs = 0)
                }
            }
            PacketType.PING -> {
                runCatching { transport?.send(Packet.pong(of = p)) }
                val voiceLang = targetLanguage?.code ?: p.langCode
                if (voiceLang != preloadedLang) {
                    preloadedLang = voiceLang
                    scope.launch {
                        // No voice yet (not installed): try again on a later PING, so a voice downloaded mid-session
                        // is loaded before the next message instead of on it.
                        if (!preloadVoice(voiceLang)) preloadedLang = -1
                    }
                }
            }
            PacketType.ACK -> pending[p.seq]?.takeIf {
                it.packet.timestamp == p.timestamp && it.packet.langCode == p.langCode
            }?.let { sent ->
                if (!pending.remove(p.seq, sent)) return
                val after = clock() - sent.releasedAt
                val rtt = _state.value.rttMs
                updateMessage(sent.messageId) {
                    it.copy(
                        status = Status.ACKED,
                        ackAfterMs = after,
                        peerTtsMs = p.ackTtsMs,
                        peerQueueMs = p.ackQueueMs,
                        rttMs = rtt,
                    )
                }
            }
            PacketType.PONG -> onPong(p)
        }
    }

    private suspend fun playbackLoop() {
        for (item in playQueue) {
            synchronized(actionLock) {
                queuedWork = (queuedWork - 1).coerceAtLeast(0)
                _state.update { it.copy(queuedMessages = queuedWork) }
            }
            try {
                // Capture shutdown/STT and source review finish before claiming playback.
                // Both claim and pressStart use the same gate, including non-Main callers.
                while (true) {
                    _state.first { it.phase == Phase.Ready }
                    val claimed = synchronized(actionLock) {
                        if (closed) throw CancellationException("Session closed")
                        if (_state.value.phase != Phase.Ready) false else {
                            _state.update { it.copy(speaking = true) }
                            true
                        }
                    }
                    if (claimed) break
                }
                val outputLangCode = (item as? Playback.Replay)?.outputLangCode ?: targetLanguage?.code ?: item.langCode
                updateMessage(item.messageId) { it.copy(outputLangCode = outputLangCode) }
                val outputText = (item as? Playback.Replay)?.outputText ?: textForPlayback(item)
                // Keep the existing ACK format: translation is part of the time before TTS begins.
                val queueMs = (item as? Playback.Remote)?.let { (clock() - it.arrivedAt).coerceAtLeast(0) }
                if (outputText == null) {
                    acknowledge(item, ttsMs = 0, queueMs = queueMs ?: 0)
                    updateMessage(item.messageId) { it.copy(queueMs = queueMs) }
                    continue
                }
                val message = _state.value.messages.firstOrNull { it.id == item.messageId }
                if (item !is Playback.Replay && message?.warnings?.isNotEmpty() == true &&
                    message.translationOrigin != TranslationOrigin.REVIEWED_PHRASE) {
                    acknowledge(item, ttsMs = 0, queueMs = queueMs ?: 0)
                    updateMessage(item.messageId) { it.copy(status = Status.NEEDS_REVIEW, queueMs = queueMs) }
                    continue
                }
                val prepared = speaker.prepare(outputText, outputLangCode)
                // Delivered either way; with no voice the translated text is shown and TTS is 0 ms.
                acknowledge(item, ttsMs = prepared?.synthMs ?: 0, queueMs = queueMs ?: 0)
                if (prepared == null) {
                    updateMessage(item.messageId) { it.copy(status = Status.NO_VOICE, queueMs = queueMs) }
                    continue
                }
                updateMessage(item.messageId) {
                    it.copy(status = Status.PLAYING, ttsMs = prepared.synthMs, queueMs = queueMs,
                        voiceChunks = prepared.chunkCount)
                }
                prepared.play()
                updateMessage(item.messageId) { it.copy(status = Status.PLAYED, totalTtsMs = prepared.totalSynthMs) }
            } catch (e: CancellationException) {
                throw e  // the session is closing (e.g. Leave while speaking): not a playback failure
            } catch (e: Exception) {
                if (closed) throw CancellationException("Session closed")
                // The original text is received even if its voice fails. Keep its receipt identity:
                // local recovery must not let a sender resend translate or play it again.
                acknowledge(item, ttsMs = 0,
                    queueMs = (item as? Playback.Remote)?.let { (clock() - it.arrivedAt).coerceAtLeast(0) } ?: 0)
                updateMessage(item.messageId) { it.copy(status = Status.FAILED, error = e.message) }
            } finally {
                synchronized(actionLock) {
                    activePlaybackIds.remove(item.messageId)
                    _state.update { it.copy(speaking = false) }
                }
            }
        }
    }

    /** A translation failure is a delivered text message, never a fallback to the wrong voice. */
    private suspend fun textForPlayback(item: Playback): String? {
        val target = targetLanguage ?: return item.text
        val startedAt = clock()
        try {
            val source = Language.fromCode(item.langCode)
                ?: throw IllegalArgumentException("Unknown source language; offline translation cannot continue.")
            if (source == target) {
                updateMessage(item.messageId) { it.copy(translationMs = 0) }
                return item.text
            }
            val engine = translator ?: throw IllegalStateException("Offline translation is not available on this phone.")
            _state.update { it.copy(translating = true) }
            val result = try {
                engine.translate(item.text, source, target)
            } finally {
                _state.update { it.copy(translating = false) }
            }
            if (result.text.isBlank()) throw IllegalStateException("Offline translation returned no text. Please try again.")
            val warnings = CriticalDetailChecker.check(item.text, result.text)
            updateMessage(item.messageId) {
                it.copy(translatedText = result.text, translationMs = result.millis.coerceAtLeast(0),
                    warnings = warnings, translationOrigin = result.origin)
            }
            return result.text
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            updateMessage(item.messageId) {
                it.copy(
                    status = Status.TRANSLATION_FAILED,
                    translationMs = (clock() - startedAt).coerceAtLeast(0),
                    translationError = e.message ?: "Offline translation failed. Please try again.",
                )
            }
            return null
        }
    }

    private suspend fun acknowledge(item: Playback, ttsMs: Long, queueMs: Long) {
        if (item !is Playback.Remote) return
        val ack = Packet.ack(of = item.packet, ttsMs = ttsMs, queueMs = queueMs)
        synchronized(seen) {
            seen[TextKey(item.packet.seq, item.packet.timestamp)] = ack
            var completed = seen.values.count { it != null }
            val entries = seen.entries.iterator()
            while (completed > SEEN_TEXTS && entries.hasNext()) {
                if (entries.next().value != null) {
                    entries.remove()
                    completed--
                }
            }
        }
        try {
            transport?.send(ack)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A duplicate after reconnect will send this saved ACK without repeating translation or audio.
        }
    }

    private suspend fun preloadVoice(langCode: Int): Boolean = try {
        speaker.preload(langCode)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    private fun enqueue(item: Playback): Boolean = synchronized(actionLock) {
        if (closed || item.messageId in activePlaybackIds) return@synchronized false
        if (!playQueue.trySend(item).isSuccess) {
            _state.update { it.copy(notice = BUSY_NOTICE) }
            return@synchronized false
        }
        activePlaybackIds += item.messageId
        queuedWork++
        _state.update { it.copy(queuedMessages = queuedWork) }
        true
    }

    private fun addMessage(m: Message) = _state.update { state ->
        val all = state.messages + m
        // Never hide a row while its packet or playback is still outstanding.
        val protected = all.filter {
            it.status == Status.QUEUED || it.status == Status.PLAYING ||
                (it.direction == Direction.OUTGOING && it.status == Status.SENT)
        }.map { it.id }.toSet()
        val history = all.filter { it.id !in protected }.takeLast((MAX_MESSAGES - protected.size).coerceAtLeast(0)).map { it.id }.toSet()
        state.copy(messages = all.filter { it.id in protected || it.id in history })
    }

    private fun updateMessage(id: Int, change: (Message) -> Message) =
        _state.update { s -> s.copy(messages = s.messages.map { if (it.id == id) change(it) else it }) }

    fun close() {
        synchronized(actionLock) {
            if (closed) return
            closed = true
            draftSpeech = null
            activePlaybackIds.clear()
            queuedWork = 0
            _state.update { it.copy(draft = null, queuedMessages = 0) }
        }
        try {
            listener.cancel()
        } catch (e: Exception) {
            _state.update { it.copy(notice = "Could not stop microphone: ${e.message ?: "capture failed"}") }
        } finally {
            try {
                transport?.close()
            } catch (e: Exception) {
                _state.update { it.copy(notice = "Could not close connection: ${e.message ?: "link failed"}") }
            } finally {
                playQueue.cancel()
                job.cancel()
                pending.clear()
                synchronized(seen) { seen.clear() }
            }
        }
    }

    companion object {
        const val MAX_MESSAGES = 100
        const val MAX_INPUT_CHARS = 1_000
        private const val MAX_PENDING = 32
        private const val BUSY_NOTICE = "Conversation is busy. Text is kept; retry when playback finishes."
        const val RTT_WINDOW = 10
        const val SEEN_TEXTS = 64
        const val PING_TIMEOUT_MS = 10_000L

        /** Shared with UI admission: validate without trimming or changing reviewed source text. */
        fun inputError(text: String): String? = when {
            text.isBlank() -> "Enter some text first"
            text.length > MAX_INPUT_CHARS -> "Use a shorter phrase (at most $MAX_INPUT_CHARS characters)."
            text.any { it.isISOControl() && it !in "\n\r\t" } -> "This phrase contains unsupported control characters."
            text.encodeToByteArray().size > PacketCodec.MAX_PAYLOAD -> "This phrase exceeds the text packet limit."
            else -> null
        }
    }
}
