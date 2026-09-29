package com.itantra.comm

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Lossless compact text encoding for TEXT payloads: Indian scripts take 1 byte per character instead of
 * UTF-8's 3. The packet's language code picks the script block, so the receiver decodes with the same table.
 *
 * Per Unicode code point `c`:
 * - `0x01..0x7F`: that byte.
 * - `c` in the language's 128-code-point block (`base..base+0x7F`): byte `0x80 + (c - base)`.
 * - U+0964 / U+0965 (danda, double danda; they live in the Devanagari block but every script uses them):
 *   bytes 0xE4 / 0xE5. Those slots (`base+0x64/0x65`) are unassigned in every other Indic block, so a literal
 *   code point there goes through the escape instead.
 * - anything else (U+0000, other scripts, emoji, ZWJ/ZWNJ, ₹): byte 0x00, then the code point's UTF-8 bytes.
 *
 * English and unknown language codes have no block: only the ASCII and escape rules apply. A high byte in such a
 * packet (e.g. a newer peer's language whose block this build doesn't know) decodes to U+FFFD rather than failing,
 * so the text still shows and the link stays up.
 */
object IndicPack {

    private const val ESCAPE = 0x00

    /** First code point of the language's script block, or 0 if it has none. */
    private fun blockBase(langCode: Int): Int = when (Language.fromCode(langCode)) {
        Language.HINDI, Language.MARATHI -> 0x0900
        Language.BENGALI -> 0x0980
        Language.GUJARATI -> 0x0A80
        Language.ODIA -> 0x0B00
        Language.TAMIL -> 0x0B80
        Language.TELUGU -> 0x0C00
        Language.KANNADA -> 0x0C80
        Language.MALAYALAM -> 0x0D00
        Language.ENGLISH, null -> 0
    }

    /** The single byte for [cp], or -1 if it needs the escape. */
    private fun slotFor(cp: Int, base: Int): Int = when {
        cp in 1..0x7F -> cp
        base == 0 -> -1
        cp == 0x0964 || cp == 0x0965 -> 0x80 + (cp - 0x0900)
        cp in base..base + 0x7F -> (cp - base).let { if (it == 0x64 || it == 0x65) -1 else 0x80 + it }
        else -> -1
    }

    fun encode(text: String, langCode: Int): ByteArray {
        val base = blockBase(langCode)
        val out = ByteArrayOutputStream(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            val slot = slotFor(cp, base)
            if (slot >= 0) {
                out.write(slot)
            } else {
                out.write(ESCAPE)
                out.write(String(Character.toChars(cp)).encodeToByteArray())
            }
        }
        return out.toByteArray()
    }

    /** @throws PacketException on a dangling or invalid escape. */
    fun decode(bytes: ByteArray, langCode: Int): String {
        val base = blockBase(langCode)
        val sb = StringBuilder(bytes.size)
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i++].toInt() and 0xFF
            when {
                b == ESCAPE -> i = readEscaped(bytes, i, sb)
                b < 0x80 -> sb.append(b.toChar())
                base == 0 -> sb.append('\uFFFD')
                b == 0xE4 || b == 0xE5 -> sb.appendCodePoint(0x0900 + (b - 0x80))
                else -> sb.appendCodePoint(base + (b - 0x80))
            }
        }
        return sb.toString()
    }

    /** Appends the one UTF-8 code point starting at [start] and returns the index after it. */
    private fun readEscaped(bytes: ByteArray, start: Int, sb: StringBuilder): Int {
        if (start >= bytes.size) throw PacketException("Escape at end of text")
        val lead = bytes[start].toInt() and 0xFF
        val n = when (lead) {
            in 0x00..0x7F -> 1
            in 0xC2..0xDF -> 2
            in 0xE0..0xEF -> 3
            in 0xF0..0xF4 -> 4
            else -> throw PacketException("Invalid UTF-8 lead byte 0x${lead.toString(16)} after escape")
        }
        if (start + n > bytes.size) throw PacketException("Truncated UTF-8 after escape")
        // The strict decoder also rejects overlong forms, surrogates and values above U+10FFFF.
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        try {
            sb.append(decoder.decode(ByteBuffer.wrap(bytes, start, n)))
        } catch (e: CharacterCodingException) {
            throw PacketException("Invalid UTF-8 after escape")
        }
        return start + n
    }
}
