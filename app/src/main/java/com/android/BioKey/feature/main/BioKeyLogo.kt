package com.android.BioKey.feature.main

import android.os.SystemClock
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.util.lerp
import com.android.BioKey.R
import com.android.BioKey.ui.brand.LogoLayer
import com.android.BioKey.ui.brand.VineSlither
import com.android.BioKey.ui.theme.animationsEnabled

/** One breath of the aura, in and out. */
private const val BREATH_MS = 5_600L
/** Not moved yet: the vines and aura draw plain, unwarped and unscaled. */
private const val STILL = -1f

/**
 * How far into its breath the aura is at [uptimeMs], 0 (smallest) to 1 (fullest): eased in and
 * out, on the shared uptime clock.
 */
internal fun breathAt(uptimeMs: Long): Float {
    val t = (uptimeMs % BREATH_MS) / BREATH_MS.toFloat()
    return EaseInOutCubic.transform(if (t < 0.5f) t * 2f else 2f - t * 2f)
}

/**
 * The two-layer BioKey wordmark over its breathing aura. Both layers are white shapes with
 * two-tone detail in their alpha, filled with the theme: letters in primary, the
 * fingerprint-ridge vines in tertiary, so the logo follows the wallpaper palette.
 *
 * The vines slither like snakes ([VineSlither]); the letters stay still. One frame loop moves
 * both the wave and the aura, and both are read only while drawing, so each frame redraws the
 * logo's own layers: no recomposition, no re-layout. Capped at 60 fps, paused off screen; still
 * when [animated] is false (lock screens, or hidden behind another page) or with "Remove
 * animations", holding the frame it was on.
 */
@Composable
fun BioKeyLogo(modifier: Modifier = Modifier, animated: Boolean = true) {
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val auraBrush = remember(primary, tertiary) {
        Brush.radialGradient(listOf(primary.copy(alpha = 0.22f), tertiary.copy(alpha = 0.09f), Color.Transparent))
    }
    val resources = LocalResources.current
    val letters = remember(resources) { LogoLayer(resources, R.drawable.ic_title_logo_letters) }
    val vines = remember(resources) { VineSlither(resources) }
    val animate = animated && animationsEnabled()
    val slither = animate && VineSlither.canAnimate
    // A logo that comes on screen moving starts where the shared clock is, rather than jumping
    // there from the plain disc on its second frame.
    val phase = remember { mutableFloatStateOf(if (slither) VineSlither.phaseAt(SystemClock.uptimeMillis()) else STILL) }
    val breath = remember { mutableFloatStateOf(if (animate) breathAt(SystemClock.uptimeMillis()) else STILL) }
    LaunchedEffect(animate) {
        if (!animate) return@LaunchedEffect
        var last = 0L
        while (true) {
            withInfiniteAnimationFrameMillis { ms ->
                if (ms - last >= 16) {
                    last = ms
                    val now = SystemClock.uptimeMillis()
                    if (slither) phase.floatValue = VineSlither.phaseAt(now)
                    breath.floatValue = breathAt(now)
                }
            }
        }
    }
    val name = stringResource(R.string.brand_name)
    Box(modifier, contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val b = breath.floatValue
                    if (b > STILL) {
                        scaleX = lerp(0.94f, 1.08f, b)
                        scaleY = scaleX
                        alpha = lerp(0.75f, 1f, b)
                    }
                }
                .background(auraBrush, CircleShape)
        )
        Spacer(
            Modifier
                .fillMaxSize()
                .semantics {
                    contentDescription = name
                    role = Role.Image
                }
                .drawBehind {
                    letters.setTint(primary.toArgb())
                    drawIntoCanvas { letters.draw(it.nativeCanvas, size.width, size.height) }
                }
        )
        Spacer(
            Modifier
                .fillMaxSize()
                .graphicsLayer()
                .drawBehind {
                    val p = phase.floatValue.takeIf { it > STILL }
                    vines.setTint(tertiary.toArgb())
                    drawIntoCanvas { vines.draw(it.nativeCanvas, size.width, size.height, p) }
                }
        )
    }
}
