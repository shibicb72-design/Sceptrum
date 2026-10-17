package com.shibi.sceptrum.ui.theme

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

val Base0 = Color(0xFF0E0E12)
val Base1 = Color(0xFF16161C)
val Base2 = Color(0xFF1C1C24)
val Base3 = Color(0xFF24242E)
val Base4 = Color(0xFF2E2E3A)
val TextHigh = Color(0xFFF4F4F8)
val TextMed = Color(0xFFB4B4C4)

/** Imperial violet, the Spectacula accent. */
val Accent = Color(0xFF7C5CFF)

internal val SceptrumDarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF2C2366),
    onPrimaryContainer = Color(0xFFE4DDFF),
    secondary = Color(0xFFB9A8FF),
    onSecondary = Color(0xFF1B1240),
    tertiary = Color(0xFFB9A8FF),
    background = Base0,
    onBackground = TextHigh,
    surface = Base1,
    onSurface = TextHigh,
    surfaceVariant = Base4,
    onSurfaceVariant = TextMed,
    inverseSurface = TextHigh,
    inverseOnSurface = Base0,
    error = Color(0xFFFF6B7A),
    outline = Color(0xFF3A3A48),
    outlineVariant = Color(0xFF2A2A35),
    surfaceBright = Base4,
    surfaceDim = Base0,
    surfaceContainer = Base2,
    surfaceContainerHigh = Base3,
    surfaceContainerHighest = Base4,
    surfaceContainerLow = Base1,
    surfaceContainerLowest = Base0,
)

internal val SceptrumLightColors = lightColorScheme(
    primary = Color(0xFF6E4EF0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE4DDFF),
    onPrimaryContainer = Color(0xFF1B1240),
    secondary = Color(0xFF5B45C6),
    onSecondary = Color.White,
    tertiary = Color(0xFF5B45C6),
    background = Color(0xFFF6F5FA),
    onBackground = Color(0xFF16161C),
    surface = Color.White,
    onSurface = Color(0xFF16161C),
    surfaceVariant = Color(0xFFE6E4EE),
    onSurfaceVariant = Color(0xFF454055),
    inverseSurface = Color(0xFF16161C),
    inverseOnSurface = Color(0xFFF6F5FA),
    error = Color(0xFFB3261E),
    outline = Color(0xFF7A7588),
    outlineVariant = Color(0xFFD4D0DE),
    surfaceBright = Color.White,
    surfaceDim = Color(0xFFE7E5EE),
    surfaceContainer = Color(0xFFEEEDF4),
    surfaceContainerHigh = Color(0xFFE6E4EE),
    surfaceContainerHighest = Color(0xFFDEDCE8),
    surfaceContainerLow = Color(0xFFF3F2F8),
    surfaceContainerLowest = Color.White,
)

val SceptrumShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/**
 * Root theme. The root [Surface] carries `contentColor = onBackground`, so no
 * text ever falls back to the platform default (Spectacula retro V1).
 */
@Composable
fun SceptrumTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) SceptrumDarkColors else SceptrumLightColors
    MaterialTheme(
        colorScheme = colorScheme,
        shapes = SceptrumShapes,
    ) {
        Surface(
            color = colorScheme.background,
            contentColor = colorScheme.onBackground,
            modifier = Modifier.fillMaxSize(),
        ) {
            content()
        }
    }
}
