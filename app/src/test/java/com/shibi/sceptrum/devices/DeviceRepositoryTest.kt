package com.shibi.sceptrum.devices

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shibi.sceptrum.data.EncryptedDeviceStoreSerializer
import com.shibi.sceptrum.store.DeviceStore
import com.shibi.sceptrum.store.PairedDevice
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class DeviceRepositoryTest {
    private val tv = PairedDevice(
        id = "mac:AA:BB:CC:DD:EE:01",
        driver = "androidtv",
        name = "Projector",
        host = "192.168.1.50",
        port = 6466,
        pairing_port = 6467,
        mac = "AA:BB:CC:DD:EE:01",
        server_cert_sha256 = ByteArray(32) { it.toByte() }.toByteString(),
        paired_at_ms = 1_000,
        last_seen_at_ms = 1_000,
    )
    private val tcl = tv.copy(id = "mac:AA:BB:CC:DD:EE:02", name = "TCL 75QM6K", host = "192.168.1.60")

    @Test
    fun roundTripSurvivesANewRepositoryOnTheSameFile() = runBlocking {
        val aead = TestStores.aead()
        val file = TestStores.tempFile()
        val first = CoroutineScope(Dispatchers.IO + Job())
        TestStores.repository(aead, file, first).savePaired(tv)
        first.coroutineContext[Job]!!.cancelAndJoin()

        val reopened = TestStores.repository(aead, file)
        assertEquals(listOf(tv), reopened.devices.first())
        assertEquals(tv, reopened.activeDevice.first())
    }

    @Test
    fun fileIsCiphertextNotProtobuf() = runBlocking {
        val file = TestStores.tempFile()
        TestStores.repository(file = file).savePaired(tv)
        val bytes = file.readBytes()
        assertFalse("name must not appear in clear", String(bytes, Charsets.ISO_8859_1).contains("Projector"))
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("192.168.1.50"))
    }

    @Test
    fun savingTheSameIdReplacesAndNewestBecomesActive() = runBlocking {
        val repo = TestStores.repository()
        repo.savePaired(tv)
        repo.savePaired(tcl)
        assertEquals(tcl, repo.activeDevice.first())
        repo.savePaired(tv.copy(host = "192.168.1.51"))
        assertEquals(listOf(tcl.id, tv.id), repo.devices.first().map { it.id })
        assertEquals("192.168.1.51", repo.activeDevice.first()?.host)
    }

    @Test
    fun setActiveForgetAndHostUpdates() = runBlocking {
        val repo = TestStores.repository()
        repo.savePaired(tv)
        repo.savePaired(tcl)
        repo.setActive(tv.id)
        assertEquals(tv.id, repo.activeDevice.first()?.id)
        repo.setActive("unknown")
        assertEquals(tv.id, repo.activeDevice.first()?.id)

        repo.updateHost(tv.id, "192.168.1.77", seenAtMs = 5_000)
        val moved = repo.devices.first().first { it.id == tv.id }
        assertEquals("192.168.1.77", moved.host)
        assertEquals(5_000, moved.last_seen_at_ms)

        repo.forget(tv.id)
        assertEquals(listOf(tcl), repo.devices.first())
        assertEquals("falls back to the first saved device", tcl, repo.activeDevice.first())
        repo.forget(tcl.id)
        assertNull(repo.activeDevice.first())
    }

    /** A file that will not decrypt (other key, restored backup) is wiped, not fatal. */
    @Test
    fun undecryptableStoreIsWiped() = runBlocking {
        val file = TestStores.tempFile()
        val first = CoroutineScope(Dispatchers.IO + Job())
        TestStores.repository(file = file, scope = first).savePaired(tv)
        first.coroutineContext[Job]!!.cancelAndJoin()
        val otherKey = TestStores.repository(TestStores.aead(), file)
        assertTrue(otherKey.devices.first().isEmpty())
        otherKey.savePaired(tcl)
        assertEquals(listOf(tcl), otherKey.devices.first())
    }

    @Test
    fun garbageFileIsWiped() = runBlocking {
        val file = TestStores.tempFile()
        file.parentFile?.mkdirs()
        file.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        assertTrue(TestStores.repository(file = file).devices.first().isEmpty())
    }

    @Test
    fun associatedDataMustMatch() = runBlocking {
        val aead = TestStores.aead()
        val sealed = ByteArrayOutputStream().also {
            EncryptedDeviceStoreSerializer({ aead }, "sceptrum-devices-v1".encodeToByteArray())
                .writeTo(DeviceStore(devices = listOf(tv)), it)
        }.toByteArray()
        val sameAd = EncryptedDeviceStoreSerializer({ aead }, "sceptrum-devices-v1".encodeToByteArray())
        assertEquals(listOf(tv), sameAd.readFrom(ByteArrayInputStream(sealed)).devices)
        val otherAd = EncryptedDeviceStoreSerializer({ aead }, "sceptrum-devices-v2".encodeToByteArray())
        val failure = runCatching { otherAd.readFrom(ByteArrayInputStream(sealed)) }.exceptionOrNull()
        assertTrue("got $failure", failure is androidx.datastore.core.CorruptionException)
    }

    @Test
    fun emptyFileIsAnEmptyStore() = runBlocking {
        val serializer = EncryptedDeviceStoreSerializer({ TestStores.aead() })
        assertEquals(DeviceStore(), serializer.readFrom(ByteArrayInputStream(ByteArray(0))))
    }
}
