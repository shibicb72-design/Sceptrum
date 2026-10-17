package com.shibi.sceptrum.ui.remote

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
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
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.shibi.sceptrum.R
import com.shibi.sceptrum.domain.AppBadge
import com.shibi.sceptrum.domain.AppShortcutError
import com.shibi.sceptrum.domain.AppShortcuts
import com.shibi.sceptrum.domain.LearnedAppTile
import com.shibi.sceptrum.domain.LearnedApps
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
import com.shibi.sceptrum.ui.background.contentColorForContainer

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
    /** Removes a "Seen on this TV" tile for good, by package. */
    val onRemoveLearned: (String) -> Unit = {},
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

/** Tiles are at least this wide: three folded (~400 dp), two in the side panel from 610 dp to the unfolded inner screen. */
internal val AppTileMinWidth = 96.dp

/** Folded: the apps grid in place of the pad, scrolling on its own. */
@Composable
fun AppsPane(
    state: AppsUiState,
    actions: AppsActions,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = !LocalBackgroundChrome.current.isLight
    var showAll by rememberSaveable { mutableStateOf(false) }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(AppTileMinWidth),
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        appsSection(
            state,
            actions,
            dark,
            showAllLearned = showAll,
            onShowAllLearned = { showAll = it },
            trailing = {
                SmallIconKey(Icons.Rounded.Close, stringResource(R.string.apps_close), size = 40.dp, onClick = onClose)
            },
        )
    }
}

/**
 * The apps section of a lazy grid: a header card with Edit/Done, the curated
 * tiles, the tiles learned from this TV under "Seen on this TV", and a footer
 * card with "Add current app", the add form or a hint. [dark] picks the badge
 * tone; it is read once by the caller rather than in every item. Past
 * [LearnedApps.PREVIEW] learned tiles, the rest wait for [showAllLearned].
 */
fun LazyGridScope.appsSection(
    state: AppsUiState,
    actions: AppsActions,
    dark: Boolean,
    showAllLearned: Boolean = false,
    onShowAllLearned: (Boolean) -> Unit = {},
    trailing: @Composable () -> Unit = {},
) {
    item(key = "apps-header", contentType = "apps-header", span = { GridItemSpan(maxLineSpan) }) {
        AppsHeader(state, actions, trailing)
    }
    val keys = tileKeys(state.shortcuts)
    itemsIndexed(state.shortcuts, key = { index, _ -> keys[index] }, contentType = { _, _ -> "app-tile" }) { index, shortcut ->
        AppTile(
            label = shortcut.label,
            badgeSeed = AppShortcuts.badgeSeed(shortcut.link),
            dark = dark,
            editing = state.editing,
            first = index == 0,
            last = index == state.shortcuts.lastIndex,
            onLaunch = { actions.onLaunch(shortcut) },
            onMoveUp = { actions.onMoveUp(index) },
            onMoveDown = { actions.onMoveDown(index) },
            onRemove = { actions.onRemove(index) },
        )
    }
    if (state.learned.isNotEmpty()) {
        item(key = "learned-header", contentType = "learned-header", span = { GridItemSpan(maxLineSpan) }) {
            LearnedHeader()
        }
        val overflow = state.learned.size > LearnedApps.PREVIEW
        val shown = if (overflow && !showAllLearned) state.learned.take(LearnedApps.PREVIEW) else state.learned
        items(shown, key = { "learned:${it.packageName}" }, contentType = { "learned-tile" }) { app ->
            LearnedTile(
                app = app,
                dark = dark,
                editing = state.editing,
                onLaunch = { actions.onLaunch(app.shortcut) },
                onRemove = { actions.onRemoveLearned(app.packageName) },
            )
        }
        if (overflow) {
            item(key = "learned-more", contentType = "learned-more", span = { GridItemSpan(maxLineSpan) }) {
                PillButton(
                    text = if (showAllLearned) {
                        stringResource(R.string.apps_show_fewer)
                    } else {
                        stringResource(R.string.apps_show_all, state.learned.size)
                    },
                    onClick = { onShowAllLearned(!showAllLearned) },
                )
            }
        }
    }
    val current = state.currentApp
    val learnHint = state.canEdit && !state.editing && state.learned.isEmpty()
    if ((state.canEdit && current != null) || state.editing || !state.canEdit || learnHint) {
        item(key = "apps-footer", contentType = "apps-footer", span = { GridItemSpan(maxLineSpan) }) {
            AppsFooter(state, actions, learnHint)
        }
    }
}

/**
 * Lazy keys for the curated tiles: the link, which the grid keeps unique, with
 * a counter on any repeat so an old stored duplicate can never crash the grid.
 */
private fun tileKeys(shortcuts: List<AppShortcut>): List<String> {
    val seen = HashMap<String, Int>()
    return shortcuts.map { tile ->
        val n = seen.merge(tile.link, 1, Int::plus) ?: 1
        if (n == 1) "app:${tile.link}" else "app:${tile.link}#$n"
    }
}

@Composable
private fun AppsHeader(state: AppsUiState, actions: AppsActions, trailing: @Composable () -> Unit) {
    GlassCard {
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
    }
}

/** "Seen on this TV": a small chip on its own glass, above the learned tiles. */
@Composable
private fun LearnedHeader() {
    Row {
        ContrastSurface(
            color = glassFill(),
            shape = RoundedCornerShape(percent = 50),
            border = BorderStroke(1.dp, LocalBackgroundChrome.current.glassBorder),
            modifier = Modifier.padding(top = 4.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Rounded.History, contentDescription = null, modifier = Modifier.size(16.dp))
                Text(
                    text = stringResource(R.string.apps_seen_on_tv),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun AppsFooter(state: AppsUiState, actions: AppsActions, learnHint: Boolean) {
    GlassCard {
        val current = state.currentApp
        if (state.canEdit && current != null) {
            PillButton(
                text = stringResource(R.string.apps_add_current, current.label),
                icon = Icons.Rounded.Add,
                onClick = actions.onAddCurrent,
            )
            if (state.editing || learnHint) Spacer(Modifier.height(12.dp))
        }
        when {
            state.editing -> AddAppForm(state, actions)
            !state.canEdit -> FooterHint(stringResource(R.string.apps_pair_to_edit))
            learnHint -> FooterHint(stringResource(R.string.apps_learn_hint))
        }
    }
}

@Composable
private fun FooterHint(text: String) {
    // Inside the glass card: onSurfaceVariant is the card's own content colour.
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** The letter badge: an opaque fill from [seed]; ContrastSurface picks the letters' colour from that fill. */
@Composable
private fun AppBadgeIcon(label: String, seed: String, dark: Boolean) {
    val fill = remember(seed, dark) { Color(AppBadge.colorsFor(seed, dark).fillArgb) }
    ContrastSurface(color = fill, shape = CircleShape, modifier = Modifier.size(44.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Text(AppShortcuts.initials(label), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun AppTile(
    label: String,
    badgeSeed: String,
    dark: Boolean,
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
            AppBadgeIcon(label, badgeSeed, dark)
            Spacer(Modifier.height(6.dp))
            TileLabel(label)
            if (editing) {
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (!first) {
                        SmallIconKey(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.apps_move_earlier, label), size = 32.dp, onClick = onMoveUp)
                    }
                    SmallIconKey(Icons.Rounded.Close, stringResource(R.string.apps_remove, label), size = 32.dp, onClick = onRemove)
                    if (!last) {
                        SmallIconKey(Icons.AutoMirrored.Rounded.ArrowForward, stringResource(R.string.apps_move_later, label), size = 32.dp, onClick = onMoveDown)
                    }
                }
            }
        }
    }
}

@Composable
private fun TileLabel(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
    )
}

/**
 * A tile for an app the TV reported: tap launches it, a long press offers
 * "Remove", and in edit mode it shows a remove button. A small history mark
 * in the corner sets it apart from the curated tiles.
 */
@Composable
private fun LearnedTile(
    app: LearnedAppTile,
    dark: Boolean,
    editing: Boolean,
    onLaunch: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    var menuOpen by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(18.dp)
    val fill = glassFill()
    val seen = stringResource(R.string.apps_seen_on_tv)
    val removeLabel = stringResource(R.string.apps_remove, app.label)
    val optionsLabel = stringResource(R.string.apps_learned_options, app.label)
    val interaction = if (editing) {
        Modifier
    } else {
        // combinedClickable gives the long-press haptic itself.
        Modifier.clip(shape).combinedClickable(
            onLongClickLabel = optionsLabel,
            onLongClick = { menuOpen = true },
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                onLaunch()
            },
        )
    }
    Box(modifier) {
        ContrastSurface(
            color = fill,
            shape = shape,
            border = BorderStroke(1.dp, LocalBackgroundChrome.current.glassBorder),
            modifier = interaction
                .height(if (editing) 136.dp else 104.dp)
                .semantics { stateDescription = seen },
        ) {
            Box {
                Icon(
                    Icons.Rounded.History,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(14.dp),
                )
                Column(
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    AppBadgeIcon(app.label, app.packageName, dark)
                    Spacer(Modifier.height(6.dp))
                    TileLabel(app.label)
                    if (editing) {
                        Spacer(Modifier.height(4.dp))
                        SmallIconKey(Icons.Rounded.Close, removeLabel, size = 32.dp, onClick = onRemove)
                    }
                }
            }
        }
        val menuText = contentColorForContainer(fill)
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, containerColor = fill) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.apps_remove_learned)) },
                leadingIcon = { Icon(Icons.Rounded.Close, contentDescription = null) },
                colors = MenuDefaults.itemColors(textColor = menuText, leadingIconColor = menuText),
                onClick = {
                    menuOpen = false
                    onRemove()
                },
            )
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
