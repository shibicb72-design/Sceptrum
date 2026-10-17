package com.shibi.sceptrum

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shibi.sceptrum.domain.AppBackground
import com.shibi.sceptrum.ui.SceptrumFrame
import com.shibi.sceptrum.ui.SceptrumScaffold
import com.shibi.sceptrum.ui.nav.NavSection
import com.shibi.sceptrum.ui.remote.RemoteScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Fold and unfold resize in place: the remote swaps layouts without a restart. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w1092dp-h984dp-xhdpi")
class FoldLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun unfoldingAddsTheSidePanelAndFoldingRemovesIt() {
        var widthDp by mutableStateOf(411)
        compose.setContent {
            Box(Modifier.width(widthDp.dp).fillMaxHeight()) {
                SceptrumFrame(darkTheme = true, background = AppBackground.Default) { widthClass ->
                    RemoteScreen(widthClass)
                }
            }
        }
        compose.onNodeWithText("Numbers").assertDoesNotExist()
        compose.onNodeWithContentDescription("Volume up").assertExists()

        widthDp = 984
        compose.waitForIdle()
        compose.onNodeWithText("Numbers").assertExists()
        compose.onNodeWithText("Devices").assertExists()

        widthDp = 411
        compose.waitForIdle()
        compose.onNodeWithText("Numbers").assertDoesNotExist()
    }

    @Test
    fun tappingACarouselTabSelectsItsSection() {
        var selected by mutableStateOf(NavSection.Remote)
        compose.setContent {
            SceptrumFrame(darkTheme = false, background = AppBackground.Solid("sky")) {
                SceptrumScaffold(section = selected, onSelect = { selected = it }) {}
            }
        }
        compose.onNodeWithContentDescription("Remote").assertIsSelected()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.waitForIdle()
        assertEquals(NavSection.Settings, selected)
    }
}
