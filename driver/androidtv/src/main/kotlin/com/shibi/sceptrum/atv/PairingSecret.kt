package com.shibi.sceptrum.atv

import java.math.BigInteger
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey

/** The 6-character hex code the TV shows during pairing. */
object PairingCode {
    const val LENGTH = 6

    /**
     * What a code field keeps of raw input (typing or paste): hex digits only,
     * uppercased, at most [LENGTH]. "4f a1-c9" becomes "4FA1C9".
     */
    fun sanitize(raw: String): String =
        raw.filter { it.isHexDigit() }.uppercase().take(LENGTH)

    /** Uppercased code with spaces removed, or null unless exactly 6 hex digits. */
    fun normalize(raw: String): String? {
        val code = raw.filterNot { it.isWhitespace() }.uppercase()
        return code.takeIf { it.length == LENGTH && it.all { c -> c.isHexDigit() } }
    }

    private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}

/**
 * The pairing secret (androidtvremote2 `pairing.py`, `async_finish_pairing`):
 * SHA-256 over the client modulus and exponent, the server modulus and
 * exponent, then the last 4 code characters as 2 bytes. The first code byte
 * must equal the first digest byte, which catches almost every typo before
 * anything is sent to the TV.
 */
object PairingSecret {
    fun compute(client: RSAPublicKey, server: RSAPublicKey, code: String): ByteArray {
        val normalized = requireNotNull(PairingCode.normalize(code)) { "pairing code must be 6 hex digits" }
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(hexBytes(client.modulus))
        digest.update(hexBytes(client.publicExponent))
        digest.update(hexBytes(server.modulus))
        digest.update(hexBytes(server.publicExponent))
        digest.update(decodeHex(normalized.substring(2)))
        return digest.digest()
    }

    /** True when the code's first byte matches the digest, as the TV will check. */
    fun checkByteMatches(secret: ByteArray, code: String): Boolean {
        val normalized = PairingCode.normalize(code) ?: return false
        return (secret[0].toInt() and 0xFF) == normalized.substring(0, 2).toInt(16)
    }

    /**
     * Python's `bytes.fromhex(f"{n:X}")`: the uppercase hex digits, not
     * `BigInteger.toByteArray()` (which adds a sign byte). An odd digit count is
     * left-padded with 0, which also gives the reference "0{e:X}" for 65537
     * ("10001" -> 01 00 01).
     */
    fun hexBytes(n: BigInteger): ByteArray {
        val hex = n.toString(16).uppercase()
        return decodeHex(if (hex.length % 2 == 1) "0$hex" else hex)
    }

    private fun decodeHex(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> hex.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
}
