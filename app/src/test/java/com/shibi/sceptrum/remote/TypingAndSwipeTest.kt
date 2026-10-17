package com.shibi.sceptrum.remote

import com.shibi.sceptrum.domain.KeyPressTyping
import com.shibi.sceptrum.domain.RemoteKey
import com.shibi.sceptrum.domain.SwipeStepper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TypingAndSwipeTest {
    @Test
    fun charactersMapToKeyEventCodes() {
        assertEquals(29, KeyPressTyping.keyCodeFor('a'))
        assertEquals(54, KeyPressTyping.keyCodeFor('z'))
        assertEquals("no shift: capitals type lower case", 29, KeyPressTyping.keyCodeFor('A'))
        assertEquals(7, KeyPressTyping.keyCodeFor('0'))
        assertEquals(16, KeyPressTyping.keyCodeFor('9'))
        assertEquals(62, KeyPressTyping.keyCodeFor(' '))
        assertEquals(56, KeyPressTyping.keyCodeFor('.'))
        assertEquals(77, KeyPressTyping.keyCodeFor('@'))
        assertNull(KeyPressTyping.keyCodeFor('é'))
    }

    @Test
    fun keysDeleteBackToTheCommonPrefixThenType() {
        assertEquals(listOf(31, 29, 48), KeyPressTyping.keysFor("", "cat"))
        assertEquals(listOf(47), KeyPressTyping.keysFor("cat", "cats"))
        assertEquals(listOf(67), KeyPressTyping.keysFor("cats", "cat"))
        assertEquals(listOf(67, 67, 32, 42), KeyPressTyping.keysFor("cat", "cdn"))
        assertEquals(List(3) { 67 }, KeyPressTyping.keysFor("cat", ""))
        assertTrue(KeyPressTyping.keysFor("same", "same").isEmpty())
        assertEquals("unmappable characters are skipped", listOf(29), KeyPressTyping.keysFor("", "aé"))
    }

    @Test
    fun swipeStepsOncePerStepAlongTheStrongerAxis() {
        val stepper = SwipeStepper(stepPx = 100f)
        assertTrue(stepper.drag(60f, 10f).isEmpty())
        assertEquals(listOf(RemoteKey.Right), stepper.drag(50f, 5f))
        assertEquals(listOf(RemoteKey.Right, RemoteKey.Right), stepper.drag(200f, 0f))
        stepper.reset()
        assertEquals(listOf(RemoteKey.Up), stepper.drag(-20f, -130f))
        stepper.reset()
        assertEquals(listOf(RemoteKey.Down, RemoteKey.Down, RemoteKey.Down), stepper.drag(0f, 320f))
        stepper.reset()
        assertEquals(listOf(RemoteKey.Left), stepper.drag(-100f, 0f))
    }

    @Test
    fun aDiagonalWobbleDoesNotStepSideways() {
        val stepper = SwipeStepper(stepPx = 100f)
        val steps = (1..10).flatMap { stepper.drag(30f, if (it % 2 == 0) 20f else -20f) }
        assertEquals(List(3) { RemoteKey.Right }, steps)
    }
}
