package com.itantra.comm

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PacketCodecTest {

    private val hindi = "नमस्ते, क्षमा कीजिए। आज मौसम बहुत अच्छा है।"

    private fun roundTrip(packet: Packet): Packet = PacketCodec.decode(PacketCodec.encode(packet))

    @Test
    fun textRoundTrip() {
        val packet = Packet.text(seq = 42, timestamp = 123_456_789L, text = hindi)
        val decoded = roundTrip(packet)
        assertEquals(packet, decoded)
        assertEquals(PacketType.TEXT, decoded.type)
        assertEquals(42, decoded.seq)
        assertEquals(Language.HINDI, decoded.language)
        assertEquals(123_456_789L, decoded.timestamp)
    }

    @Test
    fun hindiUtf8SurvivesByteForByte() {
        val decoded = roundTrip(Packet.text(1, 0, hindi))
        assertEquals(hindi, decoded.text)
        assertArrayEquals(hindi.encodeToByteArray(), decoded.payload)
        // Conjuncts (क्ष) and the danda (।) are multi-code-point; make sure none were split or lost.
        assertEquals(hindi.codePoints().toArray().toList(), decoded.text.codePoints().toArray().toList())
    }

    @Test
    fun encodedSizeIsOverheadPlusUtf8Bytes() {
        val bytes = PacketCodec.encode(Packet.text(1, 0, hindi))
        assertEquals(17, PacketCodec.OVERHEAD)
        assertEquals(PacketCodec.OVERHEAD + hindi.encodeToByteArray().size, bytes.size)
    }

    @Test
    fun emptyTextRoundTrip() {
        val decoded = roundTrip(Packet.text(7, 99, ""))
        assertEquals("", decoded.text)
        assertEquals(PacketCodec.OVERHEAD, PacketCodec.encode(decoded).size)
    }

    @Test
    fun ackCarriesReceiverTimings() {
        val original = Packet.text(seq = 300, timestamp = 5_000L, text = hindi)
        val decoded = roundTrip(Packet.ack(of = original, ttsMs = 360, queueMs = 2_150))
        assertEquals(PacketType.ACK, decoded.type)
        assertEquals(300, decoded.seq)
        assertEquals(5_000L, decoded.timestamp)
        assertEquals(360L, decoded.ackTtsMs)
        assertEquals(2_150L, decoded.ackQueueMs)
    }

    @Test
    fun pingPongEchoSeqAndTimestamp() {
        val ping = roundTrip(Packet.ping(seq = 9, timestamp = 777L))
        val pong = roundTrip(Packet.pong(of = ping))
        assertEquals(PacketType.PONG, pong.type)
        assertEquals(9, pong.seq)
        assertEquals(777L, pong.timestamp)
    }

    @Test
    fun seqAndTimestampWrapToWireWidth() {
        val decoded = roundTrip(Packet.text(seq = 65_536 + 5, timestamp = 0x1_0000_0010L, text = "x"))
        assertEquals(5, decoded.seq)
        assertEquals(0x10L, decoded.timestamp)
        assertEquals(65_535, roundTrip(Packet.text(65_535, 0xFFFF_FFFFL, "x")).seq)
        assertEquals(0xFFFF_FFFFL, roundTrip(Packet.text(0, 0xFFFF_FFFFL, "x")).timestamp)
    }

    @Test
    fun anySingleCorruptByteIsRejected() {
        val bytes = PacketCodec.encode(Packet.text(1, 1000, hindi))
        for (i in bytes.indices) {
            val corrupt = bytes.copyOf().also { it[i] = (it[i].toInt() xor 0x01).toByte() }
            assertThrows("byte $i", PacketException::class.java) { PacketCodec.decode(corrupt) }
        }
    }

    @Test
    fun corruptCrcIsReportedAsCrcMismatch() {
        val bytes = PacketCodec.encode(Packet.text(1, 1000, hindi))
        bytes[PacketCodec.HEADER_SIZE + 3] = (bytes[PacketCodec.HEADER_SIZE + 3] + 1).toByte()
        val e = assertThrows(PacketException::class.java) { PacketCodec.decode(bytes) }
        assertEquals("CRC mismatch", e.message)
    }

    @Test
    fun truncatedPacketsAreRejected() {
        val bytes = PacketCodec.encode(Packet.text(1, 1000, hindi))
        for (len in listOf(0, 5, PacketCodec.HEADER_SIZE, bytes.size - 1)) {
            assertThrows("length $len", PacketException::class.java) {
                PacketCodec.decode(bytes.copyOf(len))
            }
        }
    }

    @Test
    fun badMagicAndVersionAreRejected() {
        val bytes = PacketCodec.encode(Packet.text(1, 0, "x"))
        val badMagic = bytes.copyOf().also { it[0] = 'X'.code.toByte() }
        assertEquals("Bad magic", assertThrows(PacketException::class.java) { PacketCodec.decode(badMagic) }.message)
        val badVersion = bytes.copyOf().also { it[2] = 2 }
        assertEquals(
            "Unsupported version 2",
            assertThrows(PacketException::class.java) { PacketCodec.decode(badVersion) }.message,
        )
    }

    @Test
    fun frameLengthReadsAnnouncedSizeFromHeader() {
        val bytes = PacketCodec.encode(Packet.text(1, 0, hindi))
        assertEquals(bytes.size, PacketCodec.frameLength(bytes.copyOf(PacketCodec.HEADER_SIZE)))
    }

    @Test
    fun wireLayoutMatchesSpec() {
        val bytes = PacketCodec.encode(Packet.text(seq = 0x0102, timestamp = 0x0A0B0C0DL, text = "ab"))
        val expectedHeader = byteArrayOf(
            'i'.code.toByte(), 'T'.code.toByte(), 1, // magic + version
            1,                                       // type TEXT
            0x01, 0x02,                              // seq
            1,                                       // lang HINDI
            0x0A, 0x0B, 0x0C, 0x0D,                  // timestamp
            0x00, 0x02,                              // payload length
        )
        assertArrayEquals(expectedHeader, bytes.copyOf(PacketCodec.HEADER_SIZE))
        assertArrayEquals("ab".encodeToByteArray(), bytes.copyOfRange(13, 15))
    }
}
