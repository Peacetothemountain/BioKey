package com.android.BioKey.ui.brand

import android.content.res.Resources
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
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.util.lerp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.android.BioKey.R
import com.android.BioKey.ui.brand.LogoLayer
import com.android.BioKey.ui.brand.VineSlither
import com.android.BioKey.ui.theme.animationsEnabled
import kotlin.math.roundToInt

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
 * logo's own layers: no recomposition, no re-layout. Capped at 60 fps, paused whenever the
 * screen isn't resumed; still when [animated] is false (lock screens, or hidden behind another
 * page) or with "Remove animations", holding the frame it was on.
 *
 * Never holds a frame back for its artwork: copies sized to the logo are made in the background
 * ([LogoArt]), another size's copy standing in until they are ready. [prepareBioKeyLogo] starts
 * them ahead of a screen that shows the logo at once.
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
    // Counts copies made in the background as they become ready: read while drawing, so each one
    // redraws the layers, which take it in.
    val copies = remember { mutableIntStateOf(0) }
    val onCopyReady = remember<() -> Unit> { { copies.intValue++ } }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, animate) {
        if (!animate) return@LaunchedEffect
        // The sway field is decoded in the background, ahead of the first moving frame that needs it.
        if (slither) LogoArt.prepareField(resources)
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
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
                    copies.intValue
                    letters.setTint(primary.toArgb())
                    drawIntoCanvas { letters.draw(it.nativeCanvas, size.width, size.height, onCopyReady, standIn = true) }
                }
        )
        Spacer(
            Modifier
                .fillMaxSize()
                .graphicsLayer()
                .drawBehind {
                    copies.intValue
                    val p = phase.floatValue.takeIf { it > STILL }
                    vines.setTint(tertiary.toArgb())
                    drawIntoCanvas { vines.draw(it.nativeCanvas, size.width, size.height, p, onCopyReady, standIn = true) }
                }
        )
    }
}

/**
 * Starts making, in the background, the copies a [BioKeyLogo] laid out at [width] x [height]
 * draws: for a screen that shows one on its first frame (BioKey's own lock), so the logo is
 * there from the start. Main thread only.
 */
fun prepareBioKeyLogo(resources: Resources, width: Dp, height: Dp) {
    // Laid out in whole pixels, as Compose rounds a size in dp.
    val density = resources.displayMetrics.density
    val w = (width.value * density).roundToInt().toFloat()
    val h = (height.value * density).roundToInt().toFloat()
    for (id in intArrayOf(R.drawable.ic_title_logo_letters, R.drawable.ic_title_logo_tentacles)) {
        val art = LogoArt.artSize(resources, id) ?: continue
        val fit = LogoArt.fit(art[0], art[1], w, h)
        LogoArt.prepare(resources, id, fit.pixelWidth, fit.pixelHeight)
    }
}
