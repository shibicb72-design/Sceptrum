package com.shibi.sceptrum.devices

import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.PredefinedAeadParameters
import androidx.datastore.core.DataStore
import com.shibi.sceptrum.data.DeviceRepository
import com.shibi.sceptrum.store.DeviceStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Files

/** Tink's in-memory keyset stands in for the Keystore-wrapped one (plan §11). */
object TestStores {
    fun aead(): Aead {
        AeadConfig.register()
        return KeysetHandle.newBuilder()
            .addEntry(KeysetHandle.generateEntryFromParameters(PredefinedAeadParameters.AES256_GCM).withRandomId().makePrimary())
            .build()
            .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    fun tempFile(): File = Files.createTempDirectory("sceptrum-store").toFile().resolve("devices.pb")

    /**
     * A DeviceRepository over memory, for view-model tests that write often: on
     * Windows, back-to-back DataStore writes can fail to rename over a file the
     * virus scanner still holds. On-disk behaviour is covered in DeviceRepositoryTest.
     */
    fun memoryRepository(initial: DeviceStore = DeviceStore()) = DeviceRepository(MemoryDataStore(initial))

    private class MemoryDataStore(initial: DeviceStore) : DataStore<DeviceStore> {
        private val state = MutableStateFlow(initial)
        private val lock = Mutex()
        override val data: Flow<DeviceStore> = state

        override suspend fun updateData(transform: suspend (t: DeviceStore) -> DeviceStore): DeviceStore =
            lock.withLock { transform(state.value).also { state.value = it } }
    }

    /** Cancel [scope] to release the file before opening another store on it. */
    fun repository(
        aead: Aead = aead(),
        file: File = tempFile(),
        scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    ) = DeviceRepository(DeviceRepository.create({ aead }, scope) { file })
}
