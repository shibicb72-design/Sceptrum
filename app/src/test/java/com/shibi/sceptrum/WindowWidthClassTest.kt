package com.shibi.sceptrum

import com.shibi.sceptrum.domain.WindowWidthClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowWidthClassTest {
    @Test
    fun foldCoverScreenIsCompact() {
        assertEquals(WindowWidthClass.Compact, WindowWidthClass.fromWidthDp(411f))
        assertEquals(WindowWidthClass.Compact, WindowWidthClass.fromWidthDp(360f))
        assertFalse(WindowWidthClass.Compact.isMultiPane)
    }

    @Test
    fun splitScreenIsMedium() {
        assertEquals(WindowWidthClass.Medium, WindowWidthClass.fromWidthDp(600f))
        assertEquals(WindowWidthClass.Medium, WindowWidthClass.fromWidthDp(839.9f))
        assertTrue(WindowWidthClass.Medium.isMultiPane)
    }

    @Test
    fun unfoldedInnerScreenIsExpanded() {
        assertEquals(WindowWidthClass.Expanded, WindowWidthClass.fromWidthDp(840f))
        assertEquals(WindowWidthClass.Expanded, WindowWidthClass.fromWidthDp(984f))
        assertEquals(WindowWidthClass.Expanded, WindowWidthClass.fromWidthDp(1092f))
    }
}
