package com.android.BioKey.feature.main

import android.provider.Settings
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.isActive
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Ambient constellation behind the header: two depth layers of drifting, pulsing dots, joined by
 * links that fade with distance, the whole field fading out toward the content below.
 *
 * Performance:
 * - Draws in its own graphics layer, so a new frame re-records only this canvas, never the
 *   screen behind it.
 * - Frame state is read only in the draw phase: no recomposition or layout per frame.
 * - Capped at ~60 fps (motion is time-based, so it looks identical on 120 Hz panels at half the
 *   GPU work), paused whenever the screen isn't resumed, and frozen when the system's
 *   "Remove animations" setting is on.
 * - Particle state lives in flat float arrays updated in place: no allocation while animating.
 */
private const val FRAME_INTERVAL_NANOS = 16_000_000L
private const val MAX_STEP_SECONDS = 0.05f
private const val LINK_DIST = 220f
private const val LINK_DIST_SQ = LINK_DIST * LINK_DIST
private const val EDGE_MARGIN = 24f

private class Field(count: Int) {
    val n = count
    val x = FloatArray(count)
    val y = FloatArray(count)
    val heading = FloatArray(count)
    val speed = FloatArray(count)
    val radius = FloatArray(count)
    val phase = FloatArray(count)
    /** 1 = near layer (bigger, brighter, faster, linked), 0 = far layer. */
    val near = BooleanArray(count)
    var seeded = false
    var startNanos = 0L
    var lastNanos = 0L

    init {
        for (i in 0 until count) {
            near[i] = i % 3 != 0
            heading[i] = Random.nextFloat() * (2f * Math.PI.toFloat())
            speed[i] = if (near[i]) 14f + Random.nextFloat() * 14f else 6f + Random.nextFloat() * 6f
            radius[i] = if (near[i]) 2.8f + Random.nextFloat() * 1.6f else 1.6f + Random.nextFloat() * 0.8f
            phase[i] = Random.nextFloat() * 6.28f
        }
    }

    fun seed(w: Float, h: Float) {
        for (i in 0 until n) {
            x[i] = Random.nextFloat() * w
            y[i] = Random.nextFloat() * h
        }
        seeded = true
    }

    fun step(dt: Float, t: Float, w: Float, h: Float) {
        for (i in 0 until n) {
            // Smooth wander: the heading drifts on a slow per-particle wave instead of bouncing.
            heading[i] += sin(t * 0.35f + phase[i]) * 0.55f * dt
            x[i] += cos(heading[i]) * speed[i] * dt
            y[i] += sin(heading[i]) * speed[i] * dt
            // Wrap just outside the edges so dots glide in and out of view.
            if (x[i] < -EDGE_MARGIN) x[i] = w + EDGE_MARGIN else if (x[i] > w + EDGE_MARGIN) x[i] = -EDGE_MARGIN
            if (y[i] < -EDGE_MARGIN) y[i] = h + EDGE_MARGIN else if (y[i] > h + EDGE_MARGIN) y[i] = -EDGE_MARGIN
        }
    }
}

@Composable
fun HeaderMatrixDotsExpressive(
    modifier: Modifier = Modifier,
    particleCount: Int = 30
) {
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val context = LocalContext.current
    val animationsOff = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val field = remember(particleCount) { Field(particleCount) }
    val wash = remember(primary, tertiary) {
        Brush.verticalGradient(listOf(primary.copy(alpha = 0.14f), tertiary.copy(alpha = 0.05f), Color.Transparent))
    }

    var frameNanos by remember { mutableLongStateOf(0L) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, animationsOff) {
        if (animationsOff) {
            withFrameNanos { frameNanos = it }
            return@LaunchedEffect
        }
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            field.lastNanos = 0L // no jump after a pause
            var lastPublished = 0L
            while (isActive) {
                withFrameNanos { now ->
                    if (now - lastPublished >= FRAME_INTERVAL_NANOS) {
                        lastPublished = now
                        frameNanos = now
                    }
                }
            }
        }
    }

    Canvas(modifier = modifier.graphicsLayer()) {
        drawRect(wash)
        val w = size.width
        val h = size.height
        val now = frameNanos
        if (w <= 0f || h <= 0f || now == 0L) return@Canvas

        if (!field.seeded) {
            field.seed(w, h)
            field.startNanos = now
        }
        val dt = if (field.lastNanos == 0L) 0f else ((now - field.lastNanos) / 1e9f).coerceIn(0f, MAX_STEP_SECONDS)
        field.lastNanos = now
        val t = (now - field.startNanos) / 1e9f
        field.step(dt, t, w, h)

        val x = field.x
        val y = field.y
        val n = field.n

        // Links between near dots, fading with distance and toward the bottom.
        for (i in 0 until n) {
            if (!field.near[i]) continue
            for (j in i + 1 until n) {
                if (!field.near[j]) continue
                val dx = x[i] - x[j]
                val dy = y[i] - y[j]
                val d2 = dx * dx + dy * dy
                if (d2 >= LINK_DIST_SQ) continue
                val fade = depthFade((y[i] + y[j]) * 0.5f, h)
                drawLine(
                    color = primary.copy(alpha = (1f - d2 / LINK_DIST_SQ) * 0.30f * fade),
                    start = Offset(x[i], y[i]),
                    end = Offset(x[j], y[j]),
                    strokeWidth = 1.4f
                )
            }
        }

        // Dots: far layer dim and still-ish, near layer pulsing with a soft tertiary glow.
        for (i in 0 until n) {
            val fade = depthFade(y[i], h)
            if (fade <= 0.01f) continue
            val center = Offset(x[i], y[i])
            if (!field.near[i]) {
                drawCircle(color = tertiary.copy(alpha = 0.35f * fade), radius = field.radius[i], center = center)
                continue
            }
            val pulse = (sin(t * 2.2f + field.phase[i]) + 1f) * 0.5f
            drawCircle(
                color = tertiary.copy(alpha = (1f - pulse) * 0.16f * fade),
                radius = field.radius[i] * 2.8f + 5f * pulse,
                center = center
            )
            drawCircle(
                color = primary.copy(alpha = 0.78f * fade),
                radius = field.radius[i] + 1.6f * pulse,
                center = center
            )
        }
    }
}

/** 1 at the top, easing to 0 at the bottom edge so the field melts into the content. */
private fun depthFade(y: Float, h: Float): Float {
    val p = (y / h).coerceIn(0f, 1f)
    return 1f - p * p
}
