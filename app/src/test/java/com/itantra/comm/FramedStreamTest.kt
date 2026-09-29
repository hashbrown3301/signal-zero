package com.itantra.comm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream

class FramedStreamTest {

    private val hindi = "नमस्ते, आज मौसम बहुत अच्छा है।"

    private fun reader(bytes: ByteArray, input: InputStream = ByteArrayInputStream(bytes)) =
        FramedStream(input, ByteArrayOutputStream())

    /** Delivers at most one byte per read, like a slow radio link. */
    private class TrickleInputStream(private val bytes: ByteArray) : InputStream() {
        private var pos = 0
        override fun read(): Int = if (pos < bytes.size) bytes[pos++].toInt() and 0xFF else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            val c = read()
            if (c < 0) return -1
            b[off] = c.toByte()
            return 1
        }
    }

    private fun wire(vararg packets: Packet): ByteArray {
        val out = ByteArrayOutputStream()
        val writer = FramedStream(ByteArrayInputStream(ByteArray(0)), out)
        packets.forEach { writer.write(it) }
        return out.toByteArray()
    }

    @Test
    fun writeReturnsBytesOnWireAndReadRoundTrips() {
        val out = ByteArrayOutputStream()
        val packet = Packet.text(1, 100, hindi)
        val n = FramedStream(ByteArrayInputStream(ByteArray(0)), out).write(packet)
        assertEquals(PacketCodec.wireSize(packet), n)
        assertEquals(n, out.size())
        assertEquals(packet, reader(out.toByteArray()).read())
    }

    @Test
    fun severalPacketsBackToBackInOrder() {
        val packets = listOf(Packet.text(1, 0, "एक"), Packet.ping(2, 5), Packet.text(3, 9, hindi))
        val r = reader(wire(*packets.toTypedArray()))
        assertEquals(packets, List(3) { r.read() })
    }

    @Test
    fun oneByteAtATimeStillDecodes() {
        val packets = listOf(Packet.text(1, 0, hindi), Packet.text(2, 0, "अ".repeat(5_000)))
        val bytes = wire(*packets.toTypedArray())
        val r = reader(bytes, TrickleInputStream(bytes))
        assertEquals(packets, List(2) { r.read() })
    }

    @Test
    fun cleanEndBetweenPacketsIsEof() {
        val r = reader(wire(Packet.text(1, 0, hindi)))
        r.read()
        assertThrows(EOFException::class.java) { r.read() }
    }

    @Test
    fun truncatedPacketIsEof() {
        val bytes = wire(Packet.text(1, 0, hindi))
        assertThrows(EOFException::class.java) { reader(bytes.copyOf(bytes.size - 3)).read() }
    }

    @Test
    fun garbageIsPacketException() {
        val bytes = "GET / HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray()
        assertThrows(PacketException::class.java) { reader(bytes).read() }
    }
}
