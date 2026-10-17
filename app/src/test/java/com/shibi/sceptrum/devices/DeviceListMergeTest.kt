package com.shibi.sceptrum.devices

import com.shibi.sceptrum.domain.DeviceIds
import com.shibi.sceptrum.domain.DeviceListMerge
import com.shibi.sceptrum.domain.DiscoveredRow
import com.shibi.sceptrum.domain.DiscoveredTv
import com.shibi.sceptrum.domain.ManualAddress
import com.shibi.sceptrum.store.PairedDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceListMergeTest {
    private val projector = PairedDevice(id = "mac:01", name = "XGIMI Horizon S Max", host = "192.168.1.50")
    private val tcl = PairedDevice(id = "mac:02", name = "TCL 75QM6K", host = "192.168.1.60")

    @Test
    fun discoveredSavedDeviceIsOnlineAndNotListedTwice() {
        val lists = DeviceListMerge.merge(
            saved = listOf(projector, tcl),
            activeId = "mac:02",
            discovered = listOf(
                DiscoveredTv("XGIMI Horizon S Max", "192.168.1.50", 6466),
                DiscoveredTv("Bedroom TV", "192.168.1.70", 6466),
            ),
        )
        assertEquals(listOf(true, false), lists.saved.map { it.online })
        assertEquals(listOf(false, true), lists.saved.map { it.active })
        assertEquals(listOf(DiscoveredRow("Bedroom TV", "192.168.1.70", 6466)), lists.discovered)
        assertTrue(lists.hostChanges.isEmpty())
    }

    @Test
    fun sameNameAtANewAddressIsAHostChange() {
        val lists = DeviceListMerge.merge(
            saved = listOf(projector),
            activeId = "",
            discovered = listOf(DiscoveredTv("XGIMI Horizon S Max", "192.168.1.99", 6466)),
        )
        assertEquals(mapOf("mac:01" to "192.168.1.99"), lists.hostChanges)
        assertEquals("192.168.1.99", lists.saved.single().host)
        assertTrue("active falls back to the first device", lists.saved.single().active)
        assertTrue(lists.discovered.isEmpty())
    }

    @Test
    fun discoveredAreSortedByName() {
        val lists = DeviceListMerge.merge(
            emptyList(),
            "",
            listOf(DiscoveredTv("b TV", "10.0.0.2", 6466), DiscoveredTv("A TV", "10.0.0.1", 6466)),
        )
        assertEquals(listOf("A TV", "b TV"), lists.discovered.map { it.name })
    }

    @Test
    fun ipv4Parsing() {
        assertEquals("192.168.1.50", ManualAddress.parseIpv4(" 192.168.1.50 "))
        assertEquals("10.0.0.7", ManualAddress.parseIpv4("10.0.0.007"))
        assertNull(ManualAddress.parseIpv4("192.168.1"))
        assertNull(ManualAddress.parseIpv4("192.168.1.256"))
        assertNull(ManualAddress.parseIpv4("tv.local"))
        assertNull(ManualAddress.parseIpv4(""))
    }

    @Test
    fun privateRanges() {
        listOf("10.1.2.3", "172.16.0.1", "172.31.255.255", "192.168.0.10", "169.254.3.4")
            .forEach { assertTrue(it, ManualAddress.isPrivateLan(it)) }
        listOf("8.8.8.8", "172.32.0.1", "192.169.0.1", "100.64.0.1")
            .forEach { assertFalse(it, ManualAddress.isPrivateLan(it)) }
    }

    @Test
    fun idsPreferTheMac() {
        assertEquals("mac:AA:BB", DeviceIds.forTv("AA:BB", byteArrayOf(1)))
        assertEquals("sha256:0aff", DeviceIds.forTv(null, byteArrayOf(0x0A, 0xFF.toByte())))
        assertEquals("sha256:0aff", DeviceIds.forTv("", byteArrayOf(0x0A, 0xFF.toByte())))
    }
}
