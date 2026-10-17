package com.shibi.sceptrum.remote

import com.shibi.sceptrum.atv.KeyDirection
import com.shibi.sceptrum.atv.VolumeInfo
import com.shibi.sceptrum.domain.ConnectionStatus
import com.shibi.sceptrum.domain.RemoteKey
import com.shibi.sceptrum.session.RemoteSession
import com.shibi.sceptrum.session.SessionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Key codes, directions and timing against a fake session, in virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val session = FakeSession()
    private lateinit var vm: RemoteViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        vm = RemoteViewModel(session)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun test(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { block() }

    @Test
    fun tapsSendShortWithTheKeyEventCode() = test {
        listOf(RemoteKey.Back, RemoteKey.Power, RemoteKey.Mute, RemoteKey.Input, RemoteKey.PlayPause, RemoteKey.digit(7))
            .forEach(vm::tap)
        assertEquals(
            listOf(4, 26, 164, 178, 85, 14).map { it to KeyDirection.Short },
            session.sent,
        )
    }

    @Test
    fun holdingADpadArrowRepeatsAfter400msThenEvery120ms() = test {
        vm.press(RemoteKey.Up)
        runCurrent()
        assertEquals(1, session.sent.size)
        advanceTimeBy(399)
        assertEquals("no repeat before 400 ms", 1, session.sent.size)
        advanceTimeBy(2)
        assertEquals(2, session.sent.size)
        advanceTimeBy(120)
        assertEquals(3, session.sent.size)
        advanceTimeBy(240)
        assertEquals(5, session.sent.size)
        vm.release(RemoteKey.Up)
        advanceTimeBy(1_000)
        assertEquals(5, session.sent.size)
        assertTrue(session.sent.all { it == 19 to KeyDirection.Short })
    }

    @Test
    fun quickTapOfAnArrowSendsOnce() = test {
        vm.press(RemoteKey.Right)
        advanceTimeBy(100)
        vm.release(RemoteKey.Right)
        advanceTimeBy(1_000)
        assertEquals(listOf(22 to KeyDirection.Short), session.sent)
    }

    @Test
    fun volumeRepeatsWithATickEachStep() = test {
        val haptics = mutableListOf<RemoteHaptic>()
        val collector = launch { vm.haptics.collect { haptics += it } }
        runCurrent()
        vm.press(RemoteKey.VolumeDown)
        advanceTimeBy(400 + 120 + 1)
        vm.release(RemoteKey.VolumeDown)
        runCurrent()
        assertEquals(List(3) { 25 to KeyDirection.Short }, session.sent)
        assertEquals(listOf(RemoteHaptic.Tick, RemoteHaptic.Tick), haptics)
        collector.cancel()
    }

    @Test
    fun okTapIsShortOkHoldIsStartAndEndLong() = test {
        vm.press(RemoteKey.Ok)
        advanceTimeBy(200)
        vm.release(RemoteKey.Ok)
        assertEquals(listOf(23 to KeyDirection.Short), session.sent)

        session.sent.clear()
        vm.press(RemoteKey.Ok)
        advanceTimeBy(501)
        assertEquals(listOf(23 to KeyDirection.StartLong), session.sent)
        advanceTimeBy(2_000)
        vm.release(RemoteKey.Ok)
        assertEquals(listOf(23 to KeyDirection.StartLong, 23 to KeyDirection.EndLong), session.sent)
    }

    @Test
    fun homeLongPress() = test {
        vm.press(RemoteKey.Home)
        advanceTimeBy(600)
        vm.release(RemoteKey.Home)
        assertEquals(listOf(3 to KeyDirection.StartLong, 3 to KeyDirection.EndLong), session.sent)
    }

    @Test
    fun disconnectedKeysRejectAndDoNotRepeat() = test {
        session.connected = false
        val haptics = mutableListOf<RemoteHaptic>()
        val collector = launch { vm.haptics.collect { haptics += it } }
        runCurrent()
        vm.press(RemoteKey.Up)
        advanceTimeBy(2_000)
        vm.tap(RemoteKey.Back)
        runCurrent()
        assertEquals(listOf(19 to KeyDirection.Short, 4 to KeyDirection.Short), session.attempts)
        assertEquals(listOf(RemoteHaptic.Reject, RemoteHaptic.Reject), haptics)
        collector.cancel()
    }

    @Test
    fun repeatStopsWhenTheConnectionDrops() = test {
        vm.press(RemoteKey.VolumeUp)
        advanceTimeBy(401)
        session.connected = false
        advanceTimeBy(1_000)
        assertEquals(2, session.sent.size)
        assertEquals(3, session.attempts.size)
    }

    @Test
    fun hardwareVolumeOnlyWhenConnected() = test {
        session.state.value = SessionState("TV", ConnectionStatus.Connected, VolumeInfo(3, 100, false), true)
        assertTrue(vm.hardwareVolume(RemoteKey.VolumeUp))
        session.state.value = session.state.value.copy(status = ConnectionStatus.Reconnecting)
        assertFalse(vm.hardwareVolume(RemoteKey.VolumeUp))
        assertEquals(listOf(24 to KeyDirection.Short), session.sent)
    }

    @Test
    fun retryReachesTheSession() = test {
        vm.retry()
        assertEquals(1, session.retries)
    }

    private class FakeSession : RemoteSession {
        override val state = MutableStateFlow(SessionState("TV", ConnectionStatus.Connected))
        var connected = true
        val sent = mutableListOf<Pair<Int, KeyDirection>>()
        val attempts = mutableListOf<Pair<Int, KeyDirection>>()
        var retries = 0

        override fun send(keyCode: Int, direction: KeyDirection): Boolean {
            attempts += keyCode to direction
            if (connected) sent += keyCode to direction
            return connected
        }

        override fun retry() {
            retries++
        }
    }
}
