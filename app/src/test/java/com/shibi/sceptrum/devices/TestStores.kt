package com.shibi.sceptrum.devices

import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.PredefinedAeadParameters
import androidx.datastore.core.DataStore
import com.shibi.sceptrum.data.DeviceRepository
import com.shibi.sceptrum.data.LearnedAppsRepository
import com.shibi.sceptrum.diagnostics.ErrorLog
import com.shibi.sceptrum.store.DeviceStore
import com.shibi.sceptrum.store.LearnedAppsStore
import java.io.File
import java.io.IOException
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

    /** A LearnedAppsRepository over memory, for the same reason as [memoryRepository]. */
    fun memoryLearnedApps(
        scope: CoroutineScope,
        errors: ErrorLog? = null,
        clock: () -> Long = System::currentTimeMillis,
        initial: LearnedAppsStore = LearnedAppsStore(),
    ) = LearnedAppsRepository(MemoryDataStore(initial), scope, errors, clock)

    /** A DataStore over memory; [failWrites] makes every update throw, as a full disk would. */
    class MemoryDataStore<T>(initial: T) : DataStore<T> {
        private val state = MutableStateFlow(initial)
        private val lock = Mutex()
        var failWrites: Boolean = false
        override val data: Flow<T> = state

        override suspend fun updateData(transform: suspend (t: T) -> T): T =
            lock.withLock {
                if (failWrites) throw IOException("disk full")
                transform(state.value).also { state.value = it }
            }
    }

    /** Cancel [scope] to release the file before opening another store on it. */
    fun repository(
        aead: Aead = aead(),
        file: File = tempFile(),
        scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    ) = DeviceRepository(DeviceRepository.create({ aead }, scope) { file })
}
