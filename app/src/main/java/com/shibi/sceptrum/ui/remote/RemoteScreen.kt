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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Input
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
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
import com.shibi.sceptrum.session.SessionState
import com.shibi.sceptrum.ui.GlassCard
import com.shibi.sceptrum.ui.devices.DeviceListPane
import com.shibi.sceptrum.ui.devices.DevicesActions

/**
 * The remote.
 * - Compact (folded cover screen): one column.
 * - Medium (split screen): remote plus the number pad.
 * - Expanded (unfolded): devices, remote and number pad as three panes.
 */
@Composable
fun RemoteScreen(
    widthClass: WindowWidthClass,
    modifier: Modifier = Modifier,
    session: SessionState = SessionState(),
    actions: RemoteActions = RemoteActions(),
    devices: DevicesUiState = DevicesUiState(),
    deviceActions: DevicesActions = DevicesActions(),
) {
    when (widthClass) {
        WindowWidthClass.Compact -> RemotePad(session, actions, modifier = modifier.fillMaxSize())
        WindowWidthClass.Medium -> Row(modifier.fillMaxSize()) {
            RemotePad(session, actions, modifier = Modifier.weight(0.6f).fillMaxHeight())
            SidePanel(actions, modifier = Modifier.weight(0.4f).fillMaxHeight())
        }
        WindowWidthClass.Expanded -> Row(modifier.fillMaxSize()) {
            DeviceListPane(
                state = devices,
                actions = deviceActions,
                showDiscovery = false,
                modifier = Modifier.weight(0.24f).fillMaxHeight(),
            )
            RemotePad(session, actions, modifier = Modifier.weight(0.46f).fillMaxHeight())
            SidePanel(actions, modifier = Modifier.weight(0.30f).fillMaxHeight())
        }
    }
}

@Composable
internal fun RemotePad(session: SessionState, actions: RemoteActions, modifier: Modifier = Modifier) {
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
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                StatusChip(session.status, actions)
                val volume = session.volume
                if (volume != null && session.status == ConnectionStatus.Connected) VolumeChip(volume)
            }
            KeyRow {
                RemoteKey(Icons.AutoMirrored.Rounded.Input, stringResource(R.string.remote_input)) {
                    actions.onTap(RemoteKey.Input)
                }
                RemoteKey(Icons.Rounded.Settings, stringResource(R.string.remote_settings)) {
                    actions.onTap(RemoteKey.Settings)
                }
            }
            DpadDisc(actions = actions)
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

/** Number pad. Shown beside the pad when there is room. */
@Composable
internal fun SidePanel(actions: RemoteActions, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
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
