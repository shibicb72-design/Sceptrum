package com.shibi.sceptrum.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shibi.sceptrum.atv.PairingCode
import com.shibi.sceptrum.atv.PairingDiagnostics
import com.shibi.sceptrum.atv.PairingException
import com.shibi.sceptrum.atv.PairingFailure
import com.shibi.sceptrum.data.DeviceRepository
import com.shibi.sceptrum.diagnostics.ErrorLog
import com.shibi.sceptrum.domain.DeviceIds
import com.shibi.sceptrum.domain.PairingError
import com.shibi.sceptrum.domain.PairingTarget
import com.shibi.sceptrum.domain.PairingUiState
import com.shibi.sceptrum.store.PairedDevice
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okio.ByteString.Companion.toByteString

/**
 * Pairing flow (plan §5.5): Connecting, EnterCode, then Paired, or a Failed
 * card. A code that fails the local check keeps the TV session open; a
 * BAD_SECRET from the TV starts over because the TV shows a new code.
 */
class PairingViewModel(
    private val gateway: PairingGateway,
    private val repository: DeviceRepository,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Goes into the error log report. */
    private val appVersion: String = "dev",
    private val errors: ErrorLog? = null,
) : ViewModel() {
    private val _state = MutableStateFlow<PairingUiState>(PairingUiState.Idle)
    val state: StateFlow<PairingUiState> = _state.asStateFlow()

    private var attempt: PairingAttempt? = null
    private var job: Job? = null

    fun begin(target: PairingTarget) {
        reset()
        connect(target, carriedError = null)
    }

    fun onCodeChange(raw: String) {
        val current = _state.value as? PairingUiState.EnterCode ?: return
        if (current.submitting) return
        val code = PairingCode.sanitize(raw)
        if (code == current.code) return
        _state.value = current.copy(code = code, error = if (code.isEmpty()) current.error else null)
        if (code.length == PairingCode.LENGTH) submit()
    }

    fun submit() {
        val current = _state.value as? PairingUiState.EnterCode ?: return
        val session = attempt ?: return
        if (current.submitting || current.code.length != PairingCode.LENGTH) return
        _state.value = current.copy(submitting = true, error = null)
        job = viewModelScope.launch {
            try {
                session.submit(current.code)
                val target = current.target
                val id = DeviceIds.forTv(session.mac, session.serverSha256)
                val now = clock()
                repository.savePaired(
                    PairedDevice(
                        id = id,
                        driver = DRIVER,
                        name = target.name,
                        host = target.host,
                        port = target.port,
                        pairing_port = target.pairingPort,
                        mac = session.mac.orEmpty(),
                        server_cert_sha256 = session.serverSha256.toByteString(),
                        paired_at_ms = now,
                        last_seen_at_ms = now,
                    ),
                )
                attempt = null
                _state.value = PairingUiState.Paired(target, id)
            } catch (e: PairingException) {
                when (e.failure) {
                    PairingFailure.WrongCode -> {
                        errors?.record(
                            "Pairing",
                            "The code failed the local check (typo?)",
                            null,
                            tv = "${current.target.name} (${current.target.host}:${current.target.pairingPort})",
                        )
                        _state.value = current.copy(code = "", submitting = false, error = PairingError.WrongCode)
                    }
                    PairingFailure.BadSecret -> {
                        errors?.record(
                            "Pairing",
                            "The TV said the code was wrong and shows a new one",
                            e,
                            tv = "${current.target.name} (${current.target.host}:${current.target.pairingPort})",
                        )
                        closeAttempt()
                        connect(current.target, carriedError = PairingError.BadSecret)
                    }
                    else -> fail(current.target, e)
                }
            }
        }
    }

    /** Try again from the start after an error card. */
    fun retry() {
        val target = when (val s = _state.value) {
            is PairingUiState.Failed -> s.target
            is PairingUiState.EnterCode -> s.target
            else -> return
        }
        begin(target)
    }

    fun cancel() {
        reset()
        _state.value = PairingUiState.Idle
    }

    override fun onCleared() {
        reset()
    }

    private fun connect(target: PairingTarget, carriedError: PairingError?) {
        _state.value = PairingUiState.Connecting(target)
        job = viewModelScope.launch {
            try {
                val started = gateway.start(target)
                attempt = started
                // A manual IP has no friendly name yet: take the TV's own.
                val named = if (target.name == target.host) target.copy(name = started.tvName ?: target.name) else target
                _state.value = PairingUiState.EnterCode(named, error = carriedError)
            } catch (e: PairingException) {
                fail(target, e)
            }
        }
    }

    private fun fail(target: PairingTarget, e: PairingException) {
        closeAttempt()
        val diagnostics = e.diagnostics ?: PairingDiagnostics(
            failure = e.failure,
            step = null,
            host = target.host,
            port = target.pairingPort,
            detail = e.message,
            errors = PairingDiagnostics.chain(e.cause),
        )
        errors?.record(
            "Pairing",
            diagnostics.report(appVersion).removePrefix("Sceptrum pairing report").trim(),
            e,
            tv = "${target.name} (${target.host}:${target.pairingPort})",
        )
        _state.value = PairingUiState.Failed(target, e.failure.toUiError(), diagnostics.report(appVersion))
    }

    private fun reset() {
        job?.cancel()
        job = null
        closeAttempt()
    }

    private fun closeAttempt() {
        attempt?.close()
        attempt = null
    }

    companion object {
        const val DRIVER = "androidtv"

        fun PairingFailure.toUiError(): PairingError = when (this) {
            PairingFailure.WrongCode -> PairingError.WrongCode
            PairingFailure.BadSecret -> PairingError.BadSecret
            PairingFailure.Rejected -> PairingError.Rejected
            PairingFailure.Handshake -> PairingError.Handshake
            PairingFailure.Dropped -> PairingError.Dropped
            PairingFailure.Timeout -> PairingError.Timeout
            PairingFailure.Unreachable -> PairingError.Unreachable
        }
    }
}
