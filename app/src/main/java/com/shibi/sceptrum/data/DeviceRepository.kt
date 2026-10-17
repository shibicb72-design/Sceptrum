package com.shibi.sceptrum.data

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.shibi.sceptrum.atv.ClientIdentity
import com.shibi.sceptrum.atv.SoftwareClientIdentity
import com.shibi.sceptrum.store.ClientIdentityRecord
import com.shibi.sceptrum.store.AppShortcut
import com.shibi.sceptrum.store.DeviceStore
import com.shibi.sceptrum.store.PairedDevice
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import okio.ByteString.Companion.toByteString

/**
 * Paired devices, the active one and the phone's TLS client identity, in a
 * single DataStore file sealed with a Tink AES-256-GCM key whose own
 * key-encryption key lives in the Android Keystore.
 */
class DeviceRepository(private val dataStore: DataStore<DeviceStore>) {
    val store: Flow<DeviceStore> = dataStore.data

    val devices: Flow<List<PairedDevice>> = dataStore.data.map { it.devices }

    /** The chosen device, else the first saved one. */
    val activeDevice: Flow<PairedDevice?> = dataStore.data.map { it.active() }

    /** Saves or replaces (same id) a freshly paired device and makes it active. */
    suspend fun savePaired(device: PairedDevice) {
        dataStore.updateData { store ->
            store.copy(
                devices = store.devices.filterNot { it.id == device.id } + device,
                active_device_id = device.id,
            )
        }
    }

    suspend fun setActive(id: String) {
        dataStore.updateData { store ->
            if (store.devices.any { it.id == id }) store.copy(active_device_id = id) else store
        }
    }

    suspend fun forget(id: String) {
        dataStore.updateData { store ->
            store.copy(
                devices = store.devices.filterNot { it.id == id },
                active_device_id = store.active_device_id.takeUnless { it == id }.orEmpty(),
            )
        }
    }

    /** Same TV, new DHCP address: update silently (plan §4.4). */
    suspend fun updateHost(id: String, host: String, seenAtMs: Long) {
        dataStore.updateData { store ->
            store.copy(
                devices = store.devices.map {
                    if (it.id == id && it.host != host) it.copy(host = host, last_seen_at_ms = seenAtMs) else it
                },
            )
        }
    }

    /** A blank name is ignored. */
    suspend fun rename(id: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        update(id) { it.copy(name = trimmed) }
    }

    /** Saves the apps grid in this order; from now on it replaces the defaults. */
    suspend fun setAppShortcuts(id: String, shortcuts: List<AppShortcut>) {
        update(id) { it.copy(app_shortcuts = shortcuts, app_shortcuts_edited = true) }
    }

    /** Back to the built-in grid. */
    suspend fun resetAppShortcuts(id: String) {
        update(id) { it.copy(app_shortcuts = emptyList(), app_shortcuts_edited = false) }
    }

    /** "Show channel buttons" for this device (docs/channel-buttons.md: the TV cannot tell us). */
    suspend fun setShowChannelButtons(id: String, show: Boolean) {
        update(id) { it.copy(hide_channel_buttons = !show) }
    }

    private suspend fun update(id: String, change: (PairedDevice) -> PairedDevice) {
        dataStore.updateData { store ->
            store.copy(devices = store.devices.map { if (it.id == id) change(it) else it })
        }
    }

    suspend fun updateFeatures(id: String, features: Int) {
        dataStore.updateData { store ->
            store.copy(devices = store.devices.map { if (it.id == id) it.copy(features = features) else it })
        }
    }

    /**
     * The stored client identity, or a new one from [create] saved in the same
     * transaction. A record that no longer decodes is replaced.
     */
    suspend fun clientIdentity(create: () -> ClientIdentity): ClientIdentity {
        var result: ClientIdentity? = null
        dataStore.updateData { store ->
            val stored = store.client_identity?.let { record ->
                runCatching {
                    SoftwareClientIdentity.decode(record.private_key_pkcs8.toByteArray(), record.certificate_der.toByteArray())
                }.getOrNull()
            }
            if (stored != null) {
                result = stored
                store
            } else {
                val fresh = create().also { result = it }
                store.copy(
                    client_identity = ClientIdentityRecord(
                        private_key_pkcs8 = fresh.privateKey.encoded.toByteString(),
                        certificate_der = fresh.certificate.encoded.toByteString(),
                        created_at_ms = System.currentTimeMillis(),
                    ),
                )
            }
        }
        return checkNotNull(result)
    }

    companion object {
        private const val FILE_NAME = "devices.pb"

        /** One per process: DataStore must own its file alone. */
        fun create(context: Context): DeviceRepository {
            // Keystore and Tink set-up runs on DataStore's IO thread at the first read, not here.
            val aead by lazy { DeviceStoreCrypto.aead(context) }
            return DeviceRepository(create({ aead }) { File(context.filesDir, "datastore/$FILE_NAME") })
        }

        /** A store that cannot be decrypted (restored backup, new Keystore key) is wiped. */
        fun create(
            aead: () -> Aead,
            scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            produceFile: () -> File,
        ): DataStore<DeviceStore> = DataStoreFactory.create(
            serializer = EncryptedDeviceStoreSerializer(aead),
            corruptionHandler = ReplaceFileCorruptionHandler { DeviceStore() },
            scope = scope,
            produceFile = produceFile,
        )
    }
}

fun DeviceStore.active(): PairedDevice? = devices.firstOrNull { it.id == active_device_id } ?: devices.firstOrNull()

/**
 * Encrypts the whole store with associated data [associatedData], so a file
 * from another store or version does not decrypt. Decryption failures become
 * [CorruptionException], which the corruption handler turns into a wipe.
 */
class EncryptedDeviceStoreSerializer(
    private val aead: () -> Aead,
    private val associatedData: ByteArray = DeviceStoreCrypto.ASSOCIATED_DATA,
) : Serializer<DeviceStore> {
    override val defaultValue: DeviceStore = DeviceStore()

    override suspend fun readFrom(input: InputStream): DeviceStore {
        val sealed = input.readBytes()
        if (sealed.isEmpty()) return defaultValue
        val plain = try {
            aead().decrypt(sealed, associatedData)
        } catch (e: GeneralSecurityException) {
            throw CorruptionException("device store does not decrypt", e)
        }
        return try {
            DeviceStore.ADAPTER.decode(plain)
        } catch (e: IOException) {
            throw CorruptionException("device store does not parse", e)
        }
    }

    override suspend fun writeTo(t: DeviceStore, output: OutputStream) {
        output.write(aead().encrypt(t.encode(), associatedData))
    }
}

object DeviceStoreCrypto {
    val ASSOCIATED_DATA: ByteArray = "sceptrum-devices-v1".encodeToByteArray()
    private const val KEYSET_NAME = "sceptrum_devices_keyset"
    private const val KEYSET_PREFS = "sceptrum_crypto"
    private const val MASTER_KEY_URI = "android-keystore://sceptrum_master"

    @Volatile
    private var shared: Aead? = null

    /**
     * AES256-GCM keyset in its own SharedPreferences, wrapped by a Keystore
     * master key. Built once per process: the device and learned-apps stores
     * share it, and two builders racing on a first run could each write a keyset.
     */
    @Synchronized
    fun aead(context: Context): Aead = shared ?: build(context).also { shared = it }

    private fun build(context: Context): Aead {
        AeadConfig.register()
        return AndroidKeysetManager.Builder()
            .withSharedPref(context, KEYSET_NAME, KEYSET_PREFS)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle
            .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }
}
