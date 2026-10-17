package com.shibi.sceptrum

import com.shibi.sceptrum.domain.AppBackground
import com.shibi.sceptrum.domain.AppearancePersistence
import com.shibi.sceptrum.domain.BackgroundCatalog
import com.shibi.sceptrum.domain.shownWith
import org.junit.Assert.assertEquals
import org.junit.Test

class AppearancePersistenceTest {
    @Test
    fun everyBackdropRoundTrips() {
        BackgroundCatalog.all.forEach { background ->
            assertEquals(background, AppearancePersistence.decode(AppearancePersistence.encode(background)))
        }
    }

    @Test
    fun missingOrUnknownIdsFallBackToTheDarkDefault() {
        assertEquals(AppBackground.Default, AppearancePersistence.decode(null))
        assertEquals(AppBackground.Default, AppearancePersistence.decode(""))
        assertEquals(AppBackground.Default, AppearancePersistence.decode("solid:neon"))
        assertEquals(AppBackground.Default, AppearancePersistence.decode("gradient:"))
        // Spectacula's animated scenes are not ported; a stray id must not crash.
        assertEquals(AppBackground.Default, AppearancePersistence.decode("animated:waves"))
    }

    @Test
    fun darkOnPaintsTheDefaultAndOffRestoresThePick() {
        val pick = AppBackground.Gradient("mermaid")
        assertEquals(AppBackground.Default, pick.shownWith(darkTheme = true))
        assertEquals(pick, pick.shownWith(darkTheme = false))
    }

    @Test
    fun freshInstallOpensDark() {
        assertEquals(true, AppearancePersistence.DEFAULT_DARK_THEME)
    }
}
