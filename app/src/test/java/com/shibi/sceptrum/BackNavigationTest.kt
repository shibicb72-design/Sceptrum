package com.shibi.sceptrum

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shibi.sceptrum.devices.DeviceEdit
import com.shibi.sceptrum.devices.DevicesUiState
import com.shibi.sceptrum.domain.AppBackground
import com.shibi.sceptrum.domain.PairingTarget
import com.shibi.sceptrum.domain.PairingUiState
import com.shibi.sceptrum.ui.CloseOnBack
import com.shibi.sceptrum.ui.DevicesBack
import com.shibi.sceptrum.ui.SceptrumFrame
import com.shibi.sceptrum.ui.devices.DevicesScreen
import com.shibi.sceptrum.ui.remote.RemoteExtras
import com.shibi.sceptrum.ui.remote.RemotePanel
import com.shibi.sceptrum.ui.remote.RemoteScreen
import com.shibi.sceptrum.ui.settings.ErrorLogScreen
import com.shibi.sceptrum.ui.settings.SettingsScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * System Back through the NavigationEvent handlers, as SceptrumApp wires them:
 * each closes its card or panel first, and only with nothing open does Back
 * reach the section history (a handler registered earlier, like the NavHost's).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class BackNavigationTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var fellThrough = 0

    private val target = PairingTarget(name = "Projector", host = "192.168.1.50")
    private val edit = DeviceEdit("mac:01", "Projector", "192.168.1.50", "AA:BB:CC:DD:EE:01")

    private fun pressBack() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    /** Stands in for the NavHost's own back handling: registered first, so it is asked last. */
    @Composable
    private fun SectionHistory() {
        CloseOnBack { fellThrough++ }
    }

    @Composable
    private fun Devices(pairing: () -> PairingUiState, onPairing: (PairingUiState) -> Unit, devices: () -> DevicesUiState, onDevices: (DevicesUiState) -> Unit) {
        SceptrumFrame(darkTheme = false, background = AppBackground.Default) { widthClass ->
            SectionHistory()
            DevicesBack(
                pairingOpen = { pairing() != PairingUiState.Idle },
                editing = { devices().editing != null },
                onCancelPairing = { onPairing(PairingUiState.Idle) },
                onCloseEdit = { onDevices(devices().copy(editing = null)) },
            )
            DevicesScreen(widthClass, state = devices(), pairing = pairing())
        }
    }

    @Test
    fun backCancelsPairingBeforeClosingTheEditCard() {
        var pairing by mutableStateOf<PairingUiState>(PairingUiState.EnterCode(target))
        var devices by mutableStateOf(DevicesUiState(editing = edit))
        compose.setContent { Devices({ pairing }, { pairing = it }, { devices }, { devices = it }) }
        compose.onNodeWithContentDescription("6-character code").assertExists()

        pressBack()
        assertEquals(PairingUiState.Idle, pairing)
        assertNotNull("the edit card waits for the next Back", devices.editing)
        compose.onNodeWithContentDescription("6-character code").assertDoesNotExist()
        compose.onNodeWithText("Device").assertExists()
        assertEquals(0, fellThrough)
    }

    @Test
    fun backClosesTheEditCardThenReachesTheSectionHistory() {
        var pairing by mutableStateOf<PairingUiState>(PairingUiState.Idle)
        var devices by mutableStateOf(DevicesUiState(editing = edit))
        compose.setContent { Devices({ pairing }, { pairing = it }, { devices }, { devices = it }) }
        compose.onNodeWithText("Device").assertExists()

        pressBack()
        assertNull(devices.editing)
        compose.onNodeWithText("Device").assertDoesNotExist()
        assertEquals(0, fellThrough)

        pressBack()
        assertEquals("nothing open: Back goes on", 1, fellThrough)
    }

    @Test
    fun pairingOpenedLaterStillWinsOverTheSectionHistory() {
        var pairing by mutableStateOf<PairingUiState>(PairingUiState.Idle)
        var devices by mutableStateOf(DevicesUiState())
        compose.setContent { Devices({ pairing }, { pairing = it }, { devices }, { devices = it }) }
        pressBack()
        assertEquals(1, fellThrough)

        pairing = PairingUiState.Connecting(target)
        compose.waitForIdle()
        pressBack()
        assertEquals(PairingUiState.Idle, pairing)
        assertEquals(1, fellThrough)
    }

    @Test
    fun backFromThePairedCardReturnsToTheListWithoutOpeningTheRemote() {
        var pairing by mutableStateOf<PairingUiState>(PairingUiState.Paired(target, "mac:01"))
        var devices by mutableStateOf(DevicesUiState())
        compose.setContent { Devices({ pairing }, { pairing = it }, { devices }, { devices = it }) }
        pressBack()
        assertEquals(PairingUiState.Idle, pairing)
        assertEquals(0, fellThrough)
    }

    /** The real navigation-compose back stack, as in SceptrumApp: a card closes first, then the section pops. */
    @Test
    fun aCardClosesBeforeTheNavHostPopsTheSection() {
        lateinit var nav: NavHostController
        var editing by mutableStateOf(true)
        compose.setContent {
            nav = rememberNavController()
            NavHost(navController = nav, startDestination = "remote") {
                composable("remote") { Text("Remote section") }
                composable("devices") {
                    CloseOnBack(enabled = editing) { editing = false }
                    Text(if (editing) "Edit card" else "Device list")
                }
            }
        }
        compose.runOnUiThread { nav.navigate("devices") }
        compose.waitForIdle()
        compose.onNodeWithText("Edit card").assertExists()

        pressBack()
        assertFalse(editing)
        compose.onNodeWithText("Device list").assertExists()

        pressBack()
        compose.onNodeWithText("Remote section").assertExists()
        assertEquals("remote", nav.currentDestination?.route)
    }

    @Test
    fun backClosesTheErrorLog() {
        var showErrorLog by mutableStateOf(true)
        compose.setContent {
            SceptrumFrame(darkTheme = true, background = AppBackground.Default) { widthClass ->
                SectionHistory()
                if (showErrorLog) {
                    CloseOnBack { showErrorLog = false }
                    ErrorLogScreen(report = "Sceptrum error log: 0 entries", entryCount = 0, onCopyAll = {}, onBack = { showErrorLog = false })
                } else {
                    SettingsScreen(
                        widthClass = widthClass,
                        darkTheme = true,
                        background = AppBackground.Default,
                        onDarkThemeChange = {},
                        onSelectBackground = {},
                        onOpenErrorLog = { showErrorLog = true },
                    )
                }
            }
        }
        compose.onNodeWithText("Copy all").assertExists()

        pressBack()
        assertFalse(showErrorLog)
        compose.onNodeWithText("Copy all").assertDoesNotExist()
        assertEquals(0, fellThrough)

        pressBack()
        assertEquals(1, fellThrough)
    }

    @Test
    fun backClosesTheAppsAndKeyboardPanels() {
        var panel by mutableStateOf(RemotePanel.Apps)
        compose.setContent {
            SceptrumFrame(darkTheme = false, background = AppBackground.Default) { widthClass ->
                SectionHistory()
                CloseOnBack(enabled = panel != RemotePanel.None) { panel = RemotePanel.None }
                RemoteScreen(widthClass, extras = RemoteExtras(panel = panel, onPanel = { panel = it }))
            }
        }
        compose.onNodeWithContentDescription("Close apps").assertExists()

        pressBack()
        assertEquals(RemotePanel.None, panel)
        compose.onNodeWithContentDescription("Close apps").assertDoesNotExist()
        compose.onNodeWithContentDescription("Volume up").assertExists()
        assertEquals(0, fellThrough)

        panel = RemotePanel.Keyboard
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Close keyboard").assertExists()
        pressBack()
        assertEquals(RemotePanel.None, panel)
        assertEquals(0, fellThrough)

        pressBack()
        assertEquals(1, fellThrough)
    }
}
