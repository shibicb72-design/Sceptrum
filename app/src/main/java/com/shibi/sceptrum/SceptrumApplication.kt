package com.shibi.sceptrum

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.shibi.sceptrum.atv.RemoteConfig
import com.shibi.sceptrum.data.DeviceRepository
import com.shibi.sceptrum.data.StoredClientIdentityProvider
import com.shibi.sceptrum.devices.DevicesViewModel
import com.shibi.sceptrum.discovery.NsdTvDiscovery
import com.shibi.sceptrum.discovery.TvDiscovery
import com.shibi.sceptrum.domain.LanAccess
import com.shibi.sceptrum.domain.LanAccessState
import com.shibi.sceptrum.pairing.AtvPairingGateway
import com.shibi.sceptrum.pairing.PairingGateway
import com.shibi.sceptrum.pairing.PairingViewModel
import com.shibi.sceptrum.power.ConnectivityLanAddress
import com.shibi.sceptrum.power.UdpWaker
import com.shibi.sceptrum.power.Waker
import com.shibi.sceptrum.remote.AppsViewModel
import com.shibi.sceptrum.remote.KeyboardViewModel
import com.shibi.sceptrum.remote.RemoteViewModel
import com.shibi.sceptrum.session.ConnectivityNetworkEvents
import com.shibi.sceptrum.session.SessionManager
import com.shibi.sceptrum.settings.RemotePreferences
import com.shibi.sceptrum.settings.RemoteSettingsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class SceptrumApplication : Application() {
    val graph: AppGraph by lazy { AppGraph(this) }
}

/**
 * Manual constructor DI (plan §9.1). Everything is lazy so screens and tests
 * that never touch the network or Keystore never create them.
 */
class AppGraph(context: Context) {
    private val app = context.applicationContext

    val deviceRepository: DeviceRepository by lazy { DeviceRepository.create(app) }
    val discovery: TvDiscovery by lazy { NsdTvDiscovery(app) }
    val clientIdentity by lazy { StoredClientIdentityProvider(deviceRepository) }

    /** Shown on the TV while pairing, e.g. "Sceptrum (SM-F966B)". */
    val clientName: String = "Sceptrum (${Build.MODEL})"

    val pairingGateway: PairingGateway by lazy { AtvPairingGateway(clientIdentity, clientName) }

    /** Lives as long as the process; sessions close themselves after the grace period. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val sessionManager: SessionManager by lazy {
        SessionManager(
            activeDevice = deviceRepository.activeDevice,
            identity = clientIdentity,
            scope = appScope,
            // androidtvremote2 sends "atvremote"; plan section 7.1 uses our own package name.
            config = RemoteConfig(packageName = app.packageName, appVersion = versionName()),
            network = ConnectivityNetworkEvents(app),
        )
    }

    val remotePreferences: RemotePreferences by lazy { RemotePreferences(app) }

    /** Wake-on-LAN to the subnet broadcast of the phone's current network. */
    val waker: Waker by lazy { UdpWaker(ConnectivityLanAddress(app)) }

    val viewModelFactory: ViewModelProvider.Factory = viewModelFactory {
        initializer { DevicesViewModel(deviceRepository, discovery) }
        initializer { PairingViewModel(pairingGateway, deviceRepository, appVersion = versionName()) }
        initializer { RemoteViewModel(sessionManager, waker) }
        initializer { KeyboardViewModel(sessionManager, remotePreferences.keyPressTyping) }
        initializer { AppsViewModel(deviceRepository, sessionManager) }
        initializer { RemoteSettingsViewModel(remotePreferences) }
    }

    /** Plan §8: LAN access as Android sees it; always allowed while targetSdk is 36. */
    fun lanAccess(): LanAccessState = LanAccess.classify(
        sdkInt = Build.VERSION.SDK_INT,
        targetSdk = app.applicationInfo.targetSdkVersion,
        granted = false,
    )

    fun versionName(): String =
        runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: "0"
}

val Context.appGraph: AppGraph get() = (applicationContext as SceptrumApplication).graph
