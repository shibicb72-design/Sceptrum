package com.shibi.sceptrum

import androidx.compose.ui.graphics.toArgb
import com.shibi.sceptrum.domain.AppBackground
import com.shibi.sceptrum.domain.BackgroundCatalog
import com.shibi.sceptrum.domain.BackgroundContrast
import com.shibi.sceptrum.domain.RemoteSurfaces
import com.shibi.sceptrum.domain.shownWith
import com.shibi.sceptrum.ui.background.DarkBackgroundChrome
import com.shibi.sceptrum.ui.background.LightBackgroundChrome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundContrastTest {
    private val darkFlags = listOf(false, true)

    @Test
    fun luminanceOfWhiteAndBlack() {
        assertEquals(1.0, BackgroundContrast.relativeLuminance(0xFFFFFFFF), 0.001)
        assertEquals(0.0, BackgroundContrast.relativeLuminance(0xFF000000), 0.001)
    }

    @Test
    fun catalogHasTwentyFourPastelsPlusTheDarkDefault() {
        assertEquals(16, BackgroundCatalog.solids.size)
        assertEquals(8, BackgroundCatalog.blends.size)
        assertEquals(25, BackgroundCatalog.all.size)
        assertEquals(BackgroundCatalog.all.size, BackgroundCatalog.all.map { it.id }.toSet().size)
    }

    @Test
    fun defaultBackdropUsesLightContent() {
        val roles = BackgroundContrast.roles(BackgroundCatalog.DEFAULT_ARGB)
        assertFalse(roles.isLight)
        assertEquals(BackgroundContrast.ON_DARK, roles.onBackgroundArgb)
    }

    @Test
    fun everyPastelIsLightWithDarkText() {
        BackgroundCatalog.all.filter { it != AppBackground.Default }.forEach { background ->
            val roles = BackgroundContrast.rolesFor(background)
            assertTrue("${background.id} should be light", roles.isLight)
            assertEquals(background.id, BackgroundContrast.ON_LIGHT, roles.onBackgroundArgb)
        }
    }

    /** Page text: every backdrop (each colour of a blend) with Dark on and off. */
    @Test
    fun pageTextIsReadableOnEveryBackdropDarkOnAndOff() {
        forEveryPainted { label, background, sample ->
            val roles = BackgroundContrast.rolesFor(background)
            assertReadable("$label page text", roles.onBackgroundArgb, sample)
            assertReadable("$label page variant", roles.onVariantArgb, sample)
        }
    }

    /** Remote keys, d-pad disc, rockers and the power key: content comes from the container. */
    @Test
    fun everyButtonContainerIsReadableOnEveryBackdropDarkOnAndOff() {
        forEveryPainted { label, background, sample ->
            val pageLight = BackgroundContrast.rolesFor(background).isLight
            RemoteSurfaces.containers(pageLight).forEach { container ->
                val painted = BackgroundContrast.compositeOver(container, sample)
                val roles = BackgroundContrast.rolesForContainer(container, sample)
                val tag = "$label container ${container.toString(16)}"
                assertReadable("$tag text", roles.onBackgroundArgb, painted)
                assertTrue(
                    "$tag icon",
                    BackgroundContrast.contrastRatio(roles.onVariantArgb, painted) >=
                        BackgroundContrast.MIN_ICON_CONTRAST,
                )
            }
        }
    }

    @Test
    fun accentTextIsReadableOnEveryPage() {
        forEveryPainted { label, background, sample ->
            val chrome = if (BackgroundContrast.rolesFor(background).isLight) {
                LightBackgroundChrome
            } else {
                DarkBackgroundChrome
            }
            val accent = chrome.accentText.toArgb().toLong() and 0xFFFFFFFFL
            assertReadable("$label accent", accent, sample)
        }
    }

    @Test
    fun neverChoosesLightOnLightOrDarkOnDark() {
        assertEquals(BackgroundContrast.ON_LIGHT, BackgroundContrast.contentArgb(0xFFF8C8D4))
        assertEquals(BackgroundContrast.ON_DARK, BackgroundContrast.contentArgb(0xFF0E0E12))
    }

    @Test
    fun translucentContainerIsMeasuredOverTheWindowBehindIt() {
        // Faint white glass over the dark page is still dark: light text.
        val onDark = BackgroundContrast.rolesForContainer(RemoteSurfaces.GLASS_DARK, BackgroundCatalog.DEFAULT_ARGB)
        assertFalse(onDark.isLight)
        // Mostly-white glass over a pastel is light: dark text.
        val onPastel = BackgroundContrast.rolesForContainer(RemoteSurfaces.GLASS_LIGHT, 0xFFC5E4F7)
        assertTrue(onPastel.isLight)
    }

    @Test
    fun compositeOverHonoursAlpha() {
        assertEquals(0xFFFFFFFFL, BackgroundContrast.compositeOver(0xFFFFFFFFL, 0xFF000000L))
        assertEquals(0xFF000000L, BackgroundContrast.compositeOver(0x00FFFFFFL, 0xFF000000L))
        assertEquals(0xFF808080L, BackgroundContrast.compositeOver(0x80FFFFFFL, 0xFF000000L))
    }

    private fun forEveryPainted(block: (String, AppBackground, Long) -> Unit) {
        BackgroundCatalog.all.forEach { picked ->
            darkFlags.forEach { dark ->
                val shown = picked.shownWith(dark)
                BackgroundCatalog.colorsFor(shown).forEach { sample ->
                    block("${picked.id} dark=$dark sample=${sample.toString(16)}", shown, sample)
                }
            }
        }
    }

    private fun assertReadable(label: String, content: Long, container: Long) {
        val ratio = BackgroundContrast.contrastRatio(content, container)
        assertTrue("$label ratio $ratio", ratio >= BackgroundContrast.MIN_BODY_CONTRAST)
    }
}
