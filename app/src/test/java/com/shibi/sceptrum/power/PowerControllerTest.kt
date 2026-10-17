package com.shibi.sceptrum.power

import com.shibi.sceptrum.atv.KeyDirection
import com.shibi.sceptrum.domain.ConnectionStatus
import com.shibi.sceptrum.domain.RemoteKey
import com.shibi.sceptrum.domain.StandbyTip
import com.shibi.sceptrum.remote.FakeSession
import com.shibi.sceptrum.remote.RemoteHaptic
import com.shibi.sceptrum.remote.RemoteViewModel
import com.shibi.sceptrum.session.SessionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The power-on flow (plan §7.3) through the remote view model, in virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class PowerControllerTest {
    private val dispatcher = StandardTestDispatcher()
    private val offline = SessionState(
        deviceName = "XGIMI Horizon S Max",
        status = ConnectionStatus.Offline,
        deviceId = "mac:AA:BB:CC:01:02:03",
        mac = "AA:BB:CC:01:02:03",
    )
    private val session = FakeSession(offline)
    private val wakes = mutableListOf<String>()
    private lateinit var vm: RemoteViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        vm = RemoteViewModel(session) { mac ->
            wakes += mac
            true
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun test(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { block() }

    private val powerKeys get() = session.sent.filter { it.first == 26 }

    @Test
    fun connectedPowerIsAPlainToggle() = test {
        session.state.value = offline.copy(status = ConnectionStatus.Connected, isOn = true)
        vm.tap(RemoteKey.Power)
        runCurrent()
        assertEquals(listOf(26 to KeyDirection.Short), session.sent)
        assertTrue(wakes.isEmpty())
        assertEquals(PowerUiState.Idle, vm.power.value)
    }

    @Test
    fun offlineWithAMacWakesReconnectsAndPressesPowerIfStillInStandby() = test {
        vm.tap(RemoteKey.Power)
        runCurrent()
        assertEquals(listOf("AA:BB:CC:01:02:03"), wakes)
        assertEquals(PowerUiState.Waking("XGIMI Horizon S Max", sentPacket = true), vm.power.value)
        assertEquals("reconnects at once", 1, session.retries)

        advanceTimeBy(2_001)
        assertEquals("and again every 2 s", 2, session.retries)

        // The TV's network came back, but the panel is still in standby.
        session.state.value = offline.copy(status = ConnectionStatus.Connected, isOn = false)
        runCurrent()
        assertEquals(listOf(26 to KeyDirection.Short), powerKeys)
        assertEquals(PowerUiState.Idle, vm.power.value)
    }

    @Test
    fun aTvThatWakesFullyOnIsNotToggledBackOff() = test {
        vm.tap(RemoteKey.Power)
        runCurrent()
        advanceTimeBy(5_000)
        session.state.value = offline.copy(status = ConnectionStatus.Connected, isOn = true)
        runCurrent()
        assertTrue(powerKeys.isEmpty())
        assertEquals(PowerUiState.Idle, vm.power.value)
    }

    @Test
    fun noAnswerIn20sShowsTheDeviceTips() = test {
        vm.tap(RemoteKey.Power)
        runCurrent()
        advanceTimeBy(19_900)
        assertTrue(vm.power.value is PowerUiState.Waking)
        advanceTimeBy(200)
        val failed = vm.power.value as PowerUiState.Failed
        assertEquals("XGIMI Horizon S Max", failed.deviceName)
        assertEquals(listOf(StandbyTip.XgimiWifiOnly, StandbyTip.DeepSleep), failed.tips)
        assertTrue("a retry every 2 s: ${session.retries}", session.retries in 10..11)
        assertTrue(powerKeys.isEmpty())

        vm.dismissPowerTip()
        assertEquals(PowerUiState.Idle, vm.power.value)
    }

    @Test
    fun withoutAMacItOnlyReconnectsAndSaysSo() = test {
        session.state.value = offline.copy(mac = null)
        vm.tap(RemoteKey.Power)
        runCurrent()
        assertTrue(wakes.isEmpty())
        assertEquals(PowerUiState.Waking("XGIMI Horizon S Max", sentPacket = false), vm.power.value)
        advanceTimeBy(20_100)
        val failed = vm.power.value as PowerUiState.Failed
        assertEquals(StandbyTip.NoMac, failed.tips.first())
    }

    @Test
    fun pressingAgainWhileWakingDoesNotStartASecondWake() = test {
        vm.tap(RemoteKey.Power)
        runCurrent()
        vm.tap(RemoteKey.Power)
        runCurrent()
        assertEquals(1, wakes.size)
    }

    @Test
    fun nothingToPowerRejects() = test {
        session.state.value = SessionState()
        val haptics = mutableListOf<RemoteHaptic>()
        val collector = launch { vm.haptics.collect { haptics += it } }
        runCurrent()
        vm.tap(RemoteKey.Power)
        runCurrent()
        assertEquals(listOf(RemoteHaptic.Reject), haptics)
        assertEquals(PowerUiState.Idle, vm.power.value)
        collector.cancel()
    }
}
