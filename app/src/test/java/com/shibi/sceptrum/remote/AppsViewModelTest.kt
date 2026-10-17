package com.shibi.sceptrum.remote

import com.shibi.sceptrum.devices.TestStores
import com.shibi.sceptrum.domain.AppShortcutError
import com.shibi.sceptrum.domain.AppShortcuts
import com.shibi.sceptrum.domain.ConnectionStatus
import com.shibi.sceptrum.session.SessionState
import com.shibi.sceptrum.store.AppShortcut
import com.shibi.sceptrum.store.PairedDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The apps grid against an in-memory device store and a fake session. */
@OptIn(ExperimentalCoroutinesApi::class)
class AppsViewModelTest {
    private val repository = TestStores.memoryRepository()
    private val session = FakeSession(SessionState("Projector", ConnectionStatus.Connected))
    private lateinit var vm: AppsViewModel
    private val collector = CoroutineScope(Dispatchers.Unconfined)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        vm = AppsViewModel(repository, session)
        // Keep the WhileSubscribed state hot, as the screen does.
        collector.launch { vm.state.collect {} }
    }

    @After
    fun tearDown() {
        collector.cancel()
        Dispatchers.resetMain()
    }

    private fun awaitState(predicate: (AppsUiState) -> Boolean): AppsUiState =
        runBlocking { withTimeout(5_000) { vm.state.first(predicate) } }

    private fun pair() = runBlocking { repository.savePaired(PairedDevice(id = "mac:01", name = "Projector", host = "192.168.1.50")) }

    @Test
    fun noDeviceShowsTheDefaultsReadOnly() {
        val state = awaitState { true }
        assertEquals(AppShortcuts.defaults, state.shortcuts)
        assertFalse(state.canEdit)
        vm.setEditing(true)
        assertFalse(awaitState { true }.editing)
    }

    @Test
    fun launchingSendsTheTileLinkAndRejectsWhenOffline() {
        vm.launch(AppShortcuts.defaults[1])
        assertEquals(listOf("https://www.netflix.com/title"), session.links)

        val rejects = mutableListOf<Unit>()
        val job = collector.launch { vm.rejects.collect { rejects += it } }
        session.connected = false
        vm.launch(AppShortcuts.defaults[0])
        assertEquals(1, rejects.size)
        job.cancel()
    }

    @Test
    fun addReorderRemoveAndResetPersistPerDevice() {
        pair()
        awaitState { it.canEdit }
        vm.setEditing(true)
        vm.onLinkChange("com.spotify.tv.android")
        vm.submit()
        val added = awaitState { s -> s.shortcuts.any { it.label == "Spotify" } }
        assertEquals(AppShortcut("Spotify", "market://launch?id=com.spotify.tv.android"), added.shortcuts.last())
        assertEquals("", added.form.link)

        vm.moveUp(added.shortcuts.lastIndex)
        awaitState { it.shortcuts.getOrNull(5)?.label == "Spotify" }
        vm.remove(0)
        val edited = awaitState { it.shortcuts.firstOrNull()?.label == "Netflix" }
        assertEquals(listOf("Netflix", "Prime Video", "Disney+", "Plex", "Spotify", "Settings"), edited.shortcuts.map { it.label })

        val stored = runBlocking { repository.devices.first().single() }
        assertTrue(stored.app_shortcuts_edited)
        assertEquals(edited.shortcuts, stored.app_shortcuts)
    }

    @Test
    fun invalidAndDuplicateInputIsExplained() {
        pair()
        awaitState { it.canEdit }
        vm.setEditing(true)
        vm.onLinkChange("not a link")
        vm.submit()
        assertEquals(AppShortcutError.Invalid, awaitState { it.form.error != null }.form.error)
        vm.onLinkChange("https://www.youtube.com")
        assertNull(awaitState { true }.form.error)
        vm.submit()
        assertEquals(AppShortcutError.Duplicate, awaitState { it.form.error != null }.form.error)
    }

    @Test
    fun theTvsCurrentAppCanBeAddedOnce() {
        pair()
        session.state.value = session.state.value.copy(currentApp = "org.xbmc.kodi")
        val offered = awaitState { it.currentApp != null }
        assertEquals(AppShortcut("Kodi", "market://launch?id=org.xbmc.kodi"), offered.currentApp)
        vm.addCurrentApp()
        val after = awaitState { s -> s.shortcuts.any { it.label == "Kodi" } }
        assertNull("already in the grid: no longer offered", after.currentApp)

        vm.resetToDefaults()
        assertEquals(AppShortcuts.defaults, awaitState { it.shortcuts == AppShortcuts.defaults }.shortcuts)
    }
}
