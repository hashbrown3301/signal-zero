package com.itantra.comm

import java.net.Inet4Address
import java.net.NetworkInterface

data class LocalAddress(val iface: String, val ip: String) {
    /** Hotspot / Wi-Fi interfaces are what a peer on the same hotspot can reach. */
    val isLikelyLan: Boolean get() = rank(iface) == 0
}

/** This device's IPv4 addresses, most likely hotspot/Wi-Fi first (e.g. Samsung's swlan0/ap0). */
fun localIpv4Addresses(): List<LocalAddress> =
    runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { ni ->
                ni.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .map { LocalAddress(ni.name, it.hostAddress.orEmpty()) }
            }
            .sortedBy { rank(it.iface) }
    }.getOrDefault(emptyList())

private fun rank(iface: String): Int = when {
    iface.startsWith("swlan") || iface.startsWith("ap") || iface.startsWith("wlan") -> 0
    iface.startsWith("rmnet") || iface.startsWith("ccmni") || iface.startsWith("dummy") -> 2
    else -> 1
}

private val IPV4 = Regex("""^((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)$""")

fun isValidIpv4(s: String): Boolean = IPV4.matches(s.trim())
