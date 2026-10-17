package com.shibi.sceptrum.ui.remote

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.shibi.sceptrum.R
import com.shibi.sceptrum.domain.RemoteSurfaces
import com.shibi.sceptrum.ui.background.ContrastSurface
import com.shibi.sceptrum.ui.background.LocalBackgroundChrome
import com.shibi.sceptrum.ui.background.toComposeColor

internal val KeySize = 60.dp
private val KeyShape = RoundedCornerShape(18.dp)
private val RockerWidth = 68.dp
private val RockerHeight = 168.dp

/** Glass fill composited over the page so a key is always opaque. */
@Composable
internal fun glassFill(): Color {
    val chrome = LocalBackgroundChrome.current
    return chrome.glass.compositeOver(chrome.pageFill.copy(alpha = 1f))
}

@Composable
internal fun discFill(): Color {
    val chrome = LocalBackgroundChrome.current
    return chrome.disc.compositeOver(chrome.pageFill.copy(alpha = 1f))
}

@Composable
private fun glassBorder(): BorderStroke =
    BorderStroke(1.dp, LocalBackgroundChrome.current.glassBorder)

/** A rounded-square remote key. Content colour comes from its own fill. */
@Composable
fun RemoteKey(
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    size: Dp = KeySize,
    onClick: () -> Unit = {},
) {
    ContrastSurface(
        color = glassFill(),
        shape = KeyShape,
        border = glassBorder(),
        onClick = onClick,
        modifier = modifier.size(size),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(size * 0.42f),
            )
        }
    }
}

/** A key that shows a short text label (numbers, OK). */
@Composable
fun RemoteTextKey(
    text: String,
    modifier: Modifier = Modifier,
    size: Dp = KeySize,
    onClick: () -> Unit = {},
) {
    ContrastSurface(
        color = glassFill(),
        shape = KeyShape,
        border = glassBorder(),
        onClick = onClick,
        modifier = modifier.size(size),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = text,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
fun PowerKey(
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
    onClick: () -> Unit = {},
) {
    ContrastSurface(
        color = RemoteSurfaces.POWER.toComposeColor(),
        shape = CircleShape,
        onClick = onClick,
        modifier = modifier.size(size),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Rounded.PowerSettingsNew,
                contentDescription = stringResource(R.string.remote_power),
                modifier = Modifier.size(size * 0.5f),
            )
        }
    }
}

/** The d-pad: a filled disc with four arrows and a centre OK key. */
@Composable
fun DpadDisc(
    modifier: Modifier = Modifier,
    size: Dp = 236.dp,
    onKey: (String) -> Unit = {},
) {
    ContrastSurface(
        color = discFill(),
        shape = CircleShape,
        border = glassBorder(),
        modifier = modifier.size(size),
    ) {
        Box {
            val arrow = size * 0.30f
            DpadArrow(Icons.Rounded.KeyboardArrowUp, R.string.remote_up, arrow, Alignment.TopCenter) { onKey("up") }
            DpadArrow(Icons.Rounded.KeyboardArrowDown, R.string.remote_down, arrow, Alignment.BottomCenter) { onKey("down") }
            DpadArrow(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, R.string.remote_left, arrow, Alignment.CenterStart) { onKey("left") }
            DpadArrow(Icons.AutoMirrored.Rounded.KeyboardArrowRight, R.string.remote_right, arrow, Alignment.CenterEnd) { onKey("right") }
            ContrastSurface(
                color = glassFill(),
                shape = CircleShape,
                border = glassBorder(),
                onClick = { onKey("ok") },
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(size * 0.38f),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.remote_ok),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.DpadArrow(
    icon: ImageVector,
    labelRes: Int,
    size: Dp,
    alignment: Alignment,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .align(alignment)
            .size(size)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = stringResource(labelRes),
            tint = LocalContentColor.current,
            modifier = Modifier.size(size * 0.62f),
        )
    }
}

/**
 * A tall pill split into an upper and a lower button with a label between.
 * Volume and channel are buttons only; Sceptrum never draws a volume slider.
 */
@Composable
fun Rocker(
    label: String,
    upIcon: ImageVector,
    upDescription: String,
    downIcon: ImageVector,
    downDescription: String,
    modifier: Modifier = Modifier,
    onUp: () -> Unit = {},
    onDown: () -> Unit = {},
) {
    ContrastSurface(
        color = discFill(),
        shape = RoundedCornerShape(percent = 50),
        border = glassBorder(),
        modifier = modifier.size(width = RockerWidth, height = RockerHeight),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            RockerHalf(upIcon, upDescription, onUp, Modifier.weight(1f))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            RockerHalf(downIcon, downDescription, onDown, Modifier.weight(1f))
        }
    }
}

@Composable
private fun RockerHalf(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            modifier = Modifier.size(30.dp),
        )
    }
}

@Composable
fun VolumeRocker(modifier: Modifier = Modifier) {
    Rocker(
        label = stringResource(R.string.remote_volume),
        upIcon = Icons.Rounded.Add,
        upDescription = stringResource(R.string.remote_volume_up),
        downIcon = Icons.Rounded.Remove,
        downDescription = stringResource(R.string.remote_volume_down),
        modifier = modifier,
    )
}

@Composable
fun ChannelRocker(modifier: Modifier = Modifier) {
    Rocker(
        label = stringResource(R.string.remote_channel),
        upIcon = Icons.Rounded.KeyboardArrowUp,
        upDescription = stringResource(R.string.remote_channel_up),
        downIcon = Icons.Rounded.KeyboardArrowDown,
        downDescription = stringResource(R.string.remote_channel_down),
        modifier = modifier,
    )
}

/** Device chip: status dot and name. Placeholder until discovery lands. */
@Composable
fun DeviceChip(
    name: String,
    modifier: Modifier = Modifier,
) {
    ContrastSurface(
        color = glassFill(),
        shape = RoundedCornerShape(percent = 50),
        border = glassBorder(),
        modifier = modifier.height(48.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
            )
        }
    }
}

/** A row of keys spread evenly across the available width. */
@Composable
fun KeyRow(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}
