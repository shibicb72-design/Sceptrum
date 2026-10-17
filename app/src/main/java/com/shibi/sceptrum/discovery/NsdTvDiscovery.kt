package com.shibi.sceptrum.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import com.shibi.sceptrum.domain.DiscoveredTv
import com.shibi.sceptrum.domain.DiscoveryState
import java.net.Inet4Address
import java.net.InetAddress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Finds TVs on the LAN. Scans run for a short window; found devices are kept. */
interface TvDiscovery {
    val state: StateFlow<DiscoveryState>

    fun scan()

    fun stop()
}

/**
 * mDNS discovery of `_androidtvremote2._tcp` (plan §4.1-4.2).
 * - API 34+: one [NsdManager.ServiceInfoCallback] per service, which also
 *   reports address changes and loss.
 * - API 31-33: `resolveService` one at a time, since a parallel resolve fails
 *   with FAILURE_ALREADY_ACTIVE.
 *
 * A Wi-Fi multicast lock is held only for the scan window; some OEM builds
 * drop mDNS replies without it. Every callback is handled on the main thread.
 */
class NsdTvDiscovery(
    context: Context,
    private val windowMs: Long = SCAN_WINDOW_MS,
) : TvDiscovery {
    private val app = context.applicationContext
    private val nsd: NsdManager = app.getSystemService(NsdManager::class.java)
    private val wifi: WifiManager = app.getSystemService(WifiManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(DiscoveryState())
    override val state: StateFlow<DiscoveryState> = _state.asStateFlow()

    private var listener: NsdManager.DiscoveryListener? = null
    private var lock: WifiManager.MulticastLock? = null
    private val infoCallbacks = mutableMapOf<String, NsdManager.ServiceInfoCallback>()
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private val endWindow = Runnable { stop() }

    override fun scan(): Unit = onMain {
        if (listener != null) return@onMain
        lock = wifi.createMulticastLock(LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire()
        }
        val discovery = Listener()
        listener = discovery
        _state.update { it.copy(scanning = true, failed = false) }
        runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery) }
            .onFailure { finishScan(failed = true) }
        main.postDelayed(endWindow, windowMs)
    }

    override fun stop(): Unit = onMain {
        main.removeCallbacks(endWindow)
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        finishScan(failed = false)
    }

    private fun finishScan(failed: Boolean) {
        listener = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            infoCallbacks.values.forEach { runCatching { nsd.unregisterServiceInfoCallback(it) } }
        }
        infoCallbacks.clear()
        resolveQueue.clear()
        lock?.let { if (it.isHeld) it.release() }
        lock = null
        _state.update { it.copy(scanning = false, failed = failed || it.failed) }
    }

    private fun found(info: NsdServiceInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            watch(info)
        } else {
            resolveQueue.addLast(info)
            resolveNext()
        }
    }

    private fun lost(serviceName: String) {
        _state.update { s -> s.copy(devices = s.devices.filterNot { it.serviceName == serviceName }) }
    }

    private fun publish(serviceName: String, addresses: List<InetAddress>, port: Int) {
        val address = addresses.firstOrNull { it is Inet4Address } ?: addresses.firstOrNull() ?: return
        val tv = DiscoveredTv(serviceName = serviceName, host = address.hostAddress ?: return, port = port)
        _state.update { s -> s.copy(devices = s.devices.filterNot { it.serviceName == serviceName } + tv) }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun watch(info: NsdServiceInfo) {
        val name = info.serviceName
        if (name in infoCallbacks) return
        val callback = object : NsdManager.ServiceInfoCallback {
            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) = Unit

            override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                publish(name, serviceInfo.hostAddresses, serviceInfo.port)
            }

            override fun onServiceLost() = lost(name)

            override fun onServiceInfoCallbackUnregistered() = Unit
        }
        infoCallbacks[name] = callback
        runCatching { nsd.registerServiceInfoCallback(info, app.mainExecutor, callback) }
            .onFailure { infoCallbacks.remove(name) }
    }

    /** API 31-33 only; replaced by [watch] from API 34. */
    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (resolving || listener == null) return
        val info = resolveQueue.removeFirstOrNull() ?: return
        resolving = true
        nsd.resolveService(
            info,
            object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int): Unit = onMain {
                    resolving = false
                    resolveNext()
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo): Unit = onMain {
                    resolving = false
                    serviceInfo.host?.let { publish(serviceInfo.serviceName, listOf(it), serviceInfo.port) }
                    resolveNext()
                }
            },
        )
    }

    private inner class Listener : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) = Unit

        override fun onDiscoveryStopped(serviceType: String) = Unit

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int): Unit = onMain {
            if (listener === this) finishScan(failed = true)
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

        override fun onServiceFound(serviceInfo: NsdServiceInfo): Unit = onMain {
            if (listener === this) found(serviceInfo)
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo): Unit = onMain {
            // From API 34 the per-service callback reports loss with the address.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) lost(serviceInfo.serviceName)
        }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    companion object {
        const val SERVICE_TYPE = "_androidtvremote2._tcp"
        const val SCAN_WINDOW_MS = 8_000L
        private const val LOCK_TAG = "sceptrum-mdns"
    }
}
