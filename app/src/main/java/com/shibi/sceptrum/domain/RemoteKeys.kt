package com.shibi.sceptrum.domain

import com.shibi.sceptrum.atv.DisconnectReason
import com.shibi.sceptrum.atv.LinkState

/**
 * Every remote control and the Android `KeyEvent` code it sends (the numbers
 * in `RemoteKeyCode` of remotemessage.proto, plan §3.1).
 * - [repeats]: hold sends the key again ([KeyRepeat]); d-pad and volume.
 * - [longPress]: hold sends START_LONG, release END_LONG; a short tap sends SHORT.
 */
enum class RemoteKey(val keyCode: Int, val repeats: Boolean = false, val longPress: Boolean = false) {
    Up(19, repeats = true),
    Down(20, repeats = true),
    Left(21, repeats = true),
    Right(22, repeats = true),
    Ok(23, longPress = true),
    Back(4),
    Home(3, longPress = true),
    Menu(82),
    Power(26),
    VolumeUp(24, repeats = true),
    VolumeDown(25, repeats = true),
    Mute(164),
    ChannelUp(166),
    ChannelDown(167),
    PlayPause(85),
    Rewind(89),
    FastForward(90),
    Previous(88),
    Next(87),
    Input(178),
    Settings(176),
    Digit0(7),
    Digit1(8),
    Digit2(9),
    Digit3(10),
    Digit4(11),
    Digit5(12),
    Digit6(13),
    Digit7(14),
    Digit8(15),
    Digit9(16),
    ;

    companion object {
        fun digit(n: Int): RemoteKey {
            require(n in 0..9) { "digit $n" }
            return entries[Digit0.ordinal + n]
        }
    }
}

/** Plan §3.1: hold repeats after 400 ms, then every 120 ms; a long press starts at 500 ms. */
object KeyRepeat {
    const val INITIAL_DELAY_MS = 400L
    const val INTERVAL_MS = 120L
    const val LONG_PRESS_MS = 500L
}

/** What the status chip shows. */
enum class ConnectionStatus {
    NoDevice,
    Connecting,
    Connected,
    Reconnecting,

    /** Several retries failed, or the session is not running: offer Retry. */
    Offline,

    /** The TV no longer accepts this phone. */
    NeedsPairing,

    /** The TV's certificate changed since pairing. */
    IdentityChanged,
    ;

    val showsRetry: Boolean get() = this == Reconnecting || this == Offline
    val needsPairing: Boolean get() = this == NeedsPairing || this == IdentityChanged

    companion object {
        /** Retries before the chip says Offline instead of Reconnecting (about 1.5 s of backoff). */
        const val OFFLINE_AFTER_ATTEMPTS = 4

        fun from(link: LinkState): ConnectionStatus = when (link) {
            LinkState.Idle -> Offline
            is LinkState.Connecting -> if (link.attempt == 0) Connecting else Reconnecting
            LinkState.Connected -> Connected
            is LinkState.Retrying -> when {
                link.attempt >= OFFLINE_AFTER_ATTEMPTS -> Offline
                link.reason == DisconnectReason.Unreachable && link.attempt >= 2 -> Offline
                else -> Reconnecting
            }
            LinkState.NeedsPairing -> NeedsPairing
            LinkState.IdentityChanged -> IdentityChanged
        }
    }
}
