package com.shibi.sceptrum.remote

import com.shibi.sceptrum.domain.AppShortcutError
import com.shibi.sceptrum.domain.AppShortcuts
import com.shibi.sceptrum.store.AppShortcut
import com.shibi.sceptrum.store.PairedDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppShortcutsTest {
    private val yt = AppShortcut("YouTube", "https://www.youtube.com")
    private val nf = AppShortcut("Netflix", "https://www.netflix.com/title")
    private val plex = AppShortcut("Plex", "plex://")

    @Test
    fun defaultsFollowThePlan() {
        assertEquals(
            listOf("YouTube", "Netflix", "Prime Video", "Disney+", "Plex", "Settings"),
            AppShortcuts.defaults.map { it.label },
        )
        assertEquals("market://launch?id=com.android.tv.settings", AppShortcuts.defaults.last().link)
    }

    @Test
    fun effectiveGridIsTheDefaultsUntilEdited() {
        assertEquals(AppShortcuts.defaults, AppShortcuts.effective(null))
        assertEquals(AppShortcuts.defaults, AppShortcuts.effective(PairedDevice(id = "a")))
        val edited = PairedDevice(id = "a", app_shortcuts = listOf(plex), app_shortcuts_edited = true)
        assertEquals(listOf(plex), AppShortcuts.effective(edited))
        assertTrue(
            "an emptied grid stays empty",
            AppShortcuts.effective(PairedDevice(id = "a", app_shortcuts_edited = true)).isEmpty(),
        )
    }

    @Test
    fun linksFromWhatTheUserTyped() {
        assertEquals("https://www.youtube.com", AppShortcuts.linkFor(" https://www.youtube.com "))
        assertEquals("plex://", AppShortcuts.linkFor("plex://"))
        assertEquals("market://launch?id=com.spotify.tv.android", AppShortcuts.linkFor("com.spotify.tv.android"))
        assertNull(AppShortcuts.linkFor("spotify"))
        assertNull(AppShortcuts.linkFor("com.spotify tv"))
        assertNull(AppShortcuts.linkFor(""))
        assertNull(AppShortcuts.linkFor("1com.bad.package"))
    }

    @Test
    fun labelsAreKnownNamesHostsOrPackageParts() {
        assertEquals("Netflix", AppShortcuts.labelFor("market://launch?id=com.netflix.ninja"))
        assertEquals("Kodi", AppShortcuts.labelFor("market://launch?id=org.xbmc.kodi"))
        assertEquals("Myapp", AppShortcuts.labelFor("market://launch?id=com.example.myapp"))
        assertEquals("Prime Video", AppShortcuts.labelFor("https://app.primevideo.com"))
        assertEquals("Twitch", AppShortcuts.labelFor("https://www.twitch.tv/directory"))
        assertEquals("Spotify", AppShortcuts.labelFor("spotify://"))
        assertEquals(AppShortcut("Spotify", "spotify://"), AppShortcuts.parse("  ", "spotify://"))
        assertEquals(AppShortcut("Music", "spotify://"), AppShortcuts.parse(" Music ", "spotify://"))
        assertNull(AppShortcuts.parse("X", "not a link"))
    }

    @Test
    fun currentAppBecomesAMarketLaunchTile() {
        assertEquals(
            AppShortcut("YouTube", "market://launch?id=com.google.android.youtube.tv"),
            AppShortcuts.forPackage("com.google.android.youtube.tv"),
        )
        assertNull(AppShortcuts.forPackage(""))
        assertNull(AppShortcuts.forPackage("not a package"))
    }

    @Test
    fun addRefusesDuplicatesAndAFullGrid() {
        assertEquals(AppShortcuts.Result.Ok(listOf(yt, nf)), AppShortcuts.add(listOf(yt), nf))
        assertEquals(
            AppShortcuts.Result.Refused(AppShortcutError.Duplicate),
            AppShortcuts.add(listOf(yt), AppShortcut("Other name", yt.link)),
        )
        val full = List(AppShortcuts.MAX) { AppShortcut("A$it", "app$it://") }
        assertEquals(AppShortcuts.Result.Refused(AppShortcutError.Full), AppShortcuts.add(full, plex))
    }

    @Test
    fun moveAndRemove() {
        val list = listOf(yt, nf, plex)
        assertEquals(listOf(nf, yt, plex), AppShortcuts.move(list, 1, 0))
        assertEquals(listOf(yt, plex, nf), AppShortcuts.move(list, 1, 2))
        assertEquals(listOf(nf, plex, yt), AppShortcuts.move(list, 0, 2))
        assertEquals(list, AppShortcuts.move(list, 0, -1))
        assertEquals(list, AppShortcuts.move(list, 2, 3))
        assertEquals(listOf(yt, plex), AppShortcuts.remove(list, 1))
        assertEquals(list, AppShortcuts.remove(list, 5))
    }

    @Test
    fun initialsForTheBadge() {
        assertEquals("Yo", AppShortcuts.initials("YouTube"))
        assertEquals("PV", AppShortcuts.initials("Prime Video"))
        assertEquals("Di", AppShortcuts.initials("Disney+"))
        assertEquals("NT", AppShortcuts.initials("news-today"))
        assertEquals("?", AppShortcuts.initials(" "))
    }
}
