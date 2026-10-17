package com.shibi.sceptrum.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SettingsRemote
import androidx.compose.ui.graphics.vector.ImageVector
import com.shibi.sceptrum.R

/** The three carousel sections, in ring order. Remote sits in the middle of the arc. */
enum class NavSection(
    val labelRes: Int,
    val icon: ImageVector,
) {
    Devices(R.string.nav_devices, Icons.Rounded.Devices),
    Remote(R.string.nav_remote, Icons.Rounded.SettingsRemote),
    Settings(R.string.nav_settings, Icons.Rounded.Settings),
}

/**
 * One route per carousel section. The selection is re-derived from the route
 * whenever it changes, so every section must own a destination; otherwise the
 * route sync drags the ring back to the previous section (Spectacula lesson).
 */
object SectionRoutes {
    const val Remote = "remote"
    const val Devices = "devices"
    const val Settings = "settings"

    /** The app opens on the remote. */
    const val Start = Remote

    val all: List<String> = NavSection.entries.map(::routeFor)

    fun routeFor(section: NavSection): String = when (section) {
        NavSection.Remote -> Remote
        NavSection.Devices -> Devices
        NavSection.Settings -> Settings
    }

    fun sectionFor(route: String?): NavSection? = when (route) {
        Remote -> NavSection.Remote
        Devices -> NavSection.Devices
        Settings -> NavSection.Settings
        else -> null
    }
}
