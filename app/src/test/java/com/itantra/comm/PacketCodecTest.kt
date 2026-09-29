package com.itantra.comm

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.util.zip.CRC32

class PacketCodecTest {

    private val hindi = "नमस्ते, क्षमा कीजिए। आज मौसम बहुत अच्छा है।"

    private fun roundTrip(packet: Packet): Packet = PacketCodec.decode(PacketCodec.encode(packet))

    /** A hand-built wire-v1 frame (13 B header, UTF-8 payload, CRC32), independent of [PacketCodec.encode]. */
    private fun v1Frame(type: PacketType, seq: Int, lang: Int, ts: Long, payload: ByteArray): ByteArray {
        val buf = ByteBuffer.allocate(PacketCodec.OVERHEAD + payload.size)
        buf.put('i'.code.toByte()).put('T'.code.toByte()).put(1).put(type.code.toByte())
        buf.putShort(seq.toShort()).put(lang.toByte()).putInt(ts.toInt()).putShort(payload.size.toShort())
        buf.put(payload)
        buf.putInt(CRC32().apply { update(buf.array(), 0, buf.position()) }.value.toInt())
        return buf.array()
    }

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
    fun encodedSizeIsOverheadPlusIndicPackBytes() {
        val packet = Packet.text(1, 0, hindi)
        val bytes = PacketCodec.encode(packet)
        assertEquals(17, PacketCodec.OVERHEAD)
        assertEquals(PacketCodec.OVERHEAD + IndicPack.encode(hindi, Language.HINDI.code).size, bytes.size)
        assertEquals(bytes.size, PacketCodec.wireSize(packet))
        assertEquals(PacketCodec.encode(Packet.ping(1, 0)).size, PacketCodec.wireSize(Packet.ping(1, 0)))
    }

    @Test
    fun v2HindiSentenceIsUnder45PercentOfV1() {
        val sentence = "आज सुबह मैं अपने दोस्त के साथ बाजार गया और वहाँ से ताज़ी सब्ज़ियाँ खरीदकर फिर घर वापस लौट आया।"
        assertEquals(20, sentence.split(" ").size)
        val v1Size = v1Frame(PacketType.TEXT, 1, Language.HINDI.code, 0, sentence.encodeToByteArray()).size
        val v2Size = PacketCodec.wireSize(Packet.text(1, 0, sentence))
        println("20-word Hindi sentence: v1 $v1Size B, v2 $v2Size B (${100 * v2Size / v1Size}%)")
        assertTrue("v2 $v2Size B vs v1 $v1Size B", v2Size < 0.45 * v1Size)
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
    fun v2AckIsFourBytesAndSaturates() {
        val original = Packet.text(1, 0, "x")
        val ack = Packet.ack(of = original, ttsMs = 70_000, queueMs = 65_535)
        assertEquals(4, ack.payload.size)
        assertEquals(PacketCodec.OVERHEAD + 4, PacketCodec.encode(ack).size)
        val decoded = roundTrip(ack)
        assertEquals(65_535L, decoded.ackTtsMs)
        assertEquals(65_535L, decoded.ackQueueMs)
        assertEquals(0L, Packet.ack(of = original, ttsMs = -5, queueMs = 0).ackTtsMs)
    }

    @Test
    fun ackPayloadSizeIsEnforcedPerVersion() {
        // A v2 frame with an 8-byte ACK and a v1 frame with a 4-byte ACK are both invalid.
        val v2With8 = PacketCodec.encode(Packet(PacketType.ACK, 1, 1, 0, ByteArray(8)))
        assertThrows(PacketException::class.java) { PacketCodec.decode(v2With8) }
        assertThrows(PacketException::class.java) {
            PacketCodec.decode(v1Frame(PacketType.ACK, 1, 1, 0, ByteArray(4)))
        }
    }

    @Test
    fun v1TextStillDecodes() {
        val text = "வணக்கம்"
        val payload = text.encodeToByteArray()
        val decoded = PacketCodec.decode(v1Frame(PacketType.TEXT, 5, Language.TAMIL.code, 4_000L, payload))
        assertEquals(Packet.text(5, 4_000L, text, Language.TAMIL), decoded)
        assertEquals(text, decoded.text)
    }

    @Test
    fun v1AckWithEightByteBodyStillDecodes() {
        val body = ByteBuffer.allocate(8).putInt(360).putInt(2_150).array()
        val decoded = PacketCodec.decode(v1Frame(PacketType.ACK, 300, 1, 5_000L, body))
        assertEquals(PacketType.ACK, decoded.type)
        assertEquals(300, decoded.seq)
        assertEquals(360L, decoded.ackTtsMs)
        assertEquals(2_150L, decoded.ackQueueMs)
        // Huge v1 timings saturate into the v2 form.
        val big = ByteBuffer.allocate(8).putInt(-1).putInt(70_000).array()
        val sat = PacketCodec.decode(v1Frame(PacketType.ACK, 1, 1, 0, big))
        assertEquals(65_535L, sat.ackTtsMs)
        assertEquals(65_535L, sat.ackQueueMs)
        // A Packet built by hand with the old 8-byte body still reads.
        val manual = Packet(PacketType.ACK, 1, 1, 0, ByteBuffer.allocate(8).putInt(123_456).putInt(7).array())
        assertEquals(123_456L, manual.ackTtsMs)
        assertEquals(7L, manual.ackQueueMs)
    }

    @Test
    fun v1PingStillDecodes() {
        val ping = PacketCodec.decode(v1Frame(PacketType.PING, 9, Language.GUJARATI.code, 777L, ByteArray(0)))
        assertEquals(Packet.ping(9, 777L, Language.GUJARATI), ping)
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
        val badVersion = bytes.copyOf().also { it[2] = 3 }
        assertEquals(
            "Unsupported version 3",
            assertThrows(PacketException::class.java) { PacketCodec.decode(badVersion) }.message,
        )
    }

    @Test
    fun frameLengthReadsAnnouncedSizeFromHeader() {
        val bytes = PacketCodec.encode(Packet.text(1, 0, hindi))
        assertEquals(bytes.size, PacketCodec.frameLength(bytes.copyOf(PacketCodec.HEADER_SIZE)))
    }

    @Test
    fun languageCodesAreFixed() {
        // Never renumber: phones with different app versions must agree (docs/PHASE3_PLAN.md).
        val expected = listOf("hi", "en", "mr", "gu", "bn", "ta", "te", "kn", "ml", "or")
        assertEquals(expected, Language.entries.sortedBy { it.code }.map { it.iso })
        assertEquals((1..10).toList(), Language.entries.map { it.code }.sorted())
        assertEquals(Language.TAMIL, Language.fromIso("ta"))
    }

    @Test
    fun everyLanguageRoundTrips() {
        val samples = mapOf(
            Language.ENGLISH to "Hello, how are you?", Language.TAMIL to "வணக்கம், நீங்கள் எப்படி இருக்கிறீர்கள்?",
            Language.ODIA to "ନମସ୍କାର", Language.MALAYALAM to "നമസ്കാരം",
        )
        for (lang in Language.entries) {
            val text = samples[lang] ?: "नमस्ते"
            val decoded = roundTrip(Packet.text(seq = lang.code, timestamp = 0, text = text, language = lang))
            assertEquals(lang, decoded.language)
            assertEquals(lang.code, decoded.langCode)
            assertEquals(text, decoded.text)
        }
    }

    @Test
    fun unknownLanguageCodeStillDecodes() {
        // A newer peer may send a language this build doesn't know; that must not look like a corrupt packet.
        val future = Packet(PacketType.TEXT, seq = 3, langCode = 42, timestamp = 0, payload = "?".encodeToByteArray())
        val decoded = roundTrip(future)
        assertEquals(42, decoded.langCode)
        assertEquals(null, decoded.language)
        assertEquals("?", decoded.text)
        // ACK and PONG echo the code they answer, even if unknown.
        assertEquals(42, roundTrip(Packet.ack(of = decoded, ttsMs = 1, queueMs = 0)).langCode)
        assertEquals(42, roundTrip(Packet.pong(of = decoded)).langCode)
    }

    /** Same bytes as `scripts/fake_peer.py --selftest`, so the Kotlin and Python codecs agree. */
    @Test
    fun matchesPythonFakePeerBytes() {
        fun hex(s: String) = s.split(" ").map { it.toInt(16).toByte() }.toByteArray()
        assertArrayEquals(
            hex("69 54 02 01 01 02 01 0a 0b 0c 0d 00 02 61 62 34 8a 35 73"),
            PacketCodec.encode(Packet.text(seq = 0x0102, timestamp = 0x0A0B0C0DL, text = "ab")),
        )
        assertArrayEquals(
            hex("69 54 02 01 00 07 01 00 00 03 e8 00 06 a8 ae b8 cd a4 c7 8f c5 15 c5"),
            PacketCodec.encode(Packet.text(seq = 7, timestamp = 1000, text = "नमस्ते")),
        )
    }

    @Test
    fun wireLayoutMatchesSpec() {
        val bytes = PacketCodec.encode(Packet.text(seq = 0x0102, timestamp = 0x0A0B0C0DL, text = "ab"))
        val expectedHeader = byteArrayOf(
            'i'.code.toByte(), 'T'.code.toByte(), 2, // magic + version
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
