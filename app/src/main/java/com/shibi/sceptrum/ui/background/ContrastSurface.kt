package com.shibi.sceptrum.ui.background

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.shibi.sceptrum.domain.BackgroundContrast

@Composable
internal fun rememberSurfaceRoles(color: Color): BackgroundContrast.Roles {
    val chrome = LocalBackgroundChrome.current
    val containerArgb = color.toArgb().toLong() and 0xFFFFFFFFL
    return remember(containerArgb, chrome.sampleArgb) {
        BackgroundContrast.rolesForContainer(containerArgb, chrome.sampleArgb)
    }
}

@Composable
fun contentColorForContainer(color: Color): Color {
    return rememberSurfaceRoles(color).onBackgroundArgb.toComposeColor()
}

/**
 * Surface whose [contentColor] and on-surface roles come from the luminance
 * of [color] (composited over the window sample), never from the window
 * [androidx.compose.material3.ColorScheme.onBackground]. Ported from Spectacula.
 */
@Composable
fun ContrastSurface(
    color: Color,
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    border: BorderStroke? = null,
    shadowElevation: Dp = 0.dp,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val roles = rememberSurfaceRoles(color)
    val contentColor = roles.onBackgroundArgb.toComposeColor()
    val variant = roles.onVariantArgb.toComposeColor()
    val scheme = MaterialTheme.colorScheme.copy(
        surface = color.copy(alpha = 1f),
        onSurface = contentColor,
        onSurfaceVariant = variant,
    )
    val body: @Composable () -> Unit = {
        MaterialTheme(colorScheme = scheme, content = content)
    }
    if (onClick != null) {
        Surface(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier,
            shape = shape,
            color = color,
            contentColor = contentColor,
            shadowElevation = shadowElevation,
            border = border,
            content = body,
        )
    } else {
        Surface(
            modifier = modifier,
            shape = shape,
            color = color,
            contentColor = contentColor,
            shadowElevation = shadowElevation,
            border = border,
            content = body,
        )
    }
}
