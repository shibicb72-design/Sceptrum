package com.shibi.sceptrum.domain

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Geometry for the bottom carousel, adapted from Spectacula's ring.
 *
 * Spectacula spreads eight items round a full ring. Sceptrum has three, which
 * would sit 120° apart and leave only the front one visible, so here the items
 * keep Spectacula's 45° spacing on an open arc: rotation runs from 0 to
 * `itemCount - 1` and does not wrap. Angle 0 is the front, positive angles run
 * to the right, and every pose mirrors left to right. Screen Y grows downward;
 * a negative Y offset lifts a tile.
 */
class CarouselMath(val itemCount: Int) {
    init {
        require(itemCount >= 2) { "A carousel needs at least two items" }
    }

    val stepRadians: Float = STEP_DEGREES / 180f * PI.toFloat()

    val maxRotation: Float get() = (itemCount - 1).toFloat()

    fun clamp(rotation: Float): Float = rotation.coerceIn(0f, maxRotation)

    fun selectedIndex(rotation: Float): Int = clamp(rotation).roundToInt()

    /** Nearest detent inside the arc. */
    fun snapTarget(rotation: Float): Float = clamp(rotation).roundToInt().toFloat()

    fun rotationForIndex(index: Int): Float {
        require(index in 0 until itemCount) { "index $index is outside 0 until $itemCount" }
        return index.toFloat()
    }

    /** Signed radians from the front. */
    fun offsetRadians(index: Int, rotation: Float): Float = (index - rotation) * stepRadians

    fun translationX(offsetRadians: Float, radius: Float): Float = radius * sin(offsetRadians)

    /** 0 at the front. Side tiles rise by [liftPx] times `(1 - cos θ)`. */
    fun translationY(offsetRadians: Float, liftPx: Float): Float =
        -(1f - cos(offsetRadians)) * liftPx

    /** 1 at the front, falling towards [MIN_SCALE] at the back. */
    fun scale(offsetRadians: Float): Float {
        val facing = (cos(offsetRadians) + 1f) / 2f
        return MIN_SCALE + (1f - MIN_SCALE) * facing
    }

    /** 0 past the side of the ring, otherwise a symmetric fade towards the edge. */
    fun alpha(offsetRadians: Float): Float {
        val facing = cos(offsetRadians)
        if (facing < BACK_COS) return 0f
        val t = (facing + 1f) / 2f
        return MIN_ALPHA + (1f - MIN_ALPHA) * t
    }

    fun zIndex(offsetRadians: Float): Float = cos(offsetRadians)

    fun rotationYDegrees(offsetRadians: Float): Float {
        val degrees = offsetRadians * 180f / PI.toFloat()
        return (-degrees * TILT_PER_DEGREE).coerceIn(-MAX_TILT_DEGREES, MAX_TILT_DEGREES)
    }

    /**
     * 1:1 drag along the arc. A finger moving right yields a negative item
     * delta, so the item under the finger stays under the finger.
     */
    fun dragItems(deltaPx: Float, radiusPx: Float): Float {
        if (radiusPx <= 0f) return 0f
        return -deltaPx / (radiusPx * stepRadians)
    }

    /** Where a release with [velocity] (items per second) comes to rest. */
    fun flingTarget(rotation: Float, velocity: Float, momentum: Boolean = true): Float {
        if (!momentum) return snapTarget(rotation)
        val travel = (velocity * FLING_PROJECTION_SECONDS).coerceIn(-maxRotation, maxRotation)
        return snapTarget(rotation + travel)
    }

    /** 1 at the front, 0 one step away or further. Drives the selected-state morph. */
    fun frontness(offsetRadians: Float): Float =
        (1f - abs(offsetRadians) / stepRadians).coerceIn(0f, 1f)

    /** Label hand-off: each label fades in over the last half step. */
    fun labelAlpha(offsetRadians: Float): Float {
        val t = ((frontness(offsetRadians) - 0.5f) / 0.5f).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    companion object {
        const val STEP_DEGREES = 45f
        const val FLING_PROJECTION_SECONDS = 0.22f
        const val MIN_SCALE = 0.62f
        const val MIN_ALPHA = 0.35f
        const val BACK_COS = -0.1f
        const val TILT_PER_DEGREE = 0.6f
        const val MAX_TILT_DEGREES = 40f
        const val RADIUS_FRACTION = 0.40f
    }
}
