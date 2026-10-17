package com.shibi.sceptrum.devices

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shibi.sceptrum.discovery.TvDiscovery
import com.shibi.sceptrum.domain.DiscoveredTv
import com.shibi.sceptrum.domain.DiscoveryState
import com.shibi.sceptrum.domain.PairingTarget
import com.shibi.sceptrum.store.PairedDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
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
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class DevicesViewModelTest {
    private val discovery = FakeDiscovery()
    private val repository = TestStores.repository()
    private lateinit var vm: DevicesViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        vm = DevicesViewModel(repository, discovery, clock = { 9L })
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun awaitState(predicate: (DevicesUiState) -> Boolean) =
        runBlocking { withTimeout(5_000) { vm.state.first(predicate) } }

    @Test
    fun savedAndDiscoveredAreMerged() {
        runBlocking { repository.savePaired(PairedDevice(id = "mac:01", name = "Projector", host = "192.168.1.50")) }
        discovery.state.value = DiscoveryState(
            devices = listOf(DiscoveredTv("Projector", "192.168.1.50", 6466), DiscoveredTv("TCL", "192.168.1.60", 6466)),
            scanning = true,
        )
        val state = awaitState { it.saved.isNotEmpty() && it.discovered.isNotEmpty() }
        assertTrue(state.saved.single().online)
        assertTrue(state.saved.single().active)
        assertEquals("TCL", state.discovered.single().name)
        assertTrue(state.scanning)
        assertEquals(PairingTarget("TCL", "192.168.1.60", 6466), vm.targetFor(state.discovered.single()))
    }

    @Test
    fun newAddressForASavedTvIsStored() {
        runBlocking { repository.savePaired(PairedDevice(id = "mac:01", name = "Projector", host = "192.168.1.50")) }
        discovery.state.value = DiscoveryState(devices = listOf(DiscoveredTv("Projector", "192.168.1.77", 6466)))
        awaitState { it.saved.singleOrNull()?.host == "192.168.1.77" }
        // Poll with fresh reads: on Windows a read can race DataStore's rename of the written file.
        val deadline = System.currentTimeMillis() + 5_000
        var stored: PairedDevice? = null
        while (stored?.host != "192.168.1.77") {
            check(System.currentTimeMillis() < deadline) { "host never stored, last $stored" }
            stored = runCatching { runBlocking { repository.devices.first().single() } }.getOrNull()
            Thread.sleep(20)
        }
        assertEquals(9L, stored.last_seen_at_ms)
    }

    @Test
    fun manualEntryValidates() {
        vm.onManualChange("192.168.1.x50")
        assertEquals("192.168.1.50", awaitState { it.manual.text.isNotEmpty() }.manual.text)
        assertEquals(PairingTarget("192.168.1.50", "192.168.1.50"), vm.manualTarget())
        assertFalse(awaitState { true }.manual.invalid)

        vm.onManualChange("300.1.1.1")
        assertNull(vm.manualTarget())
        assertTrue(awaitState { it.manual.invalid }.manual.invalid)

        vm.onManualChange("8.8.8.8")
        assertEquals("8.8.8.8", vm.manualTarget()?.host)
        assertTrue(awaitState { it.manual.notLocal }.manual.notLocal)
    }

    @Test
    fun selectAndRefresh() {
        runBlocking {
            repository.savePaired(PairedDevice(id = "a", name = "A", host = "10.0.0.1"))
            repository.savePaired(PairedDevice(id = "b", name = "B", host = "10.0.0.2"))
        }
        vm.select("a")
        assertEquals("A", awaitState { s -> s.saved.any { it.active && it.id == "a" } }.saved.first { it.active }.name)
        vm.refresh()
        assertEquals(1, discovery.scans)
    }

    @Test
    fun renameKeepsTheDeviceAndIgnoresABlankName() {
        runBlocking { repository.savePaired(PairedDevice(id = "mac:01", name = "Projector", host = "192.168.1.50", mac = "AA:BB:CC:01:02:03")) }
        awaitState { it.saved.isNotEmpty() }
        vm.edit("mac:01")
        val edit = awaitState { it.editing != null }.editing!!
        assertEquals(DeviceEdit("mac:01", "Projector", "192.168.1.50", "AA:BB:CC:01:02:03"), edit)

        vm.onRenameChange("   ")
        vm.saveRename()
        assertEquals("blank names are not saved", "   ", awaitState { true }.editing?.draft)

        vm.onRenameChange("Living room projector")
        vm.saveRename()
        val renamed = awaitState { it.saved.singleOrNull()?.name == "Living room projector" }
        assertNull(renamed.editing)
        assertEquals("192.168.1.50", renamed.saved.single().host)
        assertEquals("AA:BB:CC:01:02:03", renamed.saved.single().mac)
    }

    @Test
    fun forgetAsksFirstThenRemovesAndTheNextDeviceBecomesActive() {
        runBlocking {
            repository.savePaired(PairedDevice(id = "a", name = "A", host = "10.0.0.1"))
            repository.savePaired(PairedDevice(id = "b", name = "B", host = "10.0.0.2"))
        }
        awaitState { it.saved.size == 2 }
        vm.edit("b")
        awaitState { it.editing?.id == "b" }
        vm.askForget()
        assertTrue(awaitState { it.editing?.confirmForget == true }.editing!!.confirmForget)
        assertEquals("nothing removed before confirming", 2, awaitState { true }.saved.size)

        vm.confirmForget()
        val after = awaitState { it.saved.size == 1 }
        assertEquals("a", after.saved.single().id)
        assertTrue(after.saved.single().active)
        assertNull(after.editing)
    }

    @Test
    fun forgettingATvAlsoDropsItsLearnedApps() {
        val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined)
        val learned = TestStores.memoryLearnedApps(scope)
        val withLearned = DevicesViewModel(repository, discovery, clock = { 9L }, learnedApps = learned)
        runBlocking {
            repository.savePaired(PairedDevice(id = "a", name = "A", host = "10.0.0.1"))
            learned.record("a", "org.xbmc.kodi")
            learned.record("b", "org.xbmc.kodi")
        }
        withLearned.edit("a")
        runBlocking { withTimeout(5_000) { withLearned.state.first { it.editing?.id == "a" } } }
        withLearned.askForget()
        withLearned.confirmForget()
        val stored = runBlocking { withTimeout(5_000) { learned.store.first { "a" !in it.devices } } }
        assertEquals("other TVs keep theirs", setOf("b"), stored.devices.keys)
    }

    private class FakeDiscovery : TvDiscovery {
        override val state = MutableStateFlow(DiscoveryState())
        var scans = 0

        override fun scan() {
            scans++
            state.update { it.copy(scanning = true) }
        }

        override fun stop() {
            state.update { it.copy(scanning = false) }
        }
    }
}
