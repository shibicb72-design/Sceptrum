package com.shibi.sceptrum.domain

/**
 * Settings > "Type with key presses" (plan §3.2): text as `KeyEvent` codes for
 * apps that ignore the IME batch edit. There is no Shift, so capitals type as
 * lower case, and characters without a key are skipped.
 */
object KeyPressTyping {
    const val KEYCODE_DEL = 67
    const val KEYCODE_ENTER = 66

    private val symbols = mapOf(
        ' ' to 62,
        '.' to 56,
        ',' to 55,
        '-' to 69,
        '=' to 70,
        '[' to 71,
        ']' to 72,
        '\\' to 73,
        ';' to 74,
        '\'' to 75,
        '/' to 76,
        '@' to 77,
        '+' to 81,
        '*' to 17,
        '#' to 18,
        '`' to 68,
    )

    fun keyCodeFor(c: Char): Int? = when (c) {
        in 'a'..'z' -> 29 + (c - 'a')
        in 'A'..'Z' -> 29 + (c - 'A')
        in '0'..'9' -> 7 + (c - '0')
        else -> symbols[c]
    }

    /**
     * Keys that turn the TV field from [old] into [new]: one DEL per character
     * after the common prefix, then the new characters.
     */
    fun keysFor(old: String, new: String): List<Int> {
        val common = old.commonPrefixWith(new).length
        return List(old.length - common) { KEYCODE_DEL } + new.substring(common).mapNotNull(::keyCodeFor)
    }
}

/** Swipe pad (plan §3.1): one d-pad step per [stepPx] of travel along the stronger axis. */
class SwipeStepper(private val stepPx: Float) {
    private var x = 0f
    private var y = 0f

    init {
        require(stepPx > 0f) { "step must be positive" }
    }

    /** Adds a drag delta and returns the steps it completes, in order. */
    fun drag(dx: Float, dy: Float): List<RemoteKey> {
        x += dx
        y += dy
        val steps = mutableListOf<RemoteKey>()
        while (true) {
            val horizontal = kotlin.math.abs(x) >= kotlin.math.abs(y)
            val travel = if (horizontal) x else y
            if (kotlin.math.abs(travel) < stepPx) break
            val sign = if (travel > 0) 1 else -1
            steps += when {
                horizontal && sign > 0 -> RemoteKey.Right
                horizontal -> RemoteKey.Left
                sign > 0 -> RemoteKey.Down
                else -> RemoteKey.Up
            }
            // The other axis starts over, so a slightly diagonal swipe stays on one line.
            if (horizontal) {
                x -= sign * stepPx
                y = 0f
            } else {
                y -= sign * stepPx
                x = 0f
            }
        }
        return steps
    }

    fun reset() {
        x = 0f
        y = 0f
    }
}
