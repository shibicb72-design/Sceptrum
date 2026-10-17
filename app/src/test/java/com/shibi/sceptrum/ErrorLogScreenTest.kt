package com.shibi.sceptrum

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shibi.sceptrum.domain.AppBackground
import com.shibi.sceptrum.ui.SceptrumFrame
import com.shibi.sceptrum.ui.settings.ErrorLogScreen
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
}
