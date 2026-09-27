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
            hex("69 54 01 01 01 02 01 0a 0b 0c 0d 00 02 61 62 89 40 59 bd"),
            PacketCodec.encode(Packet.text(seq = 0x0102, timestamp = 0x0A0B0C0DL, text = "ab")),
        )
        assertArrayEquals(
            hex(
                "69 54 01 01 00 07 01 00 00 03 e8 00 12 e0 a4 a8 e0 a4 ae e0 a4 b8 " +
                    "e0 a5 8d e0 a4 a4 e0 a5 87 6d bf 35 41"
            ),
            PacketCodec.encode(Packet.text(seq = 7, timestamp = 1000, text = "नमस्ते")),
        )
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
