package com.shibi.sceptrum

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shibi.sceptrum.domain.AppBackground
import com.shibi.sceptrum.domain.AppShortcuts
import com.shibi.sceptrum.domain.LearnedAppTile
import com.shibi.sceptrum.remote.AppsUiState
import com.shibi.sceptrum.store.AppShortcut
import com.shibi.sceptrum.ui.SceptrumFrame
import com.shibi.sceptrum.ui.remote.AppsActions
import com.shibi.sceptrum.ui.remote.RemoteExtras
import com.shibi.sceptrum.ui.remote.RemotePanel
import com.shibi.sceptrum.ui.remote.RemoteScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** "Seen on this TV" tiles: launch, long-press removal, and the grid folded and unfolded. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w1092dp-h984dp-xhdpi")
class LearnedTilesTest {
    @get:Rule
    val compose = createComposeRule()

    private val learned = listOf("tv.pluto.android", "org.xbmc.kodi")
        .map { LearnedAppTile(it, AppShortcuts.labelForPackage(it), AppShortcuts.launchLinkFor(it)) }

    private fun show(widthDp: () -> Int, actions: AppsActions, apps: AppsUiState = AppsUiState(canEdit = true, learned = learned)) {
        compose.setContent {
            Box(Modifier.width(widthDp().dp).fillMaxHeight()) {
                SceptrumFrame(darkTheme = true, background = AppBackground.Default) { widthClass ->
                    RemoteScreen(widthClass, extras = RemoteExtras(apps = apps, appsActions = actions, panel = RemotePanel.Apps))
                }
            }
        }
    }

    @Test
    fun tapLaunchesAndLongPressRemoves() {
        val launched = mutableListOf<AppShortcut>()
        val removed = mutableListOf<String>()
        show({ 400 }, AppsActions(onLaunch = { launched += it }, onRemoveLearned = { removed += it }))

        compose.onNodeWithText("Seen on this TV").assertExists()
        compose.onNodeWithText("Kodi").performClick()
        assertEquals(listOf(AppShortcut("Kodi", "market://launch?id=org.xbmc.kodi")), launched)

        compose.onNodeWithText("Pluto").performTouchInput { longClick() }
        compose.onNodeWithText("Remove from Apps").performClick()
        compose.waitForIdle()
        assertEquals(listOf("tv.pluto.android"), removed)
        assertEquals("a long press never launches", 1, launched.size)
    }

    @Test
    fun editModeShowsARemoveButtonOnLearnedTiles() {
        val removed = mutableListOf<String>()
        show({ 400 }, AppsActions(onRemoveLearned = { removed += it }), AppsUiState(canEdit = true, editing = true, learned = learned))
        compose.onAllNodes(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription, listOf("Remove Kodi")))
            .assertCountEquals(1)
        compose.onNode(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription, listOf("Remove Kodi")))
            .performClick()
        assertEquals(listOf("org.xbmc.kodi"), removed)
    }

    @Test
    fun theGridHasThreeColumnsFoldedAndTwoInTheSidePanel() {
        var width by mutableIntStateOf(400)
        show({ width }, AppsActions())
        // Labels are centred in their tiles, so distinct centres are distinct columns.
        fun columns(): Int = AppShortcuts.defaults.map { tile ->
            val bounds = compose.onAllNodesWithText(tile.label)[0].getUnclippedBoundsInRoot()
            Math.round((bounds.left + bounds.right).value / 2)
        }.toSet().size

        // 400 dp less 32 dp padding: three columns of at least 96 dp with 10 dp gaps.
        assertEquals(3, columns())
        compose.onNodeWithText("Kodi").assertExists()

        // Medium (610 dp) and Expanded (900 dp): the side panel, two columns.
        width = 610
        compose.waitForIdle()
        assertEquals(2, columns())
        compose.onNodeWithText("Pluto").assertExists()

        width = 900
        compose.waitForIdle()
        assertEquals(2, columns())
        compose.onNodeWithText("Seen on this TV").assertExists()
    }
}
