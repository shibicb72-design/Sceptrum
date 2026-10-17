package com.shibi.sceptrum.ui.pairing

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.shibi.sceptrum.R
import com.shibi.sceptrum.atv.PairingCode
import com.shibi.sceptrum.domain.PairingError
import com.shibi.sceptrum.domain.PairingTarget
import com.shibi.sceptrum.domain.PairingUiState
import com.shibi.sceptrum.ui.BusyIndicator
import com.shibi.sceptrum.ui.GlassCard
import com.shibi.sceptrum.ui.PillButton
import com.shibi.sceptrum.ui.background.ContrastSurface
import com.shibi.sceptrum.ui.background.LocalBackgroundChrome
import com.shibi.sceptrum.ui.remote.KeyRow
import com.shibi.sceptrum.ui.remote.RemoteKey
import com.shibi.sceptrum.ui.remote.RemoteTextKey
import com.shibi.sceptrum.ui.remote.discFill

class PairingActions(
    val onCodeChange: (String) -> Unit = {},
    val onRetry: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onOpenRemote: () -> Unit = {},
)

/** Pairing card: connecting, code entry, paired, or one error card (plan §5.5). */
@Composable
fun PairingPane(
    state: PairingUiState,
    actions: PairingActions,
    modifier: Modifier = Modifier,
) {
    val target = when (state) {
        PairingUiState.Idle -> return
        is PairingUiState.Connecting -> state.target
        is PairingUiState.EnterCode -> state.target
        is PairingUiState.Failed -> state.target
        is PairingUiState.Paired -> state.target
    }
    GlassCard(
        title = stringResource(R.string.pairing_title, target.name),
        modifier = modifier.widthIn(max = 520.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            when (state) {
                is PairingUiState.Connecting -> Busy(stringResource(R.string.pairing_connecting, target.name))
                is PairingUiState.EnterCode -> CodeEntry(state, actions)
                is PairingUiState.Failed -> FailedCard(state.error, target, actions)
                is PairingUiState.Paired -> PairedCard(target, actions)
                PairingUiState.Idle -> Unit
            }
            if (state !is PairingUiState.Paired) {
                PillButton(text = stringResource(R.string.pairing_cancel), onClick = actions.onCancel)
            }
        }
    }
}

@Composable
private fun CodeEntry(state: PairingUiState.EnterCode, actions: PairingActions) {
    Text(
        text = stringResource(R.string.pairing_enter_code, state.target.name),
        style = MaterialTheme.typography.bodyLarge,
    )
    CodeBoxes(code = state.code, enabled = !state.submitting, onCodeChange = actions.onCodeChange)
    when {
        state.submitting -> Busy(stringResource(R.string.pairing_checking))
        state.error != null -> ErrorLine(errorMessage(state.error, state.target))
    }
    HexKeypad(
        enabled = !state.submitting,
        onDigit = { actions.onCodeChange(state.code + it) },
        onBackspace = { actions.onCodeChange(state.code.dropLast(1)) },
    )
}

/**
 * Six boxes over one text field, so typing, paste and the on-screen keypad
 * all feed the same filtered value. The box after the last character is
 * highlighted, which is the auto-advance.
 */
@Composable
fun CodeBoxes(
    code: String,
    onCodeChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val label = stringResource(R.string.pairing_code_label)
    val accent = LocalBackgroundChrome.current.accentText
    val idle = LocalBackgroundChrome.current.glassBorder
    BasicTextField(
        value = code,
        onValueChange = { onCodeChange(PairingCode.sanitize(it)) },
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Ascii,
            imeAction = ImeAction.Done,
        ),
        modifier = modifier.semantics { contentDescription = label },
        decorationBox = { _ ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                repeat(PairingCode.LENGTH) { index ->
                    val char = code.getOrNull(index)?.toString().orEmpty()
                    val focused = index == code.length && enabled
                    ContrastSurface(
                        color = discFill(),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp)
                            .border(if (focused) 2.dp else 1.dp, if (focused) accent else idle, RoundedCornerShape(12.dp)),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = char,
                                style = MaterialTheme.typography.headlineSmall,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        },
    )
}

/** 0-9 and A-F, since no system keyboard has a hex layout. */
@Composable
private fun HexKeypad(enabled: Boolean, onDigit: (Char) -> Unit, onBackspace: () -> Unit) {
    val rows = listOf("012345", "6789AB", "CDEF")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row ->
            KeyRow {
                row.forEach { c -> RemoteTextKey(c.toString(), size = KeypadKey, onClick = { if (enabled) onDigit(c) }) }
                if (row.length < 6) {
                    RemoteKey(
                        icon = Icons.AutoMirrored.Rounded.Backspace,
                        contentDescription = stringResource(R.string.pairing_backspace),
                        size = KeypadKey,
                        onClick = { if (enabled) onBackspace() },
                    )
                    Spacer(Modifier.size(KeypadKey))
                }
            }
        }
    }
}

@Composable
private fun FailedCard(error: PairingError, target: PairingTarget, actions: PairingActions) {
    ErrorLine(errorMessage(error, target))
    errorHint(error)?.let { hint ->
        Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    PillButton(text = stringResource(R.string.pairing_retry), onClick = actions.onRetry, primary = true)
}

@Composable
private fun PairedCard(target: PairingTarget, actions: PairingActions) {
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(target) { haptics.performHapticFeedback(HapticFeedbackType.Confirm) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.CheckCircle, contentDescription = null, modifier = Modifier.size(32.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                text = stringResource(R.string.pairing_paired, target.name),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.pairing_paired_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    PillButton(text = stringResource(R.string.pairing_open_remote), onClick = actions.onOpenRemote, primary = true)
    PillButton(text = stringResource(R.string.pairing_done), onClick = actions.onCancel)
}

@Composable
private fun Busy(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        BusyIndicator(modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(14.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ErrorLine(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(Icons.Rounded.ErrorOutline, contentDescription = null, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Start,
        )
    }
}

@Composable
private fun errorMessage(error: PairingError, target: PairingTarget): String = when (error) {
    PairingError.WrongCode -> stringResource(R.string.pairing_error_wrong_code)
    PairingError.BadSecret -> stringResource(R.string.pairing_error_bad_secret)
    PairingError.Rejected -> stringResource(R.string.pairing_error_rejected)
    PairingError.Timeout -> stringResource(R.string.pairing_error_timeout)
    PairingError.Unreachable -> stringResource(R.string.pairing_error_unreachable, target.host, target.pairingPort)
}

@Composable
private fun errorHint(error: PairingError): String? = when (error) {
    PairingError.Rejected -> stringResource(R.string.pairing_error_rejected_hint)
    PairingError.Timeout -> stringResource(R.string.pairing_error_timeout_hint)
    PairingError.Unreachable -> stringResource(R.string.pairing_error_unreachable_hint)
    PairingError.WrongCode, PairingError.BadSecret -> null
}

private val KeypadKey = 48.dp
