package com.itantra.comm

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Socket

class TcpTransportTest {

    private val hindi = "नमस्ते, आज मौसम बहुत अच्छा है।"
    private val fastRetry = listOf(100L, 200L)
    private val open = mutableListOf<Transport>()

    @After
    fun tearDown() = open.forEach { it.close() }

    private fun <T : Transport> T.tracked(): T = also { open += it }

    private suspend fun Transport.awaitListening(): Int =
        (state.first { it is LinkState.Listening } as LinkState.Listening).port!!

    private suspend fun Transport.awaitConnected() = state.first { it is LinkState.Connected }

    /** Starts a host on a free port and a joiner connected to it. */
    private suspend fun connectedPair(): Pair<TcpTransport, TcpTransport> {
        val host = TcpTransport.host(port = 0, retryDelaysMs = fastRetry).tracked()
        val port = host.awaitListening()
        val joiner = TcpTransport.join("127.0.0.1", port, fastRetry).tracked()
        joiner.awaitConnected()
        host.awaitConnected()
        return host to joiner
    }

    private fun test(block: suspend () -> Unit) = runBlocking { withTimeout(10_000) { block() } }

    @Test
    fun hindiTextBothDirections() = test {
        val (host, joiner) = connectedPair()

        joiner.send(Packet.text(1, 100, hindi))
        val atHost = host.incoming.first()
        assertEquals(hindi, atHost.text)
        assertEquals(1, atHost.seq)

        host.send(Packet.ack(of = atHost, ttsMs = 360, queueMs = 0))
        val atJoiner = joiner.incoming.first()
        assertEquals(PacketType.ACK, atJoiner.type)
        assertEquals(1, atJoiner.seq)
        assertEquals(360L, atJoiner.ackTtsMs)
    }

    @Test
    fun sendReturnsBytesOnWire() = test {
        val (_, joiner) = connectedPair()
        val sent = joiner.send(Packet.text(1, 0, hindi))
        assertEquals(PacketCodec.wireSize(Packet.text(1, 0, hindi)), sent)
    }

    @Test
    fun hundredRapidMessagesArriveInOrder() = test {
        val (host, joiner) = connectedPair()
        var bytes = 0
        repeat(100) { bytes += joiner.send(Packet.text(it, it.toLong(), "संदेश $it")) }
        val received = host.incoming.take(100).toList()
        assertEquals((0 until 100).toList(), received.map { it.seq })
        assertEquals("संदेश 99", received.last().text)
        assertEquals(received.sumOf { PacketCodec.encode(it).size }, bytes)
    }

    @Test
    fun largePacketSplitAcrossTcpReadsArrivesIntact() = test {
        val (host, joiner) = connectedPair()
        val big = "अ".repeat(20_000) // 60,000 UTF-8 bytes
        joiner.send(Packet.text(5, 0, big))
        assertEquals(big, host.incoming.first().text)
    }

    @Test
    fun hostAcceptsNewPeerAfterDisconnect() = test {
        val (host, joiner) = connectedPair()
        joiner.close()
        val port = host.awaitListening()

        val joiner2 = TcpTransport.join("127.0.0.1", port, fastRetry).tracked()
        joiner2.awaitConnected()
        host.awaitConnected()
        joiner2.send(Packet.text(2, 0, hindi))
        assertEquals(hindi, host.incoming.first().text)
    }

    @Test
    fun joinerReconnectsWhenHostComesBack() = test {
        val host = TcpTransport.host(port = 0, retryDelaysMs = fastRetry).tracked()
        val port = host.awaitListening()
        val joiner = TcpTransport.join("127.0.0.1", port, fastRetry).tracked()
        joiner.awaitConnected()

        host.close()
        joiner.state.first { it is LinkState.Disconnected }

        val host2 = TcpTransport.host(port = port, retryDelaysMs = fastRetry).tracked()
        joiner.awaitConnected()
        host2.awaitConnected()
        joiner.send(Packet.text(3, 0, hindi))
        assertEquals(hindi, host2.incoming.first().text)
    }

    @Test
    fun garbageIsDroppedAndHostKeepsListening() = test {
        val host = TcpTransport.host(port = 0, retryDelaysMs = fastRetry).tracked()
        val port = host.awaitListening()

        Socket("127.0.0.1", port).use { raw ->
            raw.soTimeout = 5_000
            raw.getOutputStream().write("GET / HTTP/1.1\r\n\r\n".toByteArray())
            // The host must hang up on garbage: our read sees end-of-stream.
            assertEquals(-1, raw.getInputStream().read())
        }

        host.awaitListening()
        val joiner = TcpTransport.join("127.0.0.1", port, fastRetry).tracked()
        joiner.awaitConnected()
        host.awaitConnected()
        joiner.send(Packet.text(4, 0, hindi))
        assertEquals(hindi, host.incoming.first().text)
    }

    @Test
    fun sendWithoutConnectionThrows() = test {
        val joiner = TcpTransport.join("127.0.0.1", 1, fastRetry).tracked() // nothing listens on port 1
        val result = runCatching { joiner.send(Packet.text(1, 0, "x")) }
        assertTrue(result.exceptionOrNull() is java.io.IOException)
    }

    @Test
    fun closeEndsInClosedState() = test {
        val (host, joiner) = connectedPair()
        joiner.close()
        host.close()
        assertEquals(LinkState.Closed, joiner.state.value)
        assertEquals(LinkState.Closed, host.state.value)
    }
}
