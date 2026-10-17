package com.shibi.sceptrum.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shibi.sceptrum.R
import com.shibi.sceptrum.domain.AppBackground
import com.shibi.sceptrum.domain.BackgroundCatalog
import com.shibi.sceptrum.domain.WindowWidthClass
import com.shibi.sceptrum.ui.GlassCard
import com.shibi.sceptrum.ui.PillButton
import com.shibi.sceptrum.ui.SectionHeader
import com.shibi.sceptrum.ui.background.LocalBackgroundChrome
import com.shibi.sceptrum.ui.background.toComposeColor

/**
 * Settings: Dark theme toggle and the pastel grid (Dark on paints the dark
 * backdrop over the pick without overwriting it; off restores it), keyboard
 * behaviour, the debugging card that opens the error log, About and the
 * open-source licences.
 */
@Composable
fun SettingsScreen(
    widthClass: WindowWidthClass,
    darkTheme: Boolean,
    background: AppBackground,
    onDarkThemeChange: (Boolean) -> Unit,
    onSelectBackground: (AppBackground) -> Unit,
    modifier: Modifier = Modifier,
    keyPressTyping: Boolean = false,
    onKeyPressTypingChange: (Boolean) -> Unit = {},
    versionName: String = "",
    errorCount: Int = 0,
    onOpenErrorLog: () -> Unit = {},
) {
    if (widthClass.isMultiPane) {
        Row(modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .weight(0.4f)
                    .verticalScroll(rememberScrollState()),
            ) {
                SectionHeader(title = stringResource(R.string.settings_title))
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    DarkThemeCard(darkTheme, onDarkThemeChange)
                    KeyboardSettingsCard(keyPressTyping, onKeyPressTypingChange)
                    DebuggingCard(errorCount, onOpenErrorLog)
                    AboutCard(versionName)
                    LicencesCard()
                }
            }
            Column(
                modifier = Modifier
                    .weight(0.6f)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 0.dp, end = 16.dp, top = 72.dp, bottom = 16.dp),
            ) {
                BackgroundCard(darkTheme, background, onSelectBackground, columns = 6)
            }
        }
    } else {
        Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            SectionHeader(title = stringResource(R.string.settings_title))
            Column(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                DarkThemeCard(darkTheme, onDarkThemeChange)
                BackgroundCard(darkTheme, background, onSelectBackground, columns = 5)
                KeyboardSettingsCard(keyPressTyping, onKeyPressTypingChange)
                DebuggingCard(errorCount, onOpenErrorLog)
                AboutCard(versionName)
                LicencesCard()
            }
        }
    }
}

@Composable
private fun DarkThemeCard(
    darkTheme: Boolean,
    onDarkThemeChange: (Boolean) -> Unit,
) {
    GlassCard(title = stringResource(R.string.settings_appearance)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_dark),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(
                        if (darkTheme) R.string.settings_dark_on else R.string.settings_dark_off,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = darkTheme, onCheckedChange = onDarkThemeChange)
        }
    }
}

@Composable
private fun BackgroundCard(
    darkTheme: Boolean,
    selected: AppBackground,
    onSelect: (AppBackground) -> Unit,
    columns: Int,
) {
    GlassCard(title = stringResource(R.string.settings_background)) {
        if (darkTheme) {
            Text(
                text = stringResource(R.string.settings_background_dark_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        GroupLabel(stringResource(R.string.settings_solids))
        SwatchGrid(
            items = listOf(AppBackground.Default) +
                BackgroundCatalog.solids.map { AppBackground.Solid(it.id) },
            selected = selected,
            onSelect = onSelect,
            columns = columns,
        )
        Spacer(Modifier.height(12.dp))
        GroupLabel(stringResource(R.string.settings_blends))
        SwatchGrid(
            items = BackgroundCatalog.blends.map { AppBackground.Gradient(it.id) },
            selected = selected,
            onSelect = onSelect,
            columns = columns,
        )
    }
}

@Composable
private fun GroupLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 6.dp),
    )
}

@Composable
private fun SwatchGrid(
    items: List<AppBackground>,
    selected: AppBackground,
    onSelect: (AppBackground) -> Unit,
    columns: Int,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.chunked(columns).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                row.forEach { item ->
                    Swatch(
                        background = item,
                        selected = item == selected,
                        onClick = { onSelect(item) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun Swatch(
    background: AppBackground,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BackgroundCatalog.colorsFor(background).map { it.toComposeColor() }
    val brush = if (colors.size == 1) Brush.linearGradient(colors + colors) else Brush.linearGradient(colors)
    val ring = if (selected) {
        MaterialTheme.colorScheme.onSurface
    } else {
        LocalBackgroundChrome.current.cardBorder
    }
    Column(
        modifier = modifier
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .border(width = if (selected) 3.dp else 1.dp, color = ring, shape = CircleShape)
                .padding(if (selected) 5.dp else 1.dp)
                .clip(CircleShape)
                .background(brush),
        )
        Text(
            text = stringResource(backgroundLabel(background)),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun KeyboardSettingsCard(keyPressTyping: Boolean, onChange: (Boolean) -> Unit) {
    GlassCard(title = stringResource(R.string.settings_keyboard)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_key_presses),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.settings_key_presses_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = keyPressTyping, onCheckedChange = onChange)
        }
    }
}

@Composable
private fun DebuggingCard(errorCount: Int, onOpen: () -> Unit) {
    GlassCard(title = stringResource(R.string.error_log_title)) {
        Text(
            text = stringResource(R.string.error_log_summary, errorCount),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        PillButton(text = stringResource(R.string.error_log_open), onClick = onOpen)
    }
}

@Composable
private fun AboutCard(versionName: String) {
    GlassCard(title = stringResource(R.string.settings_about)) {
        Text(
            text = stringResource(R.string.settings_about_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (versionName.isNotEmpty()) {
            Text(
                text = stringResource(R.string.settings_version, versionName),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** Plan §9.1: credit androidtvremote2, Tink and Wire (all Apache-2.0). */
@Composable
private fun LicencesCard() {
    GlassCard(title = stringResource(R.string.settings_licences)) {
        listOf(
            R.string.licence_androidtvremote2,
            R.string.licence_pairing_protocol,
            R.string.licence_tink,
            R.string.licence_wire,
            R.string.licence_androidx,
            R.string.licence_full_text,
        ).forEach { line ->
            Text(
                text = stringResource(line),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
    }
}

internal fun backgroundLabel(background: AppBackground): Int = when (background) {
    AppBackground.Default -> R.string.bg_default
    is AppBackground.Solid -> when (background.swatchId) {
        "blush" -> R.string.bg_blush
        "peach" -> R.string.bg_peach
        "butter" -> R.string.bg_butter
        "mint" -> R.string.bg_mint
        "sage" -> R.string.bg_sage
        "sky" -> R.string.bg_sky
        "periwinkle" -> R.string.bg_periwinkle
        "lavender" -> R.string.bg_lavender
        "lilac" -> R.string.bg_lilac
        "rose" -> R.string.bg_rose
        "coral" -> R.string.bg_coral
        "apricot" -> R.string.bg_apricot
        "seafoam" -> R.string.bg_seafoam
        "powder_blue" -> R.string.bg_powder_blue
        "mauve" -> R.string.bg_mauve
        "cream" -> R.string.bg_cream
        else -> R.string.bg_default
    }
    is AppBackground.Gradient -> when (background.blendId) {
        "cotton_candy" -> R.string.bg_cotton_candy
        "sunrise" -> R.string.bg_sunrise
        "mermaid" -> R.string.bg_mermaid
        "sorbet" -> R.string.bg_sorbet
        "twilight" -> R.string.bg_twilight
        "peachy_keen" -> R.string.bg_peachy_keen
        "lilac_mist" -> R.string.bg_lilac_mist
        "mint_cream" -> R.string.bg_mint_cream
        else -> R.string.bg_default
    }
}
