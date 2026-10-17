package com.shibi.sceptrum.data

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import com.google.crypto.tink.Aead
import com.shibi.sceptrum.diagnostics.ErrorLog
import com.shibi.sceptrum.domain.LearnedApps
import com.shibi.sceptrum.session.ForegroundAppListener
import com.shibi.sceptrum.store.DeviceLearnedApps
import com.shibi.sceptrum.store.LearnedAppsStore
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch

/**
 * The apps each TV reported as its foreground app (docs/apps-list-options.md,
 * Option A), per device id, in their own encrypted DataStore file. The rules
 * (deny-list, cap, removal) are in [LearnedApps]; this class only stores.
 * Every failed read or write goes to the [errors] log, never to the caller.
 */
class LearnedAppsRepository(
    private val dataStore: DataStore<LearnedAppsStore>,
    private val scope: CoroutineScope,
    private val errors: ErrorLog? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    /** False for a device id no longer saved, so a late write never brings a forgotten TV's apps back. */
    private val deviceExists: suspend (String) -> Boolean = { true },
) : ForegroundAppListener {
    /**
     * Every device's learned apps. A read error is logged and shows as empty,
     * then the read is retried, so later writes still reach the tiles.
     */
    val store: Flow<LearnedAppsStore> = dataStore.data
        .retryWhen { e, attempt ->
            if (e !is IOException || attempt >= READ_RETRIES) return@retryWhen false
            errors?.record(STEP, "Could not read the learned apps", e)
            emit(LearnedAppsStore())
            delay(READ_RETRY_MS * (attempt + 1))
            true
        }
        .catch { e -> if (e is IOException) emit(LearnedAppsStore()) else throw e }

    /** Learns [appPackage] for [deviceId] in the background; see [record]. */
    override fun onForegroundApp(deviceId: String, appPackage: String) {
        if (!LearnedApps.isLearnable(appPackage)) return
        // The time is taken now, not when the write runs: two quick reports may write in either order.
        val seenAt = clock()
        scope.launch { record(deviceId, appPackage, seenAt) }
    }

    /** Remembers that [deviceId] showed [appPackage] at [nowMs]. False when nothing was saved (or it was removed). */
    suspend fun record(deviceId: String, appPackage: String, nowMs: Long = clock()): Boolean {
        if (!LearnedApps.isLearnable(appPackage) || !deviceExists(deviceId)) return false
        return edit("Could not save the app the TV opened", deviceId) { LearnedApps.record(it, appPackage, nowMs) }
    }

    /** Removes [appPackage] from [deviceId]'s tiles for good. */
    suspend fun remove(deviceId: String, appPackage: String): Boolean =
        edit("Could not remove a learned app", deviceId) { LearnedApps.remove(it, appPackage) }

    /** Drops everything learned for [deviceId]. */
    suspend fun forgetDevice(deviceId: String): Boolean =
        update("Could not clear the learned apps") { it.copy(devices = it.devices - deviceId) }

    /** Applies [change] to [deviceId]'s entry; true only when it changed and was saved. */
    private suspend fun edit(failure: String, deviceId: String, change: (DeviceLearnedApps) -> DeviceLearnedApps): Boolean {
        if (deviceId.isEmpty()) return false
        var changed = false
        val saved = update(failure) { store ->
            val before = store.devices[deviceId] ?: DeviceLearnedApps()
            val after = change(before)
            changed = after != before
            if (changed) store.copy(devices = store.devices + (deviceId to after)) else store
        }
        return saved && changed
    }

    private suspend fun update(failure: String, change: (LearnedAppsStore) -> LearnedAppsStore): Boolean =
        try {
            dataStore.updateData(change)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // IOException from the disk, GeneralSecurityException from the cipher: the tiles just stay as they were.
            errors?.record(STEP, failure, e)
            false
        }

    companion object {
        private const val STEP = "Learned apps"
        private const val READ_RETRIES = 3L
        private const val READ_RETRY_MS = 1_000L
        private const val FILE_NAME = "learned_apps.pb"
        val ASSOCIATED_DATA: ByteArray = "sceptrum-learned-apps-v1".encodeToByteArray()

        /** One per process: DataStore must own its file alone. */
        fun create(
            context: Context,
            scope: CoroutineScope,
            errors: ErrorLog?,
            deviceExists: suspend (String) -> Boolean,
        ): LearnedAppsRepository {
            val aead by lazy { DeviceStoreCrypto.aead(context) }
            val store = create({ aead }) { File(context.filesDir, "datastore/$FILE_NAME") }
            return LearnedAppsRepository(store, scope, errors, deviceExists = deviceExists)
        }

        /** A file that no longer decrypts is wiped: the apps are learned again. */
        fun create(
            aead: () -> Aead,
            scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            produceFile: () -> File,
        ): DataStore<LearnedAppsStore> = DataStoreFactory.create(
            serializer = EncryptedLearnedAppsSerializer(aead),
            corruptionHandler = ReplaceFileCorruptionHandler { LearnedAppsStore() },
            scope = scope,
            produceFile = produceFile,
        )
    }
}

/** As [EncryptedDeviceStoreSerializer], with its own associated data so neither file decrypts as the other. */
class EncryptedLearnedAppsSerializer(
    private val aead: () -> Aead,
    private val associatedData: ByteArray = LearnedAppsRepository.ASSOCIATED_DATA,
) : Serializer<LearnedAppsStore> {
    override val defaultValue: LearnedAppsStore = LearnedAppsStore()

    override suspend fun readFrom(input: InputStream): LearnedAppsStore {
        val sealed = input.readBytes()
        if (sealed.isEmpty()) return defaultValue
        val plain = try {
            aead().decrypt(sealed, associatedData)
        } catch (e: GeneralSecurityException) {
            throw CorruptionException("learned apps do not decrypt", e)
        }
        return try {
            LearnedAppsStore.ADAPTER.decode(plain)
        } catch (e: IOException) {
            throw CorruptionException("learned apps do not parse", e)
        }
    }

    override suspend fun writeTo(t: LearnedAppsStore, output: OutputStream) {
        output.write(aead().encrypt(t.encode(), associatedData))
    }
}
