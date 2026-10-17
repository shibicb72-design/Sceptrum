package com.shibi.sceptrum.remote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shibi.sceptrum.atv.KeyDirection
import com.shibi.sceptrum.domain.ConnectionStatus
import com.shibi.sceptrum.domain.KeyRepeat
import com.shibi.sceptrum.domain.RemoteKey
import com.shibi.sceptrum.domain.TvShortcut
import com.shibi.sceptrum.domain.TvShortcuts
import com.shibi.sceptrum.domain.TvStep
import com.shibi.sceptrum.power.PowerController
import com.shibi.sceptrum.power.PowerUiState
import com.shibi.sceptrum.power.Waker
import com.shibi.sceptrum.session.RemoteSession
import com.shibi.sceptrum.session.SessionState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Haptics the screen plays for key events it cannot see itself. */
enum class RemoteHaptic {
    /** One auto-repeat step (SegmentFrequentTick). */
    Tick,

    /** The key did not go through: not connected (Reject). */
    Reject,
}

/**
 * Turns presses into key injections (plan §3.1). Taps send SHORT. Repeating
 * keys send on press, then again after [KeyRepeat.INITIAL_DELAY_MS] and every
 * [KeyRepeat.INTERVAL_MS] until release. Long-press keys send SHORT on a quick
 * release, or START_LONG at [KeyRepeat.LONG_PRESS_MS] and END_LONG on release.
 */
class RemoteViewModel(
    private val session: RemoteSession,
    waker: Waker = Waker { false },
) : ViewModel() {
    val state: StateFlow<SessionState> = session.state

    private val powerFlow = PowerController(session, waker, viewModelScope)

    /** Waking the TV, or the tip card when it did not come back (plan §7.3). */
    val power: StateFlow<PowerUiState> = powerFlow.state

    private val _haptics = MutableSharedFlow<RemoteHaptic>(extraBufferCapacity = 8)
    val haptics: SharedFlow<RemoteHaptic> = _haptics

    private val held = mutableMapOf<RemoteKey, Job>()
    private val longStarted = mutableSetOf<RemoteKey>()
    private val shortcutJobs = mutableMapOf<TvShortcut, Job>()
    private val lastWorked = mutableMapOf<Pair<String?, TvShortcut>, Int>()

    fun tap(key: RemoteKey) {
        if (key == RemoteKey.Power) {
            if (!powerFlow.press()) _haptics.tryEmit(RemoteHaptic.Reject)
            return
        }
        val shortcut = TvShortcuts.forKey(key)
        if (shortcut != null) {
            runShortcut(shortcut)
            return
        }
        send(key, KeyDirection.Short)
    }

    fun dismissPowerTip() = powerFlow.dismiss()

    fun press(key: RemoteKey) {
        release(key, sendRelease = false)
        when {
            key.repeats -> {
                if (!send(key, KeyDirection.Short)) return
                held[key] = viewModelScope.launch {
                    delay(KeyRepeat.INITIAL_DELAY_MS)
                    while (send(key, KeyDirection.Short)) {
                        _haptics.tryEmit(RemoteHaptic.Tick)
                        delay(KeyRepeat.INTERVAL_MS)
                    }
                }
            }
            key.longPress -> held[key] = viewModelScope.launch {
                delay(KeyRepeat.LONG_PRESS_MS)
                if (send(key, KeyDirection.StartLong)) {
                    longStarted += key
                    _haptics.tryEmit(RemoteHaptic.Tick)
                }
            }
            else -> send(key, KeyDirection.Short)
        }
    }

    fun release(key: RemoteKey) = release(key, sendRelease = true)

    /** Phone volume keys while the remote is in front; false lets the phone handle them. */
    fun hardwareVolume(key: RemoteKey): Boolean {
        if (state.value.status != ConnectionStatus.Connected) return false
        send(key, KeyDirection.Short)
        return true
    }

    fun retry() = session.retry()

    override fun onCleared() {
        held.values.forEach { it.cancel() }
        held.clear()
        shortcutJobs.values.forEach { it.cancel() }
        shortcutJobs.clear()
    }

    private fun release(key: RemoteKey, sendRelease: Boolean) {
        val job = held.remove(key)
        job?.cancel()
        if (!sendRelease || !key.longPress) return
        if (longStarted.remove(key)) {
            send(key, KeyDirection.EndLong)
        } else if (job != null) {
            send(key, KeyDirection.Short)
        }
    }

    /**
     * Runs a shortcut's ordered fallback steps. The first step is sent right away;
     * each following step is tried only after [TvShortcuts.CONFIRM_MS] passes
     * without the TV reporting a different foreground app. The step that finally
     * worked is remembered per device and shortcut so it is tried first next time.
     */
    private fun runShortcut(shortcut: TvShortcut) {
        shortcutJobs.remove(shortcut)?.cancel()
        val startApp = session.state.value.currentApp
        val deviceId = session.state.value.deviceId
        val steps = TvShortcuts.ordered(shortcut, lastWorked[deviceId to shortcut])
        if (!sendStep(steps[0])) return
        shortcutJobs[shortcut] = viewModelScope.launch {
            for (index in steps.indices) {
                val confirmed = withTimeoutOrNull(TvShortcuts.CONFIRM_MS) {
                    session.state.first { it.currentApp != startApp }
                }
                if (confirmed != null) {
                    lastWorked[deviceId to shortcut] = shortcut.steps.indexOf(steps[index])
                    return@launch
                }
                val next = steps.getOrNull(index + 1) ?: return@launch
                if (!sendStep(next)) return@launch
            }
        }
    }

    private fun sendStep(step: TvStep): Boolean {
        val ok = when (step) {
            is TvStep.Key -> session.send(step.keyCode, KeyDirection.Short)
            is TvStep.Link -> session.launchApp(step.uri)
        }
        if (!ok) _haptics.tryEmit(RemoteHaptic.Reject)
        return ok
    }

    private fun send(key: RemoteKey, direction: KeyDirection): Boolean {
        val ok = session.send(key.keyCode, direction)
        if (!ok) _haptics.tryEmit(RemoteHaptic.Reject)
        return ok
    }
}
