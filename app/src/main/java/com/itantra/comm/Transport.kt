package com.itantra.comm

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

sealed interface LinkState {
    data object Idle : LinkState
    data class Listening(val port: Int) : LinkState
    data class Connecting(val host: String, val port: Int, val attempt: Int) : LinkState
    data class Connected(val peer: String) : LinkState
    data class Disconnected(val reason: String) : LinkState
    data object Closed : LinkState
}

/**
 * A point-to-point packet link to one peer. Knows nothing about speech.
 * Implementations: [TcpTransport] (Wi-Fi); Bluetooth later.
 */
interface Transport {
    val state: StateFlow<LinkState>

    /** Packets from the peer, in arrival order. Meant for a single collector. */
    val incoming: Flow<Packet>

    /** Sends [packet] and returns the number of bytes put on the wire. Throws if not connected. */
    suspend fun send(packet: Packet): Int

    /** Closes the link for good; [state] becomes [LinkState.Closed]. */
    fun close()
}
