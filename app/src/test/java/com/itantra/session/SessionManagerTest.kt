package com.itantra.session

import com.itantra.comm.Language
import com.itantra.comm.LinkState
import com.itantra.comm.Packet
import com.itantra.comm.PacketType
import com.itantra.comm.TcpTransport
import com.itantra.comm.Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

class SessionManagerTest {

    private val hindi = "नमस्ते, आज मौसम बहुत अच्छा है।"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val closeables = mutableListOf<() -> Unit>()

    @After
    fun tearDown() {
        closeables.forEach { it() }
        scope.cancel()
    }

    private class FakeListener(var nextText: String = "") : Listener {
        override fun start() = Unit
        override suspend fun finish(): Heard {
            delay(20)
            return Heard(nextText, recordedSec = 2f, speechSec = 1.5f, vadMs = 5, sttMs = if (nextText.isEmpty()) null else 15)
        }
        override fun cancel() = Unit
    }

    private class FakeSpeaker(
        private val synthMs: Long = 50,
        private val playMs: Long = 200,
        private val voices: Set<Int> = setOf(Language.HINDI.code),
    ) : Speaker {
        val played: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override suspend fun prepare(text: String, langCode: Int): Prepared? =
            if (langCode !in voices) null else object : Prepared {
                override val synthMs = this@FakeSpeaker.synthMs
                override suspend fun play() {
                    delay(playMs)
                    played += text
                }
            }
    }

    private fun test(block: suspend () -> Unit) = runBlocking { withTimeout(10_000) { block() } }

    /** A SessionManager hosting on a free port, plus a raw peer transport joined to it. */
    private suspend fun session(
        listener: Listener = FakeListener(hindi),
        speaker: Speaker = FakeSpeaker(),
        pingIntervalMs: Long = 0,
        language: Language = Language.HINDI,
    ): Pair<SessionManager, Transport> {
        val host = TcpTransport.host(port = 0)
        val port = (host.state.first { it is LinkState.Listening } as LinkState.Listening).port!!
        val sm = SessionManager(scope, listener, speaker, host, language = language, minPressMs = 0, pingIntervalMs = pingIntervalMs)
            .also { it.start() }
        val peer = TcpTransport.join("127.0.0.1", port)
        closeables += { sm.close() }
        closeables += { peer.close() }
        peer.state.first { it is LinkState.Connected }
        sm.state.first { it.link is LinkState.Connected }
        return sm to peer
    }

    private suspend fun SessionManager.talk() {
        assertTrue(pressStart())
        pressEnd()
        state.first { it.phase == Phase.Ready }
    }

    @Test
    fun outgoingSpeechIsSentAndAckIsMatched() = test {
        val (sm, peer) = session()
        sm.talk()

        val text = peer.incoming.first()
        assertEquals(PacketType.TEXT, text.type)
        assertEquals(hindi, text.text)

        peer.send(Packet.ack(of = text, ttsMs = 123, queueMs = 7))
        val msg = sm.state.first { it.messages.single().status == Status.ACKED }.messages.single()
        assertEquals(Direction.OUTGOING, msg.direction)
        assertEquals(123L, msg.peerTtsMs)
        assertEquals(7L, msg.peerQueueMs)
        assertEquals(17 + hindi.encodeToByteArray().size, msg.wireBytes)
        assertTrue(msg.ackAfterMs!! >= 0)
    }

    @Test
    fun incomingTextIsSpokenAndAckedWithTtsTime() = test {
        val speaker = FakeSpeaker(synthMs = 50)
        val (sm, peer) = session(speaker = speaker)

        peer.send(Packet.text(9, 1234, hindi))
        val ack = peer.incoming.first()
        assertEquals(PacketType.ACK, ack.type)
        assertEquals(9, ack.seq)
        assertEquals(1234L, ack.timestamp)
        assertEquals(50L, ack.ackTtsMs)

        sm.state.first { it.messages.singleOrNull()?.status == Status.PLAYED }
        assertEquals(listOf(hindi), speaker.played)
    }

    @Test
    fun incomingSpeechWaitsWhileTalkButtonIsHeld() = test {
        val speaker = FakeSpeaker()
        val (sm, peer) = session(speaker = speaker)

        assertTrue(sm.pressStart())
        peer.send(Packet.text(1, 0, hindi))
        sm.state.first { it.messages.any { m -> m.direction == Direction.INCOMING } }
        delay(500)
        assertTrue("must not play while the button is held", speaker.played.isEmpty())
        assertEquals(false, sm.state.value.speaking)

        sm.pressEnd()
        val ack = peer.incoming.first { it.type == PacketType.ACK }
        assertTrue("queue wait ${ack.ackQueueMs} ms", ack.ackQueueMs >= 450)
        sm.state.first { s -> s.messages.any { it.direction == Direction.INCOMING && it.status == Status.PLAYED } }
    }

    @Test
    fun pingIsAnsweredWhilePhoneIsSpeaking() = test {
        val (sm, peer) = session(speaker = FakeSpeaker(playMs = 2_000))

        peer.send(Packet.text(1, 0, hindi))
        sm.state.first { it.speaking }
        val sentAt = System.nanoTime()
        peer.send(Packet.ping(seq = 77, timestamp = 0))
        val pong = peer.incoming.first { it.type == PacketType.PONG }
        val waitedMs = (System.nanoTime() - sentAt) / 1_000_000
        assertEquals(77, pong.seq)
        assertTrue("PONG took $waitedMs ms", waitedMs < 500)
        assertTrue(sm.state.value.speaking)
    }

    @Test
    fun queuedMessagesPlayInArrivalOrder() = test {
        val speaker = FakeSpeaker(playMs = 50)
        val (sm, peer) = session(speaker = speaker)

        val texts = listOf("एक", "दो", "तीन", "चार")
        texts.forEachIndexed { i, t -> peer.send(Packet.text(i, 0, t)) }
        sm.state.first { s -> s.messages.count { it.status == Status.PLAYED } == texts.size }
        assertEquals(texts, speaker.played.toList())
    }

    @Test
    fun noSpeechSendsNothing() = test {
        val (sm, peer) = session(listener = FakeListener(nextText = ""))
        sm.talk()
        assertEquals("कुछ सुनाई नहीं दिया", sm.state.value.notice)
        assertNull(withTimeoutOrNull(300) { peer.incoming.firstOrNull() })
        assertTrue(sm.state.value.messages.isEmpty())
    }

    @Test
    fun sendWhileDisconnectedMarksMessageFailed() = test {
        val (sm, peer) = session()
        peer.close()
        sm.state.first { it.link !is LinkState.Connected }
        sm.talk()
        val msg = sm.state.first { it.messages.isNotEmpty() && it.messages.last().status == Status.FAILED }
            .messages.last()
        assertEquals(hindi, msg.text)
    }

    @Test
    fun soloModeSpeaksLocallyWithoutNetwork() = test {
        val speaker = FakeSpeaker(synthMs = 40)
        val sm = SessionManager(scope, FakeListener(hindi), speaker, transport = null, minPressMs = 0)
            .also { it.start() }
        closeables += { sm.close() }

        sm.talk()
        val msg = sm.state.first { it.messages.singleOrNull()?.status == Status.PLAYED }.messages.single()
        assertEquals(Direction.LOCAL, msg.direction)
        assertEquals(40L, msg.ttsMs)
        assertNull(msg.wireBytes)
        assertNull(sm.state.value.link)
        assertEquals(listOf(hindi), speaker.played)
    }

    @Test
    fun shortTapIsIgnored() = test {
        val sm = SessionManager(scope, FakeListener(hindi), FakeSpeaker(), transport = null, minPressMs = 300)
            .also { it.start() }
        closeables += { sm.close() }
        assertTrue(sm.pressStart())
        sm.pressEnd()
        assertEquals(Phase.Ready, sm.state.value.phase)
        assertEquals("Hold the button while you speak", sm.state.value.notice)
        assertTrue(sm.state.value.messages.isEmpty())
    }

    /** Peer side of PING/PONG: answers each PING after [delayMs]; returns the other packets. */
    private fun CoroutineScope.answerPings(peer: Transport, delayMs: Long, others: Channel<Packet>) = launch {
        peer.incoming.collect { p ->
            if (p.type == PacketType.PING) {
                launch {
                    delay(delayMs)
                    peer.send(Packet.pong(of = p))
                }
            } else {
                others.send(p)
            }
        }
    }

    @Test
    fun pingsMeasureRtt() = test {
        val (sm, peer) = session(pingIntervalMs = 50)
        val others = Channel<Packet>(Channel.UNLIMITED)
        val responder = scope.answerPings(peer, delayMs = 40, others = others)
        val rtt = sm.state.first { (it.rttMs ?: 0) >= 40 }.rttMs!!
        assertTrue("rtt $rtt ms", rtt in 40..200)
        responder.cancel()
    }

    @Test
    fun endToEndSubtractsHalfRttAndBreakdownAddsUp() = test {
        val (sm, peer) = session(pingIntervalMs = 50)
        val others = Channel<Packet>(Channel.UNLIMITED)
        val responder = scope.answerPings(peer, delayMs = 40, others = others)
        sm.state.first { it.rttMs != null }

        sm.talk()
        val text = others.receive()
        assertEquals(PacketType.TEXT, text.type)
        delay(100)
        peer.send(Packet.ack(of = text, ttsMs = 50, queueMs = 20))

        val m = sm.state.first { it.messages.singleOrNull()?.status == Status.ACKED }.messages.single()
        val rtt = m.rttMs!!
        assertEquals(m.ackAfterMs!! - rtt / 2, m.endToEndMs)
        assertEquals(rtt / 2, m.networkMs)
        val parts = m.vadMs!! + m.sttMs!! + m.networkMs!! + m.peerQueueMs!! + m.peerTtsMs!! + m.otherMs!!
        assertEquals(m.endToEndMs, parts)
        // The peer really waited 100 ms before ACKing (the 50/20 in the ACK are just reported values),
        // so release → ACK is ≥ 100 ms and end-to-end is that minus the ACK's trip back (RTT/2).
        assertTrue("ackAfter ${m.ackAfterMs} ms", m.ackAfterMs!! >= 100)
        assertTrue("end-to-end ${m.endToEndMs} < ackAfter ${m.ackAfterMs}", m.endToEndMs!! < m.ackAfterMs!!)
        responder.cancel()
    }

    @Test
    fun missingPongsLeaveLatencyUnknownButAckStillWorks() = test {
        val (sm, peer) = session(pingIntervalMs = 50) // the peer never answers PINGs
        sm.talk()
        val text = peer.incoming.first { it.type == PacketType.TEXT }
        peer.send(Packet.ack(of = text, ttsMs = 10, queueMs = 0))
        val m = sm.state.first { it.messages.singleOrNull()?.status == Status.ACKED }.messages.single()
        assertNull(sm.state.value.rttMs)
        assertNull(m.endToEndMs)
        assertEquals(10L, m.peerTtsMs)
    }

    @Test
    fun outgoingSpeechIsTaggedWithThePhonesLanguage() = test {
        val tamil = "வணக்கம், நீங்கள் எப்படி இருக்கிறீர்கள்?"
        val (sm, peer) = session(listener = FakeListener(tamil), language = Language.TAMIL)
        sm.talk()
        val text = peer.incoming.first { it.type == PacketType.TEXT }
        assertEquals(Language.TAMIL, text.language)
        assertEquals(tamil, text.text)
        assertEquals(Language.TAMIL.code, sm.state.value.messages.single().langCode)
    }

    @Test
    fun incomingLanguageWithoutVoiceIsShownAsTextAndStillAcked() = test {
        val speaker = FakeSpeaker(voices = setOf(Language.HINDI.code))
        val (sm, peer) = session(speaker = speaker)
        peer.send(Packet.text(4, 0, "வணக்கம்", Language.TAMIL))
        val ack = peer.incoming.first { it.type == PacketType.ACK }
        assertEquals(4, ack.seq)
        assertEquals(0L, ack.ackTtsMs)
        val m = sm.state.first { it.messages.singleOrNull()?.status == Status.NO_VOICE }.messages.single()
        assertEquals(Language.TAMIL.code, m.langCode)
        assertTrue(speaker.played.isEmpty())
        // The queue keeps working afterwards: a Hindi message is still spoken.
        peer.send(Packet.text(5, 0, hindi, Language.HINDI))
        sm.state.first { s -> s.messages.any { it.status == Status.PLAYED } }
        assertEquals(listOf(hindi), speaker.played.toList())
    }

    @Test
    fun soloSpeaksWithTheVoiceOfThePhonesLanguage() = test {
        val speaker = FakeSpeaker(voices = setOf(Language.TAMIL.code))
        val sm = SessionManager(scope, FakeListener("வணக்கம்"), speaker, transport = null, language = Language.TAMIL,
            minPressMs = 0).also { it.start() }
        closeables += { sm.close() }
        sm.talk()
        sm.state.first { it.messages.singleOrNull()?.status == Status.PLAYED }
        assertEquals(listOf("வணக்கம்"), speaker.played.toList())
    }

    @Test
    fun leavingWhileSpeakingIsNotAFailure() = test {
        val sm = SessionManager(scope, FakeListener(hindi), FakeSpeaker(playMs = 5_000), transport = null, minPressMs = 0)
            .also { it.start() }
        sm.talk()
        sm.state.first { it.messages.singleOrNull()?.status == Status.PLAYING }
        sm.close()  // Leave while the voice is still speaking
        delay(300)
        assertEquals(Status.PLAYING, sm.state.value.messages.single().status)
    }
}
