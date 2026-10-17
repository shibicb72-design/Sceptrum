package com.shibi.sceptrum.ui.devices

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shibi.sceptrum.R
import com.shibi.sceptrum.devices.DeviceEdit
import com.shibi.sceptrum.devices.DevicesUiState
import com.shibi.sceptrum.domain.DiscoveredRow
import com.shibi.sceptrum.domain.PairingUiState
import com.shibi.sceptrum.domain.SavedRow
import com.shibi.sceptrum.domain.WindowWidthClass
import com.shibi.sceptrum.ui.BusyIndicator
import com.shibi.sceptrum.ui.GlassCard
import com.shibi.sceptrum.ui.PillButton
import com.shibi.sceptrum.ui.SectionHeader
import com.shibi.sceptrum.ui.background.ContrastSurface
import com.shibi.sceptrum.ui.background.LocalBackgroundChrome
import com.shibi.sceptrum.ui.pairing.PairingActions
import com.shibi.sceptrum.ui.pairing.PairingPane
import com.shibi.sceptrum.ui.remote.SmallIconKey
import com.shibi.sceptrum.ui.remote.glassFill

class DevicesActions(
    val onSelect: (String) -> Unit = {},
    val onEdit: (String) -> Unit = {},
    val onRenameChange: (String) -> Unit = {},
    val onSaveRename: () -> Unit = {},
    val onShowChannelButtons: (Boolean) -> Unit = {},
    val onAskForget: () -> Unit = {},
    val onConfirmForget: () -> Unit = {},
    val onCloseEdit: () -> Unit = {},
    val onPair: (DiscoveredRow) -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onManualChange: (String) -> Unit = {},
    val onManualSubmit: () -> Unit = {},
)

/**
 * Saved and discovered devices. Pairing, or the rename / forget card, replaces
 * the list when folded and fills the detail pane when unfolded.
 */
@Composable
fun DevicesScreen(
    widthClass: WindowWidthClass,
    state: DevicesUiState,
    pairing: PairingUiState,
    modifier: Modifier = Modifier,
    actions: DevicesActions = DevicesActions(),
    pairingActions: PairingActions = PairingActions(),
) {
    val pairingOpen = pairing != PairingUiState.Idle
    val edit = state.editing
    if (widthClass.isMultiPane) {
        Row(modifier.fillMaxSize()) {
            DeviceListPane(state, actions, modifier = Modifier.weight(0.42f).fillMaxHeight())
            Box(
                modifier = Modifier
                    .weight(0.58f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 0.dp, end = 16.dp, top = 72.dp, bottom = 16.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                when {
                    pairingOpen -> PairingPane(pairing, pairingActions)
                    edit != null -> DeviceEditCard(edit, actions)
                    else -> DeviceDetailCard()
                }
            }
        }
    } else if (pairingOpen) {
        Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            SectionHeader(title = stringResource(R.string.devices_title))
            PairingPane(pairing, pairingActions, modifier = Modifier.padding(horizontal = 16.dp))
        }
    } else if (edit != null) {
        Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            SectionHeader(title = stringResource(R.string.devices_title))
            DeviceEditCard(edit, actions, modifier = Modifier.padding(horizontal = 16.dp))
        }
    } else {
        DeviceListPane(state, actions, modifier = modifier.fillMaxSize())
    }
}

/**
 * The device list. [showDiscovery] off gives the short version beside the
 * remote: saved devices only.
 */
@Composable
fun DeviceListPane(
    state: DevicesUiState,
    actions: DevicesActions,
    modifier: Modifier = Modifier,
    showDiscovery: Boolean = true,
) {
    Column(modifier = modifier.verticalScroll(rememberScrollState())) {
        SectionHeader(
            title = stringResource(R.string.devices_title),
            subtitle = stringResource(R.string.devices_subtitle),
        )
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.saved.isNotEmpty()) {
                GroupLabel(stringResource(R.string.devices_saved))
                state.saved.forEach { row ->
                    // Beside the remote the pane is narrow: renaming lives in the Devices section.
                    SavedDeviceCard(
                        row,
                        onClick = { actions.onSelect(row.id) },
                        onEdit = { actions.onEdit(row.id) }.takeIf { showDiscovery },
                    )
                }
            } else if (!showDiscovery || (state.discovered.isEmpty() && !state.scanning)) {
                EmptyCard()
            }
            if (showDiscovery) {
                DiscoverySection(state, actions)
                ManualEntryCard(state, actions)
            }
        }
    }
}

@Composable
private fun DiscoverySection(state: DevicesUiState, actions: DevicesActions) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        GroupLabel(stringResource(R.string.devices_on_network), modifier = Modifier.weight(1f))
        if (state.scanning) {
            BusyIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
        }
    }
    when {
        state.discoveryFailed -> HintText(stringResource(R.string.devices_discovery_failed))
        state.scanning && state.discovered.isEmpty() -> HintText(stringResource(R.string.devices_searching))
        state.discovered.isEmpty() -> HintText(stringResource(R.string.devices_none_found))
    }
    state.discovered.forEach { row -> DiscoveredDeviceCard(row, onPair = { actions.onPair(row) }) }
    if (!state.scanning) {
        PillButton(
            text = stringResource(R.string.devices_search_again),
            icon = Icons.Rounded.Refresh,
            onClick = actions.onRefresh,
        )
    }
}

@Composable
private fun SavedDeviceCard(row: SavedRow, onClick: () -> Unit, onEdit: (() -> Unit)?) {
    ContrastSurface(
        color = glassFill(),
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(if (row.active) 2.dp else 1.dp, if (row.active) LocalBackgroundChrome.current.accentText else LocalBackgroundChrome.current.cardBorder),
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Tv, contentDescription = null, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(row.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (row.online) {
                        Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        text = if (row.online) stringResource(R.string.devices_online) + " · " + row.host else row.host,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (row.active) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = stringResource(R.string.devices_active),
                    modifier = Modifier.size(26.dp),
                )
            }
            if (onEdit != null) {
                Spacer(Modifier.width(8.dp))
                SmallIconKey(Icons.Rounded.Edit, stringResource(R.string.devices_edit, row.name), size = 40.dp, onClick = onEdit)
            }
        }
    }
}

/** Rename, see the address and MAC, show or hide channel buttons, or forget (with a confirmation step). */
@Composable
private fun DeviceEditCard(edit: DeviceEdit, actions: DevicesActions, modifier: Modifier = Modifier) {
    GlassCard(title = stringResource(R.string.devices_edit_title), modifier = modifier) {
        OutlinedTextField(
            value = edit.draft,
            onValueChange = actions.onRenameChange,
            label = { Text(stringResource(R.string.devices_name_label)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { actions.onSaveRename() }),
            modifier = Modifier.fillMaxWidth(),
        )
        HintText(stringResource(R.string.devices_address, edit.host), Modifier.padding(top = 8.dp))
        HintText(
            if (edit.mac.isNotEmpty()) stringResource(R.string.devices_mac, edit.mac) else stringResource(R.string.devices_mac_unknown),
            Modifier.padding(top = 4.dp),
        )
        // The whole row toggles, so TalkBack reads the label with the switch state.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(top = 12.dp)
                .toggleable(value = edit.showChannelButtons, role = Role.Switch, onValueChange = actions.onShowChannelButtons),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.devices_channel_buttons),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.devices_channel_buttons_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = edit.showChannelButtons, onCheckedChange = null)
        }
        Spacer(Modifier.size(12.dp))
        if (edit.confirmForget) {
            Text(
                text = stringResource(R.string.devices_forget_confirm, edit.name),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.size(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton(stringResource(R.string.devices_forget_yes), onClick = actions.onConfirmForget, primary = true, modifier = Modifier.weight(1f))
                PillButton(stringResource(R.string.pairing_cancel), onClick = actions.onCloseEdit, modifier = Modifier.weight(1f))
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton(
                    text = stringResource(R.string.devices_save),
                    onClick = actions.onSaveRename,
                    primary = true,
                    enabled = edit.draft.isNotBlank(),
                    modifier = Modifier.weight(1f),
                )
                PillButton(stringResource(R.string.devices_close), onClick = actions.onCloseEdit, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.size(8.dp))
            PillButton(stringResource(R.string.devices_forget), icon = Icons.Rounded.Delete, onClick = actions.onAskForget)
        }
    }
}

@Composable
private fun DiscoveredDeviceCard(row: DiscoveredRow, onPair: () -> Unit) {
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Tv, contentDescription = null, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(row.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(row.host, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            PillButton(text = stringResource(R.string.devices_pair), onClick = onPair, fillWidth = false)
        }
    }
}

@Composable
private fun ManualEntryCard(state: DevicesUiState, actions: DevicesActions) {
    GlassCard(title = stringResource(R.string.devices_add_ip)) {
        OutlinedTextField(
            value = state.manual.text,
            onValueChange = actions.onManualChange,
            label = { Text(stringResource(R.string.devices_ip_label)) },
            placeholder = { Text(stringResource(R.string.devices_ip_hint)) },
            singleLine = true,
            isError = state.manual.invalid,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { actions.onManualSubmit() }),
            modifier = Modifier.fillMaxWidth(),
        )
        when {
            state.manual.invalid -> HintText(stringResource(R.string.devices_ip_invalid), Modifier.padding(top = 6.dp))
            state.manual.notLocal -> HintText(stringResource(R.string.devices_ip_not_local), Modifier.padding(top = 6.dp))
            else -> HintText(stringResource(R.string.devices_ip_tip), Modifier.padding(top = 6.dp))
        }
        Spacer(Modifier.size(10.dp))
        PillButton(
            text = stringResource(R.string.devices_pair),
            icon = Icons.Rounded.Add,
            onClick = actions.onManualSubmit,
        )
    }
}

@Composable
private fun EmptyCard() {
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Tv, contentDescription = null, modifier = Modifier.size(36.dp))
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    text = stringResource(R.string.devices_empty_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.devices_empty_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DeviceDetailCard() {
    GlassCard(title = stringResource(R.string.devices_detail_title)) {
        Text(
            text = stringResource(R.string.devices_detail_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A small heading on the page: coloured from the backdrop. */
@Composable
private fun GroupLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = modifier.padding(top = 4.dp),
    )
}

/** Secondary text. Inside a card it takes the card's variant colour; on the page, the backdrop's. */
@Composable
private fun HintText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.heightIn(min = 20.dp),
    )
}
