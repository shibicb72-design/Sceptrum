package com.shibi.sceptrum.testing.atv

import com.shibi.sceptrum.atv.ClientIdentity
import com.shibi.sceptrum.atv.StaticClientIdentity
import java.security.KeyFactory
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * Fixed RSA-2048 certificates made once by `tools/atv_fixtures.py`, so tests
 * never generate keys:
 * - `client`, `stranger`: phones (CN=Sceptrum, CN=Stranger)
 * - `tv`: SHIELD-style subject with MAC AA:BB:CC:DD:EE:01
 * - `tv_reset`: the same TV after a factory reset, Nexus-style subject
 */
object AtvFixtures {
    val client: ClientIdentity by lazy { identity("client") }
    val stranger: ClientIdentity by lazy { identity("stranger") }
    val tv: ClientIdentity by lazy { identity("tv") }
    val tvReset: ClientIdentity by lazy { identity("tv_reset") }

    fun identity(name: String): ClientIdentity =
        StaticClientIdentity(certificate(name), privateKey(name), source = "fixture")

    fun certificate(name: String): X509Certificate =
        CertificateFactory.getInstance("X.509")
            .generateCertificate(pem("$name.crt.pem").inputStream()) as X509Certificate

    /** Golden secrets computed with androidtvremote2's own expressions. */
    fun secretVectors(): List<SecretVector> {
        val json = resource("secret_vectors.json").decodeToString()
        return VectorEntry.findAll(json).map { m ->
            SecretVector(
                client = m.groupValues[1],
                server = m.groupValues[2],
                code = m.groupValues[3],
                secretHex = m.groupValues[4],
            )
        }.toList()
    }

    private fun privateKey(name: String) =
        KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(pem("$name.key.pem")))

    private fun pem(file: String): ByteArray {
        val body = resource(file).decodeToString()
            .lineSequence()
            .filterNot { it.startsWith("-----") }
            .joinToString("")
        return Base64.getDecoder().decode(body)
    }

    private fun resource(file: String): ByteArray =
        requireNotNull(AtvFixtures::class.java.getResourceAsStream("/atv/$file")) { "missing fixture $file" }
            .use { it.readBytes() }

    private val VectorEntry = Regex(
        """"client":\s*"(\w+)",\s*"server":\s*"(\w+)",\s*"code":\s*"(\w+)",\s*"secret":\s*"(\w+)"""",
    )
}

data class SecretVector(val client: String, val server: String, val code: String, val secretHex: String)
