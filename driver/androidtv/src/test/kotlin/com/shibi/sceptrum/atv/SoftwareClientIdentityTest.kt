package com.shibi.sceptrum.atv

import java.math.BigInteger
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SoftwareClientIdentityTest {
    private val now = Instant.parse("2026-10-17T12:00:00Z")
    private val identity = SoftwareClientIdentity.generate(now = now)
    private val cert = identity.certificate

    @Test
    fun rsa2048WithF4() {
        val key = cert.publicKey as RSAPublicKey
        assertEquals(2048, key.modulus.bitLength())
        assertEquals(BigInteger.valueOf(65537), key.publicExponent)
        assertEquals("RSA", identity.privateKey.algorithm)
        assertEquals(key.modulus, (identity.privateKey as RSAPrivateKey).modulus)
        assertEquals("software", identity.source)
    }

    @Test
    fun selfSignedX509v3LikeAndroidTvRemote2() {
        assertEquals(3, cert.version)
        assertEquals("SHA256withRSA", cert.sigAlgName)
        assertEquals(cert.subjectX500Principal, cert.issuerX500Principal)
        assertEquals("CN=Sceptrum", cert.subjectX500Principal.name)
        cert.verify(cert.publicKey)
        assertEquals("CA:TRUE pathlen 0", 0, cert.basicConstraints)
        assertEquals(listOf(listOf<Any>(2, "Sceptrum")), cert.subjectAlternativeNames.map { it.toList() })
        assertTrue(cert.serialNumber.signum() > 0)
    }

    @Test
    fun validTenYearsFromTheDayBefore() {
        assertEquals(Instant.parse("2026-10-16T12:00:00Z"), cert.notBefore.toInstant())
        assertEquals(Instant.parse("2036-10-13T12:00:00Z"), cert.notAfter.toInstant())
        cert.checkValidity(java.util.Date.from(now))
    }

    @Test
    fun storedFormRoundTrips() {
        val again = SoftwareClientIdentity.decode(identity.privateKey.encoded, cert.encoded)
        assertArrayEquals(cert.encoded, again.certificate.encoded)
        assertArrayEquals(identity.privateKey.encoded, again.privateKey.encoded)
        assertEquals("PKCS#8", identity.privateKey.format)
    }

    @Test
    fun derPieces() {
        // sha256WithRSAEncryption, X.690 example encoding
        assertArrayEquals(
            bytes(0x06, 0x09, 0x2A, 0x86, 0x48, 0x86, 0xF7, 0x0D, 0x01, 0x01, 0x0B),
            SelfSignedCertificate.oid("1.2.840.113549.1.1.11"),
        )
        assertArrayEquals("170D3236313031373132303030305A".hex(), SelfSignedCertificate.time(now))
        assertArrayEquals("180F32303530303130313030303030305A".hex(), SelfSignedCertificate.time(Instant.parse("2050-01-01T00:00:00Z")))
        assertArrayEquals(bytes(0x04, 0x81, 0x80) + ByteArray(128), SelfSignedCertificate.tlv(0x04, ByteArray(128)))
        assertArrayEquals(bytes(0x04, 0x82, 0x01, 0x00) + ByteArray(256), SelfSignedCertificate.tlv(0x04, ByteArray(256)))
    }

    private fun String.hex() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
}
