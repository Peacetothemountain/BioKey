package com.android.BioKey.feature.main

import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.android.BioKey.R
import com.android.BioKey.ui.theme.animationsEnabled

/**
 * The two-layer BioKey wordmark (letters in primary, tentacles in tertiary) over its breathing
 * aura. The aura is read only inside graphicsLayer, so each frame is a layer property update:
 * no recomposition, no re-layout, no re-record of the logo.
 */
@Composable
fun BioKeyLogo(modifier: Modifier = Modifier.size(240.dp, 80.dp)) {
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val auraBrush = remember(primary, tertiary) {
        Brush.radialGradient(listOf(primary.copy(alpha = 0.22f), tertiary.copy(alpha = 0.09f), Color.Transparent))
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        if (animationsEnabled()) BreathingAura(auraBrush) else Box(Modifier.fillMaxSize().background(auraBrush, CircleShape))
        Image(
            painter = painterResource(R.drawable.ic_title_logo_letters),
            contentDescription = "BioKey",
            modifier = Modifier.fillMaxHeight(),
            contentScale = ContentScale.Fit,
            colorFilter = ColorFilter.tint(primary, BlendMode.Modulate)
        )
        Image(
            painter = painterResource(R.drawable.ic_title_logo_tentacles),
            contentDescription = null,
            modifier = Modifier.fillMaxHeight(),
            contentScale = ContentScale.Fit,
            colorFilter = ColorFilter.tint(tertiary, BlendMode.Modulate)
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
