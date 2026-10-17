package com.shibi.sceptrum.remote

import com.shibi.sceptrum.atv.ImeField
import com.shibi.sceptrum.atv.KeyDirection
import com.shibi.sceptrum.domain.ConnectionStatus
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

/** The keyboard bar against a fake session, in virtual time (plan §3.2). */
@OptIn(ExperimentalCoroutinesApi::class)
class KeyboardViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val connected = SessionState("TV", ConnectionStatus.Connected, currentApp = "com.google.android.youtube.tv")
    private val session = FakeSession(connected)
    private val keyPresses = MutableStateFlow(false)
    private lateinit var vm: KeyboardViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        vm = KeyboardViewModel(session, keyPresses)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun test(block: suspend TestScope.() -> Unit) = runTest(dispatcher) {
        runCurrent()
        block()
    }

    private fun TestScope.type(vararg values: String) {
        values.forEach {
            vm.onTextChange(it)
            advanceTimeBy(50)
        }
    }

    @Test
    fun typingSendsTheWholeValueOnceTheTypistPauses() = test {
        type("c", "ca", "cat")
        assertTrue("nothing inside the 150 ms debounce", session.texts.isEmpty())
        advanceTimeBy(151)
        assertEquals(listOf("cat"), session.texts)

        vm.backspace()
        advanceTimeBy(151)
        assertEquals("backspace is a shorter value", listOf("cat", "ca"), session.texts)
        assertEquals("ca", vm.state.value.text)
    }

    @Test
    fun clearingTheFieldSendsDeletesBecauseAnEmptyEditIsRefused() = test {
        type("dog")
        advanceTimeBy(151)
        vm.clear()
        advanceTimeBy(151)
        assertEquals(listOf("dog"), session.texts)
        assertEquals(List(3) { 67 to KeyDirection.Short }, session.sent)
    }

    @Test
    fun backspaceOnAnEmptyBarDeletesOnTheTv() = test {
        vm.backspace()
        runCurrent()
        assertEquals(listOf(67 to KeyDirection.Short), session.sent)
    }

    @Test
    fun enterFlushesTheTextThenSendsEnterAndEmptiesTheBar() = test {
        type("news")
        vm.enter()
        runCurrent()
        assertEquals(listOf("news"), session.texts)
        assertEquals(listOf(66 to KeyDirection.Short), session.sent)
        assertEquals("", vm.state.value.text)
        advanceTimeBy(1_000)
        assertEquals("the debounced send was cancelled", listOf("news"), session.texts)
    }

    @Test
    fun keyPressFallbackTypesKeyEventsForTheDifference() = test {
        keyPresses.value = true
        runCurrent()
        assertTrue(vm.state.value.keyPresses)
        type("ab")
        advanceTimeBy(151)
        type("ac")
        advanceTimeBy(151)
        assertTrue(session.texts.isEmpty())
        assertEquals(listOf(29, 30, 67, 31), session.sent.map { it.first })
    }

    @Test
    fun aNewTvFieldOpensTheBarWithItsTextAndLabel() = test {
        val opens = mutableListOf<Unit>()
        val collector = launch { vm.openRequests.collect { opens += it } }
        runCurrent()
        assertFalse(vm.state.value.fieldActive)

        session.state.value = connected.copy(textField = ImeField(3, 7, value = "lo-fi", label = "Search"))
        runCurrent()
        assertEquals(1, opens.size)
        assertTrue(vm.state.value.fieldActive)
        assertEquals("lo-fi", vm.state.value.text)
        assertEquals("Search", vm.state.value.fieldLabel)
        assertEquals("com.google.android.youtube.tv", vm.state.value.app)

        // The TV echoing our own edit (same counters) does not reopen or reset the bar.
        type("lo-fi beats")
        advanceTimeBy(151)
        session.state.value = connected.copy(textField = ImeField(3, 7, value = "lo-fi beats", label = "Search"))
        runCurrent()
        assertEquals(1, opens.size)
        assertEquals(listOf("lo-fi beats"), session.texts)
        collector.cancel()
    }

    @Test
    fun notConnectedRejectsAndRetriesTheSameTextLater() = test {
        val rejects = mutableListOf<Unit>()
        val collector = launch { vm.rejects.collect { rejects += it } }
        runCurrent()
        session.connected = false
        session.state.value = connected.copy(status = ConnectionStatus.Reconnecting)
        type("abc")
        advanceTimeBy(151)
        assertEquals(1, rejects.size)
        assertFalse(vm.state.value.connected)

        session.connected = true
        type("abcd")
        advanceTimeBy(151)
        assertEquals(listOf("abcd"), session.texts)
        collector.cancel()
    }
}
