package com.shibi.sceptrum.atv

import com.shibi.sceptrum.testing.atv.AtvFixtures
import com.shibi.sceptrum.testing.atv.FakeAndroidTvServer
import com.shibi.sceptrum.testing.atv.PairingScript
import java.net.ServerSocket
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AtvPairingTest {
    private val tv = FakeAndroidTvServer()
    private val timeouts = PairingTimeouts(connectMs = 2_000, stepMs = 2_000)

    @After
    fun tearDown() = tv.close()

    private suspend fun start(timeouts: PairingTimeouts = this.timeouts) =
        AtvPairingSession.start(tv.host, AtvFixtures.client, "Sceptrum (Test)", tv.pairingPort, timeouts)

    /** Pairing with [identity] over a context that allows at most [protocol]. */
    private suspend fun startOver(protocol: String, identity: ClientIdentity) =
        AtvPairingSession.startWith(tv.host, identity, "Sceptrum (Test)", tv.pairingPort, timeouts) { trust ->
            SSLContext.getInstance(protocol).apply { init(arrayOf(SingleIdentityKeyManager(identity)), arrayOf(trust), null) }
        }

    @Test
    fun pairsWithTheCodeShownOnTheTv() = runBlocking {
        val session = start()
        assertEquals("Sceptrum (Test)", tv.pairingRequests.single().client_name)
        assertEquals("atvremote", tv.pairingRequests.single().service_name)
        assertEquals(PoloMessages.options().options, tv.optionsReceived.single())
        assertEquals(PoloMessages.configuration().configuration, tv.configurationsReceived.single())
        assertEquals("Fake TV", session.serverName)
        assertEquals(TvIdentity("SHIELD Android TV", "AA:BB:CC:DD:EE:01"), session.serverIdentity)
        assertArrayEquals(AtvTls.sha256(AtvFixtures.tv.certificate), session.serverSha256)

        val code = requireNotNull(tv.shownCode)
        session.submitCode(code.lowercase())
        assertTrue(tv.isPaired(AtvFixtures.client.certificate))
    }

    @Test
    fun generatedSoftwareIdentityPairsOverTls13AndTls12() = runBlocking {
        val phone = SoftwareClientIdentity.generate()
        for (protocol in listOf("TLSv1.3", "TLSv1.2")) {
            val session = startOver(protocol, phone)
            assertEquals(protocol, session.tlsProtocol)
            assertArrayEquals("the TV saw our certificate", phone.certificate.encoded, tv.clientCertificates.last().encoded)
            session.submitCode(requireNotNull(tv.shownCode))
        }
        assertTrue(tv.isPaired(phone.certificate))
    }

    @Test
    fun keyManagerOffersTheIdentityWhateverTheTvAsksFor() {
        val km = SingleIdentityKeyManager(AtvFixtures.client)
        for (types in listOf<Array<String>?>(null, emptyArray(), arrayOf("RSA"), arrayOf("EC", "RSASSA-PSS"))) {
            assertNotNull(km.chooseClientAlias(types, null, null))
            assertNotNull(km.chooseClientAlias(types, emptyArray(), null))
            assertNotNull(km.chooseEngineClientAlias(types, emptyArray(), null))
        }
        val alias = km.chooseClientAlias(arrayOf("RSA"), emptyArray(), null)
        assertEquals(AtvFixtures.client.certificate, km.getCertificateChain(alias).single())
        assertEquals(AtvFixtures.client.privateKey, km.getPrivateKey(alias))
    }

    @Test
    fun tvRefusesAPhoneWithoutClientCertificateTls12() = runBlocking {
        // TLS 1.2 refuses inside the handshake: an alert, or a reset if the TV closes with our flight unread.
        val e = catchFailure {
            AtvPairingSession.startWith(tv.host, null, "Sceptrum (Test)", tv.pairingPort, timeouts) { trust ->
                SSLContext.getInstance("TLSv1.2").apply { init(emptyArray<KeyManager>(), arrayOf(trust), null) }
            }
        }
        assertTrue(e.toString(), e.failure in setOf(PairingFailure.Handshake, PairingFailure.Dropped))
        assertEquals(PairingStep.TlsHandshake, e.diagnostics?.step)
        assertTrue(e.diagnostics!!.errors.isNotEmpty())
        assertTrue(tv.pairingRequests.isEmpty())
    }

    @Test
    fun tvRefusesAPhoneWithoutClientCertificateTls13() = runBlocking {
        // TLS 1.3 finishes the client handshake before the TV checks the certificate, so the refusal
        // arrives on the first read: an alert, or on Windows a TCP reset (AGENTS.md, PR 2).
        val e = catchFailure {
            AtvPairingSession.startWith(tv.host, null, "Sceptrum (Test)", tv.pairingPort, timeouts) { trust ->
                SSLContext.getInstance("TLSv1.3").apply { init(emptyArray<KeyManager>(), arrayOf(trust), null) }
            }
        }
        assertTrue(e.toString(), e.failure in setOf(PairingFailure.Handshake, PairingFailure.Dropped))
        assertTrue(e.diagnostics?.step in setOf(PairingStep.TlsHandshake, PairingStep.PairingRequestAck))
        assertTrue(tv.pairingRequests.isEmpty())
    }

    @Test
    fun everyNonOkStatusAtEveryStepIsReportedWithItsCode() = runBlocking {
        val steps = listOf(PairingStep.PairingRequestAck, PairingStep.Options, PairingStep.ConfigurationAck, PairingStep.SecretAck)
        for (step in steps) {
            for (status in listOf(400, 401, 402, 500)) {
                tv.failAt = step
                tv.failStatus = status
                val e = catchFailure { start().submitCode(requireNotNull(tv.shownCode)) }
                val expected = if (status == 402) PairingFailure.BadSecret else PairingFailure.Rejected
                assertEquals("$step / $status: $e", expected, e.failure)
                val d = requireNotNull(e.diagnostics)
                assertEquals(step, d.step)
                assertEquals(status, d.statusCode)
                assertNotNull(d.tlsProtocol)
                assertEquals("fixture", d.keySource)
                assertEquals(2048, d.keyBits)
            }
        }
        assertFalse(tv.isPaired(AtvFixtures.client.certificate))
    }

    @Test
    fun wrongCodeIsCaughtLocallyAndTheSessionStaysOpen() = runBlocking {
        val session = start()
        val code = requireNotNull(tv.shownCode)
        val typo = "%02X".format((code.substring(0, 2).toInt(16) + 1) % 256) + code.substring(2)
        expectFailure(PairingFailure.WrongCode) { session.submitCode(typo) }
        assertEquals("nothing was sent to the TV", 0, tv.secretsReceived.get())

        session.submitCode(code)
        assertTrue(tv.isPaired(AtvFixtures.client.certificate))
    }

    @Test
    fun badSecretFromTheTv() = runBlocking {
        tv.pairingScript = PairingScript.BadSecret
        val session = start()
        expectFailure(PairingFailure.BadSecret) { session.submitCode(requireNotNull(tv.shownCode)) }
        assertEquals(1, tv.secretsReceived.get())
        assertFalse(tv.isPaired(AtvFixtures.client.certificate))
    }

    @Test
    fun tvRejectsThePairingRequest() = runBlocking {
        tv.pairingScript = PairingScript.RejectRequest
        val e = expectFailure(PairingFailure.Rejected) { start() }
        assertEquals(400, e.diagnostics?.statusCode)
        assertEquals(PairingStep.PairingRequestAck, e.diagnostics?.step)
    }

    @Test
    fun tvClosesMidHandshakeIsDroppedNotRefused() = runBlocking {
        tv.pairingScript = PairingScript.DropAfterRequest
        val e = expectFailure(PairingFailure.Dropped) { start() }
        assertEquals(null, e.diagnostics?.statusCode)
        assertEquals(PairingStep.PairingRequestAck, e.diagnostics?.step)
    }

    @Test
    fun tvStopsAnswering() = runBlocking {
        tv.pairingScript = PairingScript.Stall
        val started = System.nanoTime()
        val e = expectFailure(PairingFailure.Timeout) { start(PairingTimeouts(connectMs = 1_000, stepMs = 300)) }
        assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000)
        assertEquals(PairingStep.PairingRequestAck, e.diagnostics?.step)
    }

    @Test
    fun probeReadsNameAndMacWithoutPairing() = runBlocking {
        val probe = AtvPairingSession.probe(tv.host, AtvFixtures.client, tv.pairingPort)
        assertEquals(TvIdentity("SHIELD Android TV", "AA:BB:CC:DD:EE:01"), probe.identity)
        assertArrayEquals(AtvTls.sha256(AtvFixtures.tv.certificate), probe.serverSha256)
        assertTrue(tv.pairingRequests.isEmpty())
    }

    @Test
    fun nothingListening() = runBlocking {
        val closedPort = ServerSocket(0).use { it.localPort }
        val e = expectFailure(PairingFailure.Unreachable) {
            AtvPairingSession.start("127.0.0.1", AtvFixtures.client, "Sceptrum", closedPort)
        }
        assertEquals(PairingStep.Connect, e.diagnostics?.step)
        assertEquals("127.0.0.1", e.diagnostics?.host)
        assertEquals(closedPort, e.diagnostics?.port)
    }

    private suspend fun catchFailure(block: suspend () -> Unit): PairingException {
        try {
            block()
        } catch (e: PairingException) {
            return e
        }
        fail("expected a PairingException")
        error("unreachable")
    }

    private suspend fun expectFailure(failure: PairingFailure, block: suspend () -> Unit): PairingException =
        catchFailure(block).also { assertEquals(it.toString(), failure, it.failure) }
}
