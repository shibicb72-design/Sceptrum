package com.shibi.sceptrum.atv

import com.shibi.sceptrum.atv.proto.polo.OuterMessage
import java.io.Closeable
import java.io.IOException
import java.net.SocketTimeoutException
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What a TV's pairing port reveals before pairing. */
class TvProbe(val identity: TvIdentity, val serverSha256: ByteArray)

/** Why pairing stopped. Each maps to one error card in the pairing screen. */
enum class PairingFailure {
    /** The typed code fails the first-byte check. Nothing was sent; the session stays open. */
    WrongCode,

    /** The TV answered `STATUS_BAD_SECRET`. It shows a new code, so start again. */
    BadSecret,

    /** The TV answered a status other than OK (`STATUS_ERROR`, `STATUS_BAD_CONFIGURATION`, ...) or the wrong message. */
    Rejected,

    /** The TLS handshake failed or the TLS layer raised an alert, e.g. the TV refused the client certificate. */
    Handshake,

    /** The TV closed or reset the connection without a polo status. */
    Dropped,

    /** No answer within the step timeout. */
    Timeout,

    /** TCP connect to the pairing port failed. */
    Unreachable,
}

class PairingException(
    val failure: PairingFailure,
    message: String,
    cause: Throwable? = null,
    /** Set for every failure that came from talking to a TV. */
    val diagnostics: PairingDiagnostics? = null,
) : Exception(message, cause)

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
    private val trace: Trace,
    private val clientKey: RSAPublicKey,
    /** The TV's certificate; its SHA-256 becomes the pin for port 6466. */
    val serverCertificate: X509Certificate,
    /** Name the TV gave in its PairingRequestAck, if any. */
    val serverName: String?,
) : Closeable {
    val serverIdentity: TvIdentity get() = CertificateNames.parse(serverCertificate)
    val serverSha256: ByteArray get() = AtvTls.sha256(serverCertificate)

    /** Negotiated TLS version, e.g. "TLSv1.3". */
    val tlsProtocol: String? get() = trace.protocol

    /**
     * Checks [code] locally, then sends the secret.
     * @throws PairingException [PairingFailure.WrongCode] (session still usable),
     *   or [PairingFailure.BadSecret], [PairingFailure.Rejected], [PairingFailure.Dropped],
     *   [PairingFailure.Handshake], [PairingFailure.Timeout].
     */
    suspend fun submitCode(code: String) {
        val normalized = PairingCode.normalize(code)
            ?: throw PairingException(PairingFailure.WrongCode, "code must be 6 hex digits")
        val secret = PairingSecret.compute(clientKey, serverCertificate.publicKey as RSAPublicKey, normalized)
        if (!PairingSecret.checkByteMatches(secret, normalized)) {
            throw PairingException(PairingFailure.WrongCode, "code does not match this TV")
        }
        withContext(Dispatchers.IO) {
            guard(socket, trace) {
                exchange(socket, reader, trace, PoloMessages.secret(secret), PairingStep.SecretAck)
            }
        }
        close()
    }

    override fun close() {
        runCatching { socket.close() }
    }

    /** Where the attempt is, for [PairingDiagnostics]. */
    internal class Trace(val host: String, val port: Int, val identity: ClientIdentity?) {
        @Volatile var step: PairingStep = PairingStep.Connect

        @Volatile var protocol: String? = null

        @Volatile var cipher: String? = null

        fun negotiated(socket: SSLSocket) {
            val session = socket.session
            protocol = session.protocol?.takeUnless { it == "NONE" }
            cipher = session.cipherSuite?.takeUnless { it.contains("NULL_WITH_NULL") }
        }

        fun fail(failure: PairingFailure, detail: String, cause: Throwable? = null, status: Int? = null) =
            PairingException(
                failure,
                detail,
                cause,
                PairingDiagnostics(
                    failure = failure,
                    step = step,
                    host = host,
                    port = port,
                    statusCode = status,
                    detail = detail,
                    errors = PairingDiagnostics.chain(cause),
                    keyAlgorithm = identity?.privateKey?.algorithm,
                    keyBits = PairingDiagnostics.keyBits(identity),
                    keySource = identity?.source,
                    tlsProtocol = protocol,
                    cipherSuite = cipher,
                ),
            )
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
        ): AtvPairingSession = startWith(host, identity, clientName, port, timeouts) { AtvTls.sslContext(identity, it) }

        /** [start] with the TLS context supplied, so tests can drop the client certificate or pin a TLS version. */
        internal suspend fun startWith(
            host: String,
            identity: ClientIdentity?,
            clientName: String,
            port: Int,
            timeouts: PairingTimeouts,
            context: (CapturingTrustManager) -> SSLContext,
        ): AtvPairingSession = withContext(Dispatchers.IO) {
            val trace = Trace(host, port, identity)
            val trust = CapturingTrustManager()
            val socket = connect(trace, context(trust), timeouts)
            guard(socket, trace) {
                val reader = FrameReader(socket.inputStream)
                val ack = exchange(socket, reader, trace, PoloMessages.pairingRequest(clientName), PairingStep.PairingRequestAck)
                exchange(socket, reader, trace, PoloMessages.options(), PairingStep.Options)
                exchange(socket, reader, trace, PoloMessages.configuration(), PairingStep.ConfigurationAck)
                val serverCert = trust.serverCertificate
                    ?: throw trace.fail(PairingFailure.Handshake, "TV sent no certificate")
                // The same reader carries on: it may already hold buffered bytes.
                AtvPairingSession(
                    socket = socket,
                    reader = reader,
                    trace = trace,
                    clientKey = requireNotNull(identity) { "pairing needs a client identity" }.publicKey,
                    serverCertificate = serverCert,
                    serverName = ack.serverName,
                )
            }
        }

        /**
         * Connects to the pairing port only to read the TV's certificate, for
         * its name and MAC (androidtvremote2 `async_get_name_and_mac`). The TV
         * shows nothing.
         * @throws PairingException [PairingFailure.Unreachable], [PairingFailure.Timeout]
         *   or [PairingFailure.Handshake].
         */
        suspend fun probe(
            host: String,
            identity: ClientIdentity,
            port: Int = DEFAULT_PORT,
            timeouts: PairingTimeouts = PairingTimeouts(connectMs = 2_000, stepMs = 5_000),
        ): TvProbe = withContext(Dispatchers.IO) {
            val trace = Trace(host, port, identity)
            val trust = CapturingTrustManager()
            connect(trace, AtvTls.sslContext(identity, trust), timeouts).use {
                val cert = trust.serverCertificate ?: throw trace.fail(PairingFailure.Handshake, "TV sent no certificate")
                TvProbe(CertificateNames.parse(cert), AtvTls.sha256(cert))
            }
        }

        private fun connect(trace: Trace, context: SSLContext, timeouts: PairingTimeouts): SSLSocket = try {
            AtvTls.connect(
                host = trace.host,
                port = trace.port,
                context = context,
                connectTimeoutMs = timeouts.connectMs,
                readTimeoutMs = timeouts.stepMs,
                onConnected = { trace.step = PairingStep.TlsHandshake },
            ).also { trace.negotiated(it) }
        } catch (e: UnreachableException) {
            throw trace.fail(PairingFailure.Unreachable, "cannot reach ${trace.host}:${trace.port}", e.cause ?: e)
        } catch (e: SocketTimeoutException) {
            throw trace.fail(PairingFailure.Timeout, "TLS handshake timed out", e)
        } catch (e: SSLException) {
            throw trace.fail(PairingFailure.Handshake, "TLS handshake failed", e)
        } catch (e: IOException) {
            throw trace.fail(PairingFailure.Dropped, "connection closed during the TLS handshake", e)
        } catch (e: RuntimeException) {
            // A provider that cannot use the client key may throw instead of failing the handshake.
            throw trace.fail(PairingFailure.Handshake, "TLS stack error", e)
        }

        /** Sends [request], then reads the TV's reply, which must carry the message [step] waits for. */
        private fun exchange(
            socket: SSLSocket,
            reader: FrameReader,
            trace: Trace,
            request: OuterMessage,
            step: PairingStep,
        ): PoloReply {
            trace.step = step
            Framing.write(socket.outputStream, request.encode())
            val raw = reader.read() ?: throw trace.fail(PairingFailure.Dropped, "TV closed the connection")
            val reply = try {
                PoloReply.parse(raw)
            } catch (e: IOException) {
                throw trace.fail(PairingFailure.Rejected, "unreadable reply", e)
            }
            val status = reply.status
            when (status) {
                // androidtvremote2 treats only STATUS_OK as success; a reply without a status still has to carry the ack.
                PoloMessages.STATUS_OK, null -> Unit
                PoloMessages.STATUS_BAD_SECRET ->
                    throw trace.fail(PairingFailure.BadSecret, "TV answered $status", status = status)
                else -> throw trace.fail(PairingFailure.Rejected, "TV answered $status", status = status)
            }
            if (!reply.has(step)) {
                throw trace.fail(PairingFailure.Rejected, "unexpected reply with fields ${reply.fields.sorted()}", status = status)
            }
            return reply
        }

        /** Maps socket errors to [PairingFailure] and closes the socket on any failure. */
        private inline fun <T> guard(socket: SSLSocket, trace: Trace, block: () -> T): T = try {
            block()
        } catch (e: PairingException) {
            socket.close()
            throw e
        } catch (e: SocketTimeoutException) {
            socket.close()
            throw trace.fail(PairingFailure.Timeout, "no answer from the TV", e)
        } catch (e: SSLException) {
            socket.close()
            throw trace.fail(PairingFailure.Handshake, "TLS error", e)
        } catch (e: IOException) {
            socket.close()
            throw trace.fail(PairingFailure.Dropped, "connection lost", e)
        }
    }
}
