package com.shibi.sceptrum.atv

import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager

/**
 * The phone's TLS client identity: one self-signed RSA certificate for every TV,
 * like the official app. On Android the key lives in the Keystore; JVM tests
 * use a software key from the fixtures.
 */
interface ClientIdentity {
    val certificate: X509Certificate
    val privateKey: PrivateKey

    val publicKey: RSAPublicKey get() = certificate.publicKey as RSAPublicKey
}

/** Supplies (and on first use creates) the client identity. */
fun interface ClientIdentityProvider {
    fun identity(): ClientIdentity
}

class StaticClientIdentity(
    override val certificate: X509Certificate,
    override val privateKey: PrivateKey,
) : ClientIdentity

/** The TV presented a different certificate from the one recorded at pairing. */
class PinMismatchException(val presentedSha256: ByteArray) :
    CertificateException("TV certificate does not match the pinned certificate")

/**
 * TVs use self-signed certificates, so there is no chain to verify. During
 * pairing any certificate is accepted and recorded; afterwards the recorded
 * SHA-256 fingerprint is the pin, and anything else is refused.
 */
class CapturingTrustManager(private val pinnedSha256: ByteArray? = null) : X509ExtendedTrustManager() {
    @Volatile
    var serverCertificate: X509Certificate? = null
        private set

    private fun check(chain: Array<out X509Certificate>?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("TV sent no certificate")
        val pin = pinnedSha256
        if (pin != null) {
            val presented = AtvTls.sha256(leaf)
            if (!MessageDigest.isEqual(presented, pin)) throw PinMismatchException(presented)
        }
        serverCertificate = leaf
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) =
        check(chain)

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) =
        check(chain)

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        throw CertificateException("client side only")

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) =
        throw CertificateException("client side only")

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) =
        throw CertificateException("client side only")

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/** Always offers the one client identity, whatever the TV asks for. */
class SingleIdentityKeyManager(private val identity: ClientIdentity) : X509ExtendedKeyManager() {
    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS

    override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) =
        ALIAS

    override fun getCertificateChain(alias: String?): Array<X509Certificate> = arrayOf(identity.certificate)

    override fun getPrivateKey(alias: String?): PrivateKey = identity.privateKey

    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> = arrayOf(ALIAS)

    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> = emptyArray()

    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null

    private companion object {
        const val ALIAS = "sceptrum"
    }
}

object AtvTls {
    fun sha256(certificate: X509Certificate): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(certificate.encoded)

    fun sslContext(identity: ClientIdentity, trust: CapturingTrustManager): SSLContext =
        SSLContext.getInstance("TLS").apply {
            init(arrayOf(SingleIdentityKeyManager(identity)), arrayOf(trust), null)
        }

    /**
     * TCP connect with a timeout, then the TLS handshake. A failure to connect
     * surfaces as a plain [java.io.IOException]; a refused handshake as
     * [javax.net.ssl.SSLException].
     */
    fun connect(
        host: String,
        port: Int,
        context: SSLContext,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): SSLSocket {
        val raw = Socket()
        try {
            raw.tcpNoDelay = true
            raw.connect(InetSocketAddress(host, port), connectTimeoutMs)
        } catch (e: Exception) {
            raw.close()
            throw UnreachableException(host, port, e)
        }
        val socket = context.socketFactory.createSocket(raw, host, port, true) as SSLSocket
        try {
            socket.useClientMode = true
            socket.soTimeout = readTimeoutMs
            socket.startHandshake()
        } catch (e: Exception) {
            socket.close()
            throw e
        }
        return socket
    }
}

/** TCP connect failed or timed out: wrong IP, TV off, or a different network. */
class UnreachableException(host: String, port: Int, cause: Throwable) :
    java.io.IOException("Cannot reach $host:$port", cause)
