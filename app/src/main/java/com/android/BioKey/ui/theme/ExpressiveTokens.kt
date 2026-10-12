package com.android.BioKey.ui.theme

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * Material 3 Expressive Design Tokens.
 *
 * Encompasses:
 * - Physics-based spatial springs (fast, smooth, bouncy)
 * - Container shapes for bento cards and pills
 * - Tactile haptic pulses via [triggerHaptic]
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

        fun <T> smooth(): SpringSpec<T> = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        )

        val BouncySpatial: SpringSpec<Float> get() = bouncy()
    }

    // -------------------------------------------------------------------------------------
    // Container Shapes
    // -------------------------------------------------------------------------------------
    object Shapes {
        val BentoCardLarge: RoundedCornerShape = RoundedCornerShape(20.dp)
        val BentoCardMedium: RoundedCornerShape = RoundedCornerShape(16.dp)
        val BentoCardSmall: RoundedCornerShape = RoundedCornerShape(12.dp)
        val Pill = CircleShape
    }

    // -------------------------------------------------------------------------------------
    // Tactile Haptics
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

    /**
     * Fires a predefined haptic pulse for [style] on the default vibrator.
     * Honors system "Touch feedback" setting and no-ops when disabled or unavailable.
     */
    fun triggerHaptic(context: Context, style: HapticFeedbackStyle) {
        val hapticsEnabled = android.provider.Settings.System.getInt(
            context.contentResolver,
            android.provider.Settings.System.HAPTIC_FEEDBACK_ENABLED,
            1
        ) != 0
        if (!hapticsEnabled) return

        val vibrator = context.getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return
        if (!vibrator.hasVibrator()) return

        val effectId = when (style) {
            HapticFeedbackStyle.TICK -> VibrationEffect.EFFECT_TICK
            HapticFeedbackStyle.CLICK_SUBTLE -> VibrationEffect.EFFECT_CLICK
            HapticFeedbackStyle.CONFIRM_SUCCESS -> VibrationEffect.EFFECT_HEAVY_CLICK
            HapticFeedbackStyle.REJECT_ERROR -> VibrationEffect.EFFECT_DOUBLE_CLICK
            HapticFeedbackStyle.TOGGLE_ON -> VibrationEffect.EFFECT_HEAVY_CLICK
            HapticFeedbackStyle.TOGGLE_OFF -> VibrationEffect.EFFECT_TICK
            HapticFeedbackStyle.HEAVY_SNAP -> VibrationEffect.EFFECT_HEAVY_CLICK
        }
        val effect = VibrationEffect.createPredefined(effectId)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
            } else {
                vibrator.vibrate(effect)
            }
        } catch (_: SecurityException) {
            // VIBRATE permission missing or revoked: haptics are best-effort.
        }
    }
}
