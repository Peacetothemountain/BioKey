package com.android.BioKey.feature.main

import android.os.SystemClock
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.android.BioKey.ui.brand.Wordmark
import com.android.BioKey.ui.brand.WordmarkPainter
import com.android.BioKey.ui.theme.animationsEnabled

/**
 * The BioKey [Wordmark] over its breathing aura, in the theme's colours: chrome letters in
 * primary, the vine in tertiary, so the logo follows the wallpaper palette.
 *
 * The vine's phase and the aura are read only while drawing, so each frame redraws the logo's
 * own layer: no recomposition, no re-layout. Capped at 60 fps; stops when BioKey is off screen
 * and holds still when "Remove animations" is on.
 */
@Composable
fun BioKeyLogo(modifier: Modifier = Modifier.size(240.dp, 88.dp)) {
    val colors = MaterialTheme.colorScheme
    val primary = colors.primary
    val tertiary = colors.tertiary
    val ground = colors.surface.toArgb()
    val auraBrush = remember(primary, tertiary) {
        Brush.radialGradient(listOf(primary.copy(alpha = 0.22f), tertiary.copy(alpha = 0.09f), Color.Transparent))
    }
    val painter = remember { WordmarkPainter() }
    val phase = remember { mutableFloatStateOf(Wordmark.STILL_PHASE) }
    val animate = animationsEnabled()
    if (animate) {
        LaunchedEffect(Unit) {
            var last = 0L
            while (true) {
                withInfiniteAnimationFrameMillis { ms ->
                    if (ms - last >= 16) {
                        last = ms
                        phase.floatValue = Wordmark.phaseAt(SystemClock.uptimeMillis())
                    }
                }
            }
        }
    }
    Box(
        modifier.semantics {
            contentDescription = "BioKey"
            role = Role.Image
        },
        contentAlignment = Alignment.Center
    ) {
        if (animate) BreathingAura(auraBrush) else Box(Modifier.fillMaxSize().background(auraBrush, CircleShape))
        Spacer(
            Modifier
                .fillMaxSize()
                .graphicsLayer()
                .drawBehind {
                    val p = phase.floatValue
                    drawIntoCanvas {
                        painter.draw(it.nativeCanvas, size.width, size.height, p, primary.toArgb(), tertiary.toArgb(), ground)
                    }
                }
        )
    }
}

@Composable
private fun BreathingAura(brush: Brush) {
    val breath = rememberInfiniteTransition(label = "logo_aura")
    val auraScale by breath.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(2800, easing = EaseInOutCubic), RepeatMode.Reverse),
        label = "aura_scale"
    )
    val auraAlpha by breath.animateFloat(
        initialValue = 0.75f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2800, easing = EaseInOutCubic), RepeatMode.Reverse),
        label = "aura_alpha"
    )
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = auraScale
                scaleY = auraScale
                alpha = auraAlpha
            }
            .background(brush, CircleShape)
    )
}
