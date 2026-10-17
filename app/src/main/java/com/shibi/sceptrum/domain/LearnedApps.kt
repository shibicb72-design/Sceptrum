package com.shibi.sceptrum.domain

import com.shibi.sceptrum.store.AppShortcut
import com.shibi.sceptrum.store.DeviceLearnedApps
import com.shibi.sceptrum.store.LearnedApp

/** A tile for an app the TV reported, shown after the curated grid. */
data class LearnedAppTile(
    val packageName: String,
    val label: String,
    /** Passes [AppShortcuts.isAllowed]: a known deep link or `market://launch?id=<package>`. */
    val link: String,
) {
    val shortcut: AppShortcut get() = AppShortcut(label, link)
}

/**
 * "Learn over time" (docs/apps-list-options.md, Option A): the Remote v2
 * protocol cannot list a TV's apps, but it reports the foreground app, so
 * every package it reports is remembered per device and offered as a tile.
 * Launchers, system UI and other passing screens never are ([isDenied]).
 * Pure Kotlin, so plain JUnit owns the rules.
 */
object LearnedApps {
    /** Apps kept per device; the least recently seen goes first. */
    const val MAX = 40

    /** Learned tiles shown before "Show all", so the cards below the grid stay near. */
    const val PREVIEW = 9

    /** Removed packages kept per device, so a removal sticks; the oldest removal goes first. */
    const val MAX_REMOVED = 100

    /**
     * Packages that are never an app to open: launchers, settings, system UI,
     * setup, permission and install screens, the screensaver and assistant
     * overlays, and the Play Store screen a market:// launch passes through.
     */
    val deniedPackages: Set<String> = setOf(
        "android",
        "com.android.systemui",
        "com.android.tv.settings",
        "com.android.settings",
        "com.android.vending",
        "com.android.packageinstaller",
        "com.android.permissioncontroller",
        "com.google.android.packageinstaller",
        "com.google.android.permissioncontroller",
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.google.android.tvlauncher",
        "com.google.android.apps.tv.launcherx",
        "com.google.android.leanbacklauncher",
        "com.google.android.tvrecommendations",
        "com.google.android.tungsten.setupwraith",
        "com.google.android.katniss",
        "com.google.android.apps.tv.dreamx",
        "com.google.android.backdrop",
        "com.google.android.apps.mediashell",
        "com.google.android.inputmethod.latin",
        "com.google.android.leanback.ime",
        "com.android.tv.launcher",
        "com.android.launcher3",
    )

    /**
     * Last package parts that mark a launcher or system screen on any brand:
     * the XGIMI and other vendor launchers ("com.xgimi.launcher", "...tvlauncher",
     * "...settings") are caught without knowing their exact package.
     */
    private val deniedLastParts = setOf("systemui", "settings", "tvsettings", "setupwizard", "packageinstaller", "permissioncontroller")

    /** True for launchers, settings, system UI and other packages that are not apps to open. */
    fun isDenied(appPackage: String): Boolean {
        if (appPackage in deniedPackages) return true
        val last = appPackage.substringAfterLast('.').lowercase()
        return "launcher" in last || last in deniedLastParts
    }

    /** True when [appPackage] is a valid package id that is not [isDenied]. */
    fun isLearnable(appPackage: String): Boolean = AppShortcuts.isPackage(appPackage) && !isDenied(appPackage)

    /**
     * [apps] after the TV reported [appPackage] at [nowMs]: a new entry, or the
     * existing one with a later last-seen time and one more sighting. Beyond
     * [MAX] the least recently seen is evicted. Unchanged (same instance) for a
     * denied, invalid or removed package.
     */
    fun record(apps: DeviceLearnedApps, appPackage: String, nowMs: Long): DeviceLearnedApps {
        if (!isLearnable(appPackage) || appPackage in apps.removed) return apps
        val existing = apps.apps.firstOrNull { it.package_name == appPackage }
        val updated = if (existing != null) {
            apps.apps.map {
                if (it.package_name == appPackage) {
                    it.copy(last_seen_ms = maxOf(it.last_seen_ms, nowMs), seen_count = it.seen_count + 1)
                } else {
                    it
                }
            }
        } else {
            apps.apps + LearnedApp(package_name = appPackage, first_seen_ms = nowMs, last_seen_ms = nowMs, seen_count = 1)
        }
        if (updated.size <= MAX) return apps.copy(apps = updated)
        // The app just seen always stays, even if the phone clock went back and its time is not the latest.
        val (current, others) = updated.partition { it.package_name == appPackage }
        return apps.copy(apps = others.sortedByDescending { it.last_seen_ms }.take(MAX - current.size) + current)
    }

    /** [apps] without [appPackage], which is remembered as removed so it is not learned again. */
    fun remove(apps: DeviceLearnedApps, appPackage: String): DeviceLearnedApps {
        val removed = (apps.removed - appPackage + appPackage).takeLast(MAX_REMOVED)
        return apps.copy(apps = apps.apps.filterNot { it.package_name == appPackage }, removed = removed)
    }

    /** Most seen first; ties go to the most recently seen, then the package name, so the order is stable. */
    fun ordered(apps: List<LearnedApp>): List<LearnedApp> =
        apps.sortedWith(
            compareByDescending<LearnedApp> { it.seen_count }
                .thenByDescending { it.last_seen_ms }
                .thenBy { it.package_name },
        )

    /**
     * Tiles for [apps] that the [curated] grid does not already open, in
     * [ordered] order. Denied or removed entries (from an older deny-list) and
     * links that fail [AppShortcuts.isAllowed] are left out.
     */
    fun tiles(curated: List<AppShortcut>, apps: DeviceLearnedApps?): List<LearnedAppTile> {
        if (apps == null || apps.apps.isEmpty()) return emptyList()
        return ordered(apps.apps)
            .asSequence()
            .map { it.package_name }
            // A file from elsewhere could repeat a package; a repeated lazy key would crash the grid.
            .distinct()
            .filter { isLearnable(it) && it !in apps.removed }
            .filter { pkg -> curated.none { AppShortcuts.covers(it, pkg) } }
            .map { pkg -> LearnedAppTile(pkg, AppShortcuts.labelForPackage(pkg), AppShortcuts.launchLinkFor(pkg)) }
            .filter { AppShortcuts.isAllowed(it.link) }
            .toList()
    }
}
