package com.itantra.comm

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.util.Random
import java.util.zip.CRC32

class IndicPackTest {

    private val samples = mapOf(
        Language.HINDI to "आज सुबह मैं अपने दोस्त के साथ बाजार गया और वहाँ से 12 सब्ज़ियाँ खरीदकर लौट आया।",
        Language.MARATHI to "आज सकाळी मी माझ्या मित्रासोबत बाजारात गेलो आणि भाजीपाला घेऊन घरी परतलो॥",
        Language.GUJARATI to "આજે સવારે હું મારા મિત્ર સાથે બજારમાં ગયો અને ત્યાંથી 12 શાકભાજી લઈને ઘરે પાછો આવ્યો.",
        Language.BENGALI to "আজ সকালে আমি আমার বন্ধুর সাথে বাজারে গিয়ে ১২টি সবজি কিনে বাড়ি ফিরে এসেছি।",
        Language.TAMIL to "இன்று காலை நான் என் நண்பருடன் சந்தைக்குச் சென்று 12 காய்கறிகளை வாங்கிக்கொண்டு வீடு திரும்பினேன்.",
        Language.TELUGU to "ఈ రోజు ఉదయం నేను నా స్నేహితుడితో మార్కెట్‌కి వెళ్లి కూరగాయలు కొనుక్కుని ఇంటికి తిరిగి వచ్చాను.",
        Language.KANNADA to "ಇಂದು ಬೆಳಿಗ್ಗೆ ನಾನು ನನ್ನ ಸ್ನೇಹಿತನೊಂದಿಗೆ ಮಾರುಕಟ್ಟೆಗೆ ಹೋಗಿ ತರಕಾರಿ ಖರೀದಿಸಿ ಮನೆಗೆ ಬಂದೆ.",
        Language.MALAYALAM to "ഇന്ന് രാവിലെ ഞാൻ എന്റെ സുഹൃത്തിനോടൊപ്പം ചന്തയിൽ പോയി പച്ചക്കറി വാങ്ങി വീട്ടിലെത്തി.",
        Language.ODIA to "ଆଜି ସକାଳେ ମୁଁ ମୋ ବନ୍ଧୁ ସହିତ ବଜାରକୁ ଯାଇ ପନିପରିବା କିଣି ଘରକୁ ଫେରିଲି।",
    )

    private fun cps(s: String) = s.codePointCount(0, s.length)

    private fun roundTrip(text: String, code: Int) = IndicPack.decode(IndicPack.encode(text, code), code)

    @Test
    fun sentencesRoundTripAtAboutOneBytePerChar() {
        for ((lang, text) in samples) {
            val bytes = IndicPack.encode(text, lang.code)
            println("${lang.iso}: ${text.encodeToByteArray().size} B UTF-8 -> ${bytes.size} B for ${cps(text)} chars")
            assertEquals(lang.iso, text, IndicPack.decode(bytes, lang.code))
            // Only the ZWNJ in Telugu (U+200C) needs the 4-byte escape; everything else is one byte.
            val escapes = text.count { it == '‌' }
            assertEquals(lang.iso, cps(text) + 3 * escapes, bytes.size)
        }
    }

    @Test
    fun mixedContentRoundTripsInEveryLanguage() {
        // danda, digits, English, rupee sign, ZWJ (क्‍ष), emoji
        val text = "कीमत ₹250 है। OK, क्‍ष 👍 ।॥"
        for (code in 0..255) {
            assertEquals("lang $code", text, roundTrip(text, code))
        }
        val hindi = IndicPack.encode(text, Language.HINDI.code)
        assertTrue(hindi.size < text.encodeToByteArray().size)
    }

    @Test
    fun escapedItemsCostTheirUtf8BytesPlusOne() {
        val hindi = Language.HINDI.code
        assertEquals(4, IndicPack.encode("₹", hindi).size)
        assertEquals(4, IndicPack.encode("‍", hindi).size)
        assertEquals(5, IndicPack.encode("👍", hindi).size)
        assertEquals(2, IndicPack.encode("\u0000", hindi).size)
        assertEquals(1, IndicPack.encode("।", hindi).size)
    }

    @Test
    fun englishIsPlainAscii() {
        val text = "Hello, how are you? 123"
        assertArrayEquals(text.encodeToByteArray(), IndicPack.encode(text, Language.ENGLISH.code))
        assertEquals(text, IndicPack.decode(text.encodeToByteArray(), Language.ENGLISH.code))
    }

    @Test
    fun hindiTaggedAsEnglishStillRoundTrips() {
        val text = samples.getValue(Language.HINDI)
        val bytes = IndicPack.encode(text, Language.ENGLISH.code)
        assertEquals(text, IndicPack.decode(bytes, Language.ENGLISH.code))
        // No block: every Devanagari letter is escaped, so it is bigger than plain UTF-8.
        assertTrue(bytes.size > text.encodeToByteArray().size)
        assertEquals(text, roundTrip(text, 42))
    }

    @Test
    fun dandaIsSharedByEveryScript() {
        for (lang in listOf(Language.HINDI, Language.BENGALI, Language.TAMIL, Language.ODIA, Language.MALAYALAM)) {
            assertArrayEquals(lang.iso, byteArrayOf(0xE4.toByte(), 0xE5.toByte()), IndicPack.encode("।॥", lang.code))
        }
    }

    @Test
    fun unassignedDandaSlotGoesThroughEscape() {
        // U+09E4 / U+09E5 sit at base+0x64/0x65 in the Bengali block; those bytes mean danda, so they escape.
        val bengali = Language.BENGALI.code
        assertArrayEquals(
            byteArrayOf(0x00, 0xE0.toByte(), 0xA7.toByte(), 0xA4.toByte()),
            IndicPack.encode("৤", bengali),
        )
        assertEquals("৤৥।", roundTrip("৤৥।", bengali))
        assertEquals("੤੥", roundTrip("੤੥", Language.GUJARATI.code))
    }

    @Test
    fun lastCodePointsOfEveryBlockRoundTrip() {
        for (lang in Language.entries) {
            val all = buildString { for (c in 0..0x7F) appendCodePoint(c) }
            assertEquals(lang.iso, all, roundTrip(all, lang.code))
            for (base in listOf(0x0900, 0x0980, 0x0A80, 0x0B00, 0x0B80, 0x0C00, 0x0C80, 0x0D00)) {
                val block = buildString { for (c in base - 1..base + 0x80) appendCodePoint(c) }
                assertEquals("${lang.iso} $base", block, roundTrip(block, lang.code))
            }
        }
    }

    @Test
    fun malformedInputThrowsPacketException() {
        fun bad(name: String, vararg b: Int, lang: Int = Language.HINDI.code) =
            assertThrows(name, PacketException::class.java) {
                IndicPack.decode(ByteArray(b.size) { b[it].toByte() }, lang)
            }
        bad("escape at end", 0x41, 0x00)
        bad("invalid lead", 0x00, 0x80)
        bad("invalid lead C0", 0x00, 0xC0, 0x80)
        bad("invalid lead F5", 0x00, 0xF5, 0x80, 0x80, 0x80)
        bad("truncated 2", 0x00, 0xC3)
        bad("truncated 3", 0x00, 0xE2, 0x82)
        bad("truncated 4", 0x00, 0xF0, 0x9F, 0x91)
        bad("bad continuation", 0x00, 0xC3, 0x28)
        bad("overlong 3", 0x00, 0xE0, 0x80, 0xAF)
        bad("surrogate", 0x00, 0xED, 0xA0, 0x80)
        bad("above U+10FFFF", 0x00, 0xF4, 0x90, 0x80, 0x80)
    }

    @Test
    fun highByteWithoutABlockBecomesReplacementCharInsteadOfDroppingTheLink() {
        assertEquals("a\uFFFD", IndicPack.decode(byteArrayOf(0x61, 0x80.toByte()), Language.ENGLISH.code))
        assertEquals("\uFFFD", IndicPack.decode(byteArrayOf(0xE4.toByte()), 99))
    }

    // --- fuzz --------------------------------------------------------------------------------

    private fun randomBytes(rnd: Random, maxLen: Int) = ByteArray(rnd.nextInt(maxLen + 1)).also { rnd.nextBytes(it) }

    private fun randomText(rnd: Random): String = buildString {
        repeat(rnd.nextInt(40)) {
            val cp = when (rnd.nextInt(4)) {
                0 -> rnd.nextInt(0x80)
                1 -> 0x0900 + rnd.nextInt(0x480)
                2 -> rnd.nextInt(0x10000)
                else -> 0x10000 + rnd.nextInt(0x100000)
            }
            if (cp !in 0xD800..0xDFFF) appendCodePoint(cp)
        }
    }

    /** A frame with a correct length and CRC around an arbitrary payload. */
    private fun frame(version: Int, type: Int, lang: Int, payload: ByteArray): ByteArray {
        val buf = ByteBuffer.allocate(PacketCodec.OVERHEAD + payload.size)
        buf.put('i'.code.toByte()).put('T'.code.toByte()).put(version.toByte()).put(type.toByte())
        buf.putShort(1).put(lang.toByte()).putInt(1).putShort(payload.size.toShort()).put(payload)
        return withCrc(buf.array())
    }

    private fun withCrc(bytes: ByteArray): ByteArray {
        val n = bytes.size - PacketCodec.CRC_SIZE
        if (n < 0) return bytes
        ByteBuffer.wrap(bytes).putInt(n, CRC32().apply { update(bytes, 0, n) }.value.toInt())
        return bytes
    }

    private fun decodeOrPacketException(bytes: ByteArray) {
        try {
            PacketCodec.decode(bytes)
        } catch (_: PacketException) {
            // the only allowed failure
        }
    }

    @Test
    fun fuzzDecodersOnlyThrowPacketException() {
        val rnd = Random(0x17A17AL)
        repeat(20_000) { i ->
            val lang = rnd.nextInt(256)
            when (i % 3) {
                0 -> decodeOrPacketException(randomBytes(rnd, 300))
                1 -> decodeOrPacketException(frame(1 + rnd.nextInt(2), rnd.nextInt(6), lang, randomBytes(rnd, 100)))
                else -> {
                    val valid = PacketCodec.encode(Packet(PacketType.TEXT, i, lang, 0, randomText(rnd).encodeToByteArray()))
                    repeat(1 + rnd.nextInt(3)) {
                        val pos = rnd.nextInt(valid.size)
                        valid[pos] = (valid[pos].toInt() xor (1 shl rnd.nextInt(8))).toByte()
                    }
                    // Half the time repair the CRC so the payload decoder, not the checksum, sees the damage.
                    decodeOrPacketException(if (rnd.nextBoolean()) withCrc(valid) else valid)
                }
            }
            val decoded = try {
                IndicPack.decode(randomBytes(rnd, 300), lang)
            } catch (_: PacketException) {
                null // the only allowed failure
            }
            if (decoded != null) assertEquals(decoded, roundTrip(decoded, lang))
        }
    }

    @Test
    fun fuzzRandomStringsRoundTrip() {
        val rnd = Random(42)
        repeat(20_000) {
            val text = randomText(rnd)
            val lang = rnd.nextInt(256)
            assertEquals("lang $lang", text, roundTrip(text, lang))
        }
    }

    @Test
    fun fuzzTextPacketsRoundTripThroughCodec() {
        val rnd = Random(7)
        repeat(5_000) {
            val packet = Packet(PacketType.TEXT, it, rnd.nextInt(256), 0, randomText(rnd).encodeToByteArray())
            assertEquals(packet, PacketCodec.decode(PacketCodec.encode(packet)))
        }
    }
}
