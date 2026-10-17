package com.shibi.sceptrum.session

import com.shibi.sceptrum.atv.AtvRemoteClient
import com.shibi.sceptrum.atv.AtvState
import com.shibi.sceptrum.atv.ClientIdentityProvider
import com.shibi.sceptrum.atv.KeyDirection
import com.shibi.sceptrum.atv.RemoteConfig
import com.shibi.sceptrum.atv.VolumeInfo
import com.shibi.sceptrum.domain.ConnectionStatus
import com.shibi.sceptrum.store.PairedDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the remote screen needs from the session. */
data class SessionState(
    val deviceName: String? = null,
    val status: ConnectionStatus = ConnectionStatus.NoDevice,
    val volume: VolumeInfo? = null,
    val isOn: Boolean? = null,
)

/** The remote's view of the active device. Faked in view-model tests. */
interface RemoteSession {
    val state: StateFlow<SessionState>

    /** False when not connected: nothing is queued. */
    fun send(keyCode: Int, direction: KeyDirection = KeyDirection.Short): Boolean

    fun retry()
}

/** Network change notifications; Android's ConnectivityManager in the app, a fake in tests. */
interface NetworkEvents {
    /** [onChange] gets true when the phone moved to another network or address. */
    fun start(onChange: (dropCurrent: Boolean) -> Unit)

    fun stop()
}

/**
 * Owns the control session for the active device (plan §7.2). App-scoped, no
 * service: the remote screen [acquire]s it on start and [release]s it on
 * stop; the connection then stays up for [graceMs] so switching apps and back
 * is instant. A network change triggers an immediate reconnect, and a
 * different active device (or a new pin after re-pairing) swaps the client.
 */
class SessionManager(
    activeDevice: Flow<PairedDevice?>,
    private val identity: ClientIdentityProvider,
    private val scope: CoroutineScope,
    private val config: RemoteConfig,
    private val network: NetworkEvents? = null,
    private val graceMs: Long = GRACE_MS,
) : RemoteSession {
    private val current = MutableStateFlow<Active?>(null)
    private var users = 0
    private var graceJob: Job? = null

    private class Active(val device: PairedDevice, val client: AtvRemoteClient)

    @OptIn(ExperimentalCoroutinesApi::class)
    override val state: StateFlow<SessionState> = current
        .flatMapLatest { active ->
            if (active == null) {
                flowOf(SessionState())
            } else {
                active.client.state.map { it.toSession(active.device) }
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, SessionState())

    /** The remote screen is in the foreground: hardware volume keys go to the TV. */
    val inForeground: Boolean
        @Synchronized get() = users > 0

    init {
        scope.launch {
            activeDevice
                .distinctUntilChanged { a, b -> a?.connectionKey() == b?.connectionKey() }
                .collect { swap(it) }
        }
    }

    @Synchronized
    fun acquire() {
        users++
        graceJob?.cancel()
        graceJob = null
        if (users == 1) network?.start(::onNetworkChanged)
        current.value?.client?.start()
    }

    @Synchronized
    fun release() {
        if (users == 0) return
        users--
        if (users > 0) return
        graceJob = scope.launch {
            delay(graceMs)
            synchronized(this@SessionManager) {
                if (users == 0) {
                    network?.stop()
                    current.value?.client?.stop()
                }
            }
        }
    }

    override fun send(keyCode: Int, direction: KeyDirection): Boolean =
        current.value?.client?.sendKey(keyCode, direction) ?: false

    override fun retry() {
        current.value?.client?.retryNow()
    }

    fun onNetworkChanged(dropCurrent: Boolean) {
        current.value?.client?.retryNow(dropCurrent)
    }

    @Synchronized
    private fun swap(device: PairedDevice?) {
        current.value?.client?.stop()
        val next = device?.let { Active(it, client(it)) }
        current.value = next
        if (users > 0 || graceJob?.isActive == true) next?.client?.start()
    }

    private fun client(device: PairedDevice) = AtvRemoteClient(
        host = device.host,
        identity = identity,
        pinnedSha256 = device.server_cert_sha256.toByteArray().takeIf { it.isNotEmpty() },
        scope = scope,
        config = config,
        port = device.port.takeIf { it > 0 } ?: AtvRemoteClient.DEFAULT_PORT,
    )

    private fun PairedDevice.connectionKey() = listOf(id, host, port, server_cert_sha256)

    private fun AtvState.toSession(device: PairedDevice) = SessionState(
        deviceName = device.name,
        status = ConnectionStatus.from(link),
        volume = volume,
        isOn = isOn,
    )

    companion object {
        /** Keep the session this long after the app goes to the background. */
        const val GRACE_MS = 60_000L
    }
}
