package com.shibi.sceptrum.remote

import com.shibi.sceptrum.domain.RemoteKey
import com.shibi.sceptrum.domain.TvShortcut
import com.shibi.sceptrum.domain.TvShortcuts.forKey
import com.shibi.sceptrum.domain.TvShortcuts.ordered
import com.shibi.sceptrum.domain.TvStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TvShortcutsTest {
    @Test
    fun settingsStartsWithTheSettingsKeyThenLinks() {
        val steps = TvShortcut.Settings.steps
        assertEquals(TvStep.Key(176), steps.first())
        assertTrue(steps.drop(1).all { it is TvStep.Link })
    }

    @Test
    fun inputsStartsWithTheInputKey() {
        assertEquals(TvStep.Key(178), TvShortcut.Inputs.steps.first())
    }

    @Test
    fun forKeyMapsOnlySettingsAndInput() {
        assertEquals(TvShortcut.Settings, forKey(RemoteKey.Settings))
        assertEquals(TvShortcut.Inputs, forKey(RemoteKey.Input))
        assertNull(forKey(RemoteKey.Back))
    }

    @Test
    fun orderedPutsTheLastWorkingStepFirst() {
        val steps = TvShortcut.Settings.steps
        assertEquals(listOf(steps[2], steps[0], steps[1], steps[3]), ordered(TvShortcut.Settings, 2))
    }

    @Test
    fun orderedIgnoresNullAndOutOfRange() {
        assertEquals(TvShortcut.Settings.steps, ordered(TvShortcut.Settings, null))
        assertEquals(TvShortcut.Settings.steps, ordered(TvShortcut.Settings, 99))
    }
}
