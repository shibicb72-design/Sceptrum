package com.shibi.sceptrum.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shibi.sceptrum.data.DeviceRepository
import com.shibi.sceptrum.data.LearnedAppsRepository
import com.shibi.sceptrum.diagnostics.ErrorLog
import com.shibi.sceptrum.discovery.TvDiscovery
import com.shibi.sceptrum.domain.DeviceListMerge
import com.shibi.sceptrum.domain.DiscoveredRow
import com.shibi.sceptrum.domain.ManualAddress
import com.shibi.sceptrum.domain.PairingTarget
import com.shibi.sceptrum.domain.SavedRow
import java.io.IOException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ManualEntry(
    val text: String = "",
    val invalid: Boolean = false,
    /** Valid but outside the private ranges: likely a different network. */
    val notLocal: Boolean = false,
)

/** The rename / forget card for one saved device. */
data class DeviceEdit(
    val id: String,
    val name: String,
    val host: String,
    val mac: String,
    /** The rename field. */
    val draft: String = name,
    /** "Forget <name>?" is showing. */
    val confirmForget: Boolean = false,
    /** The remote shows the CH rocker for this device. */
    val showChannelButtons: Boolean = true,
)

data class DevicesUiState(
    val saved: List<SavedRow> = emptyList(),
    val discovered: List<DiscoveredRow> = emptyList(),
    val scanning: Boolean = false,
    val discoveryFailed: Boolean = false,
    val manual: ManualEntry = ManualEntry(),
    val editing: DeviceEdit? = null,
)

/** Saved and discovered TVs, scanning, device choice and "Add by IP". */
class DevicesViewModel(
    private val repository: DeviceRepository,
    private val discovery: TvDiscovery,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Forgetting a TV also drops the apps learned from it. */
    private val learnedApps: LearnedAppsRepository? = null,
    private val errors: ErrorLog? = null,
) : ViewModel() {
    private val manual = MutableStateFlow(ManualEntry())
    private val editing = MutableStateFlow<DeviceEdit?>(null)

    val state: StateFlow<DevicesUiState> =
        combine(repository.store, discovery.state, manual, editing) { store, found, entry, edit ->
            val lists = DeviceListMerge.merge(store.devices, store.active_device_id, found.devices)
            if (lists.hostChanges.isNotEmpty()) {
                viewModelScope.launch {
                    lists.hostChanges.forEach { (id, host) -> repository.updateHost(id, host, clock()) }
                }
            }
            DevicesUiState(
                saved = lists.saved,
                discovered = lists.discovered,
                scanning = found.scanning,
                discoveryFailed = found.failed,
                manual = entry,
                // A device forgotten elsewhere closes its card.
                editing = edit?.takeIf { e -> store.devices.any { it.id == e.id } },
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DevicesUiState())

    fun refresh() = discovery.scan()

    fun stopScan() = discovery.stop()

    fun select(id: String) {
        viewModelScope.launch { repository.setActive(id) }
    }

    /** Opens the rename / forget card for a saved device. */
    fun edit(id: String) {
        viewModelScope.launch {
            val device = repository.devices.first().firstOrNull { it.id == id } ?: return@launch
            editing.value = DeviceEdit(
                id = device.id,
                name = device.name,
                host = device.host,
                mac = device.mac,
                showChannelButtons = !device.hide_channel_buttons,
            )
        }
    }

    /**
     * Saved at once, like the switches in Settings; the card stays open and,
     * like any other edit, cancels a pending Forget. A failed write puts the
     * switch back and goes to the error log.
     */
    fun setShowChannelButtons(show: Boolean) {
        val edit = editing.value ?: return
        editing.value = edit.copy(showChannelButtons = show, confirmForget = false)
        viewModelScope.launch {
            try {
                repository.setShowChannelButtons(edit.id, show)
            } catch (e: IOException) {
                errors?.record("Devices", "Could not save \"Show channel buttons\" for ${edit.name}", e)
                editing.update { current -> if (current?.id == edit.id) current.copy(showChannelButtons = !show) else current }
            }
        }
    }

    fun onRenameChange(text: String) {
        editing.value = editing.value?.copy(draft = text.take(MAX_NAME_CHARS), confirmForget = false)
    }

    /** Saves a non-blank name and closes the card. */
    fun saveRename() {
        val edit = editing.value ?: return
        val name = edit.draft.trim()
        if (name.isEmpty()) return
        editing.value = null
        if (name != edit.name) viewModelScope.launch { repository.rename(edit.id, name) }
    }

    fun askForget() {
        editing.value = editing.value?.copy(confirmForget = true)
    }

    /** Removes the device and its pin; the next saved one becomes active. */
    fun confirmForget() {
        val edit = editing.value ?: return
        editing.value = null
        // Both deletions finish even if the screen closes right after the tap.
        viewModelScope.launch {
            withContext(NonCancellable) {
                repository.forget(edit.id)
                learnedApps?.forgetDevice(edit.id)
            }
        }
    }

    fun closeEdit() {
        editing.value = null
    }

    fun onManualChange(text: String) {
        manual.value = ManualEntry(text = text.filter { it.isDigit() || it == '.' }.take(MAX_IPV4_CHARS))
    }

    /** A pairing target for the typed address, or null after flagging it invalid. */
    fun manualTarget(): PairingTarget? {
        val host = ManualAddress.parseIpv4(manual.value.text)
        if (host == null) {
            manual.value = manual.value.copy(invalid = true, notLocal = false)
            return null
        }
        manual.value = manual.value.copy(invalid = false, notLocal = !ManualAddress.isPrivateLan(host))
        // The name is the address until the TV's certificate gives a better one.
        return PairingTarget(name = host, host = host)
    }

    fun targetFor(row: DiscoveredRow) = PairingTarget(name = row.name, host = row.host, port = row.port)

    override fun onCleared() {
        discovery.stop()
    }

    private companion object {
        const val MAX_IPV4_CHARS = 15
        const val MAX_NAME_CHARS = 40
    }
}
