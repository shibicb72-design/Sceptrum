package com.shibi.sceptrum

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shibi.sceptrum.devices.DevicesUiState
import com.shibi.sceptrum.domain.AppBackground
import com.shibi.sceptrum.domain.PairingError
import com.shibi.sceptrum.domain.PairingTarget
import com.shibi.sceptrum.domain.PairingUiState
import com.shibi.sceptrum.ui.SceptrumFrame
import com.shibi.sceptrum.ui.devices.DevicesScreen
import com.shibi.sceptrum.ui.settings.ErrorLogScreen
import com.shibi.sceptrum.ui.settings.SettingsScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Settings > Debugging / Error log: the report, Copy all and Back. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w1092dp-h984dp-xhdpi")
class ErrorLogScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun copyAllCallsBackAndSaysCopied() {
        val report = "Sceptrum error log: 1 entries, app 0.1.1, phone P\n\n" +
            "2026-10-17 14:00:00 EDT  Launch app: boom\n  app 0.1.1, phone P"
        var copies = 0
        compose.setContent {
            SceptrumFrame(darkTheme = true, background = AppBackground.Default) { _ ->
                ErrorLogScreen(
                    report = report,
                    entryCount = 1,
                    onCopyAll = { copies++ },
                    onBack = {},
                )
            }
        }
        compose.onNodeWithText("Launch app: boom", substring = true).assertExists()
        compose.onNodeWithText("Copy all").performClick()
        assertEquals(1, copies)
        compose.onNodeWithText("Copied").assertExists()
    }

    @Test
    fun emptyLogSaysSoAndDisablesCopy() {
        var copies = 0
        compose.setContent {
            SceptrumFrame(darkTheme = true, background = AppBackground.Default) { _ ->
                ErrorLogScreen(
                    report = "Sceptrum error log: 0 entries, app 0.1.1, phone P",
                    entryCount = 0,
                    onCopyAll = { copies++ },
                    onBack = {},
                )
            }
        }
        compose.onNodeWithText("No errors logged.").assertExists()
        compose.onNodeWithText("Copy all").assertIsNotEnabled()
    }

    @Test
    fun backCallsOnBack() {
        var backs = 0
        compose.setContent {
            SceptrumFrame(darkTheme = true, background = AppBackground.Default) { _ ->
                ErrorLogScreen(
                    report = "Sceptrum error log: 0 entries, app 0.1.1, phone P",
                    entryCount = 0,
                    onCopyAll = {},
                    onBack = { backs++ },
                )
            }
        }
        compose.onNodeWithText("Back").performClick()
        assertEquals(1, backs)
    }

    @Test
    fun settingsDebuggingCardOpensTheLog() {
        var opened = 0
        compose.setContent {
            SceptrumFrame(darkTheme = true, background = AppBackground.Default) { widthClass ->
                SettingsScreen(
                    widthClass = widthClass,
                    darkTheme = true,
                    background = AppBackground.Default,
                    onDarkThemeChange = {},
                    onSelectBackground = {},
                    errorCount = 2,
                    onOpenErrorLog = { opened++ },
                )
            }
        }
        compose.onNodeWithText("Debugging / Error log").performScrollTo().assertExists()
        compose.onNodeWithText("Open error log").performScrollTo().performClick()
        assertEquals(1, opened)
    }

    @Test
    fun failedPairingCardPointsToTheErrorLog() {
        compose.setContent {
            SceptrumFrame(darkTheme = true, background = AppBackground.Default) { widthClass ->
                DevicesScreen(
                    widthClass = widthClass,
                    state = DevicesUiState(),
                    pairing = PairingUiState.Failed(PairingTarget("Projector", "192.168.1.50"), PairingError.Handshake),
                )
            }
        }
        compose.onNodeWithText("Details in Settings > Debugging / Error log.").performScrollTo().assertExists()
        compose.onNodeWithText("Copy details").assertDoesNotExist()
    }
}
