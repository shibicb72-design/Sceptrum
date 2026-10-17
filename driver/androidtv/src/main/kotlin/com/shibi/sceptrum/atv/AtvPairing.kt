package com.shibi.sceptrum.atv

import com.shibi.sceptrum.atv.proto.polo.Configuration
import com.shibi.sceptrum.atv.proto.polo.Options
import com.shibi.sceptrum.atv.proto.polo.OuterMessage
import com.shibi.sceptrum.atv.proto.polo.PairingRequest
import com.shibi.sceptrum.atv.proto.polo.Secret
import java.io.Closeable
import java.io.IOException
import java.net.SocketTimeoutException
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.ByteString.Companion.toByteString

/** Why pairing stopped. Each maps to one error card in the pairing screen. */
enum class PairingFailure {
    /** The typed code fails the first-byte check. Nothing was sent; the session stays open. */
    WrongCode,

    /** The TV answered `STATUS_BAD_SECRET`. It shows a new code, so start again. */
    BadSecret,

    /** `STATUS_ERROR` / `STATUS_BAD_CONFIGURATION`, or the TV closed the connection. */
    Rejected,

    /** No answer within the step timeout. */
    Timeout,

    /** TCP connect to the pairing port failed. */
    Unreachable,
}

class PairingException(val failure: PairingFailure, message: String, cause: Throwable? = null) :
    Exception(message, cause)

data class PairingTimeouts(
    val connectMs: Int = 5_000,
    /** androidtvremote2 `PAIRING_TIMEOUT`: every step is answered by the TV itself. */
    val stepMs: Int = 10_000,
)

/**
 * One pairing attempt on port 6467 (androidtvremote2 `pairing.py`):
 * PairingRequest, Options and Configuration in [start], after which the TV
 * shows its code; then the secret in [submitCode]. Blocking socket work runs
 * on [Dispatchers.IO]; [close] aborts a step in progress.
 */
class AtvPairingSession private constructor(
    private val socket: SSLSocket,
    private val reader: FrameReader,
    private val clientKey: RSAPublicKey,
    /** The TV's certificate; its SHA-256 becomes the pin for port 6466. */
    val serverCertificate: X509Certificate,
    /** Name the TV gave in its PairingRequestAck, if any. */
    val serverName: String?,
) : Closeable {
    val serverIdentity: TvIdentity get() = CertificateNames.parse(serverCertificate)
    val serverSha256: ByteArray get() = AtvTls.sha256(serverCertificate)

    /**
     * Checks [code] locally, then sends the secret.
     * @throws PairingException [PairingFailure.WrongCode] (session still usable),
     *   or [PairingFailure.BadSecret], [PairingFailure.Rejected], [PairingFailure.Timeout].
     */
    suspend fun submitCode(code: String) {
        val normalized = PairingCode.normalize(code)
            ?: throw PairingException(PairingFailure.WrongCode, "code must be 6 hex digits")
        val secret = PairingSecret.compute(clientKey, serverCertificate.publicKey as RSAPublicKey, normalized)
        if (!PairingSecret.checkByteMatches(secret, normalized)) {
            throw PairingException(PairingFailure.WrongCode, "code does not match this TV")
        }
        withContext(Dispatchers.IO) {
            guard(socket) {
                exchange(socket, reader, outer(secret = Secret(secret.toByteString()))) { it.secret_ack != null }
            }
        }
        close()
    }

    override fun close() {
        runCatching { socket.close() }
    }

    companion object {
        const val DEFAULT_PORT = 6467
        const val SERVICE_NAME = "atvremote"
        const val CODE_SYMBOLS = PairingCode.LENGTH

        /**
         * Connects and runs the handshake up to ConfigurationAck, at which point
         * the TV displays the code.
         * @param clientName shown on the TV, e.g. "Sceptrum (SM-F966B)".
         */
        suspend fun start(
            host: String,
            identity: ClientIdentity,
            clientName: String,
            port: Int = DEFAULT_PORT,
            timeouts: PairingTimeouts = PairingTimeouts(),
        ): AtvPairingSession = withContext(Dispatchers.IO) {
            val trust = CapturingTrustManager()
            val socket = try {
                AtvTls.connect(host, port, AtvTls.sslContext(identity, trust), timeouts.connectMs, timeouts.stepMs)
            } catch (e: UnreachableException) {
                throw PairingException(PairingFailure.Unreachable, "Cannot reach $host:$port", e)
            } catch (e: SocketTimeoutException) {
                throw PairingException(PairingFailure.Timeout, "TLS handshake timed out", e)
            } catch (e: IOException) {
                throw PairingException(PairingFailure.Rejected, "TLS handshake failed", e)
            }
            guard(socket) {
                val reader = FrameReader(socket.inputStream)
                val ack = exchange(
                    socket,
                    reader,
                    outer(pairingRequest = PairingRequest(service_name = SERVICE_NAME, client_name = clientName)),
                ) { it.pairing_request_ack != null }
                val hex = Options.Encoding(Options.Encoding.EncodingType.ENCODING_TYPE_HEXADECIMAL, CODE_SYMBOLS)
                exchange(
                    socket,
                    reader,
                    outer(options = Options(input_encodings = listOf(hex), preferred_role = Options.RoleType.ROLE_TYPE_INPUT)),
                ) { it.options != null }
                exchange(
                    socket,
                    reader,
                    outer(configuration = Configuration(hex, Options.RoleType.ROLE_TYPE_INPUT)),
                ) { it.configuration_ack != null }
                val serverCert = trust.serverCertificate
                    ?: throw PairingException(PairingFailure.Rejected, "TV sent no certificate")
                // The same reader carries on: it may already hold buffered bytes.
                AtvPairingSession(
                    socket = socket,
                    reader = reader,
                    clientKey = identity.publicKey,
                    serverCertificate = serverCert,
                    serverName = ack.pairing_request_ack?.server_name,
                )
            }
        }

        /** Protocol version 2 and STATUS_OK, as androidtvremote2 `_create_message`. */
        internal fun outer(
            pairingRequest: PairingRequest? = null,
            options: Options? = null,
            configuration: Configuration? = null,
            secret: Secret? = null,
        ) = OuterMessage(
            protocol_version = 2,
            status = OuterMessage.Status.STATUS_OK,
            pairing_request = pairingRequest,
            options = options,
            configuration = configuration,
            secret = secret,
        )

        private fun exchange(
            socket: SSLSocket,
            reader: FrameReader,
            request: OuterMessage,
            expected: (OuterMessage) -> Boolean,
        ): OuterMessage {
            Framing.write(socket.outputStream, request.encode())
            val raw = reader.read() ?: throw PairingException(PairingFailure.Rejected, "TV closed the connection")
            val reply = try {
                OuterMessage.ADAPTER.decode(raw)
            } catch (e: IOException) {
                throw PairingException(PairingFailure.Rejected, "unreadable reply", e)
            }
            when (reply.status) {
                OuterMessage.Status.STATUS_OK -> Unit
                OuterMessage.Status.STATUS_BAD_SECRET ->
                    throw PairingException(PairingFailure.BadSecret, "TV rejected the code")
                else -> throw PairingException(PairingFailure.Rejected, "TV answered ${reply.status}")
            }
            if (!expected(reply)) throw PairingException(PairingFailure.Rejected, "unexpected reply $reply")
            return reply
        }

        /** Maps socket errors to [PairingFailure] and closes the socket on any failure. */
        private inline fun <T> guard(socket: SSLSocket, block: () -> T): T = try {
            block()
        } catch (e: PairingException) {
            socket.close()
            throw e
        } catch (e: SocketTimeoutException) {
            socket.close()
            throw PairingException(PairingFailure.Timeout, "no answer from the TV", e)
        } catch (e: SSLException) {
            socket.close()
            throw PairingException(PairingFailure.Rejected, "TLS error", e)
        } catch (e: IOException) {
            socket.close()
            throw PairingException(PairingFailure.Rejected, "connection lost", e)
        }
    }
}
