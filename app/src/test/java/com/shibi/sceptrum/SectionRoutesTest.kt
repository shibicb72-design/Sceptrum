package com.shibi.sceptrum

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shibi.sceptrum.ui.nav.NavSection
import com.shibi.sceptrum.ui.nav.SectionRoutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SectionRoutesTest {
    @Test
    fun everySectionOwnsADistinctRoute() {
        assertEquals(NavSection.entries.size, SectionRoutes.all.toSet().size)
        NavSection.entries.forEach { section ->
            assertEquals(section, SectionRoutes.sectionFor(SectionRoutes.routeFor(section)))
        }
    }

    @Test
    fun appOpensOnTheRemote() {
        assertEquals(SectionRoutes.Remote, SectionRoutes.Start)
        assertEquals(NavSection.Remote, SectionRoutes.sectionFor(SectionRoutes.Start))
    }

    @Test
    fun remoteSitsInTheMiddleOfTheArc() {
        assertEquals(
            listOf(NavSection.Devices, NavSection.Remote, NavSection.Settings),
            NavSection.entries.toList(),
        )
    }

    @Test
    fun unknownRoutesMapToNoSection() {
        assertNull(SectionRoutes.sectionFor(null))
        assertNull(SectionRoutes.sectionFor("player"))
    }
}
