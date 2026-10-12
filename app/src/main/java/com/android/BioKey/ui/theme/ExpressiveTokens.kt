package com.android.BioKey.ui.theme

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Material 3 Expressive Design Tokens.
 *
 * Encompasses:
 * - Spatial Physics Springs (low bouncy, emphasized, smooth, snappy)
 * - Standard Material 3 Rounded Corner Shapes
 * - 8-Tier Surface Tonal Container Stack (zero shadow OLED design)
 * - 1dp Subtle Alpha Micro-Borders
 * - Tactile Multi-Tier Tactile Haptics
 * - Dynamic Biometric Holographic Halo Integrations
 */
object ExpressiveTokens {

    // -------------------------------------------------------------------------------------
    // Physics-based Spatial Springs
    // -------------------------------------------------------------------------------------
    object Springs {
        fun <T> fast(): SpringSpec<T> = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow
        )

        fun <T> bouncy(): SpringSpec<T> = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        )

        fun <T> emphasized(): SpringSpec<T> = spring(
            dampingRatio = 0.72f,
            stiffness = 260f
        )

        fun <T> smooth(): SpringSpec<T> = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        )

        fun <T> snappy(): SpringSpec<T> = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        )

        val FastSpatial: SpringSpec<Float> get() = fast()
        val BouncySpatial: SpringSpec<Float> get() = bouncy()
        val EmphasizedSpatial: SpringSpec<Float> get() = emphasized()
        val SmoothSpatial: SpringSpec<Float> get() = smooth()
        val SnappySpatial: SpringSpec<Float> get() = snappy()
    }

    // -------------------------------------------------------------------------------------
    // Material 3 Shapes & Container Roles
    // -------------------------------------------------------------------------------------
    object Shapes {
        val ExtraSmall: RoundedCornerShape = M3XShapes.ExtraSmall
        val Small: RoundedCornerShape = M3XShapes.Small
        val Medium: RoundedCornerShape = M3XShapes.Medium
        val Large: RoundedCornerShape = M3XShapes.Large
        val ExtraLarge: RoundedCornerShape = M3XShapes.ExtraLarge
        val FullPill = CircleShape

        // Standard Material 3 Container Shapes
        val BentoCardLarge: RoundedCornerShape = RoundedCornerShape(20.dp)
        val BentoCardMedium: RoundedCornerShape = RoundedCornerShape(16.dp)
        val BentoCardSmall: RoundedCornerShape = RoundedCornerShape(12.dp)
        val Pill = CircleShape

        // Grouped List Item Shapes
        val GroupTop: RoundedCornerShape = RoundedCornerShape(
            topStart = 20.dp,
            topEnd = 20.dp,
            bottomStart = 4.dp,
            bottomEnd = 4.dp
        )
        val GroupMiddle: RoundedCornerShape = RoundedCornerShape(4.dp)
        val GroupBottom: RoundedCornerShape = RoundedCornerShape(
            topStart = 4.dp,
            topEnd = 4.dp,
            bottomStart = 20.dp,
            bottomEnd = 20.dp
        )
        val GroupSingle: RoundedCornerShape = RoundedCornerShape(20.dp)

        fun forPosition(index: Int, total: Int): CornerBasedShape {
            return when {
                total <= 1 -> GroupSingle
                index == 0 -> GroupTop
                index == total - 1 -> GroupBottom
                else -> GroupMiddle
            }
        }
    }

    // -------------------------------------------------------------------------------------
    // 8-Tier Tonal Container Roles
    // -------------------------------------------------------------------------------------
    object Containers {
        val Lowest = SurfaceTonalTier.SurfaceContainerLowest
        val Base = SurfaceTonalTier.Surface
        val Dim = SurfaceTonalTier.SurfaceDim
        val Bright = SurfaceTonalTier.SurfaceBright
        val Low = SurfaceTonalTier.SurfaceContainerLow
        val Standard = SurfaceTonalTier.SurfaceContainer
        val High = SurfaceTonalTier.SurfaceContainerHigh
        val Highest = SurfaceTonalTier.SurfaceContainerHighest
    }

    // -------------------------------------------------------------------------------------
    // OLED Alpha Micro-Borders
    // -------------------------------------------------------------------------------------
    object Borders {
        val HairlineWidth: Dp = 1.dp

        @Composable
        fun subtleBorder(
            customAlpha: Float? = null,
            width: Dp = HairlineWidth
        ): BorderStroke = M3XBorderDefaults.subtleBorderStroke(customAlpha, width)
    }

    // -------------------------------------------------------------------------------------
    // Multi-Tier Tactile Haptics
    // -------------------------------------------------------------------------------------
    enum class HapticFeedbackStyle {
        TICK,
        CLICK_SUBTLE,
        CONFIRM_SUCCESS,
        REJECT_ERROR,
        TOGGLE_ON,
        TOGGLE_OFF,
        HEAVY_SNAP
    }

    fun triggerHaptic(context: Context, style: HapticFeedbackStyle) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }

            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val effectId = when (style) {
                        HapticFeedbackStyle.TICK -> VibrationEffect.EFFECT_TICK
                        HapticFeedbackStyle.CLICK_SUBTLE -> VibrationEffect.EFFECT_CLICK
                        HapticFeedbackStyle.CONFIRM_SUCCESS -> VibrationEffect.EFFECT_HEAVY_CLICK
                        HapticFeedbackStyle.REJECT_ERROR -> VibrationEffect.EFFECT_DOUBLE_CLICK
                        HapticFeedbackStyle.TOGGLE_ON -> VibrationEffect.EFFECT_HEAVY_CLICK
                        HapticFeedbackStyle.TOGGLE_OFF -> VibrationEffect.EFFECT_TICK
                        HapticFeedbackStyle.HEAVY_SNAP -> VibrationEffect.EFFECT_HEAVY_CLICK
                    }
                    vibrator.vibrate(VibrationEffect.createPredefined(effectId))
                    return
                }
            }
        } catch (_: Exception) {
            // Fallback gracefully
        }
    }
}

// Note: Modifier.springPress and Modifier.expressiveClickable are defined with complete
// continuous damped harmonic oscillator physics (0.965f squash, 1.02f release pop,
// settling to 1.0f) and Pixel 11 Pro XL dual-axis LRA haptics in ExpressiveMotionHaptics.kt.

