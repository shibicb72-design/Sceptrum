package com.shibi.sceptrum.remote

import com.shibi.sceptrum.domain.AppShortcutError
import com.shibi.sceptrum.domain.AppShortcuts
import com.shibi.sceptrum.domain.TvShortcut
import com.shibi.sceptrum.domain.TvStep
import com.shibi.sceptrum.store.AppShortcut
import com.shibi.sceptrum.store.PairedDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun onlyAllowListedSchemesAreAccepted() {
        for (link in listOf(
            "https://www.youtube.com",
            "HTTPS://www.youtube.com",
            "http://192.168.1.20:32400/web",
            "market://details?id=com.plexapp.android",
            "plex://",
            "netflix://title/80100172",
            "youtube://watch?v=x",
            "vnd.youtube:dQw4w9WgXcQ",
            "spotify:playlist:37i9dQZF1DX0XUsuxWHRQd",
            "primevideo://detail",
        )) {
            assertEquals(link, link.replaceFirst("HTTPS", "https"), AppShortcuts.linkFor(link))
            assertNull(link, AppShortcuts.refusalFor(link))
        }
        assertEquals("market://launch?id=com.netflix.ninja", AppShortcuts.linkFor("Market://launch?id=com.netflix.ninja"))
        for (link in listOf(
            "intent:#Intent;component=com.android.tv.settings/.MainSettings;end",
            "intent://scan/#Intent;scheme=zxing;package=com.google.zxing.client.android;end",
            "INTENT:#Intent;action=android.settings.SETTINGS;end",
            "file:///sdcard/Download/app.apk",
            "content://com.android.providers.downloads/all",
            "javascript:alert(1)",
            "data:text/html,<script>alert(1)</script>",
            "android-app://com.example.app",
            "tel:123",
            "ftp://example.com",
        )) {
            assertNull(link, AppShortcuts.linkFor(link))
            assertEquals(link, AppShortcutError.BlockedScheme, AppShortcuts.refusalFor(link))
            assertNull(link, AppShortcuts.parse("Name", link))
        }
    }

    @Test
    fun anIntentFragmentInsideAnAllowedSchemeIsBlocked() {
        // Intent.parseUri turns "#Intent;…;end" into an intent whatever the scheme.
        for (link in listOf(
            "https://www.youtube.com#Intent;component=com.android.tv.settings/.MainSettings;S.k=v;end",
            "https://example.com/#intent;component=com.evil/.Steal;end",
            "market://launch?id=x#Intent;action=android.settings.SETTINGS;end",
            "plex:#Intent;component=com.evil/.Steal;end",
            "vnd.youtube:x#Intent;component=com.evil/.Steal;end",
        )) {
            assertFalse(link, AppShortcuts.isAllowed(link))
            assertEquals(link, AppShortcutError.BlockedScheme, AppShortcuts.refusalFor(link))
        }
    }

    @Test
    fun logLabelsDropPathsQueriesAndFragments() {
        assertEquals("https://www.netflix.com", AppShortcuts.logLabel("https://www.netflix.com/title/123?auth=s3cret"))
        assertEquals("plex://server", AppShortcuts.logLabel("plex://server/library?X-Plex-Token=abc"))
        assertEquals("spotify:", AppShortcuts.logLabel("spotify:playlist:37i9"))
        assertEquals("market://launch?id=com.android.tv.settings", AppShortcuts.logLabel("market://launch?id=com.android.tv.settings"))
        val builtIn = "intent:#Intent;component=com.android.tv.settings/.MainSettings;end"
        assertEquals("built-in fallbacks stay whole", builtIn, AppShortcuts.logLabel(builtIn))
    }

    @Test
    fun webLinksNeedAHost() {
        for (link in listOf("https:", "https:www.youtube.com", "https://", "https:///path", "market:launch?id=x", "http://?q")) {
            assertEquals(link, AppShortcutError.BlockedScheme, AppShortcuts.refusalFor(link))
        }
        assertEquals(AppShortcutError.Invalid, AppShortcuts.refusalFor("https://x\u0000y"))
        assertEquals(AppShortcutError.Invalid, AppShortcuts.refusalFor("spotify"))
        assertEquals(AppShortcutError.Invalid, AppShortcuts.refusalFor("  "))
    }

    @Test
    fun theBuiltInIntentFallbacksAreNotUserLinks() {
        // TvShortcuts sends these directly; the user path must never accept them.
        val intents = TvShortcut.entries.flatMap { it.steps }.filterIsInstance<TvStep.Link>().map { it.uri }.filter { it.startsWith("intent:") }
        assertTrue(intents.isNotEmpty())
        intents.forEach { assertFalse(it, AppShortcuts.isAllowed(it)) }
        AppShortcuts.defaults.forEach { assertTrue(it.link, AppShortcuts.isAllowed(it.link)) }
    }

    @Test
    fun storedTilesWithBlockedSchemesAreLeftOut() {
        val evil = AppShortcut("Evil", "intent:#Intent;component=com.evil/.Steal;end")
        val file = AppShortcut("File", "file:///sdcard/x")
        val device = PairedDevice(id = "a", app_shortcuts = listOf(yt, evil, plex, file), app_shortcuts_edited = true)
        assertEquals(listOf(yt, plex), AppShortcuts.effective(device))
        assertEquals(listOf(evil, file), AppShortcuts.blocked(device))
        assertTrue(AppShortcuts.blocked(null).isEmpty())
        assertTrue(AppShortcuts.blocked(PairedDevice(id = "a", app_shortcuts = listOf(evil))).isEmpty())
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
    fun readableNamesFromUnknownPackages() {
        assertEquals("Pluto", AppShortcuts.labelForPackage("tv.pluto.android"))
        assertEquals("Geforcenow", AppShortcuts.labelForPackage("com.nvidia.geforcenow"))
        assertEquals("Bar", AppShortcuts.labelForPackage("com.foo.bar.tv.livingroom"))
        assertEquals("Xgimi", AppShortcuts.labelForPackage("com.xgimi.xgimitv"))
        assertEquals("Joyn", AppShortcuts.labelForPackage("de.prosiebensat1digital.joyn.androidtv"))
        assertEquals("Myapp", AppShortcuts.labelForPackage("com.example.myapp"))
        assertEquals("Apple TV", AppShortcuts.labelForPackage("com.apple.atve.androidtv.appletv"))
        assertEquals("all parts generic: the last one", "Tv", AppShortcuts.labelForPackage("com.android.tv"))
    }

    @Test
    fun launchLinksPreferAKnownDeepLink() {
        assertEquals("https://www.youtube.com", AppShortcuts.launchLinkFor("com.google.android.youtube.tv"))
        assertEquals("plex://", AppShortcuts.launchLinkFor("com.plexapp.android"))
        assertEquals("market://launch?id=org.xbmc.kodi", AppShortcuts.launchLinkFor("org.xbmc.kodi"))
        AppShortcuts.knownPackages.keys.forEach { assertTrue(it, AppShortcuts.isAllowed(AppShortcuts.launchLinkFor(it))) }
        assertTrue(AppShortcuts.covers(AppShortcuts.defaults[0], "com.google.android.youtube.tv"))
        assertTrue(AppShortcuts.covers(AppShortcut("Kodi", "market://launch?id=org.xbmc.kodi"), "org.xbmc.kodi"))
        assertFalse(AppShortcuts.covers(AppShortcuts.defaults[0], "org.xbmc.kodi"))
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
