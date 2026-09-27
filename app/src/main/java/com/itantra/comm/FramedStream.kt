package com.itantra.comm

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Packet framing over any byte stream (a TCP socket, a Bluetooth RFCOMM socket, a test pipe).
 * Reads the fixed header, asks [PacketCodec.frameLength] for the full size, then reads the rest.
 */
class FramedStream(input: InputStream, private val output: OutputStream) {

    private val data = DataInputStream(BufferedInputStream(input))
    private val header = ByteArray(PacketCodec.HEADER_SIZE)

    /**
     * Blocks until one whole packet has arrived.
     * @throws EOFException the stream ended (cleanly between packets, or mid-packet)
     * @throws PacketException the bytes aren't a valid packet; the stream can't be resynced
     * @throws IOException any other I/O failure
     */
    fun read(): Packet {
        data.readFully(header)
        val frame = header.copyOf(PacketCodec.frameLength(header))
        data.readFully(frame, PacketCodec.HEADER_SIZE, frame.size - PacketCodec.HEADER_SIZE)
        return PacketCodec.decode(frame)
    }

    /** Writes one packet and returns its size on the wire. Not thread-safe: callers serialize writes. */
    fun write(packet: Packet): Int {
        val bytes = PacketCodec.encode(packet)
        output.write(bytes)
        output.flush()
        return bytes.size
    }
}
