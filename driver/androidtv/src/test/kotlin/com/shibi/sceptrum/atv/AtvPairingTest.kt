package com.shibi.sceptrum.atv

import com.shibi.sceptrum.testing.atv.AtvFixtures
import com.shibi.sceptrum.testing.atv.FakeAndroidTvServer
import com.shibi.sceptrum.testing.atv.PairingScript
import java.net.ServerSocket
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AtvPairingTest {
    private val tv = FakeAndroidTvServer()

    @After
    fun tearDown() = tv.close()

    private suspend fun start(timeouts: PairingTimeouts = PairingTimeouts(connectMs = 2_000, stepMs = 2_000)) =
        AtvPairingSession.start(tv.host, AtvFixtures.client, "Sceptrum (Test)", tv.pairingPort, timeouts)

    @Test
    fun pairsWithTheCodeShownOnTheTv() = runBlocking {
        val session = start()
        assertEquals("Sceptrum (Test)", tv.pairingRequests.single().client_name)
        assertEquals("atvremote", tv.pairingRequests.single().service_name)
        assertEquals("Fake TV", session.serverName)
        assertEquals(TvIdentity("SHIELD Android TV", "AA:BB:CC:DD:EE:01"), session.serverIdentity)
        assertArrayEquals(AtvTls.sha256(AtvFixtures.tv.certificate), session.serverSha256)

        val code = requireNotNull(tv.shownCode)
        session.submitCode(code.lowercase())
        assertTrue(tv.isPaired(AtvFixtures.client.certificate))
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
        expectFailure(PairingFailure.Rejected) { start() }
    }

    @Test
    fun tvClosesMidHandshake() = runBlocking {
        tv.pairingScript = PairingScript.DropAfterRequest
        expectFailure(PairingFailure.Rejected) { start() }
    }

    @Test
    fun tvStopsAnswering() = runBlocking {
        tv.pairingScript = PairingScript.Stall
        val started = System.nanoTime()
        expectFailure(PairingFailure.Timeout) { start(PairingTimeouts(connectMs = 1_000, stepMs = 300)) }
        assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000)
    }

    @Test
    fun nothingListening() = runBlocking {
        val closedPort = ServerSocket(0).use { it.localPort }
        expectFailure(PairingFailure.Unreachable) {
            AtvPairingSession.start("127.0.0.1", AtvFixtures.client, "Sceptrum", closedPort)
        }
    }

    private suspend fun expectFailure(failure: PairingFailure, block: suspend () -> Unit) {
        try {
            block()
            fail("expected $failure")
        } catch (e: PairingException) {
            assertEquals(e.toString(), failure, e.failure)
        }
    }
}
