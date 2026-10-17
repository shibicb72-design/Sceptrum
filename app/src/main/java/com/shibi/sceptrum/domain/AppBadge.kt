package com.shibi.sceptrum.domain

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The letter badge of a tile without real artwork. The fill comes from the
 * package name (same app, same colour, on every phone), dark in the dark
 * theme and light in the light one. The fill is opaque and the letters use
 * the colour [BackgroundContrast.rolesForContainer] picks for it, which is
 * what [com.shibi.sceptrum.ui.background.ContrastSurface] paints, so the
 * badge reads the same over any app background. The fill is pushed further
 * from mid-grey until that pair reaches [BackgroundContrast.MIN_BODY_CONTRAST].
 */
object AppBadge {
    data class Colors(val fillArgb: Long, val contentArgb: Long) {
        val contrast: Double get() = BackgroundContrast.contrastRatio(fillArgb, contentArgb)
    }

    private const val SATURATION = 0.55
    private const val DARK_LIGHTNESS = 0.34
    private const val LIGHT_LIGHTNESS = 0.80
    private const val STEP = 0.02

    /** Fill and letter colours for [seed] (a package name, else a link) in the [dark] or light theme. */
    fun colorsFor(seed: String, dark: Boolean): Colors {
        val hue = hueOf(seed)
        var lightness = if (dark) DARK_LIGHTNESS else LIGHT_LIGHTNESS
        while (true) {
            val fill = hsl(hue, SATURATION, lightness)
            val content = BackgroundContrast.rolesForContainer(fill).onBackgroundArgb
            val colors = Colors(fill, content)
            // Black or white fills always pass, so the loop ends.
            if (colors.contrast >= BackgroundContrast.MIN_BODY_CONTRAST) return colors
            lightness = if (dark) (lightness - STEP).coerceAtLeast(0.0) else (lightness + STEP).coerceAtMost(1.0)
        }
    }

    /**
     * A stable hue in degrees from a 32-bit FNV-1a hash of [seed], which spreads
     * similar names ("com.a.tv", "com.b.tv") better than String.hashCode.
     */
    fun hueOf(seed: String): Double {
        var hash = 0x811C9DC5.toInt()
        for (c in seed) {
            hash = (hash xor c.code) * 0x01000193
        }
        return (abs(hash.toLong()) % 360).toDouble()
    }

    private fun hsl(hueDegrees: Double, saturation: Double, lightness: Double): Long {
        val c = (1 - abs(2 * lightness - 1)) * saturation
        val h = hueDegrees / 60.0
        val x = c * (1 - abs(h % 2 - 1))
        val (r, g, b) = when {
            h < 1 -> Triple(c, x, 0.0)
            h < 2 -> Triple(x, c, 0.0)
            h < 3 -> Triple(0.0, c, x)
            h < 4 -> Triple(0.0, x, c)
            h < 5 -> Triple(x, 0.0, c)
            else -> Triple(c, 0.0, x)
        }
        val m = lightness - c / 2
        fun channel(v: Double): Long = ((v + m) * 255).roundToInt().coerceIn(0, 255).toLong()
        return 0xFF000000L or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
    }
}
