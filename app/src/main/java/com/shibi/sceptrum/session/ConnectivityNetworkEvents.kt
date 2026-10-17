package com.shibi.sceptrum.session

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.Handler
import android.os.Looper

/**
 * Default-network callbacks (plan §7.2). The first `onAvailable` after
 * registering only reports the current network and is ignored. A different
 * network, or new addresses on the same one, means sockets bound to the old
 * address are dead: reconnect at once instead of waiting for the idle watchdog.
 * Network coming back after a loss skips the remaining backoff.
 */
class ConnectivityNetworkEvents(context: Context) : NetworkEvents {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var callback: ConnectivityManager.NetworkCallback? = null

    override fun start(onChange: (dropCurrent: Boolean) -> Unit) {
        if (callback != null) return
        val cb = object : ConnectivityManager.NetworkCallback() {
            private var network: Network? = null
            private var addresses: List<String>? = null
            private var lost = false

            override fun onAvailable(network: Network) {
                val previous = this.network
                this.network = network
                when {
                    previous != null && previous != network -> onChange(true)
                    previous == null && lost -> onChange(false)
                }
                lost = false
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                val now = linkProperties.linkAddresses.map { it.toString() }.sorted()
                val before = addresses
                addresses = now
                if (before != null && before != now) onChange(true)
            }

            override fun onLost(network: Network) {
                if (this.network == network) {
                    this.network = null
                    addresses = null
                    lost = true
                }
            }
        }
        callback = cb
        runCatching { connectivity.registerDefaultNetworkCallback(cb, main) }.onFailure { callback = null }
    }

    override fun stop() {
        callback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        callback = null
    }
}
