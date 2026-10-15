package com.android.BioKey.feature.main

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.android.BioKey.R
import com.android.BioKey.ui.theme.animationsEnabled

/**
 * The two-layer BioKey wordmark over its breathing aura. Both layers are white shapes with
 * two-tone detail in their alpha, filled with the theme: letters in primary, the vine in
 * tertiary, so the logo follows the wallpaper palette.
 *
 * The vine slithers: a wave travels along it while the letters stay still, with a glint gliding
 * over it like light on scales (Android 13+, a GPU shader; earlier versions show it still).
 * Every animated value is read only inside graphicsLayer, so each frame is a layer property
 * update: no recomposition, no re-layout, no re-record of the logo.
 */
@Composable
fun BioKeyLogo(modifier: Modifier = Modifier.size(240.dp, 80.dp)) {
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val auraBrush = remember(primary, tertiary) {
        Brush.radialGradient(listOf(primary.copy(alpha = 0.22f), tertiary.copy(alpha = 0.09f), Color.Transparent))
    }
    val animate = animationsEnabled()
    Box(modifier, contentAlignment = Alignment.Center) {
        if (animate) BreathingAura(auraBrush) else Box(Modifier.fillMaxSize().background(auraBrush, CircleShape))
        Image(
            painter = painterResource(R.drawable.ic_title_logo_letters),
            contentDescription = "BioKey",
            modifier = Modifier.fillMaxHeight(),
            contentScale = ContentScale.Fit,
            colorFilter = ColorFilter.tint(primary)
        )
        Image(
            painter = painterResource(R.drawable.ic_title_logo_tentacles),
            contentDescription = null,
            modifier = if (animate && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Modifier.fillMaxHeight().slither(glint = lerp(tertiary, Color.White, 0.55f))
            } else {
                Modifier.fillMaxHeight()
            },
            contentScale = ContentScale.Fit,
            colorFilter = ColorFilter.tint(tertiary)
        )
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun Modifier.slither(glint: Color): Modifier {
    val shader = remember { RuntimeShader(SLITHER_AGSL) }
    val seconds = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withInfiniteAnimationFrameMillis { ms ->
                // 60 fps is plenty for a slow wave; the clock wraps on the shader's common period.
                if (ms - last >= 16) {
                    last = ms
                    seconds.floatValue = (ms % SLITHER_PERIOD_MS) / 1000f
                }
            }
        }
    }
    val glintArgb = glint.toArgb()
    return graphicsLayer {
        renderEffect = slitherEffect(shader, size, seconds.floatValue, glintArgb)
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun slitherEffect(shader: RuntimeShader, size: Size, seconds: Float, glint: Int) =
    shader.run {
        setFloatUniform("size", size.width, size.height)
        setFloatUniform("time", seconds)
        setColorUniform("glint", glint)
        RenderEffect.createRuntimeShaderEffect(this, "content").asComposeRenderEffect()
    }

/** 16 s holds a whole number of both the wave (3.2 s) and the glint (4 s), so the wrap is seamless. */
private const val SLITHER_PERIOD_MS = 16_000L

private const val SLITHER_AGSL = """
uniform shader content;
uniform float2 size;
uniform float time;
layout(color) uniform half4 glint;

half4 main(float2 p) {
    float2 uv = p / size;
    // Still where the vine meets the edges, free to move in between.
    float envelope = smoothstep(0.0, 0.14, uv.x) * smoothstep(1.0, 0.86, uv.x);
    // A wave travelling left to right: the body rises and falls, and stretches a little along
    // its length, like a snake pushing itself forward.
    float phase = 6.2831853 * (uv.x * 1.6 - time / 3.2);
    float2 offset = float2(0.004 * size.x * cos(phase), 0.03 * size.y * sin(phase)) * envelope;
    half4 c = content.eval(p - offset);
    // A glint sliding along the body, off-screen for part of each pass.
    float head = fract(time / 4.0) * 1.5 - 0.25;
    float d = (uv.x - head) / 0.07;
    float band = exp(-d * d) * 0.55;
    c.rgb = mix(c.rgb, glint.rgb * c.a, half(band));
    return c;
}
"""

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
