package com.android.BioKey.ui.theme

import android.content.Context

object ExpressiveTokens {
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
