package com.shibi.sceptrum.remote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shibi.sceptrum.atv.KeyDirection
import com.shibi.sceptrum.domain.ConnectionStatus
import com.shibi.sceptrum.domain.KeyPressTyping
import com.shibi.sceptrum.session.RemoteSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class KeyboardUiState(
    val text: String = "",
    /** The TV reported a focused text field. */
    val fieldActive: Boolean = false,
    /** The field's label from the TV, e.g. "Search". */
    val fieldLabel: String = "",
    /** Foreground app package on the TV, for "Type on TV · <app>". */
    val app: String? = null,
    val connected: Boolean = false,
    /** Settings > "Type with key presses". */
    val keyPresses: Boolean = false,
)

/**
 * The keyboard bar (plan §3.2). Each edit is sent as the whole field value
 * after [debounceMs] of quiet, so a backspace is just a shorter value. Empty
 * text is cleared with `KEYCODE_DEL` (androidtvremote2 refuses an empty edit).
 * With [keyPresses] on, edits become key events instead. Enter flushes the text
 * and sends `KEYCODE_ENTER`.
 */
class KeyboardViewModel(
    private val session: RemoteSession,
    keyPresses: Flow<Boolean> = flowOf(false),
    private val debounceMs: Long = DEBOUNCE_MS,
) : ViewModel() {
    private val _state = MutableStateFlow(KeyboardUiState())
    val state: StateFlow<KeyboardUiState> = _state.asStateFlow()

    private val _openRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** The TV focused a new text field: the bar opens itself. */
    val openRequests: SharedFlow<Unit> = _openRequests

    private val _rejects = MutableSharedFlow<Unit>(extraBufferCapacity = 4)

    /** Something could not be sent (Reject haptic). */
    val rejects: SharedFlow<Unit> = _rejects

    /** What the TV field holds as far as this phone knows. */
    private var sent = ""
    private var pending: Job? = null

    init {
        viewModelScope.launch {
            keyPresses.collect { on -> _state.update { it.copy(keyPresses = on) } }
        }
        viewModelScope.launch {
            session.state.collect { s ->
                _state.update {
                    it.copy(
                        fieldActive = s.textField != null,
                        fieldLabel = s.textField?.label.orEmpty(),
                        app = s.currentApp,
                        connected = s.status == ConnectionStatus.Connected,
                    )
                }
            }
        }
        viewModelScope.launch {
            session.state
                .map { it.textField?.let { f -> f.imeCounter to f.fieldCounter } }
                .distinctUntilChanged()
                .collect { counters ->
                    if (counters == null) return@collect
                    // A new field: start from what the TV says is in it.
                    pending?.cancel()
                    val value = session.state.value.textField?.value.orEmpty()
                    sent = value
                    _state.update { it.copy(text = value) }
                    _openRequests.tryEmit(Unit)
                }
        }
    }

    fun onTextChange(text: String) {
        if (text == _state.value.text) return
        _state.update { it.copy(text = text) }
        pending?.cancel()
        pending = viewModelScope.launch {
            delay(debounceMs)
            sync(text)
        }
    }

    /** Deletes the last character, or sends one DEL when the bar is already empty. */
    fun backspace() {
        val text = _state.value.text
        if (text.isEmpty()) key(KeyPressTyping.KEYCODE_DEL) else onTextChange(text.dropLast(1))
    }

    fun clear() = onTextChange("")

    /** Sends what is typed now, then Enter; the bar starts empty for the next search. */
    fun enter() {
        pending?.cancel()
        pending = null
        sync(_state.value.text)
        key(KeyPressTyping.KEYCODE_ENTER)
        sent = ""
        _state.update { it.copy(text = "") }
    }

    private fun sync(text: String) {
        if (text == sent) return
        val ok = when {
            _state.value.keyPresses -> KeyPressTyping.keysFor(sent, text).all { session.send(it, KeyDirection.Short) }
            text.isEmpty() -> List(sent.length.coerceAtMost(MAX_CLEAR)) { KeyPressTyping.KEYCODE_DEL }
                .all { session.send(it, KeyDirection.Short) }
            else -> session.sendText(text)
        }
        if (ok) sent = text else _rejects.tryEmit(Unit)
    }

    private fun key(code: Int) {
        if (!session.send(code, KeyDirection.Short)) _rejects.tryEmit(Unit)
    }

    companion object {
        /** Plan §3.2: the whole value goes out after 150 ms without typing. */
        const val DEBOUNCE_MS = 150L

        /** Most DELs one clear sends. */
        const val MAX_CLEAR = 200
    }
}
