package com.shibi.sceptrum.atv

import com.shibi.sceptrum.testing.atv.AtvFixtures
import java.math.BigInteger
import java.security.interfaces.RSAPublicKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingSecretTest {
    /** Vectors from tools/atv_fixtures.py, which runs androidtvremote2's own expressions. */
    @Test
    fun matchesTheReferenceImplementation() {
        val vectors = AtvFixtures.secretVectors()
        assertEquals(12, vectors.size)
        for (v in vectors) {
            val secret = PairingSecret.compute(key(v.client), key(v.server), v.code)
            assertEquals("$v", v.secretHex, secret.toHex())
            assertTrue("$v", PairingSecret.checkByteMatches(secret, v.code))
        }
    }

    @Test
    fun wrongFirstByteFailsTheLocalCheck() {
        val v = AtvFixtures.secretVectors().first()
        val wrongFirst = "%02X".format((v.code.substring(0, 2).toInt(16) + 1) % 256) + v.code.substring(2)
        val secret = PairingSecret.compute(key(v.client), key(v.server), wrongFirst)
        // The digest only covers the last 4 characters, so it is unchanged; the check byte is not.
        assertEquals(v.secretHex, secret.toHex())
        assertFalse(PairingSecret.checkByteMatches(secret, wrongFirst))
    }

    @Test
    fun lowercaseAndSpacesAreTheSameCode() {
        val v = AtvFixtures.secretVectors().first()
        val messy = v.code.lowercase().chunked(2).joinToString(" ")
        assertArrayEquals(
            PairingSecret.compute(key(v.client), key(v.server), v.code),
            PairingSecret.compute(key(v.client), key(v.server), messy),
        )
    }

    @Test
    fun onlySixHexDigitsAreACode() {
        assertEquals("4FA1C9", PairingCode.normalize(" 4f a1 c9 "))
        assertNull(PairingCode.normalize("4FA1C"))
        assertNull(PairingCode.normalize("4FA1C90"))
        assertNull(PairingCode.normalize("4FA1CG"))
        assertNull(PairingCode.normalize(""))
    }

    @Test
    fun sanitizeKeepsUppercaseHexUpToSix() {
        assertEquals("4FA1C9", PairingCode.sanitize("4f-a1 c9"))
        assertEquals("ABCDEF", PairingCode.sanitize("abcdef12"))
        assertEquals("", PairingCode.sanitize("xyz!"))
        assertEquals("0", PairingCode.sanitize("0"))
    }

    @Test
    fun hexBytesMatchPythonFromhex() {
        // 65537 -> "0" + "10001" -> 01 00 01
        assertArrayEquals(byteArrayOf(1, 0, 1), PairingSecret.hexBytes(BigInteger.valueOf(65_537)))
        // No sign byte for a value with the top bit set (toByteArray would add 00).
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0x01), PairingSecret.hexBytes(BigInteger.valueOf(0xFF01)))
        // Odd digit count is left-padded instead of failing like Python.
        assertArrayEquals(byteArrayOf(0x0A, 0xBC.toByte()), PairingSecret.hexBytes(BigInteger.valueOf(0xABC)))
    }

    private fun key(name: String) = AtvFixtures.certificate(name).publicKey as RSAPublicKey

    private fun ByteArray.toHex() = joinToString("") { "%02X".format(it.toInt() and 0xFF) }
}
