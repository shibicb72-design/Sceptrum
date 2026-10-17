package com.shibi.sceptrum

import com.shibi.sceptrum.domain.CarouselMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CarouselMathTest {
    private val math = CarouselMath(3)

    @Test
    fun selectionRoundsAndClampsToTheArc() {
        assertEquals(0, math.selectedIndex(-0.8f))
        assertEquals(1, math.selectedIndex(0.6f))
        assertEquals(2, math.selectedIndex(7f))
        assertEquals(2f, math.snapTarget(2.4f), 0f)
    }

    @Test
    fun flingNeverLeavesTheArc() {
        assertEquals(2f, math.flingTarget(rotation = 1f, velocity = 50f), 0f)
        assertEquals(0f, math.flingTarget(rotation = 1f, velocity = -50f), 0f)
        assertEquals(1f, math.flingTarget(rotation = 1.2f, velocity = 50f, momentum = false), 0f)
    }

    @Test
    fun bothNeighboursStayVisibleFromTheMiddle() {
        val left = math.offsetRadians(0, rotation = 1f)
        val right = math.offsetRadians(2, rotation = 1f)
        assertTrue(math.alpha(left) > 0.5f)
        assertEquals(math.alpha(left), math.alpha(right), 1e-6f)
        assertEquals(-math.translationX(left, 100f), math.translationX(right, 100f), 1e-4f)
        assertEquals(1f, math.alpha(math.offsetRadians(1, rotation = 1f)), 1e-6f)
    }

    @Test
    fun theFarEndIsStillOnTheArc() {
        // From Devices (0), Settings (2) sits 90° away: dim but visible and tappable.
        val far = math.offsetRadians(2, rotation = 0f)
        assertTrue(math.alpha(far) >= 0.12f)
    }

    @Test
    fun dragFollowsTheFinger() {
        assertTrue(math.dragItems(deltaPx = 50f, radiusPx = 200f) < 0f)
        assertEquals(0f, math.dragItems(deltaPx = 50f, radiusPx = 0f), 0f)
    }

    @Test
    fun frontnessDrivesTheLensMorph() {
        assertEquals(1f, math.frontness(0f), 0f)
        assertEquals(0f, math.frontness(math.stepRadians), 1e-6f)
        assertEquals(0f, math.labelAlpha(math.stepRadians * 0.5f), 1e-6f)
    }
}
