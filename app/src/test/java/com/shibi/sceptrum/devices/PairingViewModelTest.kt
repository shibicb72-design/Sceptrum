package com.shibi.sceptrum.devices

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shibi.sceptrum.atv.PairingDiagnostics
import com.shibi.sceptrum.atv.PairingException
import com.shibi.sceptrum.atv.PairingFailure
import com.shibi.sceptrum.atv.PairingStep
import com.shibi.sceptrum.diagnostics.ErrorLog
import com.shibi.sceptrum.domain.PairingError
import com.shibi.sceptrum.domain.PairingTarget
import com.shibi.sceptrum.domain.PairingUiState
import com.shibi.sceptrum.pairing.PairingAttempt
import com.shibi.sceptrum.pairing.PairingGateway
import com.shibi.sceptrum.pairing.PairingViewModel
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The pairing state machine against a scripted gateway (no sockets). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class PairingViewModelTest {
    private val target = PairingTarget(name = "Projector", host = "192.168.1.50")
    private val gateway = FakeGateway()
    private val repository = TestStores.repository()
    private lateinit var vm: PairingViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        vm = PairingViewModel(gateway, repository, clock = { 42L }, appVersion = "9.9.9")
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun awaitState(predicate: (PairingUiState) -> Boolean): PairingUiState =
        runBlocking { withTimeout(5_000) { vm.state.first(predicate) } }

    @Test
    fun connectingThenCodeEntryThenPairedAndSaved() {
        val gate = CompletableDeferred<Unit>()
        gateway.beforeStart = { gate.await() }
        vm.begin(target)
        assertEquals(PairingUiState.Connecting(target), vm.state.value)
        gate.complete(Unit)
        awaitState { it is PairingUiState.EnterCode }

        vm.onCodeChange("4f a1-c")
        assertEquals("4FA1C", (vm.state.value as PairingUiState.EnterCode).code)
        vm.onCodeChange("4FA1C9")
        val paired = awaitState { it is PairingUiState.Paired } as PairingUiState.Paired

        assertEquals(listOf("4FA1C9"), gateway.attempts.single().submitted)
        assertEquals("mac:AA:BB:CC:DD:EE:01", paired.deviceId)
        val saved = runBlocking { repository.devices.first().single() }
        assertEquals("Projector", saved.name)
        assertEquals("192.168.1.50", saved.host)
        assertEquals(6466, saved.port)
        assertEquals(6467, saved.pairing_port)
        assertEquals("AA:BB:CC:DD:EE:01", saved.mac)
        assertEquals(FakeGateway.PIN.toByteString(), saved.server_cert_sha256)
        assertEquals(42L, saved.paired_at_ms)
        assertTrue(gateway.attempts.single().closed.not())
    }

    @Test
    fun inputIsFilteredToSixUppercaseHex() {
        vm.begin(target)
        awaitState { it is PairingUiState.EnterCode }
        vm.onCodeChange("zz12")
        assertEquals("12", (vm.state.value as PairingUiState.EnterCode).code)
        vm.onCodeChange("12ab")
        assertEquals("12AB", (vm.state.value as PairingUiState.EnterCode).code)
        assertTrue("not submitted before six", gateway.attempts.single().submitted.isEmpty())
    }

    @Test
    fun wrongCodeKeepsTheSessionAndClearsTheField() {
        gateway.onSubmit = { throw PairingException(PairingFailure.WrongCode, "check byte") }
        vm.begin(target)
        awaitState { it is PairingUiState.EnterCode }
        vm.onCodeChange("000000")
        val state = awaitState { (it as? PairingUiState.EnterCode)?.error != null } as PairingUiState.EnterCode
        assertEquals(PairingError.WrongCode, state.error)
        assertEquals("", state.code)
        assertEquals("same TV session, no reconnect", 1, gateway.starts.size)
        assertTrue(!gateway.attempts.single().closed)

        gateway.onSubmit = {}
        vm.onCodeChange("4FA1C9")
        awaitState { it is PairingUiState.Paired }
    }

    @Test
    fun badSecretStartsOverForTheNewCode() {
        gateway.onSubmit = { throw PairingException(PairingFailure.BadSecret, "402") }
        vm.begin(target)
        awaitState { it is PairingUiState.EnterCode }
        vm.onCodeChange("4FA1C9")
        val state = awaitState { (it as? PairingUiState.EnterCode)?.error == PairingError.BadSecret }
        assertEquals("", (state as PairingUiState.EnterCode).code)
        assertEquals(2, gateway.starts.size)
        assertTrue(gateway.attempts.first().closed)
    }

    @Test
    fun startFailuresBecomeErrorCards() {
        for ((failure, error) in listOf(
            PairingFailure.Unreachable to PairingError.Unreachable,
            PairingFailure.Timeout to PairingError.Timeout,
            PairingFailure.Rejected to PairingError.Rejected,
            PairingFailure.Handshake to PairingError.Handshake,
            PairingFailure.Dropped to PairingError.Dropped,
        )) {
            gateway.startFailure = failure
            vm.begin(target)
            val failed = awaitState { it is PairingUiState.Failed } as PairingUiState.Failed
            assertEquals(target, failed.target)
            assertEquals(error, failed.error)
            assertTrue(failed.report, failed.report.contains("error: ${failure.name}"))
        }
    }

    @Test
    fun failedPairingIsWrittenToTheErrorLog() {
        val log = ErrorLog(kotlin.io.path.createTempDirectory("errors").toFile().resolve("e.log"), "9.9.9", "Test phone")
        vm = PairingViewModel(gateway, repository, clock = { 42L }, appVersion = "9.9.9", errors = log)
        gateway.startFailure = PairingFailure.Handshake
        vm.begin(target)
        awaitState { it is PairingUiState.Failed }
        assertEquals(1, log.entries.value.size)
        assertEquals("Pairing", log.entries.value[0].step)
        assertTrue(log.entries.value[0].tv.contains("192.168.1.50"))
        assertTrue(!log.entries.value[0].message.contains("Sceptrum pairing report"))
    }

    @Test
    fun errorCardCarriesTheDriverDiagnostics() {
        gateway.startDiagnostics = PairingDiagnostics(
            failure = PairingFailure.Handshake,
            step = PairingStep.TlsHandshake,
            host = "10.0.0.242",
            port = 6467,
            errors = listOf("javax.net.ssl.SSLHandshakeException: certificate_required"),
            keyAlgorithm = "RSA",
            keyBits = 2048,
            keySource = "software",
        )
        gateway.startFailure = PairingFailure.Handshake
        vm.begin(target)
        val failed = awaitState { it is PairingUiState.Failed } as PairingUiState.Failed
        assertEquals(PairingError.Handshake, failed.error)
        assertEquals(gateway.startDiagnostics!!.report("9.9.9"), failed.report)
        assertTrue(failed.report.contains("app version: 9.9.9"))
        assertTrue(failed.report.contains("device: 10.0.0.242:6467"))
    }

    @Test
    fun failureWithoutDiagnosticsStillNamesTheTarget() {
        gateway.startFailure = PairingFailure.Timeout
        vm.begin(target)
        val report = (awaitState { it is PairingUiState.Failed } as PairingUiState.Failed).report
        assertTrue(report, report.contains("device: 192.168.1.50:6467"))
        assertTrue(report, report.contains("error: Timeout"))
    }

    @Test
    fun timeoutWhileSubmittingIsAnErrorCardAndRetryStartsAgain() {
        gateway.onSubmit = { throw PairingException(PairingFailure.Timeout, "no ack") }
        vm.begin(target)
        awaitState { it is PairingUiState.EnterCode }
        vm.onCodeChange("4FA1C9")
        assertEquals(PairingError.Timeout, (awaitState { it is PairingUiState.Failed } as PairingUiState.Failed).error)
        assertTrue(gateway.attempts.single().closed)

        gateway.onSubmit = {}
        vm.retry()
        awaitState { it is PairingUiState.EnterCode }
        assertEquals(2, gateway.starts.size)
    }

    @Test
    fun cancelClosesTheTvSession() {
        vm.begin(target)
        awaitState { it is PairingUiState.EnterCode }
        vm.cancel()
        assertEquals(PairingUiState.Idle, vm.state.value)
        assertTrue(gateway.attempts.single().closed)
    }

    @Test
    fun manualAddressTakesTheTvName() {
        vm.begin(PairingTarget(name = "192.168.1.50", host = "192.168.1.50"))
        val state = awaitState { it is PairingUiState.EnterCode } as PairingUiState.EnterCode
        assertEquals("SHIELD Android TV", state.target.name)
    }

    private class FakeGateway : PairingGateway {
        val starts = mutableListOf<PairingTarget>()
        val attempts = mutableListOf<FakeAttempt>()
        var startFailure: PairingFailure? = null
        var startDiagnostics: PairingDiagnostics? = null
        var beforeStart: suspend () -> Unit = {}
        var onSubmit: (String) -> Unit = {}

        override suspend fun start(target: PairingTarget): PairingAttempt {
            starts += target
            beforeStart()
            startFailure?.let { throw PairingException(it, "scripted", diagnostics = startDiagnostics) }
            return FakeAttempt { onSubmit(it) }.also { attempts += it }
        }

        companion object {
            val PIN = ByteArray(32) { 7 }
        }
    }

    private class FakeAttempt(private val onSubmit: (String) -> Unit) : PairingAttempt {
        val submitted = mutableListOf<String>()
        var closed = false
        override val tvName = "SHIELD Android TV"
        override val mac = "AA:BB:CC:DD:EE:01"
        override val serverSha256 = FakeGateway.PIN

        override suspend fun submit(code: String) {
            submitted += code
            onSubmit(code)
        }

        override fun close() {
            closed = true
        }
    }
}
