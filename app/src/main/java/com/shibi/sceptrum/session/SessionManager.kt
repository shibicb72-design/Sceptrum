package com.shibi.sceptrum.session

import com.shibi.sceptrum.atv.AtvRemoteClient
import com.shibi.sceptrum.atv.AtvState
import com.shibi.sceptrum.atv.ClientIdentityProvider
import com.shibi.sceptrum.atv.ImeField
import com.shibi.sceptrum.atv.KeyDirection
import com.shibi.sceptrum.atv.LinkState
import com.shibi.sceptrum.atv.RemoteConfig
import com.shibi.sceptrum.atv.VolumeInfo
import com.shibi.sceptrum.diagnostics.ErrorLog
import com.shibi.sceptrum.domain.AppShortcuts
import com.shibi.sceptrum.domain.ConnectionStatus
import com.shibi.sceptrum.store.PairedDevice
import kotlinx.coroutines.CancellationException
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
    val deviceId: String? = null,
    /** Wake-on-LAN MAC from the TV certificate, null when unknown. */
    val mac: String? = null,
    /** Vendor from the TV's last `remote_configure`, for standby tips. */
    val vendor: String? = null,
    /** Foreground app package the TV last reported. */
    val currentApp: String? = null,
    /** The focused TV text field, null until the TV reports one. */
    val textField: ImeField? = null,
    /** The device's "Show channel buttons" setting; on when there is no device. */
    val showChannelButtons: Boolean = true,
)

/** The remote's view of the active device. Faked in view-model tests. */
interface RemoteSession {
    val state: StateFlow<SessionState>

    /** False when not connected: nothing is queued. */
    fun send(keyCode: Int, direction: KeyDirection = KeyDirection.Short): Boolean

    /** Replaces the focused TV text field; false when not connected or [text] is empty. */
    fun sendText(text: String): Boolean

    /** `remote_app_link_launch_request`; false when not connected. */
    fun launchApp(link: String): Boolean

    fun retry()
}

/** Hears each foreground app a TV reports; the learned-apps store in the app, a list in tests. */
fun interface ForegroundAppListener {
    /**
     * Called when [deviceId] reports a foreground app other than its last one;
     * must not block. A very fast switch (A, B, A) can be conflated and B missed.
     */
    fun onForegroundApp(deviceId: String, appPackage: String)
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
    private val errors: ErrorLog? = null,
    /** Learns the apps the TV opens (docs/apps-list-options.md, Option A). */
    private val foregroundApps: ForegroundAppListener? = null,
) : RemoteSession {
    private val current = MutableStateFlow<Active?>(null)

    /** The last app link passed to [launchApp], for the "hung up right after" message. */
    @Volatile
    private var lastLink: String? = null

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
            activeDevice.collect { onActiveDevice(it) }
        }
        watchLinkErrors()
        if (foregroundApps != null) watchForegroundApp(foregroundApps)
    }

    /**
     * Passes each foreground app change to [listener]. The last app is kept per
     * device across disconnects, so a reconnect (or the hang-up after an app
     * link) that reports the same app again does not count it twice.
     */
    private fun watchForegroundApp(listener: ForegroundAppListener) {
        scope.launch {
            val lastApp = HashMap<String, String>()
            state
                .map { s -> s.deviceId?.let { id -> s.currentApp?.let { id to it } } }
                .distinctUntilChanged()
                .collect { seen ->
                    val (deviceId, app) = seen ?: return@collect
                    if (lastApp.put(deviceId, app) == app) return@collect
                    try {
                        listener.onForegroundApp(deviceId, app)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        errors?.record("Learned apps", "Could not learn the app the TV opened: $app", e, tvLine())
                    }
                }
        }
    }

    /**
     * Records the link transitions worth a diagnostic report: a lost or refused
     * connection, a failed first connect, a hang-up right after an app link, and
     * the terminal pairing states. Later retry cycles are ignored so the log
     * never floods.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun watchLinkErrors() {
        scope.launch {
            var previous: LinkState? = null
            current
                .flatMapLatest { active ->
                    if (active == null) {
                        flowOf<Pair<PairedDevice, LinkState>?>(null)
                    } else {
                        active.client.state.map { active.device to it.link }
                    }
                }
                .collect { value ->
                    if (value == null) return@collect
                    val (device, link) = value
                    val before = previous
                    if (link != before) {
                        when {
                            link is LinkState.Retrying && before == LinkState.Connected ->
                                if (link.afterAppLink) {
                                    errors?.record(
                                        "Launch app",
                                        "The TV hung up right after opening ${lastLink?.let(AppShortcuts::logLabel) ?: "an app"}; reconnecting",
                                        link.error,
                                        tvLine(device, link),
                                    )
                                } else {
                                    errors?.record(
                                        "Connection",
                                        "Connection lost (${link.reason})",
                                        link.error,
                                        tvLine(device, link),
                                    )
                                }
                            link is LinkState.Retrying && before is LinkState.Connecting && before.attempt == 0 ->
                                errors?.record(
                                    "Connection",
                                    "Could not connect (${link.reason})",
                                    link.error,
                                    tvLine(device, link),
                                )
                            link is LinkState.NeedsPairing ->
                                errors?.record(
                                    "Connection",
                                    "The TV no longer accepts this phone; pair again",
                                    null,
                                    tvLine(device, link),
                                )
                            link is LinkState.IdentityChanged ->
                                errors?.record(
                                    "Connection",
                                    "The TV's certificate changed since pairing",
                                    null,
                                    tvLine(device, link),
                                )
                        }
                        previous = link
                    }
                }
        }
    }

    /** The device name, address and status, e.g. "Projector (127.0.0.1:6466) Connected". */
    private fun tvLine(device: PairedDevice, link: LinkState): String =
        "${device.name} (${device.host}:${device.port}) ${ConnectionStatus.from(link)}"

    /** [tvLine] for the live device, or "" when there is none. */
    private fun tvLine(): String {
        val live = current.value ?: return ""
        return tvLine(live.device, live.client.state.value.link)
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

    override fun sendText(text: String): Boolean = current.value?.client?.sendText(text) ?: false

    override fun launchApp(link: String): Boolean {
        lastLink = link
        val sent = current.value?.client?.launchApp(link) ?: false
        if (!sent) errors?.record("Launch app", "Not sent, the TV is not connected: ${AppShortcuts.logLabel(link)}", tv = tvLine())
        return sent
    }

    override fun retry() {
        current.value?.client?.retryNow()
    }

    fun onNetworkChanged(dropCurrent: Boolean) {
        current.value?.client?.retryNow(dropCurrent)
    }

    /** A rename, a new app grid or a setting keeps the live client; a new address, pin or device swaps it. */
    @Synchronized
    private fun onActiveDevice(device: PairedDevice?) {
        val live = current.value
        if (live != null && device != null && live.device.connectionKey() == device.connectionKey()) {
            if (live.device != device) current.value = Active(device, live.client)
            return
        }
        if (live == null && device == null) return
        swap(device)
    }

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
        deviceId = device.id,
        mac = device.mac.ifEmpty { null },
        vendor = this.device?.vendor?.ifEmpty { null },
        currentApp = currentApp,
        textField = imeField,
        showChannelButtons = !device.hide_channel_buttons,
    )

    companion object {
        /** Keep the session this long after the app goes to the background. */
        const val GRACE_MS = 60_000L
    }
}
