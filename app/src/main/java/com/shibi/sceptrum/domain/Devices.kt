package com.shibi.sceptrum.domain

import com.shibi.sceptrum.store.PairedDevice

/** A TV answering mDNS for `_androidtvremote2._tcp`. [port] is the control port it advertises. */
data class DiscoveredTv(val serviceName: String, val host: String, val port: Int)

data class DiscoveryState(
    val devices: List<DiscoveredTv> = emptyList(),
    val scanning: Boolean = false,
    /** NsdManager refused to start; manual entry still works. */
    val failed: Boolean = false,
)

data class SavedRow(
    val id: String,
    val name: String,
    val host: String,
    val active: Boolean,
    /** Seen by the current or last scan. */
    val online: Boolean,
    /** Wake-on-LAN MAC, empty when the certificate had none. */
    val mac: String = "",
)

data class DiscoveredRow(val name: String, val host: String, val port: Int)

data class DeviceLists(
    val saved: List<SavedRow>,
    /** Found on the network and not paired yet. */
    val discovered: List<DiscoveredRow>,
    /** Saved devices found at a new address: id to new host. */
    val hostChanges: Map<String, String>,
)

/**
 * Merges saved and discovered TVs. A discovered TV is the saved one when the
 * address matches, or when the mDNS name matches the saved name (then the
 * address changed and is reported in [DeviceLists.hostChanges]).
 */
object DeviceListMerge {
    fun merge(saved: List<PairedDevice>, activeId: String, discovered: List<DiscoveredTv>): DeviceLists {
        val effectiveActive = activeId.takeIf { id -> saved.any { it.id == id } } ?: saved.firstOrNull()?.id
        val claimed = mutableSetOf<DiscoveredTv>()
        val hostChanges = mutableMapOf<String, String>()
        val savedRows = saved.map { device ->
            val match = discovered.firstOrNull { it.host == device.host }
                ?: discovered.firstOrNull { it.serviceName == device.name }
            if (match != null) {
                claimed += match
                if (match.host != device.host) hostChanges[device.id] = match.host
            }
            SavedRow(
                id = device.id,
                name = device.name,
                host = match?.host ?: device.host,
                active = device.id == effectiveActive,
                online = match != null,
                mac = device.mac,
            )
        }
        val discoveredRows = discovered
            .filterNot { it in claimed }
            .sortedBy { it.serviceName.lowercase() }
            .map { DiscoveredRow(name = it.serviceName, host = it.host, port = it.port) }
        return DeviceLists(savedRows, discoveredRows, hostChanges)
    }
}

/** "Add by IP" input. */
object ManualAddress {
    private val Ipv4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

    /** The trimmed dotted IPv4 address, or null if it is not one. */
    fun parseIpv4(input: String): String? {
        val match = Ipv4.matchEntire(input.trim()) ?: return null
        val octets = match.groupValues.drop(1).map { it.toInt() }
        if (octets.any { it > 255 }) return null
        return octets.joinToString(".")
    }

    /** RFC 1918 or link-local: where a home TV lives. */
    fun isPrivateLan(ipv4: String): Boolean {
        val o = ipv4.split(".").mapNotNull { it.toIntOrNull() }
        if (o.size != 4) return false
        return o[0] == 10 ||
            (o[0] == 172 && o[1] in 16..31) ||
            (o[0] == 192 && o[1] == 168) ||
            (o[0] == 169 && o[1] == 254)
    }
}

/** Plan §4.4: a device is keyed by the MAC in its certificate, else by the certificate fingerprint. */
object DeviceIds {
    fun forTv(mac: String?, certSha256: ByteArray): String =
        if (!mac.isNullOrEmpty()) "mac:$mac" else "sha256:" + certSha256.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
