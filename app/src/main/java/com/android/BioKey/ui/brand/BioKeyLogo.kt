package com.android.BioKey.ui.brand

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.android.BioKey.R
import com.android.BioKey.ui.theme.animationsEnabled
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.isActive

/**
 * BioKey's logo: its mark ([KeyMark]), upright, as large as fits [modifier]'s size, centred, in the
 * phone's colours. Still. BioKey's own lock and setup show it; the lock cover shows the same mark
 * ([WordmarkView]).
 */
@Composable
fun BioKeyLogo(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val name = stringResource(R.string.brand_name)
    Canvas(modifier.clearAndSetSemantics { contentDescription = name; role = Role.Image }) {
        drawMark(colors.primary.toArgb(), colors.secondary.toArgb(), colors.tertiary.toArgb(), motion = null)
    }
}

/** At most one frame per this long (~60 fps): the motion is slow, and time-based. */
private const val FRAME_INTERVAL_NANOS = 16_000_000L

/** One twinkle of the sparkles. */
private const val TWINKLE_SECONDS = 2.6f
/** One slow circle of each sparkle round where it sits. */
private const val DRIFT_SECONDS = 9f

/**
 * How long each ridge's colour takes to go once round the print, outermost first: the inner ones
 * quicker, so the colour spirals in towards the middle while the lines stay where they are.
 */
private val SWIRL_SECONDS = floatArrayOf(9f, 7.5f, 6f, 4.5f, 3.2f)
/** How far each sparkle drifts round where it sits (in the icon's units). */
private const val DRIFT = 1.4f

/**
 * Sets [this] to the mark's pose [seconds] into its loop: each ridge's colour turned round the
 * print's centre, the inner ones further (the lines themselves never move); the sparkles twinkling
 * (growing and brightening) out of step, each drifting round in a small circle. Every pose is a
 * smooth function of time, so the loop never jumps.
 */
internal fun KeyMark.Motion.at(seconds: Float) {
    for (i in ridgeSweep.indices) ridgeSweep[i] = (360f * seconds / SWIRL_SECONDS[i]) % 360f
    for (k in sparkleScale.indices) {
        val twinkle = (sin(2f * PI.toFloat() * seconds / TWINKLE_SECONDS + k * 2.1f) + 1f) / 2f
        sparkleScale[k] = 0.65f + 0.5f * twinkle
        sparkleAlpha[k] = 0.45f + 0.55f * twinkle
        val drift = 2f * PI.toFloat() * seconds / DRIFT_SECONDS + k * 2.3f
        sparkleDx[k] = DRIFT * cos(drift)
        sparkleDy[k] = DRIFT * sin(drift)
    }
}

/**
 * BioKey's mark turned a quarter-turn clockwise (the fingerprint on the right, the key on the
 * left), for the home header between the status and Settings tiles. As large as fits [modifier]'s
 * size, centred, in the phone's colours.
 *
 * Moves on its own, never from the phone's sensors: the print stays exactly where it is while the
 * colour in its ridges swirls round its centre, quickest in the middle; the key holds still, and
 * the sparkles twinkle and drift round it ([at]). Only while
 * [animated] (the header uncovered), the screen resumed and "Remove animations" off; otherwise it
 * holds its pose. Like the constellation behind it: its own graphics layer, the frame read only
 * while drawing (no recomposition per frame), at most 60 fps, nothing allocated per frame.
 */
@Composable
fun BioKeyMarkSideways(modifier: Modifier = Modifier, animated: Boolean = true) {
    val colors = MaterialTheme.colorScheme
    val name = stringResource(R.string.brand_name)
    val motion = remember { KeyMark.Motion() }
    val moving = animated && animationsEnabled()
    var frameNanos by remember { mutableLongStateOf(0L) }
    val startNanos = remember { longArrayOf(0L) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, moving) {
        if (!moving) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var lastPublished = 0L
            while (isActive) {
                withFrameNanos { now ->
                    if (startNanos[0] == 0L) startNanos[0] = now
                    if (now - lastPublished >= FRAME_INTERVAL_NANOS) {
                        lastPublished = now
                        frameNanos = now
                    }
                }
            }
        }
    }
    Canvas(modifier.graphicsLayer().clearAndSetSemantics { contentDescription = name; role = Role.Image }) {
        val now = frameNanos
        // Held at its first pose until it moves, so it never changes look as it starts.
        motion.at(if (now == 0L) 0f else (now - startNanos[0]) / 1e9f)
        // Laid out upright in the box turned on its side, then turned with it.
        rotate(90f) {
            drawMark(
                colors.primary.toArgb(), colors.secondary.toArgb(), colors.tertiary.toArgb(),
                motion = motion,
                width = size.height,
                height = size.width
            )
        }
    }
}

/** Draws the mark centred in this scope, in a [width] x [height] box (the scope's own by default). */
private fun DrawScope.drawMark(
    primary: Int,
    secondary: Int,
    tertiary: Int,
    motion: KeyMark.Motion?,
    width: Float = size.width,
    height: Float = size.height
) {
    drawIntoCanvas {
        KeyMark.draw(it.nativeCanvas, (size.width - width) / 2f, (size.height - height) / 2f, width, height, primary, secondary, tertiary, motion)
    }
}
