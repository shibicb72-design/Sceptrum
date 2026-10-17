package com.shibi.sceptrum.testing.atv

import com.shibi.sceptrum.atv.AtvTls
import com.shibi.sceptrum.atv.ClientIdentity
import com.shibi.sceptrum.atv.FrameReader
import com.shibi.sceptrum.atv.Framing
import com.shibi.sceptrum.atv.PairingSecret
import com.shibi.sceptrum.atv.PairingStep
import com.shibi.sceptrum.atv.proto.polo.Configuration
import com.shibi.sceptrum.atv.proto.polo.ConfigurationAck
import com.shibi.sceptrum.atv.proto.polo.Options
import com.shibi.sceptrum.atv.proto.polo.OuterMessage
import com.shibi.sceptrum.atv.proto.polo.PairingRequest
import com.shibi.sceptrum.atv.proto.polo.PairingRequestAck
import com.shibi.sceptrum.atv.proto.polo.SecretAck
import com.shibi.sceptrum.atv.proto.remote.RemoteAppInfo
import com.shibi.sceptrum.atv.proto.remote.RemoteConfigure
import com.shibi.sceptrum.atv.proto.remote.RemoteDeviceInfo
import com.shibi.sceptrum.atv.proto.remote.RemoteDirection
import com.shibi.sceptrum.atv.proto.remote.RemoteEditInfo
import com.shibi.sceptrum.atv.proto.remote.RemoteImeBatchEdit
import com.shibi.sceptrum.atv.proto.remote.RemoteImeObject
import com.shibi.sceptrum.atv.proto.remote.RemoteImeShowRequest
import com.shibi.sceptrum.atv.proto.remote.RemoteImeKeyInject
import com.shibi.sceptrum.atv.proto.remote.RemoteKeyCode
import com.shibi.sceptrum.atv.proto.remote.RemoteMessage
import com.shibi.sceptrum.atv.proto.remote.RemotePingRequest
import com.shibi.sceptrum.atv.proto.remote.RemoteSetActive
import com.shibi.sceptrum.atv.proto.remote.RemoteSetVolumeLevel
import com.shibi.sceptrum.atv.proto.remote.RemoteStart
import com.shibi.sceptrum.atv.proto.remote.RemoteTextFieldStatus
import java.io.Closeable
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedTrustManager
import okio.ByteString

/** How the pairing port answers. */
enum class PairingScript {
    /** Full handshake; the secret is checked against the code shown. */
    Normal,

    /** Replies `STATUS_ERROR` to the pairing request. */
    RejectRequest,

    /** Never answers the pairing request. */
    Stall,

    /** Closes the socket after the pairing request. */
    DropAfterRequest,

    /** Shows a code, then answers any secret with `STATUS_BAD_SECRET`. */
    BadSecret,
}

data class ReceivedKey(val keyCode: Int, val direction: RemoteDirection)

/**
 * A scripted Android TV on 127.0.0.1: pairing on [pairingPort] and the control
 * session on [remotePort], both TLS with a required client certificate, in the
 * message order androidtvremote2 expects from a real TV. Records what the
 * client sends so tests can assert on it.
 */
class FakeAndroidTvServer(
    private val identity: ClientIdentity = AtvFixtures.tv,
    /** The TV's feature mask in `remote_configure`; 623 includes voice. */
    private val features: Int = 623,
    private val model: String = "Fake TV",
    private val vendor: String = "Sceptrum Labs",
) : Closeable {
    @Volatile var pairingScript: PairingScript = PairingScript.Normal

    /** Last 4 code characters; the first 2 follow from the secret, as on a real TV. */
    @Volatile var codeSuffix: String = "A1C9"

    /** Refuse the TLS handshake on 6466 from clients that never paired. */
    @Volatile var requirePairedClient: Boolean = true

    /** Ping every n ms on 6466; 0 = never (lets the client's idle watchdog fire). */
    @Volatile var pingIntervalMs: Long = 0

    /** Send `remote_start` after set_active. Off = the client never becomes ready. */
    @Volatile var sendStart: Boolean = true

    /** Hang up the control connection right after an app link, like a TV whose remote service fails to open it. */
    @Volatile var closeOnAppLink: Boolean = false

    /** Close this many next control connections right after the TLS handshake, before sending anything. */
    val silentDrops = AtomicInteger(0)

    @Volatile var powerOn: Boolean = true

    @Volatile var volume: RemoteSetVolumeLevel =
        RemoteSetVolumeLevel(player_model = "Fake", volume_max = 100, volume_level = 23, volume_muted = false)

    /** The code on the TV screen once the client has sent its Configuration. */
    @Volatile var shownCode: String? = null
        private set

    /** Answer only this raw polo status (any number, even outside the enum) at [failAt]. */
    @Volatile var failAt: PairingStep? = null
    @Volatile var failStatus: Int = 400

    /** Client certificates presented in pairing-port handshakes. */
    val clientCertificates = CopyOnWriteArrayList<X509Certificate>()
    val pairingRequests = CopyOnWriteArrayList<PairingRequest>()
    val optionsReceived = CopyOnWriteArrayList<Options>()
    val configurationsReceived = CopyOnWriteArrayList<Configuration>()
    val secretsReceived = AtomicInteger()
    val configureReplies = CopyOnWriteArrayList<RemoteConfigure>()
    val setActiveReplies = CopyOnWriteArrayList<RemoteSetActive>()
    val keys = CopyOnWriteArrayList<ReceivedKey>()
    val pongs = CopyOnWriteArrayList<Int>()
    val appLinks = CopyOnWriteArrayList<String>()
    val imeEdits = CopyOnWriteArrayList<RemoteImeBatchEdit>()

    /** Control sessions whose TLS handshake succeeded. */
    val remoteConnections = AtomicInteger()

    private val paired = CopyOnWriteArrayList<ByteArray>()
    private val live = CopyOnWriteArrayList<Live>()
    private val pingCounter = AtomicInteger(1)
    private val context = serverContext()
    private val pairingServer = listen()
    private val remoteServer = listen()

    val host: String = "127.0.0.1"
    val pairingPort: Int = pairingServer.localPort
    val remotePort: Int = remoteServer.localPort

    init {
        acceptLoop(pairingServer, "fake-atv-pairing") { servePairing(it) }
        acceptLoop(remoteServer, "fake-atv-remote") { serveRemote(it) }
    }

    /** Marks [certificate] as paired, as if pairing had already happened. */
    fun trust(certificate: X509Certificate) {
        paired += AtvTls.sha256(certificate)
    }

    fun isPaired(certificate: X509Certificate): Boolean =
        paired.any { MessageDigest.isEqual(it, AtvTls.sha256(certificate)) }

    fun pushVolume(level: Int, max: Int = 100, muted: Boolean = false) {
        volume = RemoteSetVolumeLevel(player_model = "Fake", volume_max = max, volume_level = level, volume_muted = muted)
        broadcast(RemoteMessage(remote_set_volume_level = volume))
    }

    fun pushPower(on: Boolean) {
        powerOn = on
        broadcast(RemoteMessage(remote_start = RemoteStart(started = on)))
    }

    fun pushCurrentApp(appPackage: String) =
        broadcast(RemoteMessage(remote_ime_key_inject = RemoteImeKeyInject(app_info = RemoteAppInfo(app_package = appPackage))))

    /** A text field got focus on the TV; [value] is the text already in it. */
    fun pushImeField(imeCounter: Int, fieldCounter: Int, value: String? = null) = broadcast(
        RemoteMessage(
            remote_ime_batch_edit = RemoteImeBatchEdit(
                ime_counter = imeCounter,
                field_counter = fieldCounter,
                edit_info = value?.let { listOf(RemoteEditInfo(insert = 1, text_field_status = RemoteImeObject(it.length - 1, it.length - 1, it))) }
                    .orEmpty(),
            ),
        ),
    )

    /** The focused field asks for a keyboard, with its label (e.g. "Search"). */
    fun pushImeShow(value: String, label: String) = broadcast(
        RemoteMessage(remote_ime_show_request = RemoteImeShowRequest(RemoteTextFieldStatus(value_ = value, label = label))),
    )

    /** Simulates the network dropping: every control session is cut. */
    fun dropConnections() {
        live.forEach { it.close() }
        live.clear()
    }

    override fun close() {
        runCatching { pairingServer.close() }
        runCatching { remoteServer.close() }
        dropConnections()
    }

    private fun servePairing(socket: SSLSocket) {
        socket.startHandshake()
        val client = socket.session.peerCertificates.first() as X509Certificate
        val reader = FrameReader(socket.inputStream)
        val out = socket.outputStream
        fun next(): OuterMessage = OuterMessage.ADAPTER.decode(reader.read() ?: error("client closed"))

        clientCertificates += client
        /** Sends [message], or only the status [failStatus] when the test chose to fail at [step]. */
        fun answer(step: PairingStep, message: OuterMessage): Boolean {
            if (failAt == step) {
                write(out, byteArrayOf(0x08, 0x02, 0x10) + Framing.encodeVarint(failStatus))
                return false
            }
            write(out, ok(message))
            return true
        }

        pairingRequests += next().pairing_request ?: error("expected pairing request")
        when (pairingScript) {
            PairingScript.RejectRequest -> {
                write(out, OuterMessage(protocol_version = 2, status = OuterMessage.Status.STATUS_ERROR).encode())
                return
            }
            PairingScript.Stall -> {
                reader.read()
                return
            }
            PairingScript.DropAfterRequest -> return
            PairingScript.Normal, PairingScript.BadSecret -> Unit
        }
        if (!answer(PairingStep.PairingRequestAck, OuterMessage(protocol_version = 2, status = OuterMessage.Status.STATUS_OK, pairing_request_ack = PairingRequestAck(server_name = model)))) return
        optionsReceived += next().options ?: error("expected options")
        val hex = Options.Encoding(Options.Encoding.EncodingType.ENCODING_TYPE_HEXADECIMAL, 6)
        if (!answer(PairingStep.Options, OuterMessage(protocol_version = 2, status = OuterMessage.Status.STATUS_OK, options = Options(input_encodings = listOf(hex), preferred_role = Options.RoleType.ROLE_TYPE_INPUT)))) return
        configurationsReceived += next().configuration ?: error("expected configuration")
        val expected = PairingSecret.compute(client.publicKey as RSAPublicKey, identity.publicKey, "00$codeSuffix")
        shownCode = "%02X".format(expected[0].toInt() and 0xFF) + codeSuffix
        if (!answer(PairingStep.ConfigurationAck, OuterMessage(protocol_version = 2, status = OuterMessage.Status.STATUS_OK, configuration_ack = ConfigurationAck()))) return

        val secret = next().secret ?: error("expected secret")
        secretsReceived.incrementAndGet()
        if (failAt == PairingStep.SecretAck) {
            answer(PairingStep.SecretAck, OuterMessage(protocol_version = 2, status = OuterMessage.Status.STATUS_OK))
            return
        }
        if (pairingScript == PairingScript.Normal && secret.secret == ByteString.of(*expected)) {
            trust(client)
            write(out, ok(OuterMessage(protocol_version = 2, status = OuterMessage.Status.STATUS_OK, secret_ack = SecretAck(secret.secret))))
        } else {
            write(out, OuterMessage(protocol_version = 2, status = OuterMessage.Status.STATUS_BAD_SECRET).encode())
        }
    }

    private fun serveRemote(socket: SSLSocket) {
        socket.startHandshake()
        // TLS 1.3 finishes the client side before the server checks its certificate; the
        // handshake call above already threw for an untrusted client, which closes the socket.
        remoteConnections.incrementAndGet()
        if (silentDrops.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) {
            socket.close()
            return
        }
        val session = Live(socket)
        live += session
        val reader = FrameReader(socket.inputStream)
        fun next(): RemoteMessage? = reader.read()?.let { RemoteMessage.ADAPTER.decode(it) }

        session.send(
            RemoteMessage(
                remote_configure = RemoteConfigure(
                    code1 = features,
                    device_info = RemoteDeviceInfo(model = model, vendor = vendor, app_version = "5.2.473254133"),
                ),
            ),
        )
        configureReplies += next()?.remote_configure ?: return
        session.send(RemoteMessage(remote_set_active = RemoteSetActive()))
        setActiveReplies += next()?.remote_set_active ?: return
        if (sendStart) {
            session.send(RemoteMessage(remote_start = RemoteStart(started = powerOn)))
            session.send(RemoteMessage(remote_set_volume_level = volume))
        }
        session.startPinging()
        while (true) {
            val message = next() ?: return
            message.remote_ping_response?.let { pongs += it.val1 }
            message.remote_key_inject?.let { key ->
                keys += ReceivedKey(key.key_code.value, key.direction)
                if (key.key_code == RemoteKeyCode.KEYCODE_POWER && key.direction == RemoteDirection.SHORT) {
                    pushPower(!powerOn)
                }
            }
            message.remote_app_link_launch_request?.let {
                appLinks += it.app_link
                if (closeOnAppLink) {
                    socket.close()
                    return
                }
            }
            message.remote_ime_batch_edit?.let { imeEdits += it }
        }
    }

    private fun broadcast(message: RemoteMessage) = live.forEach { it.send(message) }

    /** One control session, written from several threads. */
    private inner class Live(private val socket: SSLSocket) {
        private val lock = Any()

        fun send(message: RemoteMessage) {
            runCatching { synchronized(lock) { write(socket.outputStream, message.encode()) } }
        }

        fun startPinging() {
            val interval = pingIntervalMs
            if (interval <= 0) return
            Thread({
                while (!socket.isClosed) {
                    Thread.sleep(interval)
                    send(RemoteMessage(remote_ping_request = RemotePingRequest(val1 = pingCounter.getAndIncrement(), val2 = 0)))
                }
            }, "fake-atv-ping").apply { isDaemon = true }.start()
        }

        fun close() {
            runCatching { socket.close() }
        }
    }

    private fun ok(message: OuterMessage): ByteArray = message.encode()

    private fun write(out: OutputStream, bytes: ByteArray) = Framing.write(out, bytes)

    private fun listen(): SSLServerSocket =
        (context.serverSocketFactory.createServerSocket(0, 16, InetAddress.getByName("127.0.0.1")) as SSLServerSocket)
            .apply { needClientAuth = true }

    private fun acceptLoop(server: SSLServerSocket, name: String, serve: (SSLSocket) -> Unit) {
        Thread({
            while (!server.isClosed) {
                val socket = runCatching { server.accept() as SSLSocket }.getOrNull() ?: continue
                Thread({
                    socket.use { runCatching { serve(it) } }
                }, "$name-conn").apply { isDaemon = true }.start()
            }
        }, name).apply { isDaemon = true }.start()
    }

    private fun serverContext(): SSLContext {
        val password = "fake".toCharArray()
        val keyStore = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("tv", identity.privateKey, password, arrayOf(identity.certificate))
        }
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(keyStore, password) }
            .keyManagers
        return SSLContext.getInstance("TLS").apply { init(keyManagers, arrayOf(ClientCertificatePolicy()), null) }
    }

    /**
     * Accepts any client certificate on the pairing port; on the control port only
     * paired ones when [requirePairedClient] is set.
     */
    private inner class ClientCertificatePolicy : X509ExtendedTrustManager() {
        private fun check(chain: Array<out X509Certificate>?, socket: Socket?) {
            val leaf = chain?.firstOrNull() ?: throw CertificateException("no client certificate")
            val onRemotePort = socket?.localPort == remotePort
            if (onRemotePort && requirePairedClient && !isPaired(leaf)) throw CertificateException("client not paired")
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain, null)

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) =
            check(chain, socket)

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) =
            check(chain, null)

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = Unit

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = Unit

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
