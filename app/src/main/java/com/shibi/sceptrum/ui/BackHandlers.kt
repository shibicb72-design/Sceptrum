package com.shibi.sceptrum.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState

/**
 * Back closes what [onBack] closes while [enabled]; otherwise Back goes on to
 * the section history. NavigationEvent (predictive back) handler, one state
 * per handler as the library requires.
 */
@Composable
fun CloseOnBack(enabled: Boolean = true, onBack: () -> Unit) {
    val state = rememberNavigationEventState(currentInfo = NavigationEventInfo.None)
    val close by rememberUpdatedState(onBack)
    NavigationBackHandler(state = state, isBackEnabled = enabled, onBackCompleted = { close() })
}

/**
 * Devices: one handler for the two cards that can be open. Back cancels
 * pairing first; with no pairing open it closes the rename / forget card.
 * [pairingOpen] and [editing] are read when Back completes, not when composed.
 */
@Composable
fun DevicesBack(
    pairingOpen: () -> Boolean,
    editing: () -> Boolean,
    onCancelPairing: () -> Unit,
    onCloseEdit: () -> Unit,
) {
    // CloseOnBack keeps the latest lambda, so these parameters are never stale.
    CloseOnBack(enabled = pairingOpen() || editing()) {
        if (pairingOpen()) onCancelPairing() else if (editing()) onCloseEdit()
    }
}
