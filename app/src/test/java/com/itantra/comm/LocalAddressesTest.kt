package com.itantra.comm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAddressesTest {

    @Test
    fun samsungHotspotWhileOnHomeWifi() {
        // The S25 during the preliminary run: hotspot on swlan0, still joined to home Wi-Fi on wlan0.
        val wifi = setOf("wlan0")
        assertEquals(AddressKind.HOTSPOT, classifyInterface("swlan0", wifi))
        assertEquals(AddressKind.WIFI, classifyInterface("wlan0", wifi))
    }

    @Test
    fun otherVendorHotspotNames() {
        assertEquals(AddressKind.HOTSPOT, classifyInterface("ap0", emptySet()))
        assertEquals(AddressKind.HOTSPOT, classifyInterface("softap0", emptySet()))
        assertEquals(AddressKind.HOTSPOT, classifyInterface("wlan1", setOf("wlan0")))
    }

    @Test
    fun androidsWifiClientListWins() {
        // If Android says wlan1 is the joined Wi-Fi, trust it over the name heuristic.
        assertEquals(AddressKind.WIFI, classifyInterface("wlan1", setOf("wlan1")))
    }

    @Test
    fun wlan0DefaultsToWifiWhenAndroidReportsNothing() {
        assertEquals(AddressKind.WIFI, classifyInterface("wlan0", emptySet()))
    }

    @Test
    fun mobileDataAndOthers() {
        assertEquals(AddressKind.MOBILE, classifyInterface("rmnet_data0", emptySet()))
        assertEquals(AddressKind.MOBILE, classifyInterface("ccmni1", emptySet()))
        assertEquals(AddressKind.OTHER, classifyInterface("tun0", emptySet()))
    }

    @Test
    fun hotspotSortsFirstAndOnlyLanKindsCount() {
        val kinds = listOf(AddressKind.MOBILE, AddressKind.WIFI, AddressKind.OTHER, AddressKind.HOTSPOT)
        assertEquals(
            listOf(AddressKind.HOTSPOT, AddressKind.WIFI, AddressKind.MOBILE, AddressKind.OTHER),
            kinds.sortedBy { it.ordinal },
        )
        assertTrue(LocalAddress("swlan0", "10.242.62.95", AddressKind.HOTSPOT).isLan)
        assertTrue(LocalAddress("wlan0", "192.168.1.13", AddressKind.WIFI).isLan)
        assertFalse(LocalAddress("rmnet0", "100.64.0.1", AddressKind.MOBILE).isLan)
    }
}
