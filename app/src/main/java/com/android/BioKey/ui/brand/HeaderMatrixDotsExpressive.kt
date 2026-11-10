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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.android.BioKey.ui.theme.animationsEnabled
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.isActive

/**
 * Ambient constellation behind the header: two depth layers of drifting, pulsing dots, joined by
 * links that fade with distance, the whole field fading out toward the content below.
 *
 * Calm on purpose:
 * - Dots and their links fade out before an edge, so a dot wrapping round to the other side never
 *   pops in or out, nor do its links.
 * - Around [quiet] (the logo's bounds) the field thins out, so no link cuts through the name.
 * - As many dots as the header has room for, the same density on a phone, an unfolded foldable or
 *   a tablet; when the header changes size (a fold, a rotation) the dots move with it.
 *
 * Performance:
 * - Draws in its own graphics layer, so a new frame re-records only this canvas, never the
 *   screen behind it.
 * - Frame state is read only in the draw phase: no recomposition or layout per frame.
 * - Capped at ~60 fps (motion is time-based, so it looks identical on 120 Hz panels at half the
 *   GPU work), paused whenever the screen isn't resumed or the header is hidden behind another
 *   page (`animated` false), and frozen when the system's "Remove animations" setting is on.
 * - Particle state lives in flat float arrays updated in place: no allocation while animating.
 *
 * Sizes, speeds and distances are in pixels of the 420 dpi phone the field was tuned on, scaled
 * to the screen's density, so it looks the same on every phone.
 */
private const val FRAME_INTERVAL_NANOS = 16_000_000L
private const val MAX_STEP_SECONDS = 0.05f
private const val TUNED_DENSITY = 2.625f
private const val LINK_DIST = 220f
private const val LINK_DIST_SQ = LINK_DIST * LINK_DIST
private const val EDGE_MARGIN = 24f
/** How far in from an edge a dot takes to fade in fully (it wraps [EDGE_MARGIN] beyond the edge). */
private const val EDGE_FADE = 56f
/** How bright the field is inside the quiet zone, and over how far round it that eases off. */
private const val QUIET_ALPHA = 0.18f
private const val QUIET_FEATHER = 40f
/** How far round a quiet zone it's fully quiet, so a link ends short of a tile's rim, not on it. */
private const val QUIET_MARGIN = 10f
/** Dots for the header the field was tuned on (a phone's, 1080 x 450 tuned pixels), and the limits. */
private const val TUNED_COUNT = 30
private const val TUNED_AREA = 1080f * 450f
private const val MIN_COUNT = 20
private const val MAX_COUNT = 72

private class Field(capacity: Int) {
    /** How many of the dots are in use: as many as the header has room for ([countFor]). */
    var n = capacity
    val x = FloatArray(capacity)
    val y = FloatArray(capacity)
    val heading = FloatArray(capacity)
    val speed = FloatArray(capacity)
    val radius = FloatArray(capacity)
    val phase = FloatArray(capacity)
    /** 1 = near layer (bigger, brighter, faster, linked), 0 = far layer. */
    val near = BooleanArray(capacity)
    var seeded = false
    var startNanos = 0L
    var lastNanos = 0L
    /** The size the dots were placed in, to move them with the header when it changes size. */
    var placedW = 0f
    var placedH = 0f

    init {
        for (i in 0 until capacity) {
            near[i] = i % 3 != 0
            heading[i] = Random.nextFloat() * (2f * Math.PI.toFloat())
            speed[i] = if (near[i]) 14f + Random.nextFloat() * 14f else 6f + Random.nextFloat() * 6f
            radius[i] = if (near[i]) 2.8f + Random.nextFloat() * 1.6f else 1.6f + Random.nextFloat() * 0.8f
            phase[i] = Random.nextFloat() * 6.28f
        }
    }

    fun seed(w: Float, h: Float) {
        for (i in x.indices) {
            x[i] = Random.nextFloat() * w
            y[i] = Random.nextFloat() * h
        }
        placedW = w
        placedH = h
        seeded = true
    }

    /** The header is now [w] x [h]: every dot keeps its place relative to it. */
    fun resize(w: Float, h: Float) {
        if (w == placedW && h == placedH) return
        val sx = w / placedW
        val sy = h / placedH
        for (i in x.indices) {
            x[i] *= sx
            y[i] *= sy
        }
        placedW = w
        placedH = h
    }

    /** Moves every dot by [dt] seconds; [unit] converts the tuned pixels to this screen's. */
    fun step(dt: Float, t: Float, w: Float, h: Float, unit: Float) {
        val margin = EDGE_MARGIN * unit
        for (i in 0 until n) {
            // Smooth wander: the heading drifts on a slow per-particle wave instead of bouncing.
            heading[i] += sin(t * 0.35f + phase[i]) * 0.55f * dt
            x[i] += cos(heading[i]) * speed[i] * unit * dt
            y[i] += sin(heading[i]) * speed[i] * unit * dt
            // Wrap just outside the edges so dots glide in and out of view.
            if (x[i] < -margin) x[i] = w + margin else if (x[i] > w + margin) x[i] = -margin
            if (y[i] < -margin) y[i] = h + margin else if (y[i] > h + margin) y[i] = -margin
        }
    }
}

/** As many dots as a [w] x [h] header (in tuned pixels) has room for, at the tuned density. */
internal fun countFor(w: Float, h: Float): Int = (TUNED_COUNT * (w * h) / TUNED_AREA).toInt().coerceIn(MIN_COUNT, MAX_COUNT)

@Composable
fun HeaderMatrixDotsExpressive(
    modifier: Modifier = Modifier,
    animated: Boolean = true,
    /** Where the field thins out: the logo, and the labelled controls, so no link cuts through them. Read while drawing. */
    quiet: () -> List<Rect> = { emptyList() }
) {
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val animationsOff = !animated || !animationsEnabled()
    val field = remember { Field(MAX_COUNT) }
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
        val unit = density / TUNED_DENSITY
        val linkDistSq = LINK_DIST_SQ * unit * unit
        val now = frameNanos
        if (w <= 0f || h <= 0f || now == 0L) return@Canvas

        if (!field.seeded) {
            field.seed(w, h)
            field.startNanos = now
        }
        field.resize(w, h)
        field.n = countFor(w / unit, h / unit)
        val edgeFade = EDGE_FADE * unit
        val quietFeather = QUIET_FEATHER * unit
        val quietZones = quiet().map { it.inflate(QUIET_MARGIN * unit) }
        // How visible a point is: fading toward the bottom, before the edges, and round the logo.
        fun visibility(px: Float, py: Float): Float =
            depthFade(py, h) * edgeFade(px, py, w, edgeFade) * quietFade(px, py, quietZones, quietFeather)
        val dt = if (field.lastNanos == 0L) 0f else ((now - field.lastNanos) / 1e9f).coerceIn(0f, MAX_STEP_SECONDS)
        field.lastNanos = now
        val t = (now - field.startNanos) / 1e9f
        field.step(dt, t, w, h, unit)

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
                if (d2 >= linkDistSq) continue
                // The dimmest of its ends and middle: a link fades with whichever is going.
                val fade = minOf(visibility(x[i], y[i]), visibility(x[j], y[j]), visibility((x[i] + x[j]) * 0.5f, (y[i] + y[j]) * 0.5f))
                if (fade <= 0.01f) continue
                drawLine(
                    color = primary.copy(alpha = (1f - d2 / linkDistSq) * 0.30f * fade),
                    start = Offset(x[i], y[i]),
                    end = Offset(x[j], y[j]),
                    strokeWidth = 1.4f * unit
                )
            }
        }

        // Dots: far layer dim and still-ish, near layer pulsing with a soft tertiary glow.
        for (i in 0 until n) {
            val fade = visibility(x[i], y[i])
            if (fade <= 0.01f) continue
            val center = Offset(x[i], y[i])
            if (!field.near[i]) {
                drawCircle(color = tertiary.copy(alpha = 0.35f * fade), radius = field.radius[i] * unit, center = center)
                continue
            }
            val pulse = (sin(t * 2.2f + field.phase[i]) + 1f) * 0.5f
            drawCircle(
                color = tertiary.copy(alpha = (1f - pulse) * 0.16f * fade),
                radius = (field.radius[i] * 2.8f + 5f * pulse) * unit,
                center = center
            )
            drawCircle(
                color = primary.copy(alpha = 0.78f * fade),
                radius = (field.radius[i] + 1.6f * pulse) * unit,
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

/** 0 at the left, right and top edges (and beyond, where dots wrap round), easing to 1 [fade] in. */
private fun edgeFade(x: Float, y: Float, w: Float, fade: Float): Float =
    smooth(minOf(x, w - x, y) / fade)

/** [QUIET_ALPHA] inside any of [quiet], easing back to 1 over [feather] round each; 1 with none. */
private fun quietFade(x: Float, y: Float, quiet: List<Rect>, feather: Float): Float {
    var fade = 1f
    for (zone in quiet) {
        val dx = maxOf(zone.left - x, 0f, x - zone.right)
        val dy = maxOf(zone.top - y, 0f, y - zone.bottom)
        val outside = kotlin.math.sqrt(dx * dx + dy * dy)
        fade = minOf(fade, QUIET_ALPHA + (1f - QUIET_ALPHA) * smooth(outside / feather))
    }
    return fade
}

/** Smoothstep of [t], clamped to 0..1: eases in and out of a fade. */
private fun smooth(t: Float): Float {
    val c = t.coerceIn(0f, 1f)
    return c * c * (3f - 2f * c)
}
