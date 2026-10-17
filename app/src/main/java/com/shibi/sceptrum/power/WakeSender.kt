package com.shibi.sceptrum.power

import com.shibi.sceptrum.domain.PowerPolicy
import com.shibi.sceptrum.domain.Subnet
import com.shibi.sceptrum.domain.WakeOnLan
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Sends Wake-on-LAN for a MAC. Faked in view-model tests. */
fun interface Waker {
    /** True if at least one packet left the phone. */
    suspend fun wake(mac: String): Boolean
}

/** The phone's IPv4 address and prefix on the current network, or null off Wi-Fi/LAN. */
data class LanAddress(val ipv4: String, val prefixLength: Int)

fun interface LanAddressSource {
    fun current(): LanAddress?
}

/**
 * Plan §7.3: the magic packet to UDP [port] at the subnet broadcast from the
 * phone's link properties, [PowerPolicy.PACKET_REPEATS] times,
 * [PowerPolicy.PACKET_GAP_MS] apart. Falls back to 255.255.255.255 when the
 * subnet is unknown. Plain java.net, so it is tested on the JVM.
 */
class UdpWaker(
    private val lan: LanAddressSource,
    private val port: Int = WakeOnLan.PORT,
    private val repeats: Int = PowerPolicy.PACKET_REPEATS,
    private val gapMs: Long = PowerPolicy.PACKET_GAP_MS,
) : Waker {
    override suspend fun wake(mac: String): Boolean {
        val bytes = WakeOnLan.parseMac(mac) ?: return false
        val packet = WakeOnLan.magicPacket(bytes)
        val target = targetAddress()
        return withContext(Dispatchers.IO) {
            var sent = false
            runCatching {
                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    val address = InetAddress.getByName(target)
                    repeat(repeats) { i ->
                        if (i > 0) delay(gapMs)
                        if (runCatching { socket.send(DatagramPacket(packet, packet.size, address, port)) }.isSuccess) sent = true
                    }
                }
            }
            sent
        }
    }

    fun targetAddress(): String =
        lan.current()?.let { Subnet.broadcast(it.ipv4, it.prefixLength) } ?: LIMITED_BROADCAST

    companion object {
        const val LIMITED_BROADCAST = "255.255.255.255"
    }
}
