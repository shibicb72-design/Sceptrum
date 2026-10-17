package com.shibi.sceptrum.devices

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shibi.sceptrum.atv.AtvTls
import com.shibi.sceptrum.atv.PairingStep
import com.shibi.sceptrum.atv.PairingTimeouts
import com.shibi.sceptrum.data.StoredClientIdentityProvider
import com.shibi.sceptrum.domain.PairingError
import com.shibi.sceptrum.domain.PairingTarget
import com.shibi.sceptrum.domain.PairingUiState
import com.shibi.sceptrum.pairing.AtvPairingGateway
import com.shibi.sceptrum.pairing.PairingViewModel
import com.shibi.sceptrum.testing.atv.AtvFixtures
import com.shibi.sceptrum.testing.atv.FakeAndroidTvServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The real gateway and driver against the fake TV: pairing saves the TV's pin and MAC. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class PairingEndToEndTest {
    private val tv = FakeAndroidTvServer()
    private val repository = TestStores.repository()
    private val gateway = AtvPairingGateway({ AtvFixtures.client }, "Sceptrum (Test)", PairingTimeouts(2_000, 2_000))
    private lateinit var vm: PairingViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        vm = PairingViewModel(gateway, repository)
    }

    @After
    fun tearDown() {
        tv.close()
        Dispatchers.resetMain()
    }

    private fun awaitState(predicate: (PairingUiState) -> Boolean) =
        runBlocking { withTimeout(10_000) { vm.state.first(predicate) } }

    @Test
    fun pairsAndStoresThePinAndMac() {
        vm.begin(PairingTarget(name = tv.host, host = tv.host, pairingPort = tv.pairingPort))
        val entry = awaitState { it is PairingUiState.EnterCode } as PairingUiState.EnterCode
        assertEquals("SHIELD Android TV", entry.target.name)

        val code = requireNotNull(tv.shownCode)
        val typo = "%02X".format((code.substring(0, 2).toInt(16) + 1) % 256) + code.substring(2)
        vm.onCodeChange(typo)
        assertEquals(PairingError.WrongCode, (awaitState { (it as? PairingUiState.EnterCode)?.error != null } as PairingUiState.EnterCode).error)
        assertEquals(0, tv.secretsReceived.get())

        vm.onCodeChange(code)
        awaitState { it is PairingUiState.Paired }
        assertTrue(tv.isPaired(AtvFixtures.client.certificate))
        val saved = runBlocking { repository.devices.first().single() }
        assertEquals("mac:AA:BB:CC:DD:EE:01", saved.id)
        assertEquals("AA:BB:CC:DD:EE:01", saved.mac)
        assertEquals(AtvTls.sha256(AtvFixtures.tv.certificate).toByteString(), saved.server_cert_sha256)
    }

    @Test
    fun storedSoftwareIdentityIsCreatedOncePresentedAndReused() {
        val provider = StoredClientIdentityProvider(repository)
        val pairing = PairingViewModel(AtvPairingGateway(provider, "Sceptrum (Test)", PairingTimeouts(2_000, 2_000)), repository)
        pairing.begin(PairingTarget(name = "TV", host = tv.host, pairingPort = tv.pairingPort))
        runBlocking { withTimeout(20_000) { pairing.state.first { it is PairingUiState.EnterCode } } }
        pairing.onCodeChange(requireNotNull(tv.shownCode))
        runBlocking { withTimeout(10_000) { pairing.state.first { it is PairingUiState.Paired } } }

        val mine = provider.identity()
        assertEquals("software", mine.source)
        assertArrayEquals(mine.certificate.encoded, tv.clientCertificates.single().encoded)
        assertTrue(tv.isPaired(mine.certificate))
        val stored = runBlocking { repository.store.first() }.client_identity
        assertArrayEquals(mine.certificate.encoded, stored?.certificate_der?.toByteArray())
        assertEquals("one record, next start reads it", mine.certificate, StoredClientIdentityProvider(repository) { error("must not regenerate") }.identity().certificate)
        pairing.cancel()
    }

    @Test
    fun tvErrorStatusShowsTheRejectedCardWithTheCode() {
        tv.failAt = PairingStep.Options
        tv.failStatus = 401
        vm.begin(PairingTarget(name = "TV", host = tv.host, pairingPort = tv.pairingPort))
        val failed = awaitState { it is PairingUiState.Failed } as PairingUiState.Failed
        assertEquals(PairingError.Rejected, failed.error)
        assertTrue(failed.report, failed.report.contains("polo status: 401 STATUS_BAD_CONFIGURATION"))
        assertTrue(failed.report, failed.report.contains("step: Options (waiting for Options)"))
        assertTrue(failed.report, failed.report.contains("client key: RSA / 2048 bit / fixture"))
    }

    @Test
    fun unreachableTv() {
        val port = tv.pairingPort
        tv.close()
        vm.begin(PairingTarget(name = "TV", host = tv.host, pairingPort = port))
        assertEquals(PairingError.Unreachable, (awaitState { it is PairingUiState.Failed } as PairingUiState.Failed).error)
    }
}
