package com.shibi.sceptrum.atv

import java.util.Base64
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PairingDiagnosticsTest {
    @Test
    fun reportListsEveryFieldInOrder() {
        val diagnostics = PairingDiagnostics(
            failure = PairingFailure.Rejected,
            step = PairingStep.Options,
            host = "10.0.0.242",
            port = 6467,
            statusCode = 400,
            detail = "TV answered 400",
            errors = emptyList(),
            keyAlgorithm = "RSA",
            keyBits = 2048,
            keySource = "software",
            tlsProtocol = "TLSv1.3",
            cipherSuite = "TLS_AES_128_GCM_SHA256",
        )
        assertEquals(
            """
            Sceptrum pairing report
            app version: 0.0.2
            error: Rejected
            step: Options (waiting for Options)
            polo status: 400 STATUS_ERROR
            detail: TV answered 400
            device: 10.0.0.242:6467
            client key: RSA / 2048 bit / software
            tls: TLSv1.3 / TLS_AES_128_GCM_SHA256
            exception: none
            """.trimIndent(),
            diagnostics.report("0.0.2"),
        )
    }

    @Test
    fun tlsFailureShowsTheExceptionChain() {
        val cause = SSLHandshakeException("Received fatal alert:\n certificate_required")
        val wrapped = java.io.IOException("outer", cause)
        val report = PairingDiagnostics(
            failure = PairingFailure.Handshake,
            step = PairingStep.TlsHandshake,
            host = "10.0.0.242",
            port = 6467,
            errors = PairingDiagnostics.chain(wrapped),
        ).report("1")
        val lines = report.lines()
        assertEquals("polo status: none received", lines.single { it.startsWith("polo status") })
        assertEquals("tls: not negotiated", lines.single { it.startsWith("tls") })
        assertEquals("exception: java.io.IOException: outer", lines.single { it.startsWith("exception") })
        assertEquals(
            "caused by: javax.net.ssl.SSLHandshakeException: Received fatal alert: certificate_required",
            lines.single { it.startsWith("caused by") },
        )
    }

    @Test
    fun noKeyMaterialInTheReport() {
        val identity = SoftwareClientIdentity.generate()
        val e = PairingException(PairingFailure.Handshake, "x")
        val report = PairingDiagnostics(
            failure = PairingFailure.Handshake,
            step = PairingStep.TlsHandshake,
            host = "h",
            port = 1,
            errors = PairingDiagnostics.chain(e),
            keyAlgorithm = identity.privateKey.algorithm,
            keyBits = PairingDiagnostics.keyBits(identity),
            keySource = identity.source,
        ).report("1")
        val b64 = Base64.getEncoder()
        assertFalse(report.contains(b64.encodeToString(identity.privateKey.encoded).take(40)))
        assertFalse(report.contains(identity.publicKey.modulus.toString(16).take(16)))
        assertFalse(report.contains(identity.publicKey.modulus.toString().take(16)))
        assertEquals("client key: RSA / 2048 bit / software", report.lines().single { it.startsWith("client key") })
    }

    @Test
    fun statusNames() {
        assertEquals("STATUS_BAD_CONFIGURATION", PairingDiagnostics.statusName(401))
        assertEquals("STATUS_BAD_SECRET", PairingDiagnostics.statusName(402))
        assertEquals("unknown", PairingDiagnostics.statusName(500))
    }
}
