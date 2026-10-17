package com.shibi.sceptrum.ui.remote

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.automirrored.rounded.KeyboardReturn
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.shibi.sceptrum.R
import com.shibi.sceptrum.domain.AppShortcutError
import com.shibi.sceptrum.domain.AppShortcuts
import com.shibi.sceptrum.domain.RemoteKey
import com.shibi.sceptrum.domain.StandbyTip
import com.shibi.sceptrum.domain.SwipeStepper
import com.shibi.sceptrum.power.PowerUiState
import com.shibi.sceptrum.remote.AppsUiState
import com.shibi.sceptrum.remote.KeyboardUiState
import com.shibi.sceptrum.store.AppShortcut
import com.shibi.sceptrum.ui.BusyIndicator
import com.shibi.sceptrum.ui.GlassCard
import com.shibi.sceptrum.ui.PillButton
import com.shibi.sceptrum.ui.background.ContrastSurface
import com.shibi.sceptrum.ui.background.LocalBackgroundChrome

/** What covers part of the folded remote: nothing, the apps grid, or the keyboard bar. */
enum class RemotePanel { None, Apps, Keyboard }

class TypingActions(
    val onTextChange: (String) -> Unit = {},
    val onBackspace: () -> Unit = {},
    val onEnter: () -> Unit = {},
    val onClear: () -> Unit = {},
)

class AppsActions(
    val onLaunch: (AppShortcut) -> Unit = {},
    val onEditing: (Boolean) -> Unit = {},
    val onLabelChange: (String) -> Unit = {},
    val onLinkChange: (String) -> Unit = {},
    val onSubmit: () -> Unit = {},
    val onAddCurrent: () -> Unit = {},
    val onRemove: (Int) -> Unit = {},
    val onMoveUp: (Int) -> Unit = {},
    val onMoveDown: (Int) -> Unit = {},
    val onReset: () -> Unit = {},
)

/** A small round glass button with an icon; content colour from its own fill. */
@Composable
internal fun SmallIconKey(
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    onClick: () -> Unit,
) {
    ContrastSurface(
        color = glassFill(),
        shape = CircleShape,
        border = BorderStroke(1.dp, LocalBackgroundChrome.current.glassBorder),
        onClick = onClick,
        modifier = modifier.size(size),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(size * 0.5f))
        }
    }
}

/** "Type on TV · YouTube", or the hint to focus a field first. */
@Composable
private fun keyboardHeadline(state: KeyboardUiState): String = when {
    !state.connected -> stringResource(R.string.keyboard_not_connected)
    !state.fieldActive -> stringResource(R.string.keyboard_select_field)
    state.app != null -> stringResource(R.string.keyboard_type_on_app, AppShortcuts.labelFor("market://launch?id=${state.app}"))
    else -> stringResource(R.string.keyboard_type_on_tv)
}

/** Text field, Delete and Enter. The field sends as you type (plan §3.2). */
@Composable
private fun KeyboardEntry(
    state: KeyboardUiState,
    actions: TypingActions,
    autoFocus: Boolean,
) {
    val focus = remember { FocusRequester() }
    if (autoFocus) {
        LaunchedEffect(focus) { runCatching { focus.requestFocus() } }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = state.text,
            onValueChange = actions.onTextChange,
            label = { Text(state.fieldLabel.ifEmpty { stringResource(R.string.keyboard_field_label) }) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { actions.onEnter() }),
            modifier = Modifier.weight(1f).focusRequester(focus),
        )
        SmallIconKey(Icons.AutoMirrored.Rounded.Backspace, stringResource(R.string.keyboard_backspace), onClick = actions.onBackspace)
        SmallIconKey(Icons.AutoMirrored.Rounded.KeyboardReturn, stringResource(R.string.keyboard_enter), onClick = actions.onEnter)
    }
    if (state.keyPresses) {
        Text(
            text = stringResource(R.string.keyboard_key_presses_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** Folded: the keyboard bar above the system keyboard, with a close button. */
@Composable
fun KeyboardBar(
    state: KeyboardUiState,
    actions: TypingActions,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = false,
) {
    GlassCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = keyboardHeadline(state),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            SmallIconKey(Icons.Rounded.Close, stringResource(R.string.keyboard_close), size = 40.dp, onClick = onClose)
        }
        Spacer(Modifier.height(8.dp))
        KeyboardEntry(state, actions, autoFocus)
    }
}

/** Unfolded: the same controls as a side-panel card. */
@Composable
fun KeyboardCard(state: KeyboardUiState, actions: TypingActions, modifier: Modifier = Modifier) {
    GlassCard(title = stringResource(R.string.keyboard_title), modifier = modifier) {
        Text(
            text = keyboardHeadline(state),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        KeyboardEntry(state, actions, autoFocus = false)
    }
}

/** Folded: the apps grid in place of the pad. */
@Composable
fun AppsPane(
    state: AppsUiState,
    actions: AppsActions,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        AppsCard(state, actions, columns = 3, trailing = {
            SmallIconKey(Icons.Rounded.Close, stringResource(R.string.apps_close), size = 40.dp, onClick = onClose)
        })
    }
}

/** The grid, its Edit/Done switch and, while editing, the add form. */
@Composable
fun AppsCard(
    state: AppsUiState,
    actions: AppsActions,
    columns: Int,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
) {
    GlassCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.apps_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (state.canEdit) {
                PillButton(
                    text = stringResource(if (state.editing) R.string.apps_done else R.string.apps_edit),
                    icon = if (state.editing) null else Icons.Rounded.Edit,
                    onClick = { actions.onEditing(!state.editing) },
                    fillWidth = false,
                )
                Spacer(Modifier.width(8.dp))
            }
            trailing()
        }
        Spacer(Modifier.height(12.dp))
        // At most 24 tiles: a plain Column of Rows (no lazy grid). Rows are cached
        // per list, and each tile is keyed by its link so a reorder within a row keeps its state.
        val rows = remember(state.shortcuts, columns) { state.shortcuts.withIndex().chunked(columns) }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            rows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { (index, shortcut) ->
                        key(shortcut.link) {
                            AppTile(
                                shortcut = shortcut,
                                editing = state.editing,
                                first = index == 0,
                                last = index == state.shortcuts.lastIndex,
                                onLaunch = { actions.onLaunch(shortcut) },
                                onMoveUp = { actions.onMoveUp(index) },
                                onMoveDown = { actions.onMoveDown(index) },
                                onRemove = { actions.onRemove(index) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        val current = state.currentApp
        if (state.canEdit && current != null) {
            Spacer(Modifier.height(12.dp))
            PillButton(
                text = stringResource(R.string.apps_add_current, current.label),
                icon = Icons.Rounded.Add,
                onClick = actions.onAddCurrent,
            )
        }
        if (state.editing) {
            Spacer(Modifier.height(12.dp))
            AddAppForm(state, actions)
        } else if (!state.canEdit) {
            Text(
                text = stringResource(R.string.apps_pair_to_edit),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

@Composable
private fun AppTile(
    shortcut: AppShortcut,
    editing: Boolean,
    first: Boolean,
    last: Boolean,
    onLaunch: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    ContrastSurface(
        color = glassFill(),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, LocalBackgroundChrome.current.glassBorder),
        onClick = if (editing) null else {
            {
                haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                onLaunch()
            }
        },
        modifier = modifier.height(if (editing) 136.dp else 104.dp),
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            ContrastSurface(color = LocalBackgroundChrome.current.accentText, shape = CircleShape, modifier = Modifier.size(44.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Text(AppShortcuts.initials(shortcut.label), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = shortcut.label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            if (editing) {
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (!first) {
                        SmallIconKey(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.apps_move_earlier, shortcut.label), size = 32.dp, onClick = onMoveUp)
                    }
                    SmallIconKey(Icons.Rounded.Close, stringResource(R.string.apps_remove, shortcut.label), size = 32.dp, onClick = onRemove)
                    if (!last) {
                        SmallIconKey(Icons.AutoMirrored.Rounded.ArrowForward, stringResource(R.string.apps_move_later, shortcut.label), size = 32.dp, onClick = onMoveDown)
                    }
                }
            }
        }
    }
}

@Composable
private fun AddAppForm(state: AppsUiState, actions: AppsActions) {
    Text(
        text = stringResource(R.string.apps_add_title),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
    )
    OutlinedTextField(
        value = state.form.link,
        onValueChange = actions.onLinkChange,
        label = { Text(stringResource(R.string.apps_add_link_label)) },
        placeholder = { Text(stringResource(R.string.apps_add_link_hint)) },
        singleLine = true,
        isError = state.form.error != null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = state.form.label,
        onValueChange = actions.onLabelChange,
        label = { Text(stringResource(R.string.apps_add_name_label)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { actions.onSubmit() }),
        modifier = Modifier.fillMaxWidth(),
    )
    state.form.error?.let { error ->
        // Inside the glass card: onSurfaceVariant is the card's own content colour.
        Text(
            text = when (error) {
                AppShortcutError.Invalid -> stringResource(R.string.apps_error_invalid)
                AppShortcutError.BlockedScheme -> stringResource(
                    R.string.apps_error_blocked_scheme,
                    AppShortcuts.allowedSchemes.joinToString(", "),
                )
                AppShortcutError.Duplicate -> stringResource(R.string.apps_error_duplicate)
                AppShortcutError.Full -> stringResource(R.string.apps_error_full)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PillButton(text = stringResource(R.string.apps_add), icon = Icons.Rounded.Add, primary = true, onClick = actions.onSubmit, modifier = Modifier.weight(1f))
        PillButton(text = stringResource(R.string.apps_reset), icon = Icons.Rounded.Restore, onClick = actions.onReset, modifier = Modifier.weight(1f))
    }
}

/**
 * Swipe pad (plan §3.1): drags become d-pad steps, one per 48 dp along the
 * stronger axis, and a tap is OK. Each step ticks.
 */
@Composable
fun SwipePad(
    actions: RemoteActions,
    modifier: Modifier = Modifier,
    size: Dp = 236.dp,
) {
    val haptics = LocalHapticFeedback.current
    val stepPx = with(LocalDensity.current) { SwipeStepDp.toPx() }
    val onTap by rememberUpdatedState(actions.onTap)
    val description = stringResource(R.string.remote_swipe_pad)
    ContrastSurface(
        color = discFill(),
        shape = RoundedCornerShape(36.dp),
        border = BorderStroke(1.dp, LocalBackgroundChrome.current.glassBorder),
        modifier = modifier.size(size),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .semantics { contentDescription = description }
                .pointerInput(stepPx) {
                    val stepper = SwipeStepper(stepPx)
                    detectDragGestures(
                        onDragStart = { stepper.reset() },
                        onDragEnd = { stepper.reset() },
                        onDragCancel = { stepper.reset() },
                    ) { change, drag ->
                        change.consume()
                        stepper.drag(drag.x, drag.y).forEach { key ->
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                            onTap(key)
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(onTap = {
                        haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                        onTap(RemoteKey.Ok)
                    })
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.remote_swipe_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(24.dp),
            )
        }
    }
}

private val SwipeStepDp = 48.dp

/** "Waking <name>…" while the power-on flow runs. */
@Composable
fun WakingChip(state: PowerUiState.Waking, modifier: Modifier = Modifier) {
    ContrastSurface(
        color = glassFill(),
        shape = RoundedCornerShape(percent = 50),
        border = BorderStroke(1.dp, LocalBackgroundChrome.current.glassBorder),
        modifier = modifier.height(40.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BusyIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
            Text(
                text = stringResource(
                    if (state.sentPacket) R.string.power_waking else R.string.power_waking_no_packet,
                    state.deviceName,
                ),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** The TV did not come back: per-device standby tips (plan §7.3). */
@Composable
fun PowerTipCard(
    state: PowerUiState.Failed,
    onTryAgain: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassCard(title = stringResource(R.string.power_failed_title, state.deviceName), modifier = modifier) {
        Text(
            text = stringResource(R.string.power_failed_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.tips.forEach { tip ->
            Row(modifier = Modifier.padding(top = 8.dp)) {
                Text("•", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(16.dp))
                Text(stringResource(tipText(tip)), style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(stringResource(R.string.power_try_again), onClick = onTryAgain, primary = true, modifier = Modifier.weight(1f))
            PillButton(stringResource(R.string.power_dismiss), onClick = onDismiss, modifier = Modifier.weight(1f))
        }
    }
}

private fun tipText(tip: StandbyTip): Int = when (tip) {
    StandbyTip.NetworkStandby -> R.string.tip_network_standby
    StandbyTip.TclScreenless -> R.string.tip_tcl_screenless
    StandbyTip.XgimiWifiOnly -> R.string.tip_xgimi_wifi_only
    StandbyTip.PreferEthernet -> R.string.tip_prefer_ethernet
    StandbyTip.NoMac -> R.string.tip_no_mac
    StandbyTip.DeepSleep -> R.string.tip_deep_sleep
}

/** First run, nothing paired: what Sceptrum needs (plan §8 onboarding copy). */
@Composable
fun WelcomeCard(onFindTv: () -> Unit, modifier: Modifier = Modifier) {
    GlassCard(title = stringResource(R.string.remote_welcome_title), modifier = modifier) {
        Text(
            text = stringResource(R.string.remote_welcome_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        PillButton(stringResource(R.string.remote_welcome_action), onClick = onFindTv, primary = true)
    }
}

/** Plan §8: shown instead of "TV offline" when Android blocks LAN access (from targetSdk 37). */
@Composable
fun LanBlockedCard(onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    GlassCard(title = stringResource(R.string.lan_blocked_title), modifier = modifier) {
        Text(
            text = stringResource(R.string.lan_blocked_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        PillButton(stringResource(R.string.lan_open_settings), onClick = onOpenSettings, primary = true)
    }
}
