package com.shibi.sceptrum.domain

/** The TV being paired. Ports are the Android TV defaults unless mDNS said otherwise. */
data class PairingTarget(
    val name: String,
    val host: String,
    val port: Int = 6466,
    val pairingPort: Int = 6467,
)

/** One error card each (plan §5.5). */
enum class PairingError {
    /** First-byte check failed locally; nothing was sent. */
    WrongCode,

    /** The TV said the secret was wrong; it now shows a new code. */
    BadSecret,

    /** The TV answered with an error status. */
    Rejected,

    /** The secure connection failed, e.g. the TV refused the phone's certificate. */
    Handshake,

    /** The TV hung up without saying why. */
    Dropped,

    /** No answer from the TV in time. */
    Timeout,

    /** Could not connect at all. */
    Unreachable,
}

sealed interface PairingUiState {
    data object Idle : PairingUiState

    data class Connecting(val target: PairingTarget) : PairingUiState

    /** The TV shows a code. [error] is WrongCode or BadSecret when the last try failed. */
    data class EnterCode(
        val target: PairingTarget,
        val code: String = "",
        val submitting: Boolean = false,
        val error: PairingError? = null,
    ) : PairingUiState

    /** [report] is the text "Copy details" puts on the clipboard. */
    data class Failed(val target: PairingTarget, val error: PairingError, val report: String = "") : PairingUiState

    data class Paired(val target: PairingTarget, val deviceId: String) : PairingUiState
}
