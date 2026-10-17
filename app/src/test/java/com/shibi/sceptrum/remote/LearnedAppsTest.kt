package com.shibi.sceptrum.remote

import com.shibi.sceptrum.domain.AppShortcuts
import com.shibi.sceptrum.domain.LearnedAppTile
import com.shibi.sceptrum.domain.LearnedApps
import com.shibi.sceptrum.store.AppShortcut
import com.shibi.sceptrum.store.DeviceLearnedApps
import com.shibi.sceptrum.store.LearnedApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The "learn over time" rules (docs/apps-list-options.md, Option A), without storage. */
class LearnedAppsTest {
    private val empty = DeviceLearnedApps()

    @Test
    fun launchersSettingsAndSystemScreensAreNeverLearned() {
        for (pkg in listOf(
            "com.google.android.tvlauncher",
            "com.google.android.apps.tv.launcherx",
            "com.android.tv.settings",
            "com.android.systemui",
            "com.android.vending",
            "com.google.android.katniss",
            "com.google.android.apps.tv.dreamx",
            // Vendor launchers and settings caught by their last part.
            "com.xgimi.launcher",
            "com.mitv.tvlauncher",
            "com.tcl.settings",
            "com.vendor.HomeLauncher",
        )) {
            assertTrue(pkg, LearnedApps.isDenied(pkg))
            assertSame(pkg, empty, LearnedApps.record(empty, pkg, 1))
        }
        for (pkg in listOf("com.netflix.ninja", "org.xbmc.kodi", "tv.pluto.android", "com.google.android.youtube.tv")) {
            assertFalse(pkg, LearnedApps.isDenied(pkg))
        }
    }

    @Test
    fun theDenyListIsOneSetAndCoversTheBuiltInHomeEntries() {
        // The known table names two launchers "Home"; neither may become a tile.
        val homes = AppShortcuts.knownPackages.filterValues { it.label == "Home" }.keys
        assertEquals(2, homes.size)
        assertTrue(LearnedApps.deniedPackages.containsAll(homes))
    }

    @Test
    fun invalidPackagesAreIgnored() {
        for (text in listOf("", "kodi", "not a package", "1com.bad", "com.ok.app#x")) {
            assertSame(text, empty, LearnedApps.record(empty, text, 1))
        }
        // A TV can send any string up to the 1 MB frame cap; only package-sized ones are kept.
        val huge = "com." + "a".repeat(1 shl 20)
        assertSame(empty, LearnedApps.record(empty, huge, 1))
        val longest = "com." + "a".repeat(AppShortcuts.MAX_PACKAGE - 4)
        assertEquals(1, LearnedApps.record(empty, longest, 1).apps.size)
        assertSame(empty, LearnedApps.record(empty, longest + "b", 1))
    }

    @Test
    fun theAppJustSeenSurvivesTheCapEvenIfTheClockWentBack() {
        var apps = empty
        for (i in 0 until LearnedApps.MAX) apps = LearnedApps.record(apps, "com.example.app$i", 5_000)
        val after = LearnedApps.record(apps, "com.example.newcomer", 1_000)
        assertEquals(LearnedApps.MAX, after.apps.size)
        assertTrue(after.apps.any { it.package_name == "com.example.newcomer" })
    }

    @Test
    fun aRepeatedPackageInAStoredFileGivesOneTile() {
        val apps = DeviceLearnedApps(apps = listOf(LearnedApp("org.xbmc.kodi", 1, 1, 1), LearnedApp("org.xbmc.kodi", 1, 2, 2)))
        assertEquals(listOf("org.xbmc.kodi"), LearnedApps.tiles(AppShortcuts.defaults, apps).map { it.packageName })
    }

    @Test
    fun aRepeatUpdatesLastSeenAndCountWithoutADuplicate() {
        val once = LearnedApps.record(empty, "org.xbmc.kodi", 100)
        val twice = LearnedApps.record(once, "org.xbmc.kodi", 250)
        assertEquals(listOf(LearnedApp("org.xbmc.kodi", first_seen_ms = 100, last_seen_ms = 250, seen_count = 2)), twice.apps)
        // A clock that steps back never moves last-seen backwards.
        val thrice = LearnedApps.record(twice, "org.xbmc.kodi", 200)
        assertEquals(250, thrice.apps.single().last_seen_ms)
        assertEquals(3, thrice.apps.single().seen_count)
    }

    @Test
    fun beyondTheCapTheLeastRecentlySeenIsEvicted() {
        var apps = empty
        for (i in 0 until LearnedApps.MAX) apps = LearnedApps.record(apps, "com.example.app$i", 1_000L + i)
        // Seeing app0 again makes app1 the least recently seen.
        apps = LearnedApps.record(apps, "com.example.app0", 5_000)
        apps = LearnedApps.record(apps, "com.example.newcomer", 6_000)
        val names = apps.apps.map { it.package_name }.toSet()
        assertEquals(LearnedApps.MAX, names.size)
        assertTrue("com.example.newcomer" in names)
        assertTrue("com.example.app0" in names)
        assertFalse("com.example.app1" in names)
    }

    @Test
    fun aRemovedAppStaysRemovedWhenTheTvReportsItAgain() {
        val learned = LearnedApps.record(LearnedApps.record(empty, "org.xbmc.kodi", 1), "tv.pluto.android", 2)
        val removed = LearnedApps.remove(learned, "org.xbmc.kodi")
        assertEquals(listOf("tv.pluto.android"), removed.apps.map { it.package_name })
        assertEquals(listOf("org.xbmc.kodi"), removed.removed)
        assertSame(removed, LearnedApps.record(removed, "org.xbmc.kodi", 3))
        assertEquals("removing twice keeps one entry", listOf("org.xbmc.kodi"), LearnedApps.remove(removed, "org.xbmc.kodi").removed)
    }

    @Test
    fun theRemovedListIsCapped() {
        var apps = empty
        for (i in 0..LearnedApps.MAX_REMOVED + 5) apps = LearnedApps.remove(apps, "com.example.app$i")
        assertEquals(LearnedApps.MAX_REMOVED, apps.removed.size)
        assertEquals("the newest removal is kept", "com.example.app${LearnedApps.MAX_REMOVED + 5}", apps.removed.last())
    }

    @Test
    fun mostSeenFirstThenNewest() {
        val apps = listOf(
            LearnedApp("com.a.one", 1, 10, 1),
            LearnedApp("com.b.two", 1, 5, 4),
            LearnedApp("com.c.three", 1, 30, 1),
        )
        assertEquals(listOf("com.b.two", "com.c.three", "com.a.one"), LearnedApps.ordered(apps).map { it.package_name })
    }

    @Test
    fun tilesSkipAppsTheCuratedGridAlreadyOpens() {
        val apps = DeviceLearnedApps(
            apps = listOf(
                // Opened by the YouTube default's deep link.
                LearnedApp("com.google.android.youtube.tv", 1, 10, 9),
                LearnedApp("org.xbmc.kodi", 1, 20, 3),
                LearnedApp("tv.pluto.android", 1, 30, 1),
                // Learned under an older deny-list.
                LearnedApp("com.google.android.tvlauncher", 1, 40, 5),
            ),
        )
        val tiles = LearnedApps.tiles(AppShortcuts.defaults, apps)
        assertEquals(
            listOf(
                LearnedAppTile("org.xbmc.kodi", "Kodi", "market://launch?id=org.xbmc.kodi"),
                LearnedAppTile("tv.pluto.android", "Pluto", "market://launch?id=tv.pluto.android"),
            ),
            tiles,
        )
        tiles.forEach { assertTrue(it.link, AppShortcuts.isAllowed(it.link)) }

        // Kodi added to the grid by hand: its learned tile goes.
        val curated = AppShortcuts.defaults + AppShortcut("Kodi", "market://launch?id=org.xbmc.kodi")
        assertEquals(listOf("tv.pluto.android"), LearnedApps.tiles(curated, apps).map { it.packageName })
        // A user-emptied grid covers nothing, so YouTube comes back with its deep link.
        val youTube = LearnedApps.tiles(emptyList(), apps).first()
        assertEquals(LearnedAppTile("com.google.android.youtube.tv", "YouTube", "https://www.youtube.com"), youTube)
        assertTrue(LearnedApps.tiles(AppShortcuts.defaults, null).isEmpty())
    }
}
