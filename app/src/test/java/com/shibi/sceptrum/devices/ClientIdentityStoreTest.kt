package com.shibi.sceptrum.devices

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shibi.sceptrum.data.DeviceRepository
import com.shibi.sceptrum.store.ClientIdentityRecord
import com.shibi.sceptrum.testing.atv.AtvFixtures
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The client identity lives in the encrypted device store (PR 4b). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ClientIdentityStoreTest {
    @Test
    fun createdOnceThenReadBackAfterReopen() = runBlocking {
        val aead = TestStores.aead()
        val file = TestStores.tempFile()
        val firstScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        var created = 0
        val first = TestStores.repository(aead, file, firstScope).clientIdentity { created++; AtvFixtures.client }
        firstScope.coroutineContext[Job]!!.cancelAndJoin()

        val reopened = TestStores.repository(aead, file)
        val again = reopened.clientIdentity { created++; AtvFixtures.stranger }
        assertEquals(1, created)
        assertArrayEquals(first.certificate.encoded, again.certificate.encoded)
        assertArrayEquals(AtvFixtures.client.privateKey.encoded, again.privateKey.encoded)
        assertEquals("software", again.source)
    }

    @Test
    fun undecodableRecordIsReplaced() = runBlocking {
        val aead = TestStores.aead()
        val file = TestStores.tempFile()
        val rawScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val broken = ClientIdentityRecord(private_key_pkcs8 = byteArrayOf(1, 2, 3).toByteString(), certificate_der = byteArrayOf(4).toByteString())
        DeviceRepository.create({ aead }, rawScope) { file }.updateData { it.copy(client_identity = broken) }
        rawScope.coroutineContext[Job]!!.cancelAndJoin()

        val repository = TestStores.repository(aead, file)
        val fresh = repository.clientIdentity { AtvFixtures.stranger }
        assertEquals(AtvFixtures.stranger.certificate, fresh.certificate)
        assertArrayEquals(
            AtvFixtures.stranger.certificate.encoded,
            repository.store.first().client_identity?.certificate_der?.toByteArray(),
        )
    }
}
