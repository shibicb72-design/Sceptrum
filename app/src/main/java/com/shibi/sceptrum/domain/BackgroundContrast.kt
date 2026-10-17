package com.shibi.sceptrum.domain

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * WCAG-style contrast helpers, ported from Spectacula. Android stays out so
 * JUnit can own the math.
 */
object BackgroundContrast {
    const val LIGHT_LUMINANCE_CUTOFF = 0.45
    const val MIN_BODY_CONTRAST = 4.5
    const val MIN_ICON_CONTRAST = 3.0

    const val ON_LIGHT: Long = 0xFF16161C
    const val ON_DARK: Long = 0xFFF4F4F8
    const val ON_LIGHT_VARIANT: Long = 0xFF454055
    const val ON_DARK_VARIANT: Long = 0xFFB4B4C4

    const val FROST_LIGHT: Long = 0xC8FFFFFF
    const val FROST_DARK: Long = 0xEB24242E

    data class Roles(
        val sampleArgb: Long,
        val isLight: Boolean,
        val onBackgroundArgb: Long,
        val onVariantArgb: Long,
        val frostArgb: Long,
    ) {
        val onSurfaceArgb: Long get() = onBackgroundArgb
    }

    fun relativeLuminance(argb: Long): Double {
        val r = linearChannel(((argb shr 16) and 0xFF).toInt())
        val g = linearChannel(((argb shr 8) and 0xFF).toInt())
        val b = linearChannel((argb and 0xFF).toInt())
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    fun isLight(argb: Long): Boolean = relativeLuminance(argb) >= LIGHT_LUMINANCE_CUTOFF

    fun contrastRatio(first: Long, second: Long): Double {
        val a = relativeLuminance(first)
        val b = relativeLuminance(second)
        val light = max(a, b)
        val dark = min(a, b)
        return (light + 0.05) / (dark + 0.05)
    }

    fun contentArgb(backgroundArgb: Long): Long {
        val darkRatio = contrastRatio(ON_LIGHT, backgroundArgb)
        val lightRatio = contrastRatio(ON_DARK, backgroundArgb)
        return if (darkRatio >= lightRatio) ON_LIGHT else ON_DARK
    }

    fun roles(sampleArgb: Long): Roles {
        val light = isLight(sampleArgb)
        return Roles(
            sampleArgb = sampleArgb,
            isLight = light,
            onBackgroundArgb = if (light) ON_LIGHT else ON_DARK,
            onVariantArgb = if (light) ON_LIGHT_VARIANT else ON_DARK_VARIANT,
            frostArgb = if (light) FROST_LIGHT else FROST_DARK,
        )
    }

    /**
     * Content roles for the surface that is actually painted, not the window
     * behind it. Translucent [containerArgb] is composited over [behindArgb]
     * before luminance is measured.
     */
    fun rolesForContainer(
        containerArgb: Long,
        behindArgb: Long = 0xFF000000L,
    ): Roles {
        return roles(compositeOver(containerArgb, behindArgb))
    }

    fun isReadable(contentArgb: Long, containerArgb: Long): Boolean {
        return contrastRatio(contentArgb, containerArgb) >= MIN_BODY_CONTRAST
    }

    fun compositeOver(foregroundArgb: Long, backgroundArgb: Long): Long {
        val fg = foregroundArgb and 0xFFFFFFFFL
        val bg = backgroundArgb and 0xFFFFFFFFL
        val fa = ((fg shr 24) and 0xFFL).toInt()
        if (fa >= 255) return 0xFF000000L or (fg and 0x00FFFFFFL)
        if (fa <= 0) return 0xFF000000L or (bg and 0x00FFFFFFL)
        val alpha = fa / 255.0
        fun mix(shift: Int): Long {
            val f = ((fg shr shift) and 0xFFL).toInt()
            val b = ((bg shr shift) and 0xFFL).toInt()
            return kotlin.math.round(f * alpha + b * (1.0 - alpha)).toLong()
        }
        return 0xFF000000L or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    fun rolesFor(background: AppBackground): Roles {
        val colors = BackgroundCatalog.colorsFor(background)
        val luminance = colors.map(::relativeLuminance).average()
        val representative = if (luminance >= LIGHT_LUMINANCE_CUTOFF) {
            colors.maxBy(::relativeLuminance)
        } else {
            colors.minBy(::relativeLuminance)
        }
        return roles(representative)
    }

    private fun linearChannel(value: Int): Double {
        val channel = value / 255.0
        return if (channel <= 0.03928) {
            channel / 12.92
        } else {
            ((channel + 0.055) / 1.055).pow(2.4)
        }
    }
}
