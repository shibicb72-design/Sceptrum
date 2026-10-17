package com.shibi.sceptrum.atv

import java.security.interfaces.RSAKey

/**
 * What the owner copies from a pairing error card: enough to tell a TLS
 * problem from a refusal by the TV. Holds no key material, codes or secrets.
 */
data class PairingDiagnostics(
    val failure: PairingFailure,
    val step: PairingStep?,
    val host: String,
    val port: Int,
    /** Raw polo status from the TV, e.g. 400; null when none arrived. */
    val statusCode: Int? = null,
    /** Short description from the driver, e.g. "TV answered 400". */
    val detail: String? = null,
    /** The exception chain, class and message of each, outermost first. */
    val errors: List<String> = emptyList(),
    val keyAlgorithm: String? = null,
    val keyBits: Int? = null,
    val keySource: String? = null,
    val tlsProtocol: String? = null,
    val cipherSuite: String? = null,
) {
    /** One `key: value` line each, in a fixed order. */
    fun report(appVersion: String): String = buildString {
        append("Sceptrum pairing report\n")
        line("app version", appVersion)
        line("error", failure.name)
        line("step", step?.let { "${it.name} (${describe(it)})" } ?: "unknown")
        line("polo status", statusCode?.let { "$it ${statusName(it)}" } ?: "none received")
        line("detail", detail)
        line("device", "$host:$port")
        line("client key", listOfNotNull(keyAlgorithm, keyBits?.let { "$it bit" }, keySource).joinToString(" / ").ifEmpty { null })
        line("tls", listOfNotNull(tlsProtocol, cipherSuite).joinToString(" / ").ifEmpty { "not negotiated" })
        if (errors.isEmpty()) line("exception", "none")
        errors.forEachIndexed { i, e -> line(if (i == 0) "exception" else "caused by", e) }
    }.trimEnd()

    private fun StringBuilder.line(key: String, value: String?) {
        if (value != null) append(key).append(": ").append(value).append('\n')
    }

    companion object {
        /** Each cause once, at most [max] deep, newlines flattened. */
        fun chain(error: Throwable?, max: Int = 6): List<String> {
            val out = mutableListOf<String>()
            val seen = mutableSetOf<Throwable>()
            var e = error
            while (e != null && out.size < max && seen.add(e)) {
                val message = e.message?.replace(Regex("\\s+"), " ")?.take(300)
                out += if (message.isNullOrBlank()) e.javaClass.name else "${e.javaClass.name}: $message"
                e = e.cause
            }
            return out
        }

        fun keyBits(identity: ClientIdentity?): Int? =
            (identity?.certificate?.publicKey as? RSAKey)?.modulus?.bitLength()

        fun statusName(code: Int): String = when (code) {
            200 -> "STATUS_OK"
            400 -> "STATUS_ERROR"
            401 -> "STATUS_BAD_CONFIGURATION"
            402 -> "STATUS_BAD_SECRET"
            else -> "unknown"
        }

        private fun describe(step: PairingStep): String = when (step) {
            PairingStep.Connect -> "TCP connect"
            PairingStep.TlsHandshake -> "TLS handshake"
            PairingStep.PairingRequestAck -> "waiting for PairingRequestAck"
            PairingStep.Options -> "waiting for Options"
            PairingStep.ConfigurationAck -> "waiting for ConfigurationAck"
            PairingStep.SecretAck -> "waiting for SecretAck"
        }
    }
}
