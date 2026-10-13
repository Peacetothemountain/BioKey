package com.android.BioKey.ui.theme

import android.content.Context
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
        val BentoCardExtraLarge: RoundedCornerShape = RoundedCornerShape(28.dp)
        val BentoCardLarge: RoundedCornerShape = RoundedCornerShape(24.dp)
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
     * Fires a Dual-Axis LRA haptic pulse for [style] by delegating to [BioKeyHaptics.dispatch].
     * Honors system "Touch feedback" setting and no-ops when disabled or unavailable.
     */
    fun triggerHaptic(context: Context, style: HapticFeedbackStyle) {
        val hapticType = when (style) {
            HapticFeedbackStyle.TICK -> ExpressiveHapticType.Tick
            HapticFeedbackStyle.CLICK_SUBTLE -> ExpressiveHapticType.Click
            HapticFeedbackStyle.CONFIRM_SUCCESS -> ExpressiveHapticType.HeavyClick
            HapticFeedbackStyle.REJECT_ERROR -> ExpressiveHapticType.ErrorRumble
            HapticFeedbackStyle.TOGGLE_ON -> ExpressiveHapticType.HeavyClick
            HapticFeedbackStyle.TOGGLE_OFF -> ExpressiveHapticType.Tick
            HapticFeedbackStyle.HEAVY_SNAP -> ExpressiveHapticType.HeavyClick
        }
        BioKeyHaptics.dispatch(context, hapticType)
    }
}
