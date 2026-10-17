package com.shibi.sceptrum.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.shibi.sceptrum.atv.ClientIdentity
import com.shibi.sceptrum.atv.ClientIdentityProvider
import com.shibi.sceptrum.atv.StaticClientIdentity
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.security.spec.RSAKeyGenParameterSpec
import java.util.Calendar
import java.util.Date
import javax.security.auth.x500.X500Principal

/**
 * The phone's TV-remote identity: one RSA-2048 key in the Android Keystore,
 * created on first use. Keystore self-signs the certificate (CN=Sceptrum,
 * 10 years), like androidtvremote2's generated one. The private key never
 * leaves the Keystore, so a new phone pairs again.
 *
 * Both PKCS#1 and PSS paddings and the NONE digest are allowed so TLS 1.2 and
 * TLS 1.3 handshakes can both sign with it.
 */
class KeystoreClientIdentityProvider(
    private val alias: String = ALIAS,
) : ClientIdentityProvider {
    @Volatile
    private var cached: ClientIdentity? = null

    @Synchronized
    override fun identity(): ClientIdentity {
        cached?.let { return it }
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val entry = keyStore.getEntry(alias, null) as? KeyStore.PrivateKeyEntry ?: run {
            generate()
            keyStore.getEntry(alias, null) as KeyStore.PrivateKeyEntry
        }
        return StaticClientIdentity(entry.certificate as X509Certificate, entry.privateKey).also { cached = it }
    }

    private fun generate() {
        val start = Date()
        val end = Calendar.getInstance().apply { add(Calendar.YEAR, VALID_YEARS) }.time
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(RSAKeyGenParameterSpec(KEY_BITS, RSAKeyGenParameterSpec.F4))
            .setDigests(
                KeyProperties.DIGEST_NONE,
                KeyProperties.DIGEST_SHA256,
                KeyProperties.DIGEST_SHA384,
                KeyProperties.DIGEST_SHA512,
            )
            .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1, KeyProperties.SIGNATURE_PADDING_RSA_PSS)
            .setCertificateSubject(X500Principal("CN=$CERT_NAME"))
            .setCertificateSerialNumber(BigInteger.valueOf(SERIAL))
            .setCertificateNotBefore(start)
            .setCertificateNotAfter(end)
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEYSTORE).run {
            initialize(spec)
            generateKeyPair()
        }
    }

    companion object {
        const val ALIAS = "sceptrum_atv_client"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val CERT_NAME = "Sceptrum"
        private const val KEY_BITS = 2048
        private const val VALID_YEARS = 10
        private const val SERIAL = 1000L
    }
}
