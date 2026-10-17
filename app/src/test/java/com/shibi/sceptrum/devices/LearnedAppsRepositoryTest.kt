package com.shibi.sceptrum.devices

import androidx.datastore.core.CorruptionException
import com.shibi.sceptrum.data.EncryptedDeviceStoreSerializer
import com.shibi.sceptrum.data.EncryptedLearnedAppsSerializer
import com.shibi.sceptrum.data.LearnedAppsRepository
import com.shibi.sceptrum.diagnostics.ErrorLog
import com.shibi.sceptrum.domain.LearnedApps
import com.shibi.sceptrum.store.DeviceLearnedApps
import com.shibi.sceptrum.store.LearnedApp
import com.shibi.sceptrum.store.LearnedAppsStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The learned-apps store. Most tests run over memory (TestStores, AGENTS.md
 * PR 5 retro: Windows can block back-to-back renames); one round trip runs
 * on disk, encrypted, through a reopen.
 */
class LearnedAppsRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val aead = TestStores.aead()
    private var now = 1_000L
    private val repo = TestStores.memoryLearnedApps(scope, clock = { now })

    @After
    fun tearDown() = scope.cancel()

    private fun apps(deviceId: String): List<LearnedApp> =
        runBlocking { repo.store.first().devices[deviceId]?.apps.orEmpty() }

    @Test
    fun aRepeatedAppIsOneEntryWithItsCountAndTimes() = runBlocking {
        assertTrue(repo.record("mac:01", "org.xbmc.kodi"))
        now = 2_000
        repo.record("mac:01", "org.xbmc.kodi")
        assertEquals(listOf(LearnedApp("org.xbmc.kodi", first_seen_ms = 1_000, last_seen_ms = 2_000, seen_count = 2)), apps("mac:01"))
    }

    @Test
    fun deniedAndInvalidPackagesAreNotStored() = runBlocking {
        assertFalse(repo.record("mac:01", "com.google.android.tvlauncher"))
        assertFalse(repo.record("mac:01", "com.android.systemui"))
        assertFalse(repo.record("mac:01", "not a package"))
        assertFalse("no device, nothing to key by", repo.record("", "org.xbmc.kodi"))
        assertTrue(repo.store.first().devices.isEmpty())
    }

    @Test
    fun theListIsCappedEvictingTheLeastRecentlySeen() = runBlocking {
        for (i in 0..LearnedApps.MAX) {
            now = 1_000L + i
            repo.record("mac:01", "com.example.app$i")
        }
        val names = apps("mac:01").map { it.package_name }
        assertEquals(LearnedApps.MAX, names.size)
        assertFalse("app0 was seen longest ago", "com.example.app0" in names)
        assertTrue("com.example.app${LearnedApps.MAX}" in names)
    }

    @Test
    fun aRemovalSticks() = runBlocking {
        repo.record("mac:01", "org.xbmc.kodi")
        repo.record("mac:01", "tv.pluto.android")
        repo.remove("mac:01", "org.xbmc.kodi")
        repo.record("mac:01", "org.xbmc.kodi")
        assertEquals(listOf("tv.pluto.android"), apps("mac:01").map { it.package_name })
        assertEquals(listOf("org.xbmc.kodi"), repo.store.first().devices["mac:01"]?.removed)
    }

    @Test
    fun eachTvLearnsItsOwnApps() = runBlocking {
        repo.record("mac:01", "org.xbmc.kodi")
        repo.record("mac:02", "tv.pluto.android")
        repo.remove("mac:02", "org.xbmc.kodi")
        repo.record("mac:02", "org.xbmc.kodi")
        assertEquals(listOf("org.xbmc.kodi"), apps("mac:01").map { it.package_name })
        assertEquals("removed on the TCL only", listOf("tv.pluto.android"), apps("mac:02").map { it.package_name })

        repo.forgetDevice("mac:02")
        assertNull(repo.store.first().devices["mac:02"])
        assertEquals(1, apps("mac:01").size)
    }

    @Test
    fun theListenerLearnsInTheBackground() {
        repo.onForegroundApp("mac:01", "org.xbmc.kodi")
        repo.onForegroundApp("mac:01", "com.google.android.apps.tv.launcherx")
        val stored = runBlocking { withTimeout(5_000) { repo.store.first { it.devices["mac:01"] != null } } }
        assertEquals(listOf("org.xbmc.kodi"), stored.devices.getValue("mac:01").apps.map { it.package_name })
    }

    @Test
    fun aFailedWriteIsLoggedAndNotThrown() = runBlocking {
        val errors = ErrorLog(File(tmp.root, "errors.log"), "test", "JVM")
        val store = TestStores.MemoryDataStore(LearnedAppsStore()).apply { failWrites = true }
        val failing = LearnedAppsRepository(store, scope, errors)
        assertFalse(failing.record("mac:01", "org.xbmc.kodi"))
        assertFalse(failing.remove("mac:01", "org.xbmc.kodi"))
        val entries = errors.entries.value
        assertEquals(2, entries.size)
        assertTrue(entries.all { it.step == "Learned apps" })
        assertTrue(entries.last().chain.single().contains("disk full"))
    }

    @Test
    fun learnedAppsSurviveAReopenOnDisk() = runBlocking {
        val file = File(tmp.root, "learned_apps.pb")
        val firstScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val first = LearnedAppsRepository(LearnedAppsRepository.create({ aead }, firstScope) { file }, scope, clock = { 42 })
        // One write per store: on Windows a second write right after can fail to rename over the file (PR 5 retro).
        assertTrue(first.record("mac:01", "org.xbmc.kodi"))
        // AGENTS.md lesson: cancel() returns before the scope is done; join before reopening the file.
        firstScope.coroutineContext[Job]!!.cancelAndJoin()

        assertFalse("the file is sealed", file.readBytes().decodeToString().contains("kodi"))
        val reopenedScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val reopened = LearnedAppsRepository(LearnedAppsRepository.create({ aead }, reopenedScope) { file }, scope)
        assertEquals(listOf(LearnedApp("org.xbmc.kodi", 42, 42, 1)), reopened.store.first().devices.getValue("mac:01").apps)
        reopenedScope.coroutineContext[Job]!!.cancelAndJoin()
    }

    @Test
    fun removalsRoundTripThroughTheSealedFormat() = runBlocking {
        val serializer = EncryptedLearnedAppsSerializer({ aead })
        val store = LearnedAppsStore(
            devices = mapOf(
                "mac:01" to DeviceLearnedApps(apps = listOf(LearnedApp("org.xbmc.kodi", 1, 2, 3)), removed = listOf("tv.pluto.android")),
                "mac:02" to DeviceLearnedApps(removed = listOf("org.xbmc.kodi")),
            ),
        )
        val sealed = ByteArrayOutputStream().also { serializer.writeTo(store, it) }.toByteArray()
        assertEquals(store, serializer.readFrom(ByteArrayInputStream(sealed)))
        // The device store's key context does not open it.
        val asDeviceStore = runCatching { EncryptedDeviceStoreSerializer({ aead }).readFrom(ByteArrayInputStream(sealed)) }
        assertTrue(asDeviceStore.exceptionOrNull() is CorruptionException)
    }

    @Test
    fun aFileFromAnotherKeyIsWipedNotFatal() = runBlocking {
        val file = File(tmp.root, "learned_apps.pb")
        val firstScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        LearnedAppsRepository(LearnedAppsRepository.create({ TestStores.aead() }, firstScope) { file }, scope)
            .record("mac:01", "org.xbmc.kodi")
        firstScope.coroutineContext[Job]!!.cancelAndJoin()

        val otherKeyScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val otherKey = TestStores.aead()
        val reopened = LearnedAppsRepository(LearnedAppsRepository.create({ otherKey }, otherKeyScope) { file }, scope)
        assertTrue(reopened.store.first().devices.isEmpty())
        otherKeyScope.coroutineContext[Job]!!.cancelAndJoin()
    }
}
