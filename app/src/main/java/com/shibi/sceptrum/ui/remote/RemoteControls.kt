package com.shibi.sceptrum.ui.remote

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.shibi.sceptrum.R
import com.shibi.sceptrum.atv.VolumeInfo
import com.shibi.sceptrum.domain.ConnectionStatus
import com.shibi.sceptrum.domain.RemoteKey
import com.shibi.sceptrum.domain.RemoteSurfaces
import com.shibi.sceptrum.ui.background.ContrastSurface
import com.shibi.sceptrum.ui.background.LocalBackgroundChrome
import com.shibi.sceptrum.ui.background.toComposeColor

internal val KeySize = 60.dp
private val KeyShape = RoundedCornerShape(18.dp)
private val RockerWidth = 68.dp
private val RockerHeight = 168.dp

/** Key callbacks. Hold-aware controls use press/release; the rest tap. */
class RemoteActions(
    val onTap: (RemoteKey) -> Unit = {},
    val onPress: (RemoteKey) -> Unit = {},
    val onRelease: (RemoteKey) -> Unit = {},
    val onRetry: () -> Unit = {},
    val onOpenDevices: () -> Unit = {},
)

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

/**
 * Press and release for hold-to-repeat and long press, with a ripple and a
 * VirtualKey haptic on touch-down. Accessibility services see a normal button
 * whose click is a press followed by a release.
 */
@Composable
fun Modifier.holdable(onPress: () -> Unit, onRelease: () -> Unit): Modifier {
    val press by rememberUpdatedState(onPress)
    val release by rememberUpdatedState(onRelease)
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    return this
        .semantics {
            role = Role.Button
            onClick {
                press()
                release()
                true
            }
        }
        .indication(interaction, ripple())
        .pointerInput(Unit) {
            detectTapGestures(
                onPress = { offset ->
                    val down = PressInteraction.Press(offset)
                    interaction.emit(down)
                    haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                    press()
                    val released = tryAwaitRelease()
                    release()
                    interaction.emit(if (released) PressInteraction.Release(down) else PressInteraction.Cancel(down))
                },
            )
        }
}

/** A rounded-square remote key. Content colour comes from its own fill. */
@Composable
fun RemoteKey(
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    size: Dp = KeySize,
    onClick: () -> Unit = {},
) {
    val haptics = LocalHapticFeedback.current
    ContrastSurface(
        color = glassFill(),
        shape = KeyShape,
        border = glassBorder(),
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
            onClick()
        },
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

/** A [RemoteKey]-style key with press and release, for long-press keys such as Home. */
@Composable
fun GlassHoldKey(
    icon: ImageVector,
    contentDescription: String,
    onPress: () -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = KeySize,
) {
    ContrastSurface(
        color = glassFill(),
        shape = KeyShape,
        border = glassBorder(),
        modifier = modifier.size(size),
    ) {
        Box(modifier = Modifier.holdable(onPress, onRelease), contentAlignment = Alignment.Center) {
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
    val haptics = LocalHapticFeedback.current
    ContrastSurface(
        color = glassFill(),
        shape = KeyShape,
        border = glassBorder(),
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
            onClick()
        },
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
    val haptics = LocalHapticFeedback.current
    ContrastSurface(
        color = RemoteSurfaces.POWER.toComposeColor(),
        shape = CircleShape,
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
            onClick()
        },
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

/** The d-pad: a filled disc with four repeating arrows and a centre OK (long press capable). */
@Composable
fun DpadDisc(
    modifier: Modifier = Modifier,
    size: Dp = 236.dp,
    actions: RemoteActions = RemoteActions(),
) {
    ContrastSurface(
        color = discFill(),
        shape = CircleShape,
        border = glassBorder(),
        modifier = modifier.size(size),
    ) {
        Box {
            val arrow = size * 0.30f
            DpadArrow(Icons.Rounded.KeyboardArrowUp, R.string.remote_up, arrow, Alignment.TopCenter, RemoteKey.Up, actions)
            DpadArrow(Icons.Rounded.KeyboardArrowDown, R.string.remote_down, arrow, Alignment.BottomCenter, RemoteKey.Down, actions)
            DpadArrow(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, R.string.remote_left, arrow, Alignment.CenterStart, RemoteKey.Left, actions)
            DpadArrow(Icons.AutoMirrored.Rounded.KeyboardArrowRight, R.string.remote_right, arrow, Alignment.CenterEnd, RemoteKey.Right, actions)
            ContrastSurface(
                color = glassFill(),
                shape = CircleShape,
                border = glassBorder(),
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(size * 0.38f),
            ) {
                Box(
                    modifier = Modifier.holdable(
                        onPress = { actions.onPress(RemoteKey.Ok) },
                        onRelease = { actions.onRelease(RemoteKey.Ok) },
                    ),
                    contentAlignment = Alignment.Center,
                ) {
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
private fun BoxScope.DpadArrow(
    icon: ImageVector,
    labelRes: Int,
    size: Dp,
    alignment: Alignment,
    key: RemoteKey,
    actions: RemoteActions,
) {
    Box(
        modifier = Modifier
            .align(alignment)
            .size(size)
            .holdable(onPress = { actions.onPress(key) }, onRelease = { actions.onRelease(key) }),
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
    upKey: RemoteKey,
    downKey: RemoteKey,
    actions: RemoteActions,
    modifier: Modifier = Modifier,
) {
    ContrastSurface(
        color = discFill(),
        shape = RoundedCornerShape(percent = 50),
        border = glassBorder(),
        modifier = modifier.size(width = RockerWidth, height = RockerHeight),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            RockerHalf(upIcon, upDescription, upKey, actions, Modifier.weight(1f))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            RockerHalf(downIcon, downDescription, downKey, actions, Modifier.weight(1f))
        }
    }
}

@Composable
private fun RockerHalf(
    icon: ImageVector,
    description: String,
    key: RemoteKey,
    actions: RemoteActions,
    modifier: Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .holdable(onPress = { actions.onPress(key) }, onRelease = { actions.onRelease(key) }),
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
fun VolumeRocker(actions: RemoteActions, modifier: Modifier = Modifier) {
    Rocker(
        label = stringResource(R.string.remote_volume),
        upIcon = Icons.Rounded.Add,
        upDescription = stringResource(R.string.remote_volume_up),
        downIcon = Icons.Rounded.Remove,
        downDescription = stringResource(R.string.remote_volume_down),
        upKey = RemoteKey.VolumeUp,
        downKey = RemoteKey.VolumeDown,
        actions = actions,
        modifier = modifier,
    )
}

@Composable
fun ChannelRocker(actions: RemoteActions, modifier: Modifier = Modifier) {
    Rocker(
        label = stringResource(R.string.remote_channel),
        upIcon = Icons.Rounded.KeyboardArrowUp,
        upDescription = stringResource(R.string.remote_channel_up),
        downIcon = Icons.Rounded.KeyboardArrowDown,
        downDescription = stringResource(R.string.remote_channel_down),
        upKey = RemoteKey.ChannelUp,
        downKey = RemoteKey.ChannelDown,
        actions = actions,
        modifier = modifier,
    )
}

/** Device chip: status dot and name. Tapping it opens Devices. */
@Composable
fun DeviceChip(
    name: String,
    status: ConnectionStatus,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    ContrastSurface(
        color = glassFill(),
        shape = RoundedCornerShape(percent = 50),
        border = glassBorder(),
        onClick = onClick,
        modifier = modifier.height(48.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(statusDotColor(status), CircleShape),
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

/** Plan §7.2: green connected, amber (re)connecting, grey offline, red pairing needed. */
@Composable
private fun statusDotColor(status: ConnectionStatus): Color = when (status) {
    ConnectionStatus.Connected -> Color(0xFF2E9E4F)
    ConnectionStatus.Connecting, ConnectionStatus.Reconnecting -> Color(0xFFE0A100)
    ConnectionStatus.NeedsPairing, ConnectionStatus.IdentityChanged -> RemoteSurfaces.POWER.toComposeColor()
    ConnectionStatus.Offline, ConnectionStatus.NoDevice -> MaterialTheme.colorScheme.onSurfaceVariant
}

/**
 * Connection status as words, with Retry while reconnecting or offline and
 * "Pair again" when the TV no longer accepts the phone.
 */
@Composable
fun StatusChip(
    status: ConnectionStatus,
    actions: RemoteActions,
    modifier: Modifier = Modifier,
) {
    val action = when {
        status.showsRetry -> stringResource(R.string.status_retry) to actions.onRetry
        status.needsPairing || status == ConnectionStatus.NoDevice ->
            stringResource(R.string.status_open_devices) to actions.onOpenDevices
        else -> null
    }
    ContrastSurface(
        color = glassFill(),
        shape = RoundedCornerShape(percent = 50),
        border = glassBorder(),
        modifier = modifier.height(40.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = if (action == null) 14.dp else 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(8.dp).background(statusDotColor(status), CircleShape))
            Text(text = statusText(status), style = MaterialTheme.typography.labelLarge)
            if (action != null) {
                ContrastSurface(
                    color = LocalBackgroundChrome.current.accentText,
                    shape = RoundedCornerShape(percent = 50),
                    onClick = action.second,
                    modifier = Modifier.height(32.dp),
                ) {
                    Box(Modifier.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                        Text(action.first, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun statusText(status: ConnectionStatus): String = stringResource(
    when (status) {
        ConnectionStatus.NoDevice -> R.string.status_no_device
        ConnectionStatus.Connecting -> R.string.status_connecting
        ConnectionStatus.Connected -> R.string.status_connected
        ConnectionStatus.Reconnecting -> R.string.status_reconnecting
        ConnectionStatus.Offline -> R.string.status_offline
        ConnectionStatus.NeedsPairing -> R.string.status_needs_pairing
        ConnectionStatus.IdentityChanged -> R.string.status_identity_changed
    },
)

/** The TV's volume as a number only: there is no bar and no slider (plan §3.3). */
@Composable
fun VolumeChip(volume: VolumeInfo, modifier: Modifier = Modifier) {
    val text = if (volume.muted) {
        stringResource(R.string.volume_muted, volume.level, volume.max)
    } else {
        stringResource(R.string.volume_level, volume.level, volume.max)
    }
    ContrastSurface(
        color = glassFill(),
        shape = RoundedCornerShape(percent = 50),
        border = glassBorder(),
        modifier = modifier.height(40.dp),
    ) {
        Box(Modifier.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
            Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
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
