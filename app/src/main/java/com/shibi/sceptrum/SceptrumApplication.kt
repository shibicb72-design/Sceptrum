package com.shibi.sceptrum

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.shibi.sceptrum.data.DeviceRepository
import com.shibi.sceptrum.data.KeystoreClientIdentityProvider
import com.shibi.sceptrum.devices.DevicesViewModel
import com.shibi.sceptrum.discovery.NsdTvDiscovery
import com.shibi.sceptrum.discovery.TvDiscovery
import com.shibi.sceptrum.pairing.AtvPairingGateway
import com.shibi.sceptrum.pairing.PairingGateway
import com.shibi.sceptrum.pairing.PairingViewModel

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
    val clientIdentity by lazy { KeystoreClientIdentityProvider() }

    /** Shown on the TV while pairing, e.g. "Sceptrum (SM-F966B)". */
    val clientName: String = "Sceptrum (${Build.MODEL})"

    val pairingGateway: PairingGateway by lazy { AtvPairingGateway(clientIdentity, clientName) }

    val viewModelFactory: ViewModelProvider.Factory = viewModelFactory {
        initializer { DevicesViewModel(deviceRepository, discovery) }
        initializer { PairingViewModel(pairingGateway, deviceRepository) }
    }
}

val Context.appGraph: AppGraph get() = (applicationContext as SceptrumApplication).graph
