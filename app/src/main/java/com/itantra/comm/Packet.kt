package com.itantra.comm

enum class PacketType(val code: Int) {
    TEXT(1), ACK(2), PING(3), PONG(4);

    companion object {
        fun fromCode(code: Int): PacketType? = entries.firstOrNull { it.code == code }
    }
}

/** Language of a TEXT packet, so the receiver can pick a matching voice. */
enum class Language(val code: Int) {
    HINDI(1);

    companion object {
        fun fromCode(code: Int): Language? = entries.firstOrNull { it.code == code }
    }
}

/**
 * One message on the wire. [seq] is 16-bit and [timestamp] is 32-bit (sender-clock ms);
 * both are masked to their wire width on creation. ACK and PONG echo the [seq] and
 * [timestamp] of the packet they answer.
 */
class Packet(
    val type: PacketType,
    seq: Int,
    val language: Language,
    timestamp: Long,
    val payload: ByteArray = ByteArray(0),
) {
    val seq: Int = seq and 0xFFFF
    val timestamp: Long = timestamp and 0xFFFF_FFFFL

    init {
        require(payload.size <= PacketCodec.MAX_PAYLOAD) {
            "Payload ${payload.size} B exceeds ${PacketCodec.MAX_PAYLOAD} B"
        }
    }

    /** UTF-8 text of a TEXT packet. */
    val text: String get() = payload.decodeToString()

    /** Receiver's TTS synthesis time, from an ACK payload. */
    val ackTtsMs: Long get() = readUInt32(0)

    /** How long the message waited in the receiver's queue (user was holding the talk button). */
    val ackQueueMs: Long get() = readUInt32(4)

    private fun readUInt32(offset: Int): Long {
        check(type == PacketType.ACK && payload.size == ACK_PAYLOAD_SIZE) { "Not an ACK packet" }
        var v = 0L
        for (i in 0 until 4) v = (v shl 8) or (payload[offset + i].toLong() and 0xFF)
        return v
    }

    override fun equals(other: Any?): Boolean =
        other is Packet && type == other.type && seq == other.seq && language == other.language &&
            timestamp == other.timestamp && payload.contentEquals(other.payload)

    override fun hashCode(): Int =
        listOf(type, seq, language, timestamp, payload.contentHashCode()).hashCode()

    override fun toString(): String =
        "Packet($type seq=$seq lang=$language ts=$timestamp payload=${payload.size} B)"

    companion object {
        const val ACK_PAYLOAD_SIZE = 8

        fun text(seq: Int, timestamp: Long, text: String, language: Language = Language.HINDI) =
            Packet(PacketType.TEXT, seq, language, timestamp, text.encodeToByteArray())

        fun ack(of: Packet, ttsMs: Long, queueMs: Long): Packet {
            val body = ByteArray(ACK_PAYLOAD_SIZE)
            writeUInt32(body, 0, ttsMs)
            writeUInt32(body, 4, queueMs)
            return Packet(PacketType.ACK, of.seq, of.language, of.timestamp, body)
        }

        fun ping(seq: Int, timestamp: Long) = Packet(PacketType.PING, seq, Language.HINDI, timestamp)

        fun pong(of: Packet) = Packet(PacketType.PONG, of.seq, of.language, of.timestamp)

        private fun writeUInt32(dst: ByteArray, offset: Int, value: Long) {
            val v = value.coerceIn(0, 0xFFFF_FFFFL)
            for (i in 0 until 4) dst[offset + i] = (v shr (24 - 8 * i)).toByte()
        }
    }
}
