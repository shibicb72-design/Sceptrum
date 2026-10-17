package com.shibi.sceptrum.power

import android.content.Context
import android.net.ConnectivityManager
import java.net.Inet4Address

/** The default network's IPv4 address and prefix, read from LinkProperties (no location permission). */
class ConnectivityLanAddress(context: Context) : LanAddressSource {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    override fun current(): LanAddress? {
        val network = connectivity.activeNetwork ?: return null
        val link = connectivity.getLinkProperties(network) ?: return null
        val v4 = link.linkAddresses.firstOrNull { it.address is Inet4Address } ?: return null
        val host = v4.address.hostAddress ?: return null
        return LanAddress(host, v4.prefixLength)
    }
}
