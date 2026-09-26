package com.itantra.comm

import java.net.Inet4Address
import java.net.NetworkInterface

enum class AddressKind(val label: String) {
    /** This phone's own hotspot: what a joining phone on the hotspot connects to. */
    HOTSPOT("hotspot"),
    /** A Wi-Fi network this phone has joined (e.g. home Wi-Fi). */
    WIFI("Wi-Fi"),
    MOBILE("mobile data"),
    OTHER("other"),
}

data class LocalAddress(val iface: String, val ip: String, val kind: AddressKind) {
    /** Reachable by a peer on the same hotspot or Wi-Fi. */
    val isLan: Boolean get() = kind == AddressKind.HOTSPOT || kind == AddressKind.WIFI
}

/**
 * This device's IPv4 addresses, hotspot first. [wifiClientIfaces] are the interfaces Android
 * reports for joined Wi-Fi networks; any other Wi-Fi-type interface is taken to be the hotspot,
 * since vendors name it differently (Samsung swlan0, others ap0 / wlan1 / softap0).
 */
fun localIpv4Addresses(wifiClientIfaces: Set<String>): List<LocalAddress> =
    runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { ni ->
                ni.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .map { it.hostAddress.orEmpty() }
                    .map { ip -> LocalAddress(ni.name, ip, classifyInterface(ni.name, wifiClientIfaces)) }
            }
            .sortedBy { it.kind.ordinal }
    }.getOrDefault(emptyList())

fun classifyInterface(iface: String, wifiClientIfaces: Set<String>): AddressKind = when {
    iface in wifiClientIfaces -> AddressKind.WIFI
    HOTSPOT_PREFIXES.any { iface.startsWith(it) } -> AddressKind.HOTSPOT
    // wlan0 is the Wi-Fi client on practically every phone; wlan1+ is usually the soft AP.
    iface == "wlan0" -> AddressKind.WIFI
    iface.startsWith("wlan") -> AddressKind.HOTSPOT
    MOBILE_PREFIXES.any { iface.startsWith(it) } -> AddressKind.MOBILE
    else -> AddressKind.OTHER
}

private val HOTSPOT_PREFIXES = listOf("swlan", "ap", "softap")
private val MOBILE_PREFIXES = listOf("rmnet", "ccmni", "dummy", "v4-rmnet")

private val IPV4 = Regex("""^((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)$""")

fun isValidIpv4(s: String): Boolean = IPV4.matches(s.trim())
