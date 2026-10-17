package com.shibi.sceptrum.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.shibi.sceptrum.R
import com.shibi.sceptrum.ui.GlassCard
import com.shibi.sceptrum.ui.PillButton
import com.shibi.sceptrum.ui.SectionHeader

/**
 * Settings > Debugging / Error log: every caught error, newest first, with one
 * Copy all button.
 */
@Composable
fun ErrorLogScreen(
    report: String,
    entryCount: Int,
    onCopyAll: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SectionHeader(title = stringResource(R.string.error_log_title))
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            var copied by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PillButton(
                    text = stringResource(R.string.error_log_back),
                    onClick = onBack,
                    modifier = Modifier.weight(1f),
                    icon = Icons.AutoMirrored.Rounded.ArrowBack,
                    fillWidth = true,
                )
                PillButton(
                    text = stringResource(
                        if (copied) R.string.error_log_copied else R.string.error_log_copy_all,
                    ),
                    onClick = {
                        onCopyAll()
                        copied = true
                    },
                    modifier = Modifier.weight(1f),
                    icon = Icons.Rounded.ContentCopy,
                    primary = true,
                    enabled = entryCount > 0,
                    fillWidth = true,
                )
            }
            Text(
                text = stringResource(R.string.error_log_summary, entryCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (entryCount == 0) {
                GlassCard {
                    Text(stringResource(R.string.error_log_empty))
                }
            } else {
                report.split("\n\n").drop(1).forEach { block ->
                    GlassCard {
                        Text(
                            text = block,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}
