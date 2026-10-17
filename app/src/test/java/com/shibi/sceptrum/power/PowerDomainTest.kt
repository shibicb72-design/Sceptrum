package com.shibi.sceptrum.power

import com.shibi.sceptrum.domain.ConnectionStatus
import com.shibi.sceptrum.domain.LanAccess
import com.shibi.sceptrum.domain.LanAccessState
import com.shibi.sceptrum.domain.PowerAction
import com.shibi.sceptrum.domain.PowerPolicy
import com.shibi.sceptrum.domain.StandbyTip
import com.shibi.sceptrum.domain.StandbyTips
import com.shibi.sceptrum.domain.Subnet
import com.shibi.sceptrum.domain.WakeOnLan
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plan §11 pure policies: WoL bytes, subnet broadcast, PowerPolicy, tips and LanAccess. */
class PowerDomainTest {
    private val mac = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0x01, 0x02, 0x03)

    @Test
    fun magicPacketIsSixFfThenTheMacSixteenTimes() {
        val packet = WakeOnLan.magicPacket(mac)
        assertEquals(102, packet.size)
        assertArrayEquals(ByteArray(6) { 0xFF.toByte() }, packet.copyOfRange(0, 6))
        for (i in 0 until 16) {
            assertArrayEquals("repeat $i", mac, packet.copyOfRange(6 + i * 6, 12 + i * 6))
        }
    }

    @Test
    fun macParsingAcceptsColonsDashesAndBareHex() {
        assertArrayEquals(mac, WakeOnLan.parseMac("AA:BB:CC:01:02:03"))
        assertArrayEquals(mac, WakeOnLan.parseMac("aa-bb-cc-01-02-03"))
        assertArrayEquals(mac, WakeOnLan.parseMac(" aabbcc010203 "))
        assertNull(WakeOnLan.parseMac("AA:BB:CC:01:02"))
        assertNull(WakeOnLan.parseMac("GG:BB:CC:01:02:03"))
        assertNull(WakeOnLan.parseMac(""))
    }

    @Test
    fun subnetBroadcast() {
        assertEquals("192.168.1.255", Subnet.broadcast("192.168.1.50", 24))
        assertEquals("10.0.15.255", Subnet.broadcast("10.0.12.7", 22))
        assertEquals("172.16.255.255", Subnet.broadcast("172.16.4.9", 16))
        assertEquals("192.168.1.50", Subnet.broadcast("192.168.1.50", 32))
        assertEquals("255.255.255.255", Subnet.broadcast("192.168.1.50", 0))
        assertNull(Subnet.broadcast("192.168.1", 24))
        assertNull(Subnet.broadcast("192.168.1.300", 24))
        assertNull(Subnet.broadcast("192.168.1.5", 33))
    }

    @Test
    fun powerButtonPolicy() {
        val known = "AA:BB:CC:01:02:03"
        assertEquals(PowerAction.SendPowerKey, PowerPolicy.onPress(ConnectionStatus.Connected, known))
        assertEquals(PowerAction.SendPowerKey, PowerPolicy.onPress(ConnectionStatus.Connected, null))
        for (status in listOf(ConnectionStatus.Offline, ConnectionStatus.Reconnecting, ConnectionStatus.Connecting)) {
            assertEquals(PowerAction.Wake(known), PowerPolicy.onPress(status, known))
            assertEquals(PowerAction.Reconnect, PowerPolicy.onPress(status, null))
            assertEquals("a broken MAC cannot wake anything", PowerAction.Reconnect, PowerPolicy.onPress(status, "nope"))
        }
        for (status in listOf(ConnectionStatus.NoDevice, ConnectionStatus.NeedsPairing, ConnectionStatus.IdentityChanged)) {
            assertEquals(PowerAction.Unavailable, PowerPolicy.onPress(status, known))
        }
        assertTrue(PowerPolicy.powerKeyAfterWake(isOn = false))
        assertFalse(PowerPolicy.powerKeyAfterWake(isOn = true))
        assertFalse("unknown state: do not toggle it back off", PowerPolicy.powerKeyAfterWake(isOn = null))
        assertEquals(20_000L, PowerPolicy.WAKE_WINDOW_MS)
        assertEquals(3, PowerPolicy.PACKET_REPEATS)
    }

    @Test
    fun standbyTipsPerDevice() {
        assertEquals(
            listOf(StandbyTip.XgimiWifiOnly, StandbyTip.DeepSleep),
            StandbyTips.forDevice("XGIMI Horizon S Max", null, hasMac = true),
        )
        assertEquals(
            listOf(StandbyTip.TclScreenless, StandbyTip.PreferEthernet, StandbyTip.DeepSleep),
            StandbyTips.forDevice("Living room", "TCL", hasMac = true),
        )
        assertEquals(
            listOf(StandbyTip.NoMac, StandbyTip.NetworkStandby, StandbyTip.PreferEthernet, StandbyTip.DeepSleep),
            StandbyTips.forDevice("Bedroom TV", null, hasMac = false),
        )
    }

    @Test
    fun lanAccessIsOnlyBlockedFromTarget37WithoutTheGrant() {
        assertEquals(LanAccessState.Allowed, LanAccess.classify(sdkInt = 37, targetSdk = 36, granted = false))
        assertEquals(LanAccessState.Allowed, LanAccess.classify(sdkInt = 36, targetSdk = 37, granted = false))
        assertEquals(LanAccessState.Allowed, LanAccess.classify(sdkInt = 37, targetSdk = 37, granted = true))
        assertEquals(LanAccessState.Blocked, LanAccess.classify(sdkInt = 37, targetSdk = 37, granted = false))
    }

    @Test
    fun udpWakerSendsThreeMagicPacketsToTheBroadcastAddress() {
        DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { listener ->
            listener.soTimeout = 3_000
            // A /32 "subnet" makes the broadcast the loopback address itself.
            val waker = UdpWaker({ LanAddress("127.0.0.1", 32) }, port = listener.localPort, gapMs = 10)
            assertEquals("127.0.0.1", waker.targetAddress())
            assertTrue(runBlocking { waker.wake("AA:BB:CC:01:02:03") })
            repeat(3) {
                val buffer = ByteArray(200)
                val packet = DatagramPacket(buffer, buffer.size)
                listener.receive(packet)
                assertArrayEquals(WakeOnLan.magicPacket(mac), buffer.copyOf(packet.length))
            }
        }
    }

    @Test
    fun udpWakerFallsBackToTheLimitedBroadcastAndRefusesBadMacs() {
        val waker = UdpWaker({ null })
        assertEquals("255.255.255.255", waker.targetAddress())
        assertFalse(runBlocking { waker.wake("not a mac") })
        assertEquals("192.168.1.255", UdpWaker({ LanAddress("192.168.1.23", 24) }).targetAddress())
    }
}
