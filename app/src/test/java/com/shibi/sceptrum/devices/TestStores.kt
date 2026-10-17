package com.shibi.sceptrum.devices

import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.PredefinedAeadParameters
import com.shibi.sceptrum.data.DeviceRepository
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

    /** Cancel [scope] to release the file before opening another store on it. */
    fun repository(
        aead: Aead = aead(),
        file: File = tempFile(),
        scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    ) = DeviceRepository(DeviceRepository.create({ aead }, scope) { file })
}
