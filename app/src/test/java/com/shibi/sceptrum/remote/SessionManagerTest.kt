package com.shibi.sceptrum.remote

import com.shibi.sceptrum.atv.AtvTls
import com.shibi.sceptrum.atv.KeyDirection
import com.shibi.sceptrum.atv.RemoteConfig
import com.shibi.sceptrum.atv.VolumeInfo
import com.shibi.sceptrum.atv.proto.remote.RemoteDirection
import com.shibi.sceptrum.devices.TestStores
import com.shibi.sceptrum.diagnostics.ErrorLog
import com.shibi.sceptrum.domain.ConnectionStatus
import com.shibi.sceptrum.session.NetworkEvents
import com.shibi.sceptrum.session.SessionManager
import com.shibi.sceptrum.session.SessionState
import com.shibi.sceptrum.store.PairedDevice
import com.shibi.sceptrum.testing.atv.AtvFixtures
import com.shibi.sceptrum.testing.atv.FakeAndroidTvServer
import com.shibi.sceptrum.testing.atv.ReceivedKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The real session stack (SessionManager, AtvRemoteClient) against the fake TV. */
class SessionManagerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tvs = mutableListOf<FakeAndroidTvServer>()
    private val active = MutableStateFlow<PairedDevice?>(null)
    private val network = FakeNetwork()
    private val config = RemoteConfig(connectTimeoutMs = 2_000, idleTimeoutMs = 2_000, backoffInitialMs = 50, backoffMaxMs = 200)

    private val manager = SessionManager(active, { AtvFixtures.client }, scope, config, network, graceMs = 300)

    @After
    fun tearDown() {
        manager.release()
        tvs.forEach { it.close() }
        scope.cancel()
    }

    private fun tv(name: String = "Projector") = FakeAndroidTvServer(model = name).apply {
        trust(AtvFixtures.client.certificate)
        pingIntervalMs = 100
        tvs += this
    }

    private fun FakeAndroidTvServer.asDevice(name: String) = PairedDevice(
        id = "mac:$name",
        name = name,
        host = host,
        port = remotePort,
        server_cert_sha256 = AtvTls.sha256(AtvFixtures.tv.certificate).toByteString(),
    )

    private fun await(predicate: (SessionState) -> Boolean): SessionState =
        runBlocking { withTimeout(8_000) { manager.state.first(predicate) } }

    private fun eventually(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "condition not met" }
            Thread.sleep(10)
        }
    }

    @Test
    fun connectsOnAcquireAndSendsKeys() {
        val tv = tv()
        active.value = tv.asDevice("Projector")
        await { it.deviceName == "Projector" }
        Thread.sleep(200)
        assertEquals("nothing connects before the remote screen starts", 0, tv.remoteConnections.get())

        manager.acquire()
        val state = await { it.status == ConnectionStatus.Connected && it.volume != null }
        assertEquals(VolumeInfo(23, 100, false), state.volume)
        assertEquals(true, state.isOn)
        assertTrue(manager.inForeground)

        assertTrue(manager.send(24, KeyDirection.Short))
        eventually { tv.keys.isNotEmpty() }
        assertEquals(ReceivedKey(24, RemoteDirection.SHORT), tv.keys.single())
        tv.pushVolume(24)
        await { it.volume?.level == 24 }
    }

    @Test
    fun releaseKeepsTheSessionForTheGracePeriodThenCloses() {
        val tv = tv()
        active.value = tv.asDevice("Projector")
        manager.acquire()
        await { it.status == ConnectionStatus.Connected }
        manager.release()
        assertFalse(manager.inForeground)
        assertEquals(ConnectionStatus.Connected, manager.state.value.status)
        manager.acquire()
        Thread.sleep(500)
        assertEquals("re-acquired inside the grace period", ConnectionStatus.Connected, manager.state.value.status)
        assertEquals(1, tv.remoteConnections.get())

        manager.release()
        await { it.status == ConnectionStatus.Offline }
        assertFalse(manager.send(24, KeyDirection.Short))
        assertTrue(network.stopped)
    }

    @Test
    fun networkChangeReconnectsAtOnce() {
        val tv = tv()
        active.value = tv.asDevice("Projector")
        manager.acquire()
        await { it.status == ConnectionStatus.Connected }
        assertTrue(network.started)

        network.change(dropCurrent = true)
        eventually { tv.remoteConnections.get() == 2 }
        await { it.status == ConnectionStatus.Connected }
    }

    @Test
    fun reconnectsAfterTheTvDropsTheConnection() {
        val tv = tv()
        active.value = tv.asDevice("Projector")
        manager.acquire()
        await { it.status == ConnectionStatus.Connected }
        tv.dropConnections()
        await { it.status == ConnectionStatus.Reconnecting || it.status == ConnectionStatus.Connecting }
        await { it.status == ConnectionStatus.Connected }
        assertEquals(2, tv.remoteConnections.get())
    }

    @Test
    fun switchingDevicesSwapsTheSession() {
        val first = tv("Projector")
        val second = tv("TCL")
        active.value = first.asDevice("Projector")
        manager.acquire()
        await { it.status == ConnectionStatus.Connected && it.deviceName == "Projector" }

        active.value = second.asDevice("TCL")
        await { it.status == ConnectionStatus.Connected && it.deviceName == "TCL" }
        assertTrue(manager.send(19, KeyDirection.Short))
        eventually { second.keys.isNotEmpty() }
        assertTrue(first.keys.isEmpty())
    }

    @Test
    fun retryFromOfflineReconnects() {
        val tv = tv()
        active.value = tv.asDevice("Projector").copy(port = closedPort())
        manager.acquire()
        await { it.status == ConnectionStatus.Offline }
        active.value = tv.asDevice("Projector")
        await { it.status == ConnectionStatus.Connected }
        manager.retry()
        assertEquals(ConnectionStatus.Connected, manager.state.value.status)
    }

    @Test
    fun textAndAppLinksReachTheTvWithItsImeCounters() {
        val tv = tv()
        active.value = tv.asDevice("Projector").copy(mac = "AA:BB:CC:01:02:03")
        manager.acquire()
        await { it.status == ConnectionStatus.Connected }
        assertEquals("AA:BB:CC:01:02:03", manager.state.value.mac)
        assertEquals("Sceptrum Labs", manager.state.value.vendor)

        tv.pushCurrentApp("com.google.android.youtube.tv")
        tv.pushImeField(imeCounter = 6, fieldCounter = 2)
        tv.pushImeShow(value = "", label = "Search")
        val focused = await { it.textField?.label == "Search" && it.currentApp != null }
        assertEquals("com.google.android.youtube.tv", focused.currentApp)

        assertTrue(manager.sendText("never gonna"))
        assertTrue(manager.launchApp("https://www.netflix.com/title"))
        eventually { tv.imeEdits.isNotEmpty() && tv.appLinks.isNotEmpty() }
        val edit = tv.imeEdits.single()
        assertEquals(6, edit.ime_counter)
        assertEquals(2, edit.field_counter)
        assertEquals("never gonna", edit.edit_info.single().text_field_status?.value_)
        assertEquals(listOf("https://www.netflix.com/title"), tv.appLinks.toList())
    }

    @Test
    fun renamingTheActiveDeviceKeepsTheConnection() {
        val tv = tv()
        val device = tv.asDevice("Projector")
        active.value = device
        manager.acquire()
        await { it.status == ConnectionStatus.Connected }

        active.value = device.copy(name = "Living room")
        await { it.deviceName == "Living room" && it.status == ConnectionStatus.Connected }
        Thread.sleep(200)
        assertEquals(1, tv.remoteConnections.get())
        assertTrue(manager.send(23, KeyDirection.Short))
    }

    @Test
    fun noDeviceMeansNoDeviceStatus() {
        assertEquals(ConnectionStatus.NoDevice, manager.state.value.status)
        assertFalse(manager.send(19, KeyDirection.Short))
        assertFalse(manager.sendText("a"))
        assertFalse(manager.launchApp("plex://"))
    }

    @Test
    fun appLinkHangUpIsLoggedAndTheSessionComesBack() {
        val log = errorLog()
        val m = SessionManager(active, { AtvFixtures.client }, scope, config, network, graceMs = 300, errors = log)
        val tv = tv()
        active.value = tv.asDevice("Projector")
        m.acquire()
        runBlocking { withTimeout(8_000) { m.state.first { it.status == ConnectionStatus.Connected } } }
        tv.closeOnAppLink = true
        assertTrue(m.launchApp("https://www.netflix.com/title"))
        eventually { log.entries.value.any { it.step == "Launch app" && it.message.contains("netflix") } }
        tv.closeOnAppLink = false
        runBlocking { withTimeout(8_000) { m.state.first { it.status == ConnectionStatus.Connected } } }
        m.release()
    }

    @Test
    fun launchWhileDisconnectedIsLogged() {
        val log = errorLog()
        val m = SessionManager(active, { AtvFixtures.client }, scope, config, network, graceMs = 300, errors = log)
        assertFalse(m.launchApp("plex://"))
        assertTrue(log.entries.value.single().message.contains("plex://"))
    }

    @Test
    fun droppedConnectionIsLoggedOnce() {
        val log = errorLog()
        val m = SessionManager(active, { AtvFixtures.client }, scope, config, network, graceMs = 300, errors = log)
        val tv = tv()
        active.value = tv.asDevice("Projector")
        m.acquire()
        runBlocking { withTimeout(8_000) { m.state.first { it.status == ConnectionStatus.Connected } } }
        tv.dropConnections()
        eventually { log.entries.value.any { it.step == "Connection" && it.message.startsWith("Connection lost") } }
        runBlocking { withTimeout(8_000) { m.state.first { it.status == ConnectionStatus.Connected } } }
        assertEquals(1, log.entries.value.count { it.step == "Connection" })
        m.release()
    }

    @Test
    fun theTvsForegroundAppsAreLearnedPerDevice() {
        val learned = TestStores.memoryLearnedApps(scope)
        val m = SessionManager(active, { AtvFixtures.client }, scope, config, network, graceMs = 300, foregroundApps = learned)
        val tv = tv()
        active.value = tv.asDevice("Projector")
        m.acquire()
        runBlocking { withTimeout(8_000) { m.state.first { it.status == ConnectionStatus.Connected } } }

        // Home, Kodi, Home, Kodi: each switch is awaited so no state is conflated away.
        for (app in listOf("com.google.android.tvlauncher", "org.xbmc.kodi", "com.google.android.tvlauncher", "org.xbmc.kodi")) {
            tv.pushCurrentApp(app)
            runBlocking { withTimeout(8_000) { m.state.first { it.currentApp == app } } }
            Thread.sleep(100)
        }

        val stored = runBlocking {
            withTimeout(8_000) { learned.store.first { (it.devices["mac:Projector"]?.apps?.firstOrNull()?.seen_count ?: 0) >= 2 } }
        }
        val apps = stored.devices.getValue("mac:Projector").apps
        assertEquals("the launcher is never learned", listOf("org.xbmc.kodi"), apps.map { it.package_name })
        assertEquals(setOf("mac:Projector"), stored.devices.keys)
        m.release()
    }

    @Test
    fun aReconnectReportingTheSameAppDoesNotCountItAgain() {
        val reports = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String>>()
        val m = SessionManager(
            active, { AtvFixtures.client }, scope, config, network, graceMs = 300,
            foregroundApps = { id, app -> reports += id to app },
        )
        val tv = tv()
        active.value = tv.asDevice("Projector")
        m.acquire()
        runBlocking { withTimeout(8_000) { m.state.first { it.status == ConnectionStatus.Connected } } }
        tv.pushCurrentApp("com.netflix.ninja")
        eventually { reports.isNotEmpty() }

        // The TV hangs up (as after an app link) and reports the same app on reconnect.
        tv.dropConnections()
        runBlocking { withTimeout(8_000) { m.state.first { it.currentApp == null } } }
        runBlocking { withTimeout(8_000) { m.state.first { it.status == ConnectionStatus.Connected } } }
        tv.pushCurrentApp("com.netflix.ninja")
        runBlocking { withTimeout(8_000) { m.state.first { it.currentApp == "com.netflix.ninja" } } }
        tv.pushCurrentApp("org.xbmc.kodi")
        eventually { reports.size >= 2 }
        assertEquals(listOf("mac:Projector" to "com.netflix.ninja", "mac:Projector" to "org.xbmc.kodi"), reports.toList())
        m.release()
    }

    @Test
    fun aFailingListenerIsLoggedAndTheSessionCarriesOn() {
        val log = errorLog()
        val m = SessionManager(
            active, { AtvFixtures.client }, scope, config, network, graceMs = 300, errors = log,
            foregroundApps = { _, _ -> throw IllegalStateException("store closed") },
        )
        val tv = tv()
        active.value = tv.asDevice("Projector")
        m.acquire()
        runBlocking { withTimeout(8_000) { m.state.first { it.status == ConnectionStatus.Connected } } }
        tv.pushCurrentApp("org.xbmc.kodi")
        eventually { log.entries.value.any { it.step == "Learned apps" } }
        tv.pushCurrentApp("tv.pluto.android")
        runBlocking { withTimeout(8_000) { m.state.first { it.currentApp == "tv.pluto.android" } } }
        assertEquals(ConnectionStatus.Connected, m.state.value.status)
        m.release()
    }

    private fun errorLog() = ErrorLog(
        kotlin.io.path.createTempDirectory("errors").toFile().resolve("e.log"),
        "0.1.1",
        "Test phone",
    )

    private fun closedPort() = java.net.ServerSocket(0).use { it.localPort }

    private class FakeNetwork : NetworkEvents {
        private var listener: ((Boolean) -> Unit)? = null
        var started = false
        var stopped = false

        override fun start(onChange: (dropCurrent: Boolean) -> Unit) {
            started = true
            listener = onChange
        }

        override fun stop() {
            stopped = true
            listener = null
        }

        fun change(dropCurrent: Boolean) = listener?.invoke(dropCurrent)
    }
}
