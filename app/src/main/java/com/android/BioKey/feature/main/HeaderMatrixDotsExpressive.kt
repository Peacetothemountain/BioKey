package com.android.BioKey.feature.main

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.isActive
import kotlin.math.sin
import kotlin.random.Random

/**
 * Ambient particle field behind the main screen header.
 *
 * Motion is integrated against the real frame time, so the drift speed is identical on 60Hz and
 * 120Hz panels and doesn't jump after a dropped frame. The particle array is allocated once and
 * updated in place, so draw passes don't allocate.
 */
private class MatrixParticle(
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    val phaseOffset: Float,
    val baseRadius: Float
)

/** Drift speed in px/s. */
private const val MAX_SPEED = 24f
/** Longest step integrated in one frame, so resuming after a pause doesn't teleport particles. */
private const val MAX_STEP_SECONDS = 0.05f
private const val MAX_LINK_DIST_SQ = 230f * 230f

@Composable
fun HeaderMatrixDotsExpressive(
    modifier: Modifier = Modifier
        .fillMaxWidth()
        .height(280.dp),
    particleCount: Int = 26
) {
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary

    val particles = remember(particleCount) {
        Array(particleCount) { i ->
            MatrixParticle(
                x = Random.nextFloat() * 1000f,
                y = Random.nextFloat() * 600f,
                vx = (Random.nextFloat() * 2f - 1f) * MAX_SPEED,
                vy = (Random.nextFloat() * 2f - 1f) * MAX_SPEED,
                phaseOffset = i * 0.35f,
                baseRadius = 3f + Random.nextFloat() * 1.5f
            )
        }
    }

    var frameNanos by remember { mutableLongStateOf(0L) }
    val clock = remember { LongArray(2) } // [0] = first frame, [1] = previous frame
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            clock[1] = 0L // reset delta time on resume
            while (isActive) {
                withFrameNanos { frameNanos = it }
            }
        }
    }

    Canvas(
        modifier = modifier.background(
            Brush.verticalGradient(
                listOf(
                    primary.copy(alpha = 0.14f),
                    tertiary.copy(alpha = 0.05f),
                    Color.Transparent
                )
            )
        )
    ) {
        val w = size.width
        val h = size.height
        val now = frameNanos
        if (w <= 0f || h <= 0f || now == 0L) return@Canvas

        if (clock[0] == 0L) {
            clock[0] = now
            clock[1] = now
            for (p in particles) {
                p.x = Random.nextFloat() * w
                p.y = Random.nextFloat() * h
            }
        }
        val dt = ((now - clock[1]) / 1_000_000_000f).coerceIn(0f, MAX_STEP_SECONDS)
        clock[1] = now
        val seconds = (now - clock[0]) / 1_000_000_000f

        // 1. Integrate motion, reflecting off the canvas edges.
        for (p in particles) {
            p.x += p.vx * dt
            p.y += p.vy * dt
            if (p.x < 0f) {
                p.x = 0f
                p.vx = -p.vx
            } else if (p.x > w) {
                p.x = w
                p.vx = -p.vx
            }
            if (p.y < 0f) {
                p.y = 0f
                p.vy = -p.vy
            } else if (p.y > h) {
                p.y = h
                p.vy = -p.vy
            }
        }

        // 2. Constellation lines that fade with distance.
        for (i in particles.indices) {
            val p1 = particles[i]
            for (j in i + 1 until particles.size) {
                val p2 = particles[j]
                val dx = p1.x - p2.x
                val dy = p1.y - p2.y
                val distSq = dx * dx + dy * dy
                if (distSq < MAX_LINK_DIST_SQ) {
                    drawLine(
                        color = primary.copy(alpha = (1f - distSq / MAX_LINK_DIST_SQ) * 0.32f),
                        start = Offset(p1.x, p1.y),
                        end = Offset(p2.x, p2.y),
                        strokeWidth = 1.6f
                    )
                }
            }
        }

        // 3. Pulsing dots with a soft glow.
        for (p in particles) {
            val pulse = (sin(seconds * 2.5f + p.phaseOffset) + 1f) * 0.5f
            val center = Offset(p.x, p.y)
            drawCircle(
                color = primary.copy(alpha = 0.75f),
                radius = p.baseRadius + 1.8f * pulse,
                center = center
            )
            drawCircle(
                color = tertiary.copy(alpha = (1f - pulse) * 0.18f),
                radius = p.baseRadius * 2.8f + 5f * pulse,
                center = center
            )
        }
    }
}
