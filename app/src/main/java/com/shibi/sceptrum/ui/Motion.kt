package com.shibi.sceptrum.ui

import android.provider.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.shibi.sceptrum.ui.background.LocalBackgroundChrome

/** Animator duration scale 0 (Developer options or Remove animations) means snap, never loop (plan §2.3). */
val LocalReduceMotion = staticCompositionLocalOf { false }

@Composable
fun systemReducesMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/** Spinner in the backdrop accent; a still arc when motion is reduced. */
@Composable
fun BusyIndicator(
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 3.dp,
) {
    val color = LocalBackgroundChrome.current.accentText
    if (LocalReduceMotion.current) {
        CircularProgressIndicator(progress = { STILL_ARC }, color = color, strokeWidth = strokeWidth, modifier = modifier)
    } else {
        CircularProgressIndicator(color = color, strokeWidth = strokeWidth, modifier = modifier)
    }
}

private const val STILL_ARC = 0.7f
