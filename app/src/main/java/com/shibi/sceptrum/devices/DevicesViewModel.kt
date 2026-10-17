package com.shibi.sceptrum.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shibi.sceptrum.data.DeviceRepository
import com.shibi.sceptrum.discovery.TvDiscovery
import com.shibi.sceptrum.domain.DeviceListMerge
import com.shibi.sceptrum.domain.DiscoveredRow
import com.shibi.sceptrum.domain.ManualAddress
import com.shibi.sceptrum.domain.PairingTarget
import com.shibi.sceptrum.domain.SavedRow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ManualEntry(
    val text: String = "",
    val invalid: Boolean = false,
    /** Valid but outside the private ranges: likely a different network. */
    val notLocal: Boolean = false,
)

data class DevicesUiState(
    val saved: List<SavedRow> = emptyList(),
    val discovered: List<DiscoveredRow> = emptyList(),
    val scanning: Boolean = false,
    val discoveryFailed: Boolean = false,
    val manual: ManualEntry = ManualEntry(),
)

/** Saved and discovered TVs, scanning, device choice and "Add by IP". */
class DevicesViewModel(
    private val repository: DeviceRepository,
    private val discovery: TvDiscovery,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val manual = MutableStateFlow(ManualEntry())

    val state: StateFlow<DevicesUiState> =
        combine(repository.store, discovery.state, manual) { store, found, entry ->
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
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DevicesUiState())

    fun refresh() = discovery.scan()

    fun stopScan() = discovery.stop()

    fun select(id: String) {
        viewModelScope.launch { repository.setActive(id) }
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
    }
}
