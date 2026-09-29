package com.itantra.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import com.itantra.comm.FramedStream
import com.itantra.comm.LinkState
import com.itantra.comm.Packet
import com.itantra.comm.PacketException
import com.itantra.comm.Transport
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
import java.util.UUID

/**
 * [Transport] over one Bluetooth Classic RFCOMM connection, mirroring [com.itantra.comm.TcpTransport]:
 *
 * - [host]: registers the iTantra service ([APP_UUID]) and serves one paired phone at a time;
 *   after a drop it goes back to listening (and re-registers if Bluetooth was toggled).
 * - [join]: connects to a paired phone's iTantra service and keeps reconnecting with backoff until [close].
 *
 * Framing is shared with TCP via [FramedStream]. Callers must hold BLUETOOTH_CONNECT (Android 12+).
 */
@SuppressLint("MissingPermission") // the UI only creates this after the permission is granted
class BluetoothTransport private constructor(
    private val adapter: BluetoothAdapter,
    private val role: Role,
    private val retryDelaysMs: List<Long>,
) : Transport {

    private sealed interface Role {
        data object Host : Role
        data class Join(val address: String, val name: String) : Role
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<LinkState>(LinkState.Idle)
    private val inbox = Channel<Packet>(Channel.UNLIMITED)
    private val writeLock = Mutex()

    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var server: BluetoothServerSocket? = null
    @Volatile private var framed: FramedStream? = null
    @Volatile private var closed = false

    override val state: StateFlow<LinkState> = _state.asStateFlow()
    override val incoming: Flow<Packet> = inbox.receiveAsFlow()

    private fun start() {
        scope.launch {
            when (role) {
                Role.Host -> runHost()
                is Role.Join -> runJoin(role.address, role.name)
            }
        }
    }

    private suspend fun runHost() {
        while (!closed) {
            val srv = try {
                adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, APP_UUID)
            } catch (e: Exception) { // IOException, or SecurityException if the permission was revoked
                setState(LinkState.Disconnected("Cannot listen: ${e.message ?: "is Bluetooth on?"}"))
                delay(retryDelaysMs.last())
                continue
            }
            server = srv
            while (!closed) {
                setState(LinkState.Listening())
                val client = try {
                    srv.accept()
                } catch (e: IOException) {
                    break // closed by close(), or Bluetooth switched off: re-register above
                }
                setState(LinkState.Disconnected(serve(client)))
            }
            runCatching { srv.close() }
            server = null
            if (!closed) delay(retryDelaysMs.first())
        }
    }

    private suspend fun runJoin(address: String, name: String) {
        var failures = 0
        while (!closed) {
            setState(LinkState.Connecting(name, port = null, attempt = failures + 1))
            val reason = try {
                val s = adapter.getRemoteDevice(address).createRfcommSocketToServiceRecord(APP_UUID)
                socket = s // lets close() abort a pending connect
                s.connect()
                failures = 0
                serve(s)
            } catch (e: Exception) {
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
    private suspend fun serve(s: BluetoothSocket): String {
        val stream = FramedStream(s.inputStream, s.outputStream)
        socket = s
        framed = stream
        setState(LinkState.Connected(s.remoteDevice?.name ?: s.remoteDevice?.address ?: "peer"))
        return try {
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
        val stream = framed?.takeIf { socket?.isConnected == true } ?: throw IOException("Not connected")
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
        /** Identifies iTantra's RFCOMM service; both phones must use the same value. */
        val APP_UUID: UUID = UUID.fromString("5c1f0a7e-3b9d-4e62-8a41-d27f6c9e0b35")
        private const val SERVICE_NAME = "iTantra"
        private val DEFAULT_RETRY_MS = listOf(1_000L, 2_000L, 5_000L)

        fun host(adapter: BluetoothAdapter, retryDelaysMs: List<Long> = DEFAULT_RETRY_MS) =
            BluetoothTransport(adapter, Role.Host, retryDelaysMs).also { it.start() }

        /** Connects to the paired phone at [address] ([name] is shown while connecting). */
        fun join(adapter: BluetoothAdapter, address: String, name: String, retryDelaysMs: List<Long> = DEFAULT_RETRY_MS) =
            BluetoothTransport(adapter, Role.Join(address, name), retryDelaysMs).also { it.start() }
    }
}
