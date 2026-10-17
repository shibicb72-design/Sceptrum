package com.shibi.sceptrum.ui.remote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Input
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Gamepad
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Swipe
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.shibi.sceptrum.R
import com.shibi.sceptrum.devices.DevicesUiState
import com.shibi.sceptrum.domain.ConnectionStatus
import com.shibi.sceptrum.domain.RemoteKey
import com.shibi.sceptrum.domain.WindowWidthClass
import com.shibi.sceptrum.power.PowerUiState
import com.shibi.sceptrum.remote.AppsUiState
import com.shibi.sceptrum.remote.KeyboardUiState
import com.shibi.sceptrum.session.SessionState
import com.shibi.sceptrum.ui.GlassCard
import com.shibi.sceptrum.ui.devices.DeviceListPane
import com.shibi.sceptrum.ui.devices.DevicesActions

/** Everything the remote shows besides the session itself. */
class RemoteExtras(
    val keyboard: KeyboardUiState = KeyboardUiState(),
    val typing: TypingActions = TypingActions(),
    val apps: AppsUiState = AppsUiState(),
    val appsActions: AppsActions = AppsActions(),
    val power: PowerUiState = PowerUiState.Idle,
    val onDismissPower: () -> Unit = {},
    /** Folded only: what covers the pad. Unfolded shows apps and keyboard in the side panel. */
    val panel: RemotePanel = RemotePanel.None,
    val onPanel: (RemotePanel) -> Unit = {},
    /** Swipe pad instead of the d-pad buttons. */
    val swipeMode: Boolean = false,
    val onSwipeMode: (Boolean) -> Unit = {},
    /** Focus the keyboard bar's field when it opens (off in screenshots, so no cursor blinks). */
    val autoFocusKeyboard: Boolean = false,
    /** Android blocks LAN access (plan §8); never true while targetSdk is 36. */
    val lanBlocked: Boolean = false,
    val onOpenLanSettings: () -> Unit = {},
)

/**
 * The remote.
 * - Compact (folded cover screen): one column; Apps covers the pad and the
 *   keyboard bar sits at the bottom.
 * - Medium (split screen): remote plus apps, keyboard and numbers.
 * - Expanded (unfolded): devices, remote and that side panel as three panes.
 */
@Composable
fun RemoteScreen(
    widthClass: WindowWidthClass,
    modifier: Modifier = Modifier,
    session: SessionState = SessionState(),
    actions: RemoteActions = RemoteActions(),
    devices: DevicesUiState = DevicesUiState(),
    deviceActions: DevicesActions = DevicesActions(),
    extras: RemoteExtras = RemoteExtras(),
) {
    when (widthClass) {
        WindowWidthClass.Compact -> Column(modifier.fillMaxSize()) {
            if (extras.panel == RemotePanel.Apps) {
                AppsPane(
                    state = extras.apps,
                    actions = extras.appsActions,
                    onClose = { extras.onPanel(RemotePanel.None) },
                    modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                )
            } else {
                RemotePad(session, actions, extras, compact = true, modifier = Modifier.weight(1f).fillMaxWidth())
            }
            if (extras.panel == RemotePanel.Keyboard) {
                KeyboardBar(
                    state = extras.keyboard,
                    actions = extras.typing,
                    onClose = { extras.onPanel(RemotePanel.None) },
                    autoFocus = extras.autoFocusKeyboard,
                    modifier = Modifier.imePadding().padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
        WindowWidthClass.Medium -> Row(modifier.fillMaxSize()) {
            RemotePad(session, actions, extras, compact = false, modifier = Modifier.weight(0.6f).fillMaxHeight())
            SidePanel(actions, extras, columns = 2, modifier = Modifier.weight(0.4f).fillMaxHeight())
        }
        WindowWidthClass.Expanded -> Row(modifier.fillMaxSize()) {
            DeviceListPane(
                state = devices,
                actions = deviceActions,
                showDiscovery = false,
                modifier = Modifier.weight(0.24f).fillMaxHeight(),
            )
            RemotePad(session, actions, extras, compact = false, modifier = Modifier.weight(0.46f).fillMaxHeight())
            SidePanel(actions, extras, columns = 3, modifier = Modifier.weight(0.30f).fillMaxHeight())
        }
    }
}

@Composable
internal fun RemotePad(
    session: SessionState,
    actions: RemoteActions,
    extras: RemoteExtras,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DeviceChip(
                    name = session.deviceName ?: stringResource(R.string.remote_no_device),
                    status = session.status,
                    onClick = actions.onOpenDevices,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                PowerKey(onClick = { actions.onTap(RemoteKey.Power) })
            }
            val power = extras.power
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (power is PowerUiState.Waking) WakingChip(power) else StatusChip(session.status, actions)
                val volume = session.volume
                if (volume != null && session.status == ConnectionStatus.Connected) VolumeChip(volume)
            }
            when {
                extras.lanBlocked -> LanBlockedCard(onOpenSettings = extras.onOpenLanSettings)
                session.status == ConnectionStatus.NoDevice -> WelcomeCard(onFindTv = actions.onOpenDevices)
            }
            if (power is PowerUiState.Failed) {
                PowerTipCard(
                    state = power,
                    onTryAgain = { actions.onTap(RemoteKey.Power) },
                    onDismiss = extras.onDismissPower,
                )
            }
            KeyRow {
                RemoteKey(Icons.AutoMirrored.Rounded.Input, stringResource(R.string.remote_input)) {
                    actions.onTap(RemoteKey.Input)
                }
                if (compact) {
                    RemoteKey(Icons.Rounded.Apps, stringResource(R.string.remote_apps)) {
                        extras.onPanel(RemotePanel.Apps)
                    }
                    RemoteKey(Icons.Rounded.Keyboard, stringResource(R.string.remote_keyboard)) {
                        extras.onPanel(if (extras.panel == RemotePanel.Keyboard) RemotePanel.None else RemotePanel.Keyboard)
                    }
                }
                RemoteKey(
                    icon = if (extras.swipeMode) Icons.Rounded.Gamepad else Icons.Rounded.Swipe,
                    contentDescription = stringResource(
                        if (extras.swipeMode) R.string.remote_dpad_buttons else R.string.remote_swipe_pad,
                    ),
                ) {
                    extras.onSwipeMode(!extras.swipeMode)
                }
                RemoteKey(Icons.Rounded.Settings, stringResource(R.string.remote_settings)) {
                    actions.onTap(RemoteKey.Settings)
                }
            }
            if (extras.swipeMode) SwipePad(actions = actions) else DpadDisc(actions = actions)
            KeyRow {
                RemoteKey(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.remote_back)) {
                    actions.onTap(RemoteKey.Back)
                }
                HomeKey(actions)
                RemoteKey(Icons.Rounded.Menu, stringResource(R.string.remote_menu)) {
                    actions.onTap(RemoteKey.Menu)
                }
            }
            KeyRow {
                VolumeRocker(actions)
                RemoteKey(Icons.AutoMirrored.Rounded.VolumeOff, stringResource(R.string.remote_mute)) {
                    actions.onTap(RemoteKey.Mute)
                }
                ChannelRocker(actions)
            }
            KeyRow {
                MediaKey(Icons.Rounded.FastRewind, R.string.remote_rewind, RemoteKey.Rewind, actions)
                MediaKey(Icons.Rounded.SkipPrevious, R.string.remote_previous, RemoteKey.Previous, actions)
                MediaKey(Icons.Rounded.PlayArrow, R.string.remote_play_pause, RemoteKey.PlayPause, actions)
                MediaKey(Icons.Rounded.SkipNext, R.string.remote_next, RemoteKey.Next, actions)
                MediaKey(Icons.Rounded.FastForward, R.string.remote_fast_forward, RemoteKey.FastForward, actions)
            }
        }
    }
}

/** Home: tap goes home; a long press opens the TV's own long-press action. */
@Composable
private fun HomeKey(actions: RemoteActions) {
    GlassHoldKey(
        icon = Icons.Rounded.Home,
        contentDescription = stringResource(R.string.remote_home),
        onPress = { actions.onPress(RemoteKey.Home) },
        onRelease = { actions.onRelease(RemoteKey.Home) },
    )
}

@Composable
private fun MediaKey(icon: androidx.compose.ui.graphics.vector.ImageVector, label: Int, key: RemoteKey, actions: RemoteActions) {
    RemoteKey(icon, stringResource(label), size = 52.dp) { actions.onTap(key) }
}

/** Apps, keyboard and number pad beside the remote when there is room. */
@Composable
internal fun SidePanel(actions: RemoteActions, extras: RemoteExtras, columns: Int, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AppsCard(extras.apps, extras.appsActions, columns = columns)
        KeyboardCard(extras.keyboard, extras.typing)
        GlassCard(title = stringResource(R.string.remote_panel_numbers)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9), listOf(0))
                    .forEach { row ->
                        KeyRow {
                            row.forEach { digit ->
                                RemoteTextKey(digit.toString(), size = 56.dp) { actions.onTap(RemoteKey.digit(digit)) }
                            }
                        }
                    }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}
