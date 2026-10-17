package com.shibi.sceptrum.domain

/** Wake-on-LAN magic packet (plan §7.3): six 0xFF bytes, then the MAC sixteen times. */
object WakeOnLan {
    const val PORT = 9
    const val PACKET_SIZE = 6 + 16 * 6

    private val Mac = Regex("^[0-9A-Fa-f]{2}([:-]?[0-9A-Fa-f]{2}){5}$")

    /** "AA:BB:CC:DD:EE:FF", "aa-bb-…" or "aabbccddeeff" to six bytes; null if it is not a MAC. */
    fun parseMac(mac: String): ByteArray? {
        val text = mac.trim()
        if (!Mac.matches(text)) return null
        val hex = text.filter { it != ':' && it != '-' }
        return ByteArray(6) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    fun magicPacket(mac: ByteArray): ByteArray {
        require(mac.size == 6) { "MAC must be 6 bytes" }
        return ByteArray(PACKET_SIZE) { i -> if (i < 6) 0xFF.toByte() else mac[(i - 6) % 6] }
    }
}

/** IPv4 subnet maths for the WoL broadcast address. */
object Subnet {
    /** Directed broadcast of [ipv4]/[prefixLength], e.g. 192.168.1.50/24 to 192.168.1.255. */
    fun broadcast(ipv4: String, prefixLength: Int): String? {
        if (prefixLength !in 0..32) return null
        val address = toInt(ipv4) ?: return null
        val hostMask = if (prefixLength == 0) -1 else (1 shl (32 - prefixLength)) - 1
        return fromInt(address or hostMask)
    }

    private fun toInt(ipv4: String): Int? {
        val parts = ipv4.trim().split(".")
        if (parts.size != 4) return null
        var value = 0
        for (part in parts) {
            val octet = part.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
            value = (value shl 8) or octet
        }
        return value
    }

    private fun fromInt(value: Int): String =
        (3 downTo 0).joinToString(".") { ((value ushr (it * 8)) and 0xFF).toString() }
}

/** What the Power button does right now. */
sealed interface PowerAction {
    /** Connected: toggle with `KEYCODE_POWER`. */
    data object SendPowerKey : PowerAction

    /** Not reachable and the MAC is known: magic packets, then wait and reconnect. */
    data class Wake(val mac: String) : PowerAction

    /** Not reachable and no MAC: only wait and reconnect, in case it is just slow. */
    data object Reconnect : PowerAction

    /** Nothing to talk to (no device, or the TV must be paired again). */
    data object Unavailable : PowerAction
}

/**
 * Plan §7.3 power button, as a pure decision. While connected the key toggles
 * both ways (many Google TVs keep 6466 up in standby); otherwise the phone
 * tries to wake the TV and reconnect for [WAKE_WINDOW_MS], and once connected
 * sends `KEYCODE_POWER` only if the TV still reports standby.
 */
object PowerPolicy {
    const val WAKE_WINDOW_MS = 20_000L
    const val PACKET_REPEATS = 3
    const val PACKET_GAP_MS = 100L

    /** How often to cut the reconnect backoff short while waking. */
    const val RETRY_EVERY_MS = 2_000L

    fun onPress(status: ConnectionStatus, mac: String?): PowerAction = when (status) {
        ConnectionStatus.Connected -> PowerAction.SendPowerKey
        ConnectionStatus.NoDevice, ConnectionStatus.NeedsPairing, ConnectionStatus.IdentityChanged -> PowerAction.Unavailable
        ConnectionStatus.Connecting, ConnectionStatus.Reconnecting, ConnectionStatus.Offline ->
            if (mac != null && WakeOnLan.parseMac(mac) != null) PowerAction.Wake(mac) else PowerAction.Reconnect
    }

    /** After a wake reconnects: trust `remote_start.started`, not the open socket (plan §7.3). */
    fun powerKeyAfterWake(isOn: Boolean?): Boolean = isOn == false
}

/** Per-device standby advice for the "couldn't wake" card. */
enum class StandbyTip {
    /** Turn on network standby / Wake on LAN / Quick start in the TV's power settings. */
    NetworkStandby,

    /** TCL: Settings > System > Power & energy > Screenless service. */
    TclScreenless,

    /** XGIMI projectors are Wi-Fi only: look for network standby, else use the projector's remote. */
    XgimiWifiOnly,

    /** Wake over Wi-Fi is unreliable; Ethernet works far better. */
    PreferEthernet,

    /** The certificate gave no MAC, so no magic packet could be sent. */
    NoMac,

    /** Deep sleep turns the network off; nothing on the LAN can wake it. */
    DeepSleep,
}

object StandbyTips {
    fun forDevice(name: String?, vendor: String?, hasMac: Boolean): List<StandbyTip> {
        val who = listOfNotNull(name, vendor).joinToString(" ").lowercase()
        val brand = when {
            "xgimi" in who -> StandbyTip.XgimiWifiOnly
            "tcl" in who -> StandbyTip.TclScreenless
            else -> StandbyTip.NetworkStandby
        }
        return buildList {
            if (!hasMac) add(StandbyTip.NoMac)
            add(brand)
            if (brand != StandbyTip.XgimiWifiOnly) add(StandbyTip.PreferEthernet)
            add(StandbyTip.DeepSleep)
        }
    }
}

/** Plan §8: whether the phone may open LAN sockets. */
enum class LanAccessState { Allowed, Blocked }

object LanAccess {
    /** Android 17 enforces ACCESS_LOCAL_NETWORK only for apps that target 37 or later. */
    const val ENFORCED_FROM_SDK = 37

    fun classify(sdkInt: Int, targetSdk: Int, granted: Boolean): LanAccessState = when {
        sdkInt < ENFORCED_FROM_SDK || targetSdk < ENFORCED_FROM_SDK || granted -> LanAccessState.Allowed
        else -> LanAccessState.Blocked
    }
}
