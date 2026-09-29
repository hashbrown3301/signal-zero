package com.itantra.comm

import java.nio.ByteBuffer
import java.util.zip.CRC32

class PacketException(message: String) : Exception(message)

/**
 * Binary wire format (big-endian):
 *
 * | magic "iT" 2 B | version 1 B | type 1 B | seq 2 B | lang 1 B | timestamp 4 B | length 2 B |
 * | payload N B | CRC32 4 B (over everything before it) |
 *
 * Header is [HEADER_SIZE] bytes; total overhead per packet is [OVERHEAD] bytes.
 *
 * Version 2 (written) differs from version 1 (still read) only in the payload:
 *
 * | type | v1 payload | v2 payload |
 * |------|------------|------------|
 * | TEXT | UTF-8 | [IndicPack] bytes for the packet's lang (1 B/char for Indian scripts) |
 * | ACK | ttsMs, queueMs as uint32 each (8 B) | ttsMs, queueMs as uint16 each, saturating (4 B) |
 * | PING, PONG | empty | empty |
 *
 * [Packet.payload] is always UTF-8 in memory; this codec converts both ways, so nothing above it changes.
 */
object PacketCodec {

    const val VERSION = 2
    const val HEADER_SIZE = 13
    const val CRC_SIZE = 4
    const val OVERHEAD = HEADER_SIZE + CRC_SIZE
    const val MAX_PAYLOAD = 0xFFFF

    private const val VERSION_1 = 1
    private const val MAGIC_0 = 'i'.code.toByte()
    private const val MAGIC_1 = 'T'.code.toByte()
    private const val LENGTH_OFFSET = 11

    fun encode(packet: Packet): ByteArray {
        val payload = wirePayload(packet)
        require(payload.size <= MAX_PAYLOAD) { "Wire payload ${payload.size} B exceeds $MAX_PAYLOAD B" }
        val buf = ByteBuffer.allocate(OVERHEAD + payload.size)
        buf.put(MAGIC_0).put(MAGIC_1).put(VERSION.toByte())
        buf.put(packet.type.code.toByte())
        buf.putShort(packet.seq.toShort())
        buf.put(packet.langCode.toByte())
        buf.putInt(packet.timestamp.toInt())
        buf.putShort(payload.size.toShort())
        buf.put(payload)
        val crc = CRC32().apply { update(buf.array(), 0, buf.position()) }
        buf.putInt(crc.value.toInt())
        return buf.array()
    }

    /** Bytes [encode] would produce for [packet], without building them. */
    fun wireSize(packet: Packet): Int = OVERHEAD + wirePayload(packet).size

    private fun wirePayload(packet: Packet): ByteArray =
        if (packet.type == PacketType.TEXT) IndicPack.encode(packet.text, packet.langCode) else packet.payload

    /**
     * Validates a header and returns the full packet size it announces, so a stream
     * reader knows how many more bytes to read. [header] must hold at least [HEADER_SIZE] bytes.
     */
    fun frameLength(header: ByteArray): Int {
        if (header.size < HEADER_SIZE) throw PacketException("Header too short: ${header.size} B")
        if (header[0] != MAGIC_0 || header[1] != MAGIC_1) throw PacketException("Bad magic")
        val version = header[2].toInt() and 0xFF
        if (version != VERSION && version != VERSION_1) throw PacketException("Unsupported version $version")
        val length = ((header[LENGTH_OFFSET].toInt() and 0xFF) shl 8) or
            (header[LENGTH_OFFSET + 1].toInt() and 0xFF)
        return OVERHEAD + length
    }

    fun decode(bytes: ByteArray): Packet {
        val size = frameLength(bytes)
        if (bytes.size != size) throw PacketException("Expected $size B, got ${bytes.size} B")

        val buf = ByteBuffer.wrap(bytes)
        val expectedCrc = buf.getInt(size - CRC_SIZE).toLong() and 0xFFFF_FFFFL
        val actualCrc = CRC32().apply { update(bytes, 0, size - CRC_SIZE) }.value
        if (expectedCrc != actualCrc) throw PacketException("CRC mismatch")

        val version = bytes[2].toInt() and 0xFF
        buf.position(3)
        val typeCode = buf.get().toInt() and 0xFF
        val type = PacketType.fromCode(typeCode) ?: throw PacketException("Unknown type $typeCode")
        val seq = buf.getShort().toInt() and 0xFFFF
        val langCode = buf.get().toInt() and 0xFF
        // An unknown language code is not an error: the packet is valid, this build just lacks that language.
        val timestamp = buf.getInt().toLong() and 0xFFFF_FFFFL
        val length = buf.getShort().toInt() and 0xFFFF
        val wire = ByteArray(length).also { buf.get(it) }

        val payload = when {
            type == PacketType.ACK -> ackPayload(version, wire)
            type == PacketType.TEXT && version != VERSION_1 -> textPayload(wire, langCode)
            else -> wire
        }
        return Packet(type, seq, langCode, timestamp, payload)
    }

    /** Returns the v2 (4 B) form; a v1 ACK's uint32 timings are saturated to uint16. */
    private fun ackPayload(version: Int, wire: ByteArray): ByteArray {
        val expected = if (version == VERSION_1) Packet.ACK_PAYLOAD_SIZE_V1 else Packet.ACK_PAYLOAD_SIZE
        if (wire.size != expected) throw PacketException("ACK payload must be $expected B, got ${wire.size} B")
        if (version != VERSION_1) return wire
        val b = ByteBuffer.wrap(wire)
        return Packet.ackPayload(b.getInt().toLong() and 0xFFFF_FFFFL, b.getInt().toLong() and 0xFFFF_FFFFL)
    }

    private fun textPayload(wire: ByteArray, langCode: Int): ByteArray {
        val utf8 = IndicPack.decode(wire, langCode).encodeToByteArray()
        // 1 wire byte can expand to 3 UTF-8 bytes, so a valid frame can still not fit a Packet.
        if (utf8.size > MAX_PAYLOAD) throw PacketException("Text is ${utf8.size} B as UTF-8, max $MAX_PAYLOAD B")
        return utf8
    }
}
