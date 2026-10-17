package com.shibi.sceptrum.ui.background

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.shibi.sceptrum.domain.AppBackground
import com.shibi.sceptrum.domain.BackgroundCatalog
import com.shibi.sceptrum.domain.BackgroundContrast
import com.shibi.sceptrum.domain.RemoteSurfaces
import com.shibi.sceptrum.ui.theme.Base0
import com.shibi.sceptrum.ui.theme.SceptrumDarkColors

/** Page-level colours derived from the painted backdrop (ported from Spectacula). */
data class BackgroundChrome(
    val isLight: Boolean,
    val carouselIcon: Color,
    val carouselTile: Color,
    val carouselTileBorder: Color,
    val cardBorder: Color,
    val sampleArgb: Long,
    val pageFill: Color,
    /** Accent for text and small icons on the page and cards. */
    val accentText: Color,
    /** Remote keys: opaque once composited over [pageFill]. */
    val glass: Color,
    /** D-pad disc and rockers. */
    val disc: Color,
    val glassBorder: Color,
)

val DarkBackgroundChrome = BackgroundChrome(
    isLight = false,
    carouselIcon = Color.White.copy(alpha = 0.92f),
    carouselTile = Color.White.copy(alpha = 0.12f),
    carouselTileBorder = Color.White.copy(alpha = 0.22f),
    cardBorder = Color.White.copy(alpha = 0.10f),
    sampleArgb = BackgroundCatalog.DEFAULT_ARGB,
    pageFill = Color(0xFF0E0E12),
    accentText = Color(0xFFB9A8FF),
    glass = RemoteSurfaces.GLASS_DARK.toComposeColor(),
    disc = RemoteSurfaces.DISC_DARK.toComposeColor(),
    // Decorative rings need about 40% white on dark to read (Spectacula lesson).
    glassBorder = Color.White.copy(alpha = 0.40f),
)

val LightBackgroundChrome = BackgroundChrome(
    isLight = true,
    carouselIcon = Color(0xFF1B1240),
    carouselTile = Color.White.copy(alpha = 0.78f),
    carouselTileBorder = Color(0xFF1B1240).copy(alpha = 0.16f),
    cardBorder = Color(0xFF1B1240).copy(alpha = 0.10f),
    sampleArgb = 0xFFF8C8D4,
    pageFill = Color(0xFFF8C8D4),
    accentText = Color(0xFF4A34B0),
    glass = RemoteSurfaces.GLASS_LIGHT.toComposeColor(),
    disc = RemoteSurfaces.DISC_LIGHT.toComposeColor(),
    glassBorder = Color(0xFF1B1240).copy(alpha = 0.14f),
)

val LocalBackgroundChrome = staticCompositionLocalOf { DarkBackgroundChrome }

fun chromeFor(roles: BackgroundContrast.Roles): BackgroundChrome {
    val base = if (roles.isLight) LightBackgroundChrome else DarkBackgroundChrome
    return base.copy(
        sampleArgb = roles.sampleArgb,
        pageFill = roles.sampleArgb.toComposeColor(),
    )
}

fun Long.toComposeColor(): Color = Color((this and 0xFFFFFFFFL).toInt())

/**
 * Paints [background] behind [content] and supplies backdrop-derived content
 * roles and [LocalBackgroundChrome].
 */
@Composable
fun AppBackgroundHost(
    background: AppBackground,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = remember(background) {
        BackgroundCatalog.colorsFor(background).map { it.toComposeColor() }
    }
    Box(modifier = modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            when {
                colors.isEmpty() -> drawRect(Base0)
                colors.size == 1 -> drawRect(colors[0])
                else -> drawRect(brush = Brush.linearGradient(colors))
            }
        }
        BackgroundContentTheme(background = background, content = content)
    }
}

@Composable
fun BackgroundContentTheme(
    background: AppBackground,
    content: @Composable () -> Unit,
) {
    val roles = remember(background) { BackgroundContrast.rolesFor(background) }
    val chrome = remember(roles) { chromeFor(roles) }
    val scheme = backgroundContentScheme(MaterialTheme.colorScheme, roles, chrome)
    CompositionLocalProvider(LocalBackgroundChrome provides chrome) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/**
 * Content roles follow the backdrop, not the theme flag. A dark backdrop gets
 * the dark scheme even when the theme is light, so text never goes dark-on-dark.
 */
internal fun backgroundContentScheme(
    base: ColorScheme,
    roles: BackgroundContrast.Roles,
    chrome: BackgroundChrome,
    darkScheme: ColorScheme = SceptrumDarkColors,
): ColorScheme {
    return if (roles.isLight) {
        base.copy(
            primary = chrome.accentText,
            background = chrome.pageFill,
            onBackground = roles.onBackgroundArgb.toComposeColor(),
            surface = Color(0xFFFBF8FF),
            onSurface = roles.onSurfaceArgb.toComposeColor(),
            surfaceVariant = Color(0xFFE8E4F2),
            onSurfaceVariant = roles.onVariantArgb.toComposeColor(),
            surfaceContainer = roles.frostArgb.toComposeColor(),
            surfaceContainerLow = Color.White.copy(alpha = 0.72f),
            surfaceContainerHigh = Color(0xFFF7F4FB),
            surfaceContainerHighest = Color.White.copy(alpha = 0.92f),
            inverseSurface = Color(0xFF16161C),
            inverseOnSurface = Color(0xFFF4F4F8),
            outline = Color(0xFF7A7588),
            outlineVariant = Color(0xFFD4D0DE),
        )
    } else {
        darkScheme.copy(background = chrome.pageFill)
    }
}
