package com.shibi.sceptrum

import com.shibi.sceptrum.domain.AppBadge
import com.shibi.sceptrum.domain.AppShortcuts
import com.shibi.sceptrum.domain.BackgroundContrast
import com.shibi.sceptrum.domain.LearnedApps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Generated badges are never dark-on-dark (or light-on-light), whatever the package, theme or app background. */
class AppBadgeTest {
    /** Real TV packages plus a sweep of made-up ones, so every hue band is hit. */
    private val seeds: List<String> =
        AppShortcuts.knownPackages.keys.toList() +
            LearnedApps.deniedPackages +
            AppShortcuts.defaults.map { AppShortcuts.badgeSeed(it.link) } +
            listOf("tv.pluto.android", "com.nvidia.geforcenow", "com.xgimi.player", "a.b") +
            (0 until 2_000).map { "com.vendor$it.app${it * 7}" }

    /** What the badge may sit over: both themes' page fills and the darkest and lightest backdrops. */
    private val behind = listOf(0xFF000000L, 0xFFFFFFFFL, 0xFF16161CL, 0xFFF4F4F8L, 0xFF808080L)

    @Test
    fun everyBadgeMeetsBodyTextContrastInBothThemes() {
        val hues = mutableSetOf<Int>()
        for (dark in listOf(true, false)) {
            for (seed in seeds) {
                val colors = AppBadge.colorsFor(seed, dark)
                assertEquals("opaque fill for $seed", 0xFF, ((colors.fillArgb shr 24) and 0xFF).toInt())
                assertTrue(
                    "$seed dark=$dark: ${"%.2f".format(colors.contrast)}:1",
                    colors.contrast >= BackgroundContrast.MIN_BODY_CONTRAST,
                )
                hues += (AppBadge.hueOf(seed) / 30).toInt()
            }
        }
        assertEquals("the sweep covers every 30-degree hue band", 12, hues.size)
    }

    @Test
    fun theLettersAreWhatContrastSurfacePaintsOverAnyBackground() {
        // ContrastSurface takes the content colour from rolesForContainer(fill, sample);
        // an opaque fill makes the sample irrelevant, so custom backdrops cannot change it.
        for (dark in listOf(true, false)) {
            for (seed in seeds.take(300)) {
                val colors = AppBadge.colorsFor(seed, dark)
                for (sample in behind) {
                    val painted = BackgroundContrast.rolesForContainer(colors.fillArgb, sample).onBackgroundArgb
                    assertEquals("$seed over ${sample.toString(16)}", colors.contentArgb, painted)
                }
            }
        }
    }

    @Test
    fun darkThemeBadgesAreDarkAndLightThemeBadgesLight() {
        for (seed in seeds.take(300)) {
            assertEquals(seed, BackgroundContrast.ON_DARK, AppBadge.colorsFor(seed, dark = true).contentArgb)
            assertEquals(seed, BackgroundContrast.ON_LIGHT, AppBadge.colorsFor(seed, dark = false).contentArgb)
        }
    }

    @Test
    fun theColourIsStablePerPackage() {
        assertEquals(AppBadge.colorsFor("org.xbmc.kodi", true), AppBadge.colorsFor("org.xbmc.kodi", true))
        // The YouTube default tile and a learned YouTube share a colour.
        assertEquals("com.google.android.youtube.tv", AppShortcuts.badgeSeed("https://www.youtube.com"))
        assertEquals("org.xbmc.kodi", AppShortcuts.badgeSeed("market://launch?id=org.xbmc.kodi"))
        val distinct = listOf("com.netflix.ninja", "org.xbmc.kodi", "com.plexapp.android", "tv.pluto.android")
            .map { AppBadge.colorsFor(it, true).fillArgb }
            .toSet()
        assertTrue("different apps usually differ", distinct.size >= 3)
    }
}
