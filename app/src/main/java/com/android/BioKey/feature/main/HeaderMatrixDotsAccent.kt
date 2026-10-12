package com.android.BioKey.feature.main

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private class MatrixParticle(
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float
)

/**
 * Authentic BioKey animated matrix particle field.
 * Renders 22 floating particles with distance-based dynamic mesh lines
 * and sinusoidal pulsating glow auras, layered over a vertical theme gradient.
 */
@Composable
fun HeaderMatrixDotsAccent(
    modifier: Modifier = Modifier
        .fillMaxWidth()
        .height(260.dp)
) {
    val infiniteTransition = rememberInfiniteTransition(label = "header_matrix")
    val time by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 80000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "time"
    )

    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary

    val particles = remember {
        Array(22) {
            val x = (Math.random() * 1000.0).toFloat()
            val y = (Math.random() * 500.0).toFloat()
            val vx = ((Math.random() * 2.0 - 1.0) * 0.5).toFloat()
            val vy = ((Math.random() * 2.0 - 1.0) * 0.5).toFloat()
            MatrixParticle(x, y, vx, vy)
        }
    }

    Canvas(
        modifier = modifier
            .background(
                Brush.verticalGradient(
                    listOf(
                        primary.copy(alpha = 0.12f),
                        tertiary.copy(alpha = 0.04f),
                        Color.Transparent
                    )
                )
            )
    ) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        // 1. Particle motion and screen edge bounce
        for (p in particles) {
            p.x += p.vx
            p.y += p.vy
            if (p.x < 0f) {
                p.x = 0f
                p.vx *= -1f
            } else if (p.x > w) {
                p.x = w
                p.vx *= -1f
            }
            if (p.y < 0f) {
                p.y = 0f
                p.vy *= -1f
            } else if (p.y > h) {
                p.y = h
                p.vy *= -1f
            }
        }

        // 2. Interactive mesh constellation lines (dist^2 < 48400)
        val maxDistSq = 48400f
        for (i in particles.indices) {
            for (j in i + 1 until particles.size) {
                val p1 = particles[i]
                val p2 = particles[j]
                val dx = p1.x - p2.x
                val dy = p1.y - p2.y
                val distSq = dx * dx + dy * dy
                if (distSq < maxDistSq) {
                    val alphaFactor = (1f - (distSq / maxDistSq)) * 0.35f
                    drawLine(
                        color = primary.copy(alpha = alphaFactor),
                        start = Offset(p1.x, p1.y),
                        end = Offset(p2.x, p2.y),
                        strokeWidth = 1.8f
                    )
                }
            }
        }

        // 3. Glowing pulsating matrix dots
        for (i in particles.indices) {
            val p = particles[i]
            val pulse = ((Math.sin((time * 0.1f + i).toDouble()) + 1.0) * 0.5).toFloat()

            // Core dot
            val dotRadius = 3f + 2f * pulse
            drawCircle(
                color = primary.copy(alpha = 0.70f),
                radius = dotRadius,
                center = Offset(p.x, p.y)
            )

            // Outer glow aura
            val glowRadius = 8f + 5f * pulse
            val glowAlpha = (1f - pulse) * 0.15f
            drawCircle(
                color = tertiary.copy(alpha = glowAlpha),
                radius = glowRadius,
                center = Offset(p.x, p.y)
            )
        }
    }
}
