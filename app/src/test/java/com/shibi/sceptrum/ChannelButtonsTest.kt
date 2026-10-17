package com.shibi.sceptrum

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shibi.sceptrum.devices.DeviceEdit
import com.shibi.sceptrum.devices.DevicesUiState
import com.shibi.sceptrum.domain.AppBackground
import com.shibi.sceptrum.domain.ConnectionStatus
import com.shibi.sceptrum.domain.PairingUiState
import com.shibi.sceptrum.session.SessionState
import com.shibi.sceptrum.ui.SceptrumFrame
import com.shibi.sceptrum.ui.devices.DevicesActions
import com.shibi.sceptrum.ui.devices.DevicesScreen
import com.shibi.sceptrum.ui.remote.RemoteScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The CH rocker follows the device's "Show channel buttons" setting, folded and unfolded. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w1092dp-h984dp-xhdpi")
class ChannelButtonsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun rockerFollowsTheSettingAtEveryWidth() {
        var session by mutableStateOf(SessionState(deviceName = "TCL", status = ConnectionStatus.Connected))
        var widthDp by mutableStateOf(400)
        compose.setContent {
            Box(Modifier.width(widthDp.dp).fillMaxHeight()) {
                SceptrumFrame(darkTheme = false, background = AppBackground.Default) { widthClass ->
                    RemoteScreen(widthClass, session = session)
                }
            }
        }
        for (width in listOf(400, 610, 984)) {
            widthDp = width
            session = session.copy(showChannelButtons = true)
            compose.waitForIdle()
            compose.onNodeWithContentDescription("Channel up").assertExists()
            compose.onNodeWithContentDescription("Channel down").assertExists()

            session = session.copy(showChannelButtons = false)
            compose.waitForIdle()
            compose.onNodeWithContentDescription("Channel up").assertDoesNotExist()
            compose.onNodeWithContentDescription("Channel down").assertDoesNotExist()
            // Volume and mute stay.
            compose.onNodeWithContentDescription("Volume up").assertExists()
            compose.onNodeWithContentDescription("Mute").assertExists()
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun tappingTheRowFlipsTheSwitch() {
        val changes = mutableListOf<Boolean>()
        var edit by mutableStateOf(DeviceEdit("mac:01", "Projector", "192.168.1.50", ""))
        compose.setContent {
            SceptrumFrame(darkTheme = false, background = AppBackground.Default) { widthClass ->
                DevicesScreen(
                    widthClass = widthClass,
                    state = DevicesUiState(editing = edit),
                    pairing = PairingUiState.Idle,
                    actions = DevicesActions(onShowChannelButtons = { show ->
                        changes += show
                        edit = edit.copy(showChannelButtons = show)
                    }),
                )
            }
        }
        val row = compose.onNodeWithText("Show channel buttons", substring = true)
        row.assertIsOn()
        row.performClick()
        compose.waitForIdle()
        row.assertIsOff()
        row.performClick()
        assertEquals(listOf(false, true), changes)
    }
}
