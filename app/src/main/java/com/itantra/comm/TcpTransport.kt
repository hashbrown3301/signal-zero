package com.itantra.comm

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * [Transport] over one TCP connection.
 *
 * - [host]: listens on 0.0.0.0:port and serves one peer at a time; when the peer
 *   drops it goes back to listening.
 * - [join]: connects to a host and keeps reconnecting (with backoff) until [close].
 *
 * Framing is done by [FramedStream]. A malformed packet closes the connection, because the
 * stream can't be resynced after it.
 */
class TcpTransport private constructor(
    private val role: Role,
    private val retryDelaysMs: List<Long>,
) : Transport {

    private sealed interface Role {
        data class Host(val port: Int) : Role
        data class Join(val host: String, val port: Int) : Role
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<LinkState>(LinkState.Idle)
    private val inbox = Channel<Packet>(Channel.UNLIMITED)
    private val writeLock = Mutex()

    @Volatile private var socket: Socket? = null
    @Volatile private var framed: FramedStream? = null
    @Volatile private var server: ServerSocket? = null
    @Volatile private var closed = false

    override val state: StateFlow<LinkState> = _state.asStateFlow()
    override val incoming: Flow<Packet> = inbox.receiveAsFlow()

    private fun start() {
        scope.launch {
            when (role) {
                is Role.Host -> runHost(role.port)
                is Role.Join -> runJoin(role.host, role.port)
            }
        }
    }

    private suspend fun runHost(port: Int) {
        val srv = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress("0.0.0.0", port))
            }
        } catch (e: IOException) {
            setState(LinkState.Disconnected("Cannot listen on port $port: ${e.message}"))
            return
        }
        server = srv
        // close() can run while bind() is finishing, before the server was registered.
        if (closed) {
            srv.close()
            return
        }
        while (!closed) {
            setState(LinkState.Listening(srv.localPort))
            val client = try {
                srv.accept()
            } catch (e: IOException) {
                break // server socket closed by close()
            }
            setState(LinkState.Disconnected(serve(client)))
        }
    }

    private suspend fun runJoin(host: String, port: Int) {
        var failures = 0
        while (!closed) {
            setState(LinkState.Connecting(host, port, failures + 1))
            val reason = try {
                val s = Socket()
                socket = s // lets close() abort a pending connect
                if (closed) {
                    s.close()
                    return
                }
                s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                failures = 0
                serve(s)
            } catch (e: IOException) {
                runCatching { socket?.close() } // a failed connect still holds a file descriptor
                socket = null
                failures++
                "Connect failed: ${e.message}"
            }
            if (closed) break
            setState(LinkState.Disconnected(reason))
            delay(retryDelaysMs[(failures - 1).coerceIn(0, retryDelaysMs.lastIndex)])
        }
    }

    /** Reads packets from [s] until the connection ends; returns why it ended. */
    private suspend fun serve(s: Socket): String {
        socket = s
        return try {
            // Either close() sees this registered socket or this check catches a
            // close that happened between accept() and registration.
            if (closed) return "Closed"
            s.tcpNoDelay = true // tiny packets: don't let Nagle's algorithm hold them back
            s.keepAlive = true
            val stream = FramedStream(s.getInputStream(), s.getOutputStream())
            framed = stream
            setState(LinkState.Connected("${s.inetAddress.hostAddress}:${s.port}"))
            while (true) inbox.send(stream.read())
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        } catch (e: EOFException) {
            "Peer closed the connection"
        } catch (e: PacketException) {
            "Bad packet: ${e.message}"
        } catch (e: IOException) {
            if (closed) "Closed" else "Connection lost: ${e.message}"
        } finally {
            framed = null
            socket = null
            runCatching { s.close() }
        }
    }

    override suspend fun send(packet: Packet): Int = writeLock.withLock {
        val stream = framed?.takeIf { socket?.let { it.isConnected && !it.isClosed } == true }
            ?: throw IOException("Not connected")
        withContext(Dispatchers.IO) { stream.write(packet) }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { server?.close() }
        runCatching { socket?.close() }
        scope.cancel()
        inbox.close()
        _state.value = LinkState.Closed
    }

    private fun setState(s: LinkState) {
        if (!closed) _state.value = s
    }

    companion object {
        const val DEFAULT_PORT = 5005
        private const val CONNECT_TIMEOUT_MS = 3_000
        private val DEFAULT_RETRY_MS = listOf(1_000L, 2_000L, 5_000L)

        /** Listens for one peer on [port] (0 = any free port; see [LinkState.Listening]). */
        fun host(port: Int = DEFAULT_PORT, retryDelaysMs: List<Long> = DEFAULT_RETRY_MS) =
            TcpTransport(Role.Host(port), retryDelaysMs).also { it.start() }

        /** Connects to [host]:[port], retrying after 1 s, 2 s, then every 5 s. */
        fun join(host: String, port: Int = DEFAULT_PORT, retryDelaysMs: List<Long> = DEFAULT_RETRY_MS) =
            TcpTransport(Role.Join(host, port), retryDelaysMs).also { it.start() }
    }
}
