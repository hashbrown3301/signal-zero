package com.itantra.session

import com.itantra.comm.Language
import com.itantra.comm.LinkState
import com.itantra.comm.Packet
import com.itantra.comm.PacketCodec
import com.itantra.comm.PacketType
import com.itantra.comm.Transport
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

    /** Received, but this phone has no voice for the message's language: shown as text only. */
    NO_VOICE,
}

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
) {
    /** One-way network estimate: RTT / 2. */
    val networkMs: Long? get() = rttMs?.let { it / 2 }

    /**
     * OUTGOING: button release → receiver's audio starts, all on this phone's clock:
     * (ACK received − release) − the ACK's trip back (RTT / 2).
     */
    val endToEndMs: Long? get() = ackAfterMs?.let { after -> networkMs?.let { after - it } }

    /** Whatever [endToEndMs] contains beyond the named stages (recorder stop, encoding, …). */
    val otherMs: Long?
        get() = endToEndMs?.let {
            it - (vadMs ?: 0) - (sttMs ?: 0) - (networkMs ?: 0) - (peerQueueMs ?: 0) - (peerTtsMs ?: 0)
        }
}

enum class Phase { Ready, Listening, Processing }

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
) {
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)

    private val _state = MutableStateFlow(SessionState(link = transport?.state?.value))
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
    }

    private data class Pending(val messageId: Int, val releasedAt: Long)

    private val talking = MutableStateFlow(false)
    private val playQueue = Channel<Playback>(Channel.UNLIMITED)
    private val pending = ConcurrentHashMap<Int, Pending>()
    private val nextId = AtomicInteger()
    private var nextSeq = 0
    private var pressedAt = 0L

    private val pingSentAt = ConcurrentHashMap<Int, Long>()
    private var nextPingSeq = 0
    private val rttSamples = ArrayDeque<Long>() // guarded by itself

    fun start() {
        scope.launch { playbackLoop() }
        if (transport != null) {
            scope.launch {
                transport.state.collect { link ->
                    // A new connection may take a different path; start RTT fresh.
                    if (link !is LinkState.Connected) resetRtt()
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
        }
    }

    private suspend fun pingLoop(transport: Transport) {
        while (true) {
            transport.state.first { it is LinkState.Connected }
            val seq = nextPingSeq++ and 0xFFFF
            val now = clock()
            pingSentAt[seq] = now
            pingSentAt.entries.removeIf { now - it.value > PING_TIMEOUT_MS } // lost PONGs
            runCatching { transport.send(Packet.ping(seq, now)) }
            delay(pingIntervalMs)
        }
    }

    private fun onPong(p: Packet) {
        val sentAt = pingSentAt.remove(p.seq) ?: return
        val rtt = clock() - sentAt
        val median = synchronized(rttSamples) {
            rttSamples.addLast(rtt)
            while (rttSamples.size > RTT_WINDOW) rttSamples.removeFirst()
            rttSamples.sorted()[rttSamples.size / 2]
        }
        _state.update { it.copy(rttMs = median) }
    }

    private fun resetRtt() {
        synchronized(rttSamples) { rttSamples.clear() }
        pingSentAt.clear()
        _state.update { it.copy(rttMs = null) }
    }

    /** Talk button down. Returns false if talking isn't possible right now. */
    fun pressStart(): Boolean {
        val s = _state.value
        if (s.phase != Phase.Ready || s.speaking) return false
        try {
            listener.start()
        } catch (e: Exception) {
            _state.update { it.copy(notice = "Microphone unavailable: ${e.message}") }
            return false
        }
        pressedAt = clock()
        talking.value = true
        _state.update { it.copy(phase = Phase.Listening, notice = null) }
        return true
    }

    /** Talk button up. */
    fun pressEnd() {
        if (_state.value.phase != Phase.Listening) return
        val releasedAt = clock()
        talking.value = false
        if (releasedAt - pressedAt < minPressMs) {
            listener.cancel()
            _state.update { it.copy(phase = Phase.Ready, notice = "Hold the button while you speak") }
            return
        }
        _state.update { it.copy(phase = Phase.Processing) }
        scope.launch {
            try {
                handleSpeech(listener.finish(), releasedAt)
            } catch (e: Exception) {
                _state.update { it.copy(notice = "Processing failed: ${e.message}") }
            } finally {
                _state.update { it.copy(phase = Phase.Ready) }
            }
        }
    }

    private suspend fun handleSpeech(heard: Heard, releasedAt: Long) {
        if (heard.text.isBlank()) {
            _state.update { it.copy(notice = "कुछ सुनाई नहीं दिया") }
            return
        }
        val base = Message(
            id = nextId.getAndIncrement(),
            direction = if (transport == null) Direction.LOCAL else Direction.OUTGOING,
            text = heard.text,
            status = Status.SENT,
            recordedSec = heard.recordedSec,
            speechSec = heard.speechSec,
            vadMs = heard.vadMs,
            sttMs = heard.sttMs,
            langCode = language.code,
        )
        if (transport == null) {
            addMessage(base.copy(status = Status.QUEUED))
            playQueue.send(Playback.Local(base.id, heard.text, language.code))
            return
        }

        val seq = nextSeq++ and 0xFFFF
        val packet = Packet.text(seq, releasedAt, heard.text, language)
        pending[seq] = Pending(base.id, releasedAt)
        addMessage(base.copy(seq = seq, wireBytes = PacketCodec.OVERHEAD + packet.payload.size))
        try {
            transport.send(packet)
        } catch (e: Exception) {
            pending.remove(seq)
            updateMessage(base.id) { it.copy(status = Status.FAILED, error = e.message ?: "Send failed") }
        }
    }

    private suspend fun onPacket(p: Packet) {
        when (p.type) {
            PacketType.TEXT -> {
                val id = nextId.getAndIncrement()
                addMessage(
                    Message(
                        id = id,
                        direction = Direction.INCOMING,
                        text = p.text,
                        status = Status.QUEUED,
                        langCode = p.langCode,
                        seq = p.seq,
                        wireBytes = PacketCodec.OVERHEAD + p.payload.size,
                    )
                )
                playQueue.send(Playback.Remote(p, id, clock()))
            }
            PacketType.PING -> runCatching { transport?.send(Packet.pong(of = p)) }
            PacketType.ACK -> pending.remove(p.seq)?.let { sent ->
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
            // Half-duplex: never speak over the user. Re-check after claiming `speaking`,
            // in case the button went down in between.
            while (true) {
                talking.first { !it }
                _state.update { it.copy(speaking = true) }
                if (!talking.value) break
                _state.update { it.copy(speaking = false) }
            }
            try {
                val queueMs = (item as? Playback.Remote)?.let { clock() - it.arrivedAt }
                val prepared = speaker.prepare(item.text, item.langCode)
                if (item is Playback.Remote) {
                    // Delivered either way; with no voice the text is shown and the ACK reports 0 ms TTS.
                    runCatching {
                        transport?.send(Packet.ack(of = item.packet, ttsMs = prepared?.synthMs ?: 0, queueMs = queueMs ?: 0))
                    }
                }
                if (prepared == null) {
                    updateMessage(item.messageId) { it.copy(status = Status.NO_VOICE, queueMs = queueMs) }
                    continue
                }
                updateMessage(item.messageId) {
                    it.copy(status = Status.PLAYING, ttsMs = prepared.synthMs, queueMs = queueMs)
                }
                prepared.play()
                updateMessage(item.messageId) { it.copy(status = Status.PLAYED) }
            } catch (e: Exception) {
                updateMessage(item.messageId) { it.copy(status = Status.FAILED, error = e.message) }
            } finally {
                _state.update { it.copy(speaking = false) }
            }
        }
    }

    private fun addMessage(m: Message) =
        _state.update { it.copy(messages = (it.messages + m).takeLast(MAX_MESSAGES)) }

    private fun updateMessage(id: Int, change: (Message) -> Message) =
        _state.update { s -> s.copy(messages = s.messages.map { if (it.id == id) change(it) else it }) }

    fun close() {
        listener.cancel()
        transport?.close()
        playQueue.close()
        job.cancel()
    }

    private companion object {
        const val MAX_MESSAGES = 100
        const val RTT_WINDOW = 10
        const val PING_TIMEOUT_MS = 10_000L
    }
}
