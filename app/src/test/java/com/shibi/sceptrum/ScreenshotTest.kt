package com.shibi.sceptrum

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.shibi.sceptrum.domain.AppBackground
import com.shibi.sceptrum.domain.WindowWidthClass
import com.shibi.sceptrum.ui.SceptrumFrame
import com.shibi.sceptrum.ui.SceptrumScaffold
import com.shibi.sceptrum.ui.devices.DevicesScreen
import com.shibi.sceptrum.ui.nav.NavSection
import com.shibi.sceptrum.ui.remote.RemoteScreen
import com.shibi.sceptrum.ui.settings.SettingsScreen
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val Folded = "w411dp-h891dp-xxhdpi"
private const val Unfolded = "w984dp-h1092dp-xhdpi"
private const val Dir = "build/outputs/roborazzi"

/**
 * Every screen folded (Fold 7 cover screen, Compact) and unfolded (inner
 * screen, Expanded), on the `sky` pastel with Dark off and on the dark theme.
 * Full paths so `recordRoborazziDebug` writes where the CI artifact looks.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = Folded)
class ScreenshotTest {

    @Test fun remoteFoldedPastel() = shot("remote_folded_pastel", NavSection.Remote, dark = false)

    @Test fun remoteFoldedDark() = shot("remote_folded_dark", NavSection.Remote, dark = true)

    @Config(qualifiers = Unfolded)
    @Test fun remoteUnfoldedPastel() = shot("remote_unfolded_pastel", NavSection.Remote, dark = false)

    @Config(qualifiers = Unfolded)
    @Test fun remoteUnfoldedDark() = shot("remote_unfolded_dark", NavSection.Remote, dark = true)

    @Test fun devicesFoldedPastel() = shot("devices_folded_pastel", NavSection.Devices, dark = false)

    @Test fun devicesFoldedDark() = shot("devices_folded_dark", NavSection.Devices, dark = true)

    @Config(qualifiers = Unfolded)
    @Test fun devicesUnfoldedPastel() = shot("devices_unfolded_pastel", NavSection.Devices, dark = false)

    @Config(qualifiers = Unfolded)
    @Test fun devicesUnfoldedDark() = shot("devices_unfolded_dark", NavSection.Devices, dark = true)

    @Test fun settingsFoldedPastel() = shot("settings_folded_pastel", NavSection.Settings, dark = false)

    @Test fun settingsFoldedDark() = shot("settings_folded_dark", NavSection.Settings, dark = true)

    @Config(qualifiers = Unfolded)
    @Test fun settingsUnfoldedPastel() = shot("settings_unfolded_pastel", NavSection.Settings, dark = false)

    @Config(qualifiers = Unfolded)
    @Test fun settingsUnfoldedDark() = shot("settings_unfolded_dark", NavSection.Settings, dark = true)

    /** A blend backdrop, to show the gradient under the glass keys. */
    @Test fun remoteFoldedBlend() =
        shot("remote_folded_blend", NavSection.Remote, dark = false, background = AppBackground.Gradient("mermaid"))

    @Test
    fun launcherIcon() {
        captureRoboImage("$Dir/launcher_icon.png") {
            Row(
                modifier = Modifier
                    .background(Color(0xFF2B2B33))
                    .padding(20.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Box(Modifier.size(160.dp).clip(CircleShape)) {
                    Image(painterResource(R.drawable.ic_launcher_background), null, Modifier.size(160.dp))
                    Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(160.dp))
                }
                Box(Modifier.size(160.dp).clip(CircleShape).background(Color(0xFFDCE3F0))) {
                    Image(
                        painterResource(R.drawable.ic_launcher_monochrome),
                        null,
                        Modifier.size(160.dp),
                        colorFilter = ColorFilter.tint(Color(0xFF2D3A55)),
                    )
                }
            }
        }
    }

    private fun shot(
        name: String,
        section: NavSection,
        dark: Boolean,
        background: AppBackground = AppBackground.Solid("sky"),
    ) {
        captureRoboImage("$Dir/$name.png") {
            SceptrumFrame(darkTheme = dark, background = background) { widthClass ->
                SceptrumScaffold(section = section, onSelect = {}) {
                    Screen(section, widthClass, dark, background)
                }
            }
        }
    }

    @Composable
    private fun Screen(
        section: NavSection,
        widthClass: WindowWidthClass,
        dark: Boolean,
        background: AppBackground,
    ) {
        when (section) {
            NavSection.Remote -> RemoteScreen(widthClass)
            NavSection.Devices -> DevicesScreen(widthClass)
            NavSection.Settings -> SettingsScreen(
                widthClass = widthClass,
                darkTheme = dark,
                background = background,
                onDarkThemeChange = {},
                onSelectBackground = {},
            )
        }
    }
}
