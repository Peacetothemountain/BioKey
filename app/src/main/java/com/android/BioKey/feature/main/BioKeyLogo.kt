package com.android.BioKey.feature.main

import android.os.SystemClock
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.android.BioKey.R
import com.android.BioKey.ui.brand.VineSlither
import com.android.BioKey.ui.theme.animationsEnabled

/**
 * The two-layer BioKey wordmark over its breathing aura. Both layers are white shapes with
 * two-tone detail in their alpha, filled with the theme: letters in primary, the
 * fingerprint-ridge vines in tertiary, so the logo follows the wallpaper palette.
 *
 * The vines slither like snakes ([VineSlither]); the letters stay still. The wave and the aura
 * are read only while drawing, so each frame redraws the logo's own layers: no recomposition,
 * no re-layout. Capped at 60 fps, paused off screen; still when [animated] is false (lock
 * screens) or with "Remove animations".
 */
@Composable
fun BioKeyLogo(modifier: Modifier = Modifier, animated: Boolean = true) {
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val auraBrush = remember(primary, tertiary) {
        Brush.radialGradient(listOf(primary.copy(alpha = 0.22f), tertiary.copy(alpha = 0.09f), Color.Transparent))
    }
    val resources = LocalResources.current
    val vines = remember(resources) { VineSlither(resources) }
    val animate = animated && animationsEnabled()
    val slither = animate && VineSlither.canAnimate
    val phase = remember { mutableFloatStateOf(0f) }
    if (slither) {
        LaunchedEffect(Unit) {
            var last = 0L
            while (true) {
                withInfiniteAnimationFrameMillis { ms ->
                    if (ms - last >= 16) {
                        last = ms
                        phase.floatValue = VineSlither.phaseAt(SystemClock.uptimeMillis())
                    }
                }
            }
        }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        if (animate) BreathingAura(auraBrush) else Box(Modifier.fillMaxSize().background(auraBrush, CircleShape))
        Image(
            painter = painterResource(R.drawable.ic_title_logo_letters),
            contentDescription = "BioKey",
            modifier = Modifier.fillMaxHeight(),
            contentScale = ContentScale.Fit,
            colorFilter = ColorFilter.tint(primary)
        )
        Spacer(
            Modifier
                .fillMaxSize()
                .graphicsLayer()
                .drawBehind {
                    val p = if (slither) phase.floatValue else null
                    vines.setTint(tertiary.toArgb())
                    drawIntoCanvas { vines.draw(it.nativeCanvas, size.width, size.height, p) }
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
