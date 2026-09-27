package com.itantra.comm

enum class PacketType(val code: Int) {
    TEXT(1), ACK(2), PING(3), PONG(4);

    companion object {
        fun fromCode(code: Int): PacketType? = entries.firstOrNull { it.code == code }
    }
}

/**
 * Language of a TEXT packet, so the receiver can pick a matching voice. The wire [code]s are fixed forever
 * (docs/PHASE3_PLAN.md); [iso] matches the pack ids (`ta-speak`, `ta-listen`, …).
 */
enum class Language(val code: Int, val iso: String) {
    HINDI(1, "hi"), ENGLISH(2, "en"), MARATHI(3, "mr"), GUJARATI(4, "gu"), BENGALI(5, "bn"),
    TAMIL(6, "ta"), TELUGU(7, "te"), KANNADA(8, "kn"), MALAYALAM(9, "ml"), ODIA(10, "or");

    companion object {
        fun fromCode(code: Int): Language? = entries.firstOrNull { it.code == code }
        fun fromIso(iso: String): Language? = entries.firstOrNull { it.iso == iso }
    }
}

/**
 * One message on the wire. [seq] is 16-bit and [timestamp] is 32-bit (sender-clock ms);
 * both are masked to their wire width on creation. ACK and PONG echo the [seq] and
 * [timestamp] of the packet they answer.
 *
 * [langCode] is kept as a raw byte so a packet in a language this build doesn't know (a newer peer)
 * still decodes: [language] is then null and the receiver shows the text instead of dropping the link.
 */
class Packet(
    val type: PacketType,
    seq: Int,
    langCode: Int,
    timestamp: Long,
    val payload: ByteArray = ByteArray(0),
) {
    constructor(type: PacketType, seq: Int, language: Language, timestamp: Long, payload: ByteArray = ByteArray(0)) :
        this(type, seq, language.code, timestamp, payload)

    val seq: Int = seq and 0xFFFF
    val langCode: Int = langCode and 0xFF
    val timestamp: Long = timestamp and 0xFFFF_FFFFL

    /** null if [langCode] is unknown to this build. */
    val language: Language? get() = Language.fromCode(langCode)

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
        other is Packet && type == other.type && seq == other.seq && langCode == other.langCode &&
            timestamp == other.timestamp && payload.contentEquals(other.payload)

    override fun hashCode(): Int =
        listOf(type, seq, langCode, timestamp, payload.contentHashCode()).hashCode()

    override fun toString(): String =
        "Packet($type seq=$seq lang=${language ?: "unknown($langCode)"} ts=$timestamp payload=${payload.size} B)"

    companion object {
        const val ACK_PAYLOAD_SIZE = 8

        fun text(seq: Int, timestamp: Long, text: String, language: Language = Language.HINDI) =
            Packet(PacketType.TEXT, seq, language, timestamp, text.encodeToByteArray())

        fun ack(of: Packet, ttsMs: Long, queueMs: Long): Packet {
            val body = ByteArray(ACK_PAYLOAD_SIZE)
            writeUInt32(body, 0, ttsMs)
            writeUInt32(body, 4, queueMs)
            return Packet(PacketType.ACK, of.seq, of.langCode, of.timestamp, body)
        }

        fun ping(seq: Int, timestamp: Long) = Packet(PacketType.PING, seq, Language.HINDI, timestamp)

        fun pong(of: Packet) = Packet(PacketType.PONG, of.seq, of.langCode, of.timestamp)

        private fun writeUInt32(dst: ByteArray, offset: Int, value: Long) {
            val v = value.coerceIn(0, 0xFFFF_FFFFL)
            for (i in 0 until 4) dst[offset + i] = (v shr (24 - 8 * i)).toByte()
        }
    }
}
