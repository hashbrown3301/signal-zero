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
 */
object PacketCodec {

    const val VERSION = 1
    const val HEADER_SIZE = 13
    const val CRC_SIZE = 4
    const val OVERHEAD = HEADER_SIZE + CRC_SIZE
    const val MAX_PAYLOAD = 0xFFFF

    private const val MAGIC_0 = 'i'.code.toByte()
    private const val MAGIC_1 = 'T'.code.toByte()
    private const val LENGTH_OFFSET = 11

    fun encode(packet: Packet): ByteArray {
        val buf = ByteBuffer.allocate(OVERHEAD + packet.payload.size)
        buf.put(MAGIC_0).put(MAGIC_1).put(VERSION.toByte())
        buf.put(packet.type.code.toByte())
        buf.putShort(packet.seq.toShort())
        buf.put(packet.langCode.toByte())
        buf.putInt(packet.timestamp.toInt())
        buf.putShort(packet.payload.size.toShort())
        buf.put(packet.payload)
        val crc = CRC32().apply { update(buf.array(), 0, buf.position()) }
        buf.putInt(crc.value.toInt())
        return buf.array()
    }

    /**
     * Validates a header and returns the full packet size it announces, so a stream
     * reader knows how many more bytes to read. [header] must hold at least [HEADER_SIZE] bytes.
     */
    fun frameLength(header: ByteArray): Int {
        if (header.size < HEADER_SIZE) throw PacketException("Header too short: ${header.size} B")
        if (header[0] != MAGIC_0 || header[1] != MAGIC_1) throw PacketException("Bad magic")
        val version = header[2].toInt() and 0xFF
        if (version != VERSION) throw PacketException("Unsupported version $version")
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

        buf.position(3)
        val typeCode = buf.get().toInt() and 0xFF
        val type = PacketType.fromCode(typeCode) ?: throw PacketException("Unknown type $typeCode")
        val seq = buf.getShort().toInt() and 0xFFFF
        val langCode = buf.get().toInt() and 0xFF
        // An unknown language code is not an error: the packet is valid, this build just lacks that language.
        val timestamp = buf.getInt().toLong() and 0xFFFF_FFFFL
        val length = buf.getShort().toInt() and 0xFFFF
        val payload = ByteArray(length).also { buf.get(it) }

        if (type == PacketType.ACK && length != Packet.ACK_PAYLOAD_SIZE) {
            throw PacketException("ACK payload must be ${Packet.ACK_PAYLOAD_SIZE} B, got $length B")
        }
        return Packet(type, seq, langCode, timestamp, payload)
    }
}
