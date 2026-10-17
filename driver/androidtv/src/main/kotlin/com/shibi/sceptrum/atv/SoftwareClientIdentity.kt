package com.shibi.sceptrum.atv

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAKeyGenParameterSpec
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * The client identity androidtvremote2 makes (`certificate_generator.py`): a
 * software RSA-2048 key, e = 65537, and a self-signed X.509 v3 certificate
 * signed SHA256withRSA, CN and DNS SAN set to the name, BasicConstraints
 * CA:TRUE pathlen 0, valid 10 years.
 *
 * Software rather than Android Keystore because the TLS stack signs the client
 * CertificateVerify itself: TLS 1.3 (and TLS 1.2 against a TV that offers it)
 * picks RSA-PSS, which Conscrypt performs as a raw RSA operation, and a
 * Keystore signing key refuses raw RSA unless it was made with padding NONE.
 * A key the TLS stack holds in memory always works, as it does for Python.
 */
object SoftwareClientIdentity {
    const val SOURCE = "software"
    const val KEY_BITS = 2048
    const val DEFAULT_NAME = "Sceptrum"
    val VALIDITY: Duration = Duration.ofDays(10 * 365)

    /** Takes a moment (RSA key generation); call off the main thread. */
    fun generate(
        commonName: String = DEFAULT_NAME,
        now: Instant = Instant.now(),
        random: SecureRandom = SecureRandom(),
    ): ClientIdentity {
        val keys = KeyPairGenerator.getInstance("RSA").run {
            initialize(RSAKeyGenParameterSpec(KEY_BITS, RSAKeyGenParameterSpec.F4), random)
            generateKeyPair()
        }
        // A day early, so a TV whose clock lags still sees the certificate as valid.
        val notBefore = now.truncatedTo(ChronoUnit.SECONDS).minus(Duration.ofDays(1))
        val der = SelfSignedCertificate.build(keys, commonName, BigInteger(63, random).inc(), notBefore, notBefore + VALIDITY)
        return decode(keys.private.encoded, der)
    }

    /** PKCS#8 private key and DER certificate, as stored. */
    fun decode(privateKeyPkcs8: ByteArray, certificateDer: ByteArray): ClientIdentity {
        val certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(certificateDer.inputStream()) as X509Certificate
        val privateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(privateKeyPkcs8))
        return StaticClientIdentity(certificate, privateKey, SOURCE)
    }
}

/** Minimal DER writer for one self-signed X.509 v3 certificate (RFC 5280). */
internal object SelfSignedCertificate {
    private const val SHA256_WITH_RSA = "1.2.840.113549.1.1.11"
    private const val COMMON_NAME = "2.5.4.3"
    private const val BASIC_CONSTRAINTS = "2.5.29.19"
    private const val SUBJECT_ALT_NAME = "2.5.29.17"

    fun build(keys: KeyPair, commonName: String, serial: BigInteger, notBefore: Instant, notAfter: Instant): ByteArray {
        val algorithm = sequence(oid(SHA256_WITH_RSA), byteArrayOf(0x05, 0x00))
        val name = sequence(set(sequence(oid(COMMON_NAME), tlv(0x0C, commonName.encodeToByteArray()))))
        val extensions = sequence(
            // cA TRUE, pathLenConstraint 0; not critical, as androidtvremote2.
            sequence(oid(BASIC_CONSTRAINTS), tlv(0x04, sequence(byteArrayOf(0x01, 0x01, 0xFF.toByte()), integer(BigInteger.ZERO)))),
            sequence(oid(SUBJECT_ALT_NAME), tlv(0x04, sequence(tlv(0x82, commonName.encodeToByteArray())))),
        )
        val tbs = sequence(
            tlv(0xA0, integer(BigInteger.valueOf(2))),
            integer(serial),
            algorithm,
            name,
            sequence(time(notBefore), time(notAfter)),
            name,
            keys.public.encoded,
            tlv(0xA3, extensions),
        )
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(keys.private)
            update(tbs)
            sign()
        }
        return sequence(tbs, algorithm, tlv(0x03, byteArrayOf(0) + signature))
    }

    fun tlv(tag: Int, content: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        write(tag)
        val n = content.size
        when {
            n < 0x80 -> write(n)
            n < 0x100 -> { write(0x81); write(n) }
            n < 0x10000 -> { write(0x82); write(n ushr 8); write(n and 0xFF) }
            else -> { write(0x83); write(n ushr 16); write((n ushr 8) and 0xFF); write(n and 0xFF) }
        }
        write(content)
    }.toByteArray()

    private fun sequence(vararg parts: ByteArray) = tlv(0x30, concat(parts))

    private fun set(vararg parts: ByteArray) = tlv(0x31, concat(parts))

    private fun integer(value: BigInteger) = tlv(0x02, value.toByteArray())

    fun oid(dotted: String): ByteArray {
        val arcs = dotted.split('.').map { it.toLong() }
        val out = ByteArrayOutputStream()
        (listOf(arcs[0] * 40 + arcs[1]) + arcs.drop(2)).forEach { arc ->
            val groups = mutableListOf<Int>()
            var v = arc
            do {
                groups += (v and 0x7F).toInt()
                v = v ushr 7
            } while (v > 0)
            groups.reversed().forEachIndexed { i, g -> out.write(if (i < groups.size - 1) g or 0x80 else g) }
        }
        return tlv(0x06, out.toByteArray())
    }

    /** UTCTime through 2049, GeneralizedTime after, as RFC 5280 §4.1.2.5 requires. */
    fun time(instant: Instant): ByteArray {
        val utc = instant.atZone(ZoneOffset.UTC)
        return if (utc.year < 2050) {
            tlv(0x17, utc.format(DateTimeFormatter.ofPattern("yyMMddHHmmss'Z'")).encodeToByteArray())
        } else {
            tlv(0x18, utc.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss'Z'")).encodeToByteArray())
        }
    }

    private fun concat(parts: Array<out ByteArray>): ByteArray =
        ByteArrayOutputStream().apply { parts.forEach { write(it) } }.toByteArray()
}
