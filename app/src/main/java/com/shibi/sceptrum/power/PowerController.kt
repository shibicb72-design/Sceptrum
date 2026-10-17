package com.shibi.sceptrum.power

import com.shibi.sceptrum.atv.KeyDirection
import com.shibi.sceptrum.domain.ConnectionStatus
import com.shibi.sceptrum.domain.PowerAction
import com.shibi.sceptrum.domain.PowerPolicy
import com.shibi.sceptrum.domain.RemoteKey
import com.shibi.sceptrum.domain.StandbyTip
import com.shibi.sceptrum.domain.StandbyTips
import com.shibi.sceptrum.session.RemoteSession
import com.shibi.sceptrum.session.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

sealed interface PowerUiState {
    data object Idle : PowerUiState

    /** Magic packets sent (or not, without a MAC); reconnecting for up to 20 s. */
    data class Waking(val deviceName: String, val sentPacket: Boolean) : PowerUiState

    /** The TV did not come back: show what to change on it. */
    data class Failed(val deviceName: String, val tips: List<StandbyTip>) : PowerUiState
}

/**
 * The power-on flow (plan §7.3) on top of [PowerPolicy]: send the key while
 * connected; otherwise wake, keep cutting the reconnect backoff short, and once
 * connected press Power if the TV still says standby. Runs in [scope] (the
 * remote view model), so it is tested in virtual time.
 */
class PowerController(
    private val session: RemoteSession,
    private val waker: Waker,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<PowerUiState>(PowerUiState.Idle)
    val state: StateFlow<PowerUiState> = _state.asStateFlow()

    private var job: Job? = null

    /** False when the press did nothing at all (Reject haptic). */
    fun press(): Boolean {
        val now = session.state.value
        return when (val action = PowerPolicy.onPress(now.status, now.mac)) {
            PowerAction.SendPowerKey -> {
                cancel()
                session.send(RemoteKey.Power.keyCode, KeyDirection.Short)
            }
            PowerAction.Unavailable -> false
            is PowerAction.Wake -> start(now.deviceName.orEmpty(), action.mac)
            PowerAction.Reconnect -> start(now.deviceName.orEmpty(), mac = null)
        }
    }

    fun dismiss() {
        cancel()
    }

    private fun cancel() {
        job?.cancel()
        job = null
        _state.value = PowerUiState.Idle
    }

    private fun start(name: String, mac: String?): Boolean {
        if (job?.isActive == true) return true
        job = scope.launch {
            _state.value = PowerUiState.Waking(name, sentPacket = false)
            val sent = mac != null && runCatching { waker.wake(mac) }.getOrDefault(false)
            _state.value = PowerUiState.Waking(name, sentPacket = sent)
            val connected = withTimeoutOrNull(PowerPolicy.WAKE_WINDOW_MS) { awaitConnected() }
            if (connected != null) {
                if (PowerPolicy.powerKeyAfterWake(connected.isOn)) session.send(RemoteKey.Power.keyCode, KeyDirection.Short)
                _state.value = PowerUiState.Idle
            } else {
                val latest = session.state.value
                _state.value = PowerUiState.Failed(name, StandbyTips.forDevice(name, latest.vendor, hasMac = mac != null))
            }
        }
        return true
    }

    /** Reconnects now, then again every [PowerPolicy.RETRY_EVERY_MS] until the TV answers. */
    private suspend fun awaitConnected(): SessionState {
        while (true) {
            session.retry()
            withTimeoutOrNull(PowerPolicy.RETRY_EVERY_MS) {
                session.state.first { it.status == ConnectionStatus.Connected }
            }?.let { return it }
        }
    }
}
