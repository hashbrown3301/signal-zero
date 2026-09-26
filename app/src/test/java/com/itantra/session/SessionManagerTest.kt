package com.itantra.session

import com.itantra.comm.LinkState
import com.itantra.comm.Packet
import com.itantra.comm.PacketType
import com.itantra.comm.TcpTransport
import com.itantra.comm.Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

    private class FakeSpeaker(private val synthMs: Long = 50, private val playMs: Long = 200) : Speaker {
        val played: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override suspend fun prepare(text: String) = object : Prepared {
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
    ): Pair<SessionManager, Transport> {
        val host = TcpTransport.host(port = 0)
        val port = (host.state.first { it is LinkState.Listening } as LinkState.Listening).port
        val sm = SessionManager(scope, listener, speaker, host, minPressMs = 0).also { it.start() }
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
}
