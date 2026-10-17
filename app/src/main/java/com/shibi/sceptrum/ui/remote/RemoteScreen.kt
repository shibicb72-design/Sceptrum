package com.shibi.sceptrum.ui.remote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.automirrored.rounded.Input
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.shibi.sceptrum.R
import com.shibi.sceptrum.domain.WindowWidthClass
import com.shibi.sceptrum.ui.GlassCard
import com.shibi.sceptrum.ui.devices.DeviceListPane

/**
 * Static remote layout (no networking yet).
 * - Compact (folded cover screen): one column.
 * - Medium (split screen): remote plus the side panel.
 * - Expanded (unfolded): devices, remote and side panel as three panes.
 */
@Composable
fun RemoteScreen(
    widthClass: WindowWidthClass,
    modifier: Modifier = Modifier,
) {
    when (widthClass) {
        WindowWidthClass.Compact -> RemotePad(modifier = modifier.fillMaxSize())
        WindowWidthClass.Medium -> Row(modifier.fillMaxSize()) {
            RemotePad(modifier = Modifier.weight(0.6f).fillMaxHeight())
            SidePanel(modifier = Modifier.weight(0.4f).fillMaxHeight())
        }
        WindowWidthClass.Expanded -> Row(modifier.fillMaxSize()) {
            DeviceListPane(modifier = Modifier.weight(0.24f).fillMaxHeight())
            RemotePad(modifier = Modifier.weight(0.46f).fillMaxHeight())
            SidePanel(modifier = Modifier.weight(0.30f).fillMaxHeight())
        }
    }
}

@Composable
internal fun RemotePad(modifier: Modifier = Modifier) {
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
                    name = stringResource(R.string.remote_no_device),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                PowerKey()
            }
            KeyRow {
                RemoteKey(Icons.AutoMirrored.Rounded.Input, stringResource(R.string.remote_input))
                RemoteKey(Icons.Rounded.Apps, stringResource(R.string.remote_apps))
                RemoteKey(Icons.Rounded.Keyboard, stringResource(R.string.remote_keyboard))
            }
            DpadDisc()
            KeyRow {
                RemoteKey(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.remote_back))
                RemoteKey(Icons.Rounded.Home, stringResource(R.string.remote_home))
                RemoteKey(Icons.Rounded.Menu, stringResource(R.string.remote_menu))
            }
            KeyRow {
                VolumeRocker()
                RemoteKey(Icons.AutoMirrored.Rounded.VolumeOff, stringResource(R.string.remote_mute))
                ChannelRocker()
            }
            KeyRow {
                RemoteKey(Icons.Rounded.FastRewind, stringResource(R.string.remote_rewind), size = 52.dp)
                RemoteKey(Icons.Rounded.SkipPrevious, stringResource(R.string.remote_previous), size = 52.dp)
                RemoteKey(Icons.Rounded.PlayArrow, stringResource(R.string.remote_play_pause), size = 52.dp)
                RemoteKey(Icons.Rounded.SkipNext, stringResource(R.string.remote_next), size = 52.dp)
                RemoteKey(Icons.Rounded.FastForward, stringResource(R.string.remote_fast_forward), size = 52.dp)
            }
            Text(
                text = stringResource(R.string.remote_preview_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.78f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Apps and number keys. Shown beside the pad when there is room. */
@Composable
internal fun SidePanel(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        GlassCard(title = stringResource(R.string.remote_panel_apps)) {
            Text(
                text = stringResource(R.string.remote_panel_apps_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        GlassCard(title = stringResource(R.string.remote_panel_numbers)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("0"))
                    .forEach { row ->
                        KeyRow {
                            row.forEach { digit -> RemoteTextKey(digit, size = 56.dp) }
                        }
                    }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}
