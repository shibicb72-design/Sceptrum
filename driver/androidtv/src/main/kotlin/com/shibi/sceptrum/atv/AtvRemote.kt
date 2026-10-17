package com.shibi.sceptrum.atv

import com.shibi.sceptrum.atv.proto.remote.RemoteAppLinkLaunchRequest
import com.shibi.sceptrum.atv.proto.remote.RemoteConfigure
import com.shibi.sceptrum.atv.proto.remote.RemoteDeviceInfo
import com.shibi.sceptrum.atv.proto.remote.RemoteDirection
import com.shibi.sceptrum.atv.proto.remote.RemoteEditInfo
import com.shibi.sceptrum.atv.proto.remote.RemoteImeBatchEdit
import com.shibi.sceptrum.atv.proto.remote.RemoteImeObject
import com.shibi.sceptrum.atv.proto.remote.RemoteKeyCode
import com.shibi.sceptrum.atv.proto.remote.RemoteKeyInject
import com.shibi.sceptrum.atv.proto.remote.RemoteMessage
import com.shibi.sceptrum.atv.proto.remote.RemotePingResponse
import com.shibi.sceptrum.atv.proto.remote.RemoteSetActive
import java.io.EOFException
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Feature bits negotiated in `remote_configure` (androidtvremote2 `remote.py`). */
object AtvFeatures {
    const val PING = 1
    const val KEY = 2
    const val IME = 4
    const val VOICE = 8
    const val POWER = 32
    const val VOLUME = 64
    const val APP_LINK = 512

    /** PING, KEY, IME, POWER, VOLUME and APP_LINK: 615. Voice is out of scope. */
    const val REQUESTED = PING or KEY or IME or POWER or VOLUME or APP_LINK
}

/** `RemoteDirection`: a tap, or the two halves of a long press. */
enum class KeyDirection(internal val proto: RemoteDirection) {
    Short(RemoteDirection.SHORT),
    StartLong(RemoteDirection.START_LONG),
    EndLong(RemoteDirection.END_LONG),
}

data class VolumeInfo(val level: Int, val max: Int, val muted: Boolean)

data class TvDeviceInfo(val model: String, val vendor: String, val softwareVersion: String)

/**
 * The focused TV text field. The counters come from the TV's last
 * `remote_ime_batch_edit` and text edits echo them; [value] and [label] are
 * what the TV reported for the field, when it said anything.
 */
data class ImeField(
    val imeCounter: Int,
    val fieldCounter: Int,
    val value: String = "",
    val label: String = "",
)

/** `remote_ime_batch_edit` and `remote_app_link_launch_request` payloads (androidtvremote2 `send_text`, `send_launch_app_command`). */
object AtvEdits {
    private val UriScheme = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

    /**
     * Replaces the whole field with [text], cursor after the last character,
     * echoing [field]'s counters (0 and 0 before the TV has sent any).
     * androidtvremote2 refuses empty text, so callers clear a field with
     * `KEYCODE_DEL` instead.
     */
    fun replaceText(field: ImeField?, text: String): RemoteImeBatchEdit {
        require(text.isNotEmpty()) { "empty text" }
        val cursor = text.length - 1
        return RemoteImeBatchEdit(
            ime_counter = field?.imeCounter ?: 0,
            field_counter = field?.fieldCounter ?: 0,
            edit_info = listOf(RemoteEditInfo(insert = 1, text_field_status = RemoteImeObject(cursor, cursor, text))),
        )
    }

    /** A URL with a scheme is sent as is; a bare package id becomes `market://launch?id=<package>`. */
    fun appLink(appLinkOrPackage: String): String =
        if (UriScheme.containsMatchIn(appLinkOrPackage)) appLinkOrPackage else "market://launch?id=$appLinkOrPackage"
}

enum class DisconnectReason { Unreachable, ConnectionLost, Idle, Timeout }

sealed interface LinkState {
    /** Not started, or stopped. */
    data object Idle : LinkState

    /** TLS connect and handshake in progress. [attempt] is 0 for the first try. */
    data class Connecting(val attempt: Int) : LinkState

    /** `remote_start` received: keys go through. */
    data object Connected : LinkState

    /** Waiting [retryInMs] before attempt number [attempt]. */
    data class Retrying(
        val attempt: Int,
        val retryInMs: Long,
        val reason: DisconnectReason,
        /** The TV hung up within [RemoteConfig.appLinkGraceMs] of an app link. */
        val afterAppLink: Boolean = false,
        /** What ended the last connection, when it was an exception. */
        val error: Throwable? = null,
    ) : LinkState

    /** The TV refused this phone's certificate ("Need to pair again"). No more retries. */
    data object NeedsPairing : LinkState

    /** The TV's certificate is not the pinned one (factory reset or impostor). No more retries. */
    data object IdentityChanged : LinkState
}

data class AtvState(
    val link: LinkState = LinkState.Idle,
    /** `remote_start.started`: true on, false standby, null unknown. */
    val isOn: Boolean? = null,
    val volume: VolumeInfo? = null,
    /** Foreground app package from `remote_ime_key_inject`. */
    val currentApp: String? = null,
    val device: TvDeviceInfo? = null,
    /** Features both sides agreed on; 0 until configured. */
    val features: Int = 0,
    val imeField: ImeField? = null,
)

data class RemoteConfig(
    /** `remote_configure.device_info.package_name`; androidtvremote2 sends "atvremote". */
    val packageName: String = "atvremote",
    val appVersion: String = "1.0.0",
    val requestedFeatures: Int = AtvFeatures.REQUESTED,
    val connectTimeoutMs: Int = 5_000,
    /** From TLS connected to `remote_start`. */
    val readyTimeoutMs: Long = 10_000,
    /** androidtvremote2: close when nothing is sent or received for 16 s (the TV pings every 5 s). */
    val idleTimeoutMs: Long = 16_000,
    val backoffInitialMs: Long = 100,
    val backoffMaxMs: Long = 30_000,
    /**
     * With TLS 1.3 the client's handshake completes before the TV checks its
     * certificate, and the TV's alert can be lost to a TCP reset. A TV that
     * closes this many connections in a row before sending anything (a paired
     * phone always gets `remote_configure` first) is treated as refusing this
     * phone.
     */
    val silentClosesMeanUnpaired: Int = 3,
    /**
     * After an app link, a hang-up within this window is the TV failing to
     * open the link, not a network loss or a refused phone.
     */
    val appLinkGraceMs: Long = 15_000,
)

/** androidtvremote2 `_async_reconnect`: 0.1 s, doubling, capped at 30 s, reset on success. */
class Backoff(private val initialMs: Long = 100, private val maxMs: Long = 30_000) {
    private var nextMs = initialMs

    fun next(): Long {
        val delay = nextMs
        nextMs = minOf(nextMs * 2, maxMs)
        return delay
    }

    fun reset() {
        nextMs = initialMs
    }
}

/**
 * The control session on port 6466, kept alive with reconnects. The TV leads
 * the handshake (`remote_configure`, `remote_set_active`, `remote_start`) and
 * pings about every 5 s; this side answers, tracks power, volume, current app
 * and IME counters, and injects keys.
 *
 * Commands are not queued while disconnected: they return false.
 */
class AtvRemoteClient(
    private val host: String,
    private val identity: ClientIdentityProvider,
    private val pinnedSha256: ByteArray?,
    private val scope: CoroutineScope,
    private val config: RemoteConfig = RemoteConfig(),
    private val port: Int = DEFAULT_PORT,
) {
    private val _state = MutableStateFlow(AtvState())
    val state: StateFlow<AtvState> = _state.asStateFlow()

    private val kick = Channel<Unit>(Channel.CONFLATED)
    private var loop: Job? = null

    @Volatile
    private var current: Connection? = null

    /**
     * [System.nanoTime] of the last app link sent, or 0 before the first one.
     * It is set before the send so a hang-up can never be seen before the timestamp.
     */
    @Volatile
    private var lastAppLinkAt = 0L

    /** Starts connecting, or restarts after [stop] or a terminal state. No-op while running. */
    @Synchronized
    fun start() {
        if (loop?.isActive == true) return
        loop = scope.launch {
            try {
                runLoop()
            } catch (e: CancellationException) {
                _state.update { it.disconnected().copy(link = LinkState.Idle) }
                throw e
            }
        }
    }

    @Synchronized
    fun stop() {
        loop?.cancel()
        loop = null
        current?.close()
        current = null
        _state.update { it.disconnected().copy(link = LinkState.Idle) }
    }

    /**
     * Skip the backoff wait and try now (Retry button, network came back). With
     * [dropCurrent], a live connection is closed first, for when the phone's
     * network changed under it.
     */
    fun retryNow(dropCurrent: Boolean = false) {
        if (loop?.isActive != true) {
            start()
            return
        }
        if (dropCurrent) current?.close()
        kick.trySend(Unit)
    }

    fun sendKey(keyCode: Int, direction: KeyDirection = KeyDirection.Short): Boolean {
        val key = RemoteKeyCode.fromValue(keyCode) ?: return false
        return sendWhenReady(RemoteMessage(remote_key_inject = RemoteKeyInject(key, direction.proto)))
    }

    /** A URL is sent as is; a bare package id becomes `market://launch?id=<package>`. */
    fun launchApp(appLinkOrPackage: String): Boolean {
        val previous = lastAppLinkAt
        lastAppLinkAt = System.nanoTime()
        val sent = sendWhenReady(
            RemoteMessage(remote_app_link_launch_request = RemoteAppLinkLaunchRequest(AtvEdits.appLink(appLinkOrPackage))),
        )
        if (!sent) lastAppLinkAt = previous
        return sent
    }

    /**
     * Replaces the focused TV text field with [text] (androidtvremote2 `send_text`):
     * one batch edit echoing the TV's counters, cursor at the end. Empty text is
     * refused (false).
     */
    fun sendText(text: String): Boolean {
        if (text.isEmpty()) return false
        val field = _state.value.imeField
        val sent = sendWhenReady(RemoteMessage(remote_ime_batch_edit = AtvEdits.replaceText(field, text)))
        if (sent && field != null) _state.update { it.copy(imeField = it.imeField?.copy(value = text)) }
        return sent
    }

    private fun sendWhenReady(message: RemoteMessage): Boolean {
        val connection = current ?: return false
        return connection.ready && connection.send(message)
    }

    private suspend fun runLoop() {
        val backoff = Backoff(config.backoffInitialMs, config.backoffMaxMs)
        var attempt = 0
        var silentCloses = 0
        while (true) {
            _state.update { it.copy(link = LinkState.Connecting(attempt)) }
            val ending = connectOnce()
            _state.update { it.disconnected() }
            var afterAppLink = false
            var lastError: Throwable? = null
            val reason = when (ending) {
                Ending.NeedsPairing -> {
                    _state.update { it.copy(link = LinkState.NeedsPairing) }
                    return
                }
                Ending.PinMismatch -> {
                    _state.update { it.copy(link = LinkState.IdentityChanged) }
                    return
                }
                is Ending.Retry -> {
                    if (ending.wasReady) {
                        backoff.reset()
                        attempt = 0
                    }
                    lastError = ending.error
                    afterAppLink = lastAppLinkAt != 0L &&
                        System.nanoTime() - lastAppLinkAt < config.appLinkGraceMs * NANOS_PER_MS
                    silentCloses = when {
                        afterAppLink -> 0
                        ending.closedSilently -> silentCloses + 1
                        else -> 0
                    }
                    if (silentCloses >= config.silentClosesMeanUnpaired) {
                        _state.update { it.copy(link = LinkState.NeedsPairing) }
                        return
                    }
                    ending.reason
                }
            }
            attempt++
            val wait = backoff.next()
            while (kick.tryReceive().isSuccess) {
                // Drop retry requests made while still connected.
            }
            _state.update { it.copy(link = LinkState.Retrying(attempt, wait, reason, afterAppLink, lastError)) }
            withTimeoutOrNull(wait) { kick.receive() }
        }
    }

    private suspend fun connectOnce(): Ending = withContext(Dispatchers.IO) {
        val socket = try {
            AtvTls.connect(
                host = host,
                port = port,
                context = AtvTls.sslContext(identity.identity(), CapturingTrustManager(pinnedSha256)),
                connectTimeoutMs = config.connectTimeoutMs,
                readTimeoutMs = config.connectTimeoutMs,
            ).apply { soTimeout = 0 }
        } catch (e: Exception) {
            // TCP connected but the TLS handshake broke off: counts as a silent close.
            val ending = classify(e, wasReady = false)
            val tcpConnected = e !is UnreachableException
            return@withContext if (ending is Ending.Retry && tcpConnected) ending.copy(closedSilently = true) else ending
        }
        val connection = Connection(socket)
        current = connection
        try {
            connection.run()
        } finally {
            current = null
            connection.close()
        }
    }

    private sealed interface Ending {
        data object NeedsPairing : Ending
        data object PinMismatch : Ending
        data class Retry(
            val reason: DisconnectReason,
            val wasReady: Boolean,
            /** TLS was up but the TV closed before sending a single message. */
            val closedSilently: Boolean = false,
            val error: Throwable? = null,
        ) : Ending
    }

    private fun classify(error: Throwable, wasReady: Boolean): Ending {
        val chain = generateSequence(error) { it.cause }.toList()
        if (chain.any { it is PinMismatchException }) return Ending.PinMismatch
        if (chain.any { it is UnreachableException }) return Ending.Retry(DisconnectReason.Unreachable, wasReady, error = error)
        val network = chain.any { it is SocketException || it is EOFException || it is SocketTimeoutException }
        // androidtvremote2 maps an SSL error before the session is up to InvalidAuth("Need to pair again").
        if (!wasReady && !network && chain.any { it is SSLException }) return Ending.NeedsPairing
        return Ending.Retry(DisconnectReason.ConnectionLost, wasReady, error = error)
    }

    private fun AtvState.disconnected() = copy(isOn = null, currentApp = null, imeField = null)

    /** One TLS connection: a blocking reader, a writer draining the outbox, and the watchdog. */
    private inner class Connection(private val socket: SSLSocket) {
        private val outbox = Channel<ByteArray>(OUTBOX_CAPACITY)
        private val ended = CompletableDeferred<Ending>()
        private val openedAt = System.nanoTime()

        @Volatile
        private var lastActivity = System.nanoTime()

        @Volatile
        var ready = false
            private set

        @Volatile
        private var receivedAny = false

        @Volatile
        private var negotiated = config.requestedFeatures

        fun send(message: RemoteMessage): Boolean = !closed && outbox.trySend(message.encode()).isSuccess

        @Volatile
        private var closed = false

        /** Safe from any thread; nothing more is accepted for sending. */
        fun close() {
            closed = true
            runCatching { socket.close() }
        }

        suspend fun run(): Ending = coroutineScope {
            launch { readLoop() }
            launch {
                try {
                    for (bytes in outbox) {
                        Framing.write(socket.outputStream, bytes)
                        lastActivity = System.nanoTime()
                    }
                } catch (e: IOException) {
                    ended.complete(classify(e, ready))
                }
            }
            launch { watchdog() }
            try {
                ended.await()
            } finally {
                close()
                outbox.close()
                coroutineContext.cancelChildren()
            }
        }

        private fun readLoop() {
            try {
                val reader = FrameReader(socket.inputStream)
                while (true) {
                    val frame = reader.read() ?: break
                    lastActivity = System.nanoTime()
                    receivedAny = true
                    handle(frame)
                }
                ended.complete(Ending.Retry(DisconnectReason.ConnectionLost, ready, closedSilently = !receivedAny))
            } catch (e: Exception) {
                val ending = classify(e, ready)
                ended.complete(
                    if (ending is Ending.Retry && !receivedAny) ending.copy(closedSilently = true) else ending,
                )
            }
        }

        private suspend fun watchdog() {
            val tick = (config.idleTimeoutMs / 8).coerceIn(10, 1_000)
            while (true) {
                delay(tick)
                val now = System.nanoTime()
                if (!ready && now - openedAt > config.readyTimeoutMs * NANOS_PER_MS) {
                    ended.complete(Ending.Retry(DisconnectReason.Timeout, wasReady = false))
                } else if (now - lastActivity > config.idleTimeoutMs * NANOS_PER_MS) {
                    ended.complete(Ending.Retry(DisconnectReason.Idle, ready))
                }
            }
        }

        private fun handle(raw: ByteArray) {
            val message = try {
                RemoteMessage.ADAPTER.decode(raw)
            } catch (_: IOException) {
                return
            }
            message.remote_configure?.let { configure ->
                negotiated = config.requestedFeatures and configure.code1
                val info = configure.device_info
                _state.update {
                    it.copy(
                        features = negotiated,
                        device = info?.let { d -> TvDeviceInfo(d.model, d.vendor, d.app_version) },
                    )
                }
                send(
                    RemoteMessage(
                        remote_configure = RemoteConfigure(
                            code1 = negotiated,
                            device_info = RemoteDeviceInfo(
                                unknown1 = 1,
                                unknown2 = "1",
                                package_name = config.packageName,
                                app_version = config.appVersion,
                            ),
                        ),
                    ),
                )
            }
            if (message.remote_set_active != null) {
                send(RemoteMessage(remote_set_active = RemoteSetActive(active = negotiated)))
            }
            message.remote_ping_request?.let { ping ->
                send(RemoteMessage(remote_ping_response = RemotePingResponse(val1 = ping.val1)))
            }
            message.remote_start?.let { start ->
                ready = true
                _state.update { it.copy(link = LinkState.Connected, isOn = start.started) }
            }
            message.remote_set_volume_level?.let { v ->
                _state.update { it.copy(volume = VolumeInfo(v.volume_level, v.volume_max, v.volume_muted)) }
            }
            message.remote_ime_key_inject?.app_info?.let { app ->
                _state.update { it.copy(currentApp = app.app_package.ifEmpty { null }) }
            }
            message.remote_ime_batch_edit?.let { edit ->
                val value = edit.edit_info.lastOrNull()?.text_field_status?.value_.orEmpty()
                _state.update {
                    it.copy(imeField = ImeField(edit.ime_counter, edit.field_counter, value, it.imeField?.label.orEmpty()))
                }
            }
            // A field asked for the keyboard: keep the counters, take its text and label.
            message.remote_ime_show_request?.remote_text_field_status?.let { status ->
                _state.update {
                    val field = it.imeField ?: ImeField(0, 0)
                    it.copy(imeField = field.copy(value = status.value_, label = status.label))
                }
            }
        }
    }

    companion object {
        const val DEFAULT_PORT = 6466
        private const val OUTBOX_CAPACITY = 64
        private const val NANOS_PER_MS = 1_000_000L
    }
}
