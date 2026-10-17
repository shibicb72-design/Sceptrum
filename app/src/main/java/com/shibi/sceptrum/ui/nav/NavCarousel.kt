package com.shibi.sceptrum.ui.nav

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.shibi.sceptrum.domain.CarouselMath
import com.shibi.sceptrum.ui.background.BackgroundChrome
import com.shibi.sceptrum.ui.background.LocalBackgroundChrome
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private val LabelGap = 6.dp
private val LabelHeight = 18.dp
private val TopBreathing = 6.dp
private val BottomPadding = 8.dp
private val BackLift = 6.dp
private val MinTile = 44.dp
private val MaxTile = 60.dp
private val PillShape = RoundedCornerShape(percent = 50)

/** Lilac glow under the lens on dark chrome; deep violet comes from `accentText` on light. */
private val DarkGlow = Color(0xFF8E7BFF)

// Material 3 Expressive motion scheme, as in Spectacula's carousel.
private val SpatialSpring = spring<Float>(dampingRatio = 0.8f, stiffness = 380f)
private val LensSettleSpring = spring<Float>(dampingRatio = 0.6f, stiffness = 800f)
private val LensPressSpring = spring<Float>(dampingRatio = 1f, stiffness = 3800f)

internal data class CarouselLayout(
    val centerX: Float,
    val centerY: Float,
    val radius: Float,
    val liftPx: Float,
    val tileRadius: Float,
    val labelExtent: Float,
)

/**
 * Spectacula-style glass carousel: icons travel on an arc and pass through a
 * fixed lens at the front. Tap an icon or drag the arc to change section.
 */
@Composable
fun NavCarousel(
    selected: NavSection,
    onSelect: (NavSection) -> Unit,
    modifier: Modifier = Modifier,
    sections: List<NavSection> = NavSection.entries,
) {
    val math = remember(sections.size) { CarouselMath(sections.size) }
    val rotation = remember { Animatable(sections.indexOf(selected).coerceAtLeast(0).toFloat()) }
    val energy = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val onSelectState = rememberUpdatedState(onSelect)
    val reduceMotion = rememberReduceMotion()
    var gestureActive by remember { mutableStateOf(false) }
    var settleGeneration by remember { mutableIntStateOf(0) }
    val selectedIndex by remember { derivedStateOf { math.selectedIndex(rotation.value) } }

    LaunchedEffect(selected) {
        if (gestureActive) return@LaunchedEffect
        val index = sections.indexOf(selected)
        if (index < 0) return@LaunchedEffect
        val target = math.rotationForIndex(index)
        if (abs(rotation.value - target) > 0.01f) {
            if (reduceMotion) rotation.snapTo(target) else rotation.animateTo(target, SpatialSpring)
        }
    }

    fun lift() {
        scope.launch {
            if (reduceMotion) energy.snapTo(1f) else energy.animateTo(1f, LensPressSpring)
        }
    }

    fun settle(target: Float, velocity: Float, generation: Int) {
        scope.launch {
            if (reduceMotion) {
                rotation.snapTo(target)
            } else {
                rotation.animateTo(target, SpatialSpring, initialVelocity = velocity)
            }
            if (settleGeneration != generation) return@launch
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            gestureActive = false
            onSelectState.value(sections[math.selectedIndex(target)])
            if (reduceMotion) energy.snapTo(0f) else energy.animateTo(0f, LensSettleSpring)
        }
    }

    fun selectIndex(index: Int) {
        val generation = ++settleGeneration
        gestureActive = true
        lift()
        scope.launch { rotation.stop() }
        settle(math.rotationForIndex(index), 0f, generation)
    }

    NavCarouselContent(
        sections = sections,
        rotation = { rotation.value },
        energy = { energy.value },
        selectedIndex = selectedIndex,
        onSelectIndex = ::selectIndex,
        modifier = modifier,
    ) { layoutState ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(8f)
                .pointerInput(math) {
                    val slop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val generation = ++settleGeneration
                        scope.launch { rotation.stop() }
                        gestureActive = true
                        var spun = rotation.value
                        val tracker = VelocityTracker()
                        tracker.addPosition(down.uptimeMillis, down.position)
                        var totalX = 0f
                        var totalY = 0f
                        var dragging = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                change.consume()
                                val currentLayout = layoutState()
                                if (!dragging) {
                                    val hit = hitTest(down.position, currentLayout, rotation.value, math)
                                    if (hit != null) {
                                        lift()
                                        settle(math.rotationForIndex(hit), 0f, generation)
                                    } else if (settleGeneration == generation) {
                                        gestureActive = false
                                    }
                                } else {
                                    val velocity = tracker.calculateVelocity().x
                                    val itemsPerSecond = math.dragItems(velocity, currentLayout.radius)
                                    val target = math.flingTarget(
                                        rotation = rotation.value,
                                        velocity = itemsPerSecond,
                                        momentum = !reduceMotion,
                                    )
                                    settle(target, itemsPerSecond, generation)
                                }
                                break
                            }
                            val delta = change.positionChange()
                            totalX += delta.x
                            totalY += delta.y
                            tracker.addPosition(change.uptimeMillis, change.position)
                            if (!dragging && (abs(totalX) > slop || abs(totalY) > slop)) {
                                dragging = abs(totalX) >= abs(totalY)
                                if (!dragging) {
                                    if (settleGeneration == generation) gestureActive = false
                                    break
                                }
                                lift()
                            }
                            if (dragging) {
                                change.consume()
                                spun = math.clamp(spun + math.dragItems(delta.x, layoutState().radius))
                                val target = spun
                                scope.launch { rotation.snapTo(target) }
                            }
                        }
                    }
                },
        )
    }
}

/**
 * The arc itself, stateless. Per-frame values ([rotation], [energy]) are read
 * in layout, layer or draw lambdas, so spinning never recomposes.
 */
@Composable
internal fun NavCarouselContent(
    rotation: () -> Float,
    energy: () -> Float,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    sections: List<NavSection> = NavSection.entries,
    onSelectIndex: (Int) -> Unit = {},
    overlay: @Composable BoxScope.(layout: () -> CarouselLayout) -> Unit = {},
) {
    val math = remember(sections.size) { CarouselMath(sections.size) }
    val chrome = LocalBackgroundChrome.current
    val lens = remember(chrome) { lensColors(chrome) }

    Column(modifier = modifier.fillMaxWidth()) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .selectableGroup(),
        ) {
            val density = LocalDensity.current
            val densityScale = density.density
            val tileSize = with(density) {
                (maxWidth.toPx() * 0.145f).coerceIn(MinTile.toPx(), MaxTile.toPx()).toDp()
            }
            val bodyHeight = tileSize + TopBreathing + LabelGap + LabelHeight
            val layout = with(density) {
                val tileRadius = tileSize.toPx() / 2f
                val labelExtent = (LabelGap + LabelHeight).toPx()
                val widthPx = constraints.maxWidth.toFloat()
                CarouselLayout(
                    centerX = widthPx / 2f,
                    centerY = bodyHeight.toPx() - labelExtent - tileRadius,
                    radius = widthPx * CarouselMath.RADIUS_FRACTION,
                    liftPx = BackLift.toPx(),
                    tileRadius = tileRadius,
                    labelExtent = labelExtent,
                )
            }
            val layoutState = rememberUpdatedState(layout)
            val pillWidth = tileSize * 1.42f
            val pillHeight = tileSize * 0.86f

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(bodyHeight),
            ) {
                // Soft light pooled under the lens.
                Spacer(
                    modifier = Modifier
                        .fillMaxSize()
                        .drawBehind {
                            val glowY = layout.centerY + pillHeight.toPx() * 0.42f
                            val rx = pillWidth.toPx() * 1.15f
                            val ry = 14.dp.toPx()
                            val strength = 1f - 0.45f * energy()
                            val center = Offset(layout.centerX, glowY)
                            drawOval(
                                brush = Brush.radialGradient(
                                    0f to lens.glow.copy(alpha = lens.glow.alpha * strength),
                                    1f to Color.Transparent,
                                    center = center,
                                    radius = rx,
                                ),
                                topLeft = Offset(center.x - rx, center.y - ry),
                                size = Size(rx * 2f, ry * 2f),
                            )
                        },
                )

                // The lens: fixed at the front; icons pass through it and morph.
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (layout.centerX - pillWidth.toPx() / 2f).roundToInt(),
                                (layout.centerY - pillHeight.toPx() / 2f).roundToInt(),
                            )
                        }
                        .size(width = pillWidth, height = pillHeight)
                        .graphicsLayer {
                            val e = energy()
                            scaleX = 1f - 0.08f * e
                            scaleY = 1f - 0.06f * e
                            shadowElevation = (10f - 5f * e) * densityScale
                            shape = PillShape
                            clip = true
                            ambientShadowColor = lens.ambient
                            spotShadowColor = lens.spot
                        }
                        .background(lens.fill)
                        .border(width = 1.dp, brush = lens.rim, shape = PillShape),
                )

                CarouselArc(
                    sections = sections,
                    math = math,
                    rotation = rotation,
                    layout = layout,
                    tileSize = tileSize,
                    selectedIndex = selectedIndex,
                    chrome = chrome,
                    lensIcon = lens.icon,
                    cameraDistancePx = 12f * densityScale,
                    onSelectIndex = onSelectIndex,
                )

                CarouselLabels(
                    sections = sections,
                    math = math,
                    rotation = rotation,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .zIndex(7f),
                )

                overlay { layoutState.value }
            }
        }
        Spacer(Modifier.height(BottomPadding))
    }
}

@Composable
private fun CarouselArc(
    sections: List<NavSection>,
    math: CarouselMath,
    rotation: () -> Float,
    layout: CarouselLayout,
    tileSize: Dp,
    selectedIndex: Int,
    chrome: BackgroundChrome,
    lensIcon: Color,
    cameraDistancePx: Float,
    onSelectIndex: (Int) -> Unit,
) {
    Layout(
        modifier = Modifier.fillMaxSize().zIndex(2f),
        content = {
            sections.forEachIndexed { index, section ->
                ArcItem(
                    section = section,
                    frontness = { math.frontness(math.offsetRadians(index, rotation())) },
                    selected = index == selectedIndex,
                    tileSize = tileSize,
                    chrome = chrome,
                    lensIcon = lensIcon,
                    onClick = { onSelectIndex(index) },
                )
            }
        },
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            val turn = rotation()
            placeables.forEachIndexed { index, placeable ->
                val offset = math.offsetRadians(index, turn)
                val x = (layout.centerX - placeable.width / 2f).roundToInt()
                val y = (layout.centerY - placeable.height / 2f).roundToInt()
                placeable.placeWithLayer(x, y, zIndex = math.zIndex(offset) + 1f) {
                    val scale = math.scale(offset)
                    translationX = math.translationX(offset, layout.radius)
                    translationY = math.translationY(offset, layout.liftPx)
                    scaleX = scale
                    scaleY = scale
                    alpha = math.alpha(offset)
                    rotationY = math.rotationYDegrees(offset)
                    cameraDistance = cameraDistancePx
                    transformOrigin = TransformOrigin.Center
                }
            }
        }
    }
}

@Composable
private fun ArcItem(
    section: NavSection,
    frontness: () -> Float,
    selected: Boolean,
    tileSize: Dp,
    chrome: BackgroundChrome,
    lensIcon: Color,
    onClick: () -> Unit,
) {
    val label = stringResource(section.labelRes)
    val iconSize = tileSize * 0.42f
    Box(
        modifier = Modifier
            .size(tileSize)
            .semantics {
                role = Role.Tab
                this.selected = selected
                contentDescription = label
                onClick {
                    onClick()
                    true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // A quiet glass bead that dissolves into the lens as it reaches the front.
        Spacer(
            modifier = Modifier
                .size(tileSize * 0.80f)
                .drawBehind {
                    val away = 1f - frontness()
                    if (away <= 0.01f) return@drawBehind
                    drawCircle(chrome.carouselTile.copy(alpha = chrome.carouselTile.alpha * away))
                    drawCircle(
                        color = chrome.carouselTileBorder.copy(
                            alpha = chrome.carouselTileBorder.alpha * away,
                        ),
                        radius = size.minDimension / 2f - 0.5.dp.toPx(),
                        style = Stroke(width = 1.dp.toPx()),
                    )
                },
        )
        Icon(
            imageVector = section.icon,
            contentDescription = null,
            tint = chrome.carouselIcon,
            modifier = Modifier
                .size(iconSize)
                .graphicsLayer {
                    val f = frontness()
                    alpha = 1f - f
                    scaleX = 1f + 0.14f * f
                    scaleY = 1f + 0.14f * f
                },
        )
        Icon(
            imageVector = section.icon,
            contentDescription = null,
            tint = lensIcon,
            modifier = Modifier
                .size(iconSize)
                .graphicsLayer {
                    val f = frontness()
                    alpha = f
                    scaleX = 1f + 0.14f * f
                    scaleY = 1f + 0.14f * f
                },
        )
    }
}

/** All labels stacked; each fades with its own item so two words never smear. */
@Composable
private fun CarouselLabels(
    sections: List<NavSection>,
    math: CarouselMath,
    rotation: () -> Float,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .width(160.dp)
            .height(LabelHeight)
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        sections.forEachIndexed { index, section ->
            Text(
                text = stringResource(section.labelRes),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        alpha = math.labelAlpha(math.offsetRadians(index, rotation()))
                    },
            )
        }
    }
}

private class LensColors(
    val fill: Brush,
    val rim: Brush,
    val icon: Color,
    val glow: Color,
    val spot: Color,
    val ambient: Color,
)

private fun lensColors(chrome: BackgroundChrome): LensColors {
    // Opaque glass: tints are composited over the page so the soft elevation
    // shadow never shows through the lens as a dark slab.
    val page = chrome.pageFill.copy(alpha = 1f)
    return if (chrome.isLight) {
        LensColors(
            fill = Brush.verticalGradient(
                0f to Color.White,
                1f to Color.White.copy(alpha = 0.84f).compositeOver(page),
            ),
            rim = Brush.verticalGradient(
                0f to Color.White,
                1f to chrome.accentText.copy(alpha = 0.16f),
            ),
            icon = chrome.accentText,
            glow = chrome.accentText.copy(alpha = 0.22f),
            spot = chrome.accentText.copy(alpha = 0.38f),
            ambient = Color.Black.copy(alpha = 0.18f),
        )
    } else {
        LensColors(
            fill = Brush.verticalGradient(
                0f to Color.White.copy(alpha = 0.20f).compositeOver(page),
                1f to Color.White.copy(alpha = 0.08f).compositeOver(page),
            ),
            rim = Brush.verticalGradient(
                0f to Color.White.copy(alpha = 0.50f),
                1f to Color.White.copy(alpha = 0.06f),
            ),
            icon = Color.White,
            glow = DarkGlow.copy(alpha = 0.36f),
            spot = DarkGlow.copy(alpha = 0.60f),
            ambient = Color.Black.copy(alpha = 0.50f),
        )
    }
}

/** True when the system "Remove animations" setting (animator scale 0) is on. */
@Composable
private fun rememberReduceMotion(): Boolean {
    if (LocalInspectionMode.current) return false
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }
}

private fun hitTest(
    position: Offset,
    layout: CarouselLayout,
    rotation: Float,
    math: CarouselMath,
): Int? {
    var best = -1
    var bestDepth = Float.NEGATIVE_INFINITY
    for (index in 0 until math.itemCount) {
        val offset = math.offsetRadians(index, rotation)
        val scale = math.scale(offset)
        if (math.alpha(offset) < 0.12f) continue
        val pointX = layout.centerX + math.translationX(offset, layout.radius)
        val pointY = layout.centerY + math.translationY(offset, layout.liftPx)
        val dx = abs(position.x - pointX)
        val dy = position.y - pointY
        val halfW = layout.tileRadius * scale * 1.35f
        val top = layout.tileRadius * scale
        val bottom = (layout.tileRadius + layout.labelExtent) * scale
        if (dx > halfW || dy < -top || dy > bottom) continue
        val depth = math.zIndex(offset)
        if (depth > bestDepth) {
            bestDepth = depth
            best = index
        }
    }
    return best.takeIf { it >= 0 }
}
