package com.shibi.sceptrum.remote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shibi.sceptrum.atv.KeyDirection
import com.shibi.sceptrum.domain.ConnectionStatus
import com.shibi.sceptrum.domain.KeyRepeat
import com.shibi.sceptrum.domain.RemoteKey
import com.shibi.sceptrum.session.RemoteSession
import com.shibi.sceptrum.session.SessionState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

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
class RemoteViewModel(private val session: RemoteSession) : ViewModel() {
    val state: StateFlow<SessionState> = session.state

    private val _haptics = MutableSharedFlow<RemoteHaptic>(extraBufferCapacity = 8)
    val haptics: SharedFlow<RemoteHaptic> = _haptics

    private val held = mutableMapOf<RemoteKey, Job>()
    private val longStarted = mutableSetOf<RemoteKey>()

    fun tap(key: RemoteKey) {
        send(key, KeyDirection.Short)
    }

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

    private fun send(key: RemoteKey, direction: KeyDirection): Boolean {
        val ok = session.send(key.keyCode, direction)
        if (!ok) _haptics.tryEmit(RemoteHaptic.Reject)
        return ok
    }
}
