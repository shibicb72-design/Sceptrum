package com.shibi.sceptrum

import android.provider.Settings
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
import com.shibi.sceptrum.atv.VolumeInfo
import com.shibi.sceptrum.devices.DeviceEdit
import com.shibi.sceptrum.devices.DevicesUiState
import com.shibi.sceptrum.domain.AppShortcutError
import com.shibi.sceptrum.domain.StandbyTips
import com.shibi.sceptrum.power.PowerUiState
import com.shibi.sceptrum.remote.AddAppForm
import com.shibi.sceptrum.remote.AppsUiState
import com.shibi.sceptrum.remote.KeyboardUiState
import com.shibi.sceptrum.store.AppShortcut
import com.shibi.sceptrum.domain.ConnectionStatus
import com.shibi.sceptrum.domain.AppBackground
import com.shibi.sceptrum.domain.DiscoveredRow
import com.shibi.sceptrum.domain.PairingError
import com.shibi.sceptrum.domain.PairingTarget
import com.shibi.sceptrum.domain.PairingUiState
import com.shibi.sceptrum.domain.SavedRow
import com.shibi.sceptrum.domain.WindowWidthClass
import com.shibi.sceptrum.session.SessionState
import com.shibi.sceptrum.ui.SceptrumFrame
import com.shibi.sceptrum.ui.SceptrumScaffold
import com.shibi.sceptrum.ui.devices.DevicesScreen
import com.shibi.sceptrum.ui.nav.NavSection
import com.shibi.sceptrum.ui.remote.RemoteExtras
import com.shibi.sceptrum.ui.remote.RemotePanel
import com.shibi.sceptrum.ui.remote.RemoteScreen
import com.shibi.sceptrum.ui.settings.SettingsScreen
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val Folded = "w411dp-h891dp-xxhdpi"
private const val Unfolded = "w984dp-h1092dp-xhdpi"
private const val Dir = "build/outputs/roborazzi"

private val Target = PairingTarget(name = "XGIMI Horizon S Max", host = "192.168.1.50")
private val Connected = SessionState(
    deviceName = "XGIMI Horizon S Max",
    status = ConnectionStatus.Connected,
    volume = VolumeInfo(level = 23, max = 100, muted = false),
    isOn = true,
)
private val CodeEntry = PairingUiState.EnterCode(Target, code = "4FA")
private val Offline = Connected.copy(status = ConnectionStatus.Offline, volume = null, isOn = null)
private val KeyboardOpen = RemoteExtras(
    keyboard = KeyboardUiState(
        text = "lo-fi beats",
        fieldActive = true,
        fieldLabel = "Search",
        app = "com.google.android.youtube.tv",
        connected = true,
    ),
    panel = RemotePanel.Keyboard,
)
private val AppsShown = AppsUiState(
    canEdit = true,
    currentApp = AppShortcut("Kodi", "market://launch?id=org.xbmc.kodi"),
)
private val AppsOpen = RemoteExtras(apps = AppsShown, panel = RemotePanel.Apps)
private val AppsEditing = AppsShown.copy(editing = true)
private val PowerTip = RemoteExtras(
    power = PowerUiState.Failed("XGIMI Horizon S Max", StandbyTips.forDevice("XGIMI Horizon S Max", null, hasMac = true)),
)
private val Editing = DeviceEdit("mac:01", "XGIMI Horizon S Max", "192.168.1.50", "AA:BB:CC:DD:EE:01", draft = "Living room projector")
private val SampleDevices = DevicesUiState(
    saved = listOf(
        SavedRow("mac:01", "XGIMI Horizon S Max", "192.168.1.50", active = true, online = true),
        SavedRow("mac:02", "TCL 75QM6K", "192.168.1.60", active = false, online = false),
    ),
    discovered = listOf(DiscoveredRow("Bedroom TV", "192.168.1.71", 6466)),
    scanning = true,
)

/**
 * Every screen folded (Fold 7 cover screen, Compact) and unfolded (inner
 * screen, Expanded), on the `sky` pastel with Dark off and on the dark theme.
 * Full paths so `recordRoborazziDebug` writes where the CI artifact looks.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = Folded)
class ScreenshotTest {
    /** Animations off, so spinners are still and every capture reaches idle. */
    @Before
    fun reduceMotion() {
        val resolver = RuntimeEnvironment.getApplication().contentResolver
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
    }

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

    @Test fun remoteConnectedFoldedPastel() =
        shot("remote_connected_folded_pastel", NavSection.Remote, dark = false, session = Connected)

    @Test fun remoteConnectedFoldedDark() = shot("remote_connected_folded_dark", NavSection.Remote, dark = true, session = Connected)

    @Config(qualifiers = Unfolded)
    @Test fun remoteConnectedUnfoldedPastel() =
        shot("remote_connected_unfolded_pastel", NavSection.Remote, dark = false, session = Connected)

    @Config(qualifiers = Unfolded)
    @Test fun remoteConnectedUnfoldedDark() =
        shot("remote_connected_unfolded_dark", NavSection.Remote, dark = true, session = Connected)

    @Test fun remoteMutedFoldedDark() = shot(
        "remote_muted_folded_dark",
        NavSection.Remote,
        dark = true,
        session = Connected.copy(volume = VolumeInfo(23, 100, muted = true)),
    )

    @Test fun remoteReconnectingFoldedPastel() = shot(
        "remote_reconnecting_folded_pastel",
        NavSection.Remote,
        dark = false,
        session = Connected.copy(status = ConnectionStatus.Reconnecting),
    )

    @Test fun remoteOfflineFoldedDark() = shot(
        "remote_offline_folded_dark",
        NavSection.Remote,
        dark = true,
        session = Connected.copy(status = ConnectionStatus.Offline),
    )

    @Test fun remoteNeedsPairingFoldedPastel() = shot(
        "remote_needs_pairing_folded_pastel",
        NavSection.Remote,
        dark = false,
        session = Connected.copy(status = ConnectionStatus.NeedsPairing),
    )

    @Test fun devicesEmptyFoldedPastel() =
        shot("devices_empty_folded_pastel", NavSection.Devices, dark = false, devices = DevicesUiState())

    @Test fun pairingCodeFoldedPastel() = shot("pairing_code_folded_pastel", NavSection.Devices, dark = false, pairing = CodeEntry)

    @Test fun pairingCodeFoldedDark() = shot("pairing_code_folded_dark", NavSection.Devices, dark = true, pairing = CodeEntry)

    @Config(qualifiers = Unfolded)
    @Test fun pairingCodeUnfoldedPastel() =
        shot("pairing_code_unfolded_pastel", NavSection.Devices, dark = false, pairing = CodeEntry)

    @Config(qualifiers = Unfolded)
    @Test fun pairingCodeUnfoldedDark() = shot("pairing_code_unfolded_dark", NavSection.Devices, dark = true, pairing = CodeEntry)

    @Test fun pairingConnectingFoldedPastel() =
        shot("pairing_connecting_folded_pastel", NavSection.Devices, dark = false, pairing = PairingUiState.Connecting(Target))

    @Test fun pairingWrongCodeFoldedPastel() = shot(
        "pairing_wrong_code_folded_pastel",
        NavSection.Devices,
        dark = false,
        pairing = PairingUiState.EnterCode(Target, error = PairingError.WrongCode),
    )

    @Test fun pairingBadSecretFoldedDark() = shot(
        "pairing_bad_secret_folded_dark",
        NavSection.Devices,
        dark = true,
        pairing = PairingUiState.EnterCode(Target, error = PairingError.BadSecret),
    )

    @Test fun pairingTimeoutFoldedPastel() = shot(
        "pairing_timeout_folded_pastel",
        NavSection.Devices,
        dark = false,
        pairing = PairingUiState.Failed(Target, PairingError.Timeout),
    )

    @Test fun pairingRejectedFoldedDark() = shot(
        "pairing_rejected_folded_dark",
        NavSection.Devices,
        dark = true,
        pairing = PairingUiState.Failed(Target, PairingError.Rejected),
    )

    @Config(qualifiers = Unfolded)
    @Test fun pairingUnreachableUnfoldedPastel() = shot(
        "pairing_unreachable_unfolded_pastel",
        NavSection.Devices,
        dark = false,
        pairing = PairingUiState.Failed(Target, PairingError.Unreachable),
    )

    @Test fun pairingPairedFoldedDark() = shot(
        "pairing_paired_folded_dark",
        NavSection.Devices,
        dark = true,
        pairing = PairingUiState.Paired(Target, "mac:AA:BB:CC:DD:EE:01"),
    )

    @Test fun keyboardFoldedPastel() = shot("keyboard_folded_pastel", NavSection.Remote, dark = false, session = Connected, extras = KeyboardOpen)

    @Test fun keyboardFoldedDark() = shot("keyboard_folded_dark", NavSection.Remote, dark = true, session = Connected, extras = KeyboardOpen)

    @Test fun keyboardNoFieldFoldedPastel() = shot(
        "keyboard_no_field_folded_pastel",
        NavSection.Remote,
        dark = false,
        session = Connected,
        extras = RemoteExtras(keyboard = KeyboardUiState(connected = true, keyPresses = true), panel = RemotePanel.Keyboard),
    )

    @Config(qualifiers = Unfolded)
    @Test fun keyboardUnfoldedDark() = shot("keyboard_unfolded_dark", NavSection.Remote, dark = true, session = Connected, extras = KeyboardOpen)

    @Test fun appsFoldedPastel() = shot("apps_folded_pastel", NavSection.Remote, dark = false, session = Connected, extras = AppsOpen)

    @Test fun appsFoldedDark() = shot("apps_folded_dark", NavSection.Remote, dark = true, session = Connected, extras = AppsOpen)

    @Test fun appsEditingFoldedPastel() = shot(
        "apps_editing_folded_pastel",
        NavSection.Remote,
        dark = false,
        session = Connected,
        extras = RemoteExtras(
            apps = AppsEditing.copy(form = AddAppForm(link = "spotify", error = AppShortcutError.Invalid)),
            panel = RemotePanel.Apps,
        ),
    )

    @Config(qualifiers = Unfolded)
    @Test fun appsEditingUnfoldedDark() =
        shot("apps_editing_unfolded_dark", NavSection.Remote, dark = true, session = Connected, extras = RemoteExtras(apps = AppsEditing))

    @Test fun swipePadFoldedPastel() =
        shot("swipe_pad_folded_pastel", NavSection.Remote, dark = false, session = Connected, extras = RemoteExtras(swipeMode = true))

    @Test fun swipePadFoldedDark() =
        shot("swipe_pad_folded_dark", NavSection.Remote, dark = true, session = Connected, extras = RemoteExtras(swipeMode = true))

    @Test fun powerWakingFoldedPastel() = shot(
        "power_waking_folded_pastel",
        NavSection.Remote,
        dark = false,
        session = Offline,
        extras = RemoteExtras(power = PowerUiState.Waking("XGIMI Horizon S Max", sentPacket = true)),
    )

    @Test fun powerTipFoldedDark() = shot("power_tip_folded_dark", NavSection.Remote, dark = true, session = Offline, extras = PowerTip)

    @Test fun powerTipFoldedPastel() = shot("power_tip_folded_pastel", NavSection.Remote, dark = false, session = Offline, extras = PowerTip)

    @Config(qualifiers = Unfolded)
    @Test fun powerTipUnfoldedPastel() =
        shot("power_tip_unfolded_pastel", NavSection.Remote, dark = false, session = Offline, extras = PowerTip)

    @Test fun lanBlockedFoldedPastel() = shot(
        "lan_blocked_folded_pastel",
        NavSection.Remote,
        dark = false,
        session = Offline,
        extras = RemoteExtras(lanBlocked = true),
    )

    @Test fun deviceEditFoldedPastel() =
        shot("device_edit_folded_pastel", NavSection.Devices, dark = false, devices = SampleDevices.copy(editing = Editing))

    @Test fun deviceEditFoldedDark() =
        shot("device_edit_folded_dark", NavSection.Devices, dark = true, devices = SampleDevices.copy(editing = Editing))

    @Config(qualifiers = Unfolded)
    @Test fun deviceForgetUnfoldedPastel() = shot(
        "device_forget_unfolded_pastel",
        NavSection.Devices,
        dark = false,
        devices = SampleDevices.copy(editing = Editing.copy(confirmForget = true)),
    )

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
        devices: DevicesUiState = SampleDevices,
        pairing: PairingUiState = PairingUiState.Idle,
        session: SessionState = SessionState(),
        extras: RemoteExtras = RemoteExtras(),
    ) {
        captureRoboImage("$Dir/$name.png") {
            SceptrumFrame(darkTheme = dark, background = background) { widthClass ->
                SceptrumScaffold(section = section, onSelect = {}) {
                    Screen(section, widthClass, dark, background, devices, pairing, session, extras)
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
        devices: DevicesUiState,
        pairing: PairingUiState,
        session: SessionState,
        extras: RemoteExtras,
    ) {
        when (section) {
            NavSection.Remote -> RemoteScreen(widthClass, session = session, devices = devices, extras = extras)
            NavSection.Devices -> DevicesScreen(widthClass, state = devices, pairing = pairing)
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
