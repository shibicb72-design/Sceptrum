package com.shibi.sceptrum.ui.devices

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shibi.sceptrum.R
import com.shibi.sceptrum.domain.WindowWidthClass
import com.shibi.sceptrum.ui.GlassCard
import com.shibi.sceptrum.ui.SectionHeader
import com.shibi.sceptrum.ui.background.ContrastSurface
import com.shibi.sceptrum.ui.background.LocalBackgroundChrome
import com.shibi.sceptrum.ui.remote.glassFill

/** Devices placeholder: list on the left, details on the right when unfolded. */
@Composable
fun DevicesScreen(
    widthClass: WindowWidthClass,
    modifier: Modifier = Modifier,
) {
    if (widthClass.isMultiPane) {
        Row(modifier.fillMaxSize()) {
            DeviceListPane(modifier = Modifier.weight(0.42f).fillMaxHeight())
            DeviceDetailPane(modifier = Modifier.weight(0.58f).fillMaxHeight())
        }
    } else {
        DeviceListPane(modifier = modifier.fillMaxSize())
    }
}

@Composable
fun DeviceListPane(modifier: Modifier = Modifier) {
    Column(modifier = modifier.verticalScroll(rememberScrollState())) {
        SectionHeader(
            title = stringResource(R.string.devices_title),
            subtitle = stringResource(R.string.devices_subtitle),
        )
        Column(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            GlassCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.Tv,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                    )
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
            AddDeviceButton()
        }
    }
}

@Composable
private fun AddDeviceButton() {
    ContrastSurface(
        color = glassFill(),
        shape = RoundedCornerShape(percent = 50),
        border = BorderStroke(1.dp, LocalBackgroundChrome.current.glassBorder),
        onClick = {},
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.devices_add_ip),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun DeviceDetailPane(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.padding(16.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        GlassCard(
            title = stringResource(R.string.devices_detail_title),
            modifier = Modifier.padding(top = 72.dp),
        ) {
            Text(
                text = stringResource(R.string.devices_detail_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
