package com.android.BioKey.ui.theme

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.annotation.RequiresApi
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * =========================================================================================
 * MATERIAL 3 EXPRESSIVE MOTION PHYSICS & TACTILE HAPTICS
 * =========================================================================================
 *
 * System Architecture:
 * 1. Continuous Damped Harmonic Oscillator Engine:
 *    Governed by: m * x''(t) + c * x'(t) + k * x(t) = 0
 *    - Normalized mass: m = 1.0f
 *    - Damping ratio: zeta = c / (2 * sqrt(m * k))
 *    - Natural angular frequency: omega_n = sqrt(k / m)
 *    - Underdamped oscillation: omega_d = omega_n * sqrt(1 - zeta^2)
 *    - Max overshoot ratio: M_p = exp(-pi * zeta / sqrt(1 - zeta^2))
 *
 * 2. Touch Kinematics:
 *    - Touch-down squash to 0.965f via critically damped spring (zeta=1.0f, k=800f).
 *    - Touch-release pop to 1.02f overshoot via medium bouncy spring (zeta=0.5f, k=600f),
 *      settling cleanly to 1.0f via (zeta=0.5f, k=400f).
 *    - Touch-cancel return to 1.0f without overshoot via spatial exit (zeta=1.0f, k=400f).
 *
 * 3. Predictive Back Transitions:
 *    - Quadratic resistance curve: f(p) = 1 - (1 - p)^2
 *    - Continuous corner radius morphing: 0dp -> 32dp
 *    - Scale attenuation: 1.0 -> 0.88
 *    - Spatial parallax displacement (+/- 36dp)
 *    - Dual threshold ratchet haptics at 30% and 65% drag progress.
 *
 * 4. Dual-Axis Linear Resonant Actuator (LRA) Haptic Choreography Matrix:
 *    - Pixel 11 Pro XL (67080DLDV0028L) reference actuator optimization.
 *    - API 31-37 hardware primitives: PRIMITIVE_CLICK, PRIMITIVE_THUD, PRIMITIVE_SPIN,
 *      PRIMITIVE_QUICK_RISE, PRIMITIVE_SLOW_RISE, PRIMITIVE_QUICK_FALL, PRIMITIVE_TICK,
 *      PRIMITIVE_LOW_TICK.
 *    - Quad-tier fallback: Composition -> Predefined -> Waveform -> View feedback.
 */
object ExpressiveMotionTokens {

    // =====================================================================================
    // 1. DAMPING RATIO TOKENS (zeta)
    // =====================================================================================

    /**
     * DampingRatio = 1.0f (Critical Damping)
     * Peak Overshoot: 0.0%
     * Analytical solution: x(t) = (C1 + C2 * t) * exp(-omega_n * t)
     * MANDATED USE:
     * - System exits, screen dismissals, swipe deletion.
     * - Touch-down compression: finger contact must cushion firmly without bouncing.
     */
    const val DampingRatioNoBouncy: Float = Spring.DampingRatioNoBouncy // 1.0f

    /**
     * DampingRatio = 0.75f (Subtle / Low Bounciness)
     * Peak Overshoot: ~2.83% (exp(-pi * 0.75 / sqrt(1 - 0.75^2)) = 0.0283)
     * MANDATED USE:
     * - Screen enter transitions, container expansions, dialog/sheet pop-ins.
     * - Predictive back gesture cancellation settling.
     */
    const val DampingRatioLowBouncy: Float = Spring.DampingRatioLowBouncy // 0.75f

    /**
     * DampingRatio = 0.5f (Medium / Playful Bounciness)
     * Peak Overshoot: ~16.30% (exp(-pi * 0.5 / sqrt(1 - 0.25)) = 0.1630)
     * MANDATED USE:
     * - Micro-interactions, scale-on-release pop (1.02f overshoot), switch thumbs, FABs.
     */
    const val DampingRatioMediumBouncy: Float = Spring.DampingRatioMediumBouncy // 0.5f

    /**
     * DampingRatio = 0.2f (High / Exaggerated Bounciness)
     * Peak Overshoot: ~52.7%
     * MANDATED USE:
     * - Celebratory reward feedback, pull-to-refresh snap-back.
     */
    const val DampingRatioHighBouncy: Float = Spring.DampingRatioHighBouncy // 0.2f


    // =====================================================================================
    // 2. STIFFNESS TOKENS (k)
    // =====================================================================================

    /**
     * Stiffness = 10,000f (High Stiffness / Ultra-Responsive)
     * Natural frequency: omega_n = 100 rad/s. Settling: ~40-60ms.
     * MANDATED USE:
     * - Direct gesture tracking, finger drag sync, slider thumb tracking.
     */
    const val StiffnessHigh: Float = Spring.StiffnessHigh // 10000.0f

    /**
     * Stiffness = 1,500f (Medium Stiffness / Snappy)
     * Natural frequency: omega_n = 38.73 rad/s. Settling: ~120-150ms.
     * MANDATED USE:
     * - Quick state toggles, checkbox ticks, icon glyph morphs.
     */
    const val StiffnessMedium: Float = Spring.StiffnessMedium // 1500.0f

    /**
     * Stiffness = 400f (Medium-Low Stiffness / Expressive Spatial Standard)
     * Natural frequency: omega_n = 20 rad/s. Settling: ~260-350ms.
     * MANDATED USE:
     * - Container morphing, card expansions, scale release settling, modal surfaces.
     */
    const val StiffnessMediumLow: Float = Spring.StiffnessMediumLow // 400.0f

    /**
     * Stiffness = 200f (Low Stiffness / Deliberate & Fluid)
     * Natural frequency: omega_n = 14.14 rad/s. Settling: ~450-600ms.
     * MANDATED USE:
     * - Fullscreen page transitions, shared elements, predictive back settling.
     */
    const val StiffnessLow: Float = Spring.StiffnessLow // 200.0f

    /**
     * Stiffness = 50f (Very Low Stiffness / Ambient)
     * Settling: ~900-1400ms.
     * MANDATED USE:
     * - Ambient security aura breathing, background glow pulses.
     */
    const val StiffnessVeryLow: Float = Spring.StiffnessVeryLow // 50.0f


    // =====================================================================================
    // 3. SPECIALIZED SPRING PRESETS
    // =====================================================================================

    /**
     * Spatial Enter: Subtle bounce (0.75f) with MediumLow stiffness (400f).
     */
    fun <T> spatialEnter(): SpringSpec<T> = spring(
        dampingRatio = DampingRatioLowBouncy,
        stiffness = StiffnessMediumLow
    )

    /**
     * Spatial Exit: Critically damped (1.0f) with MediumLow stiffness (400f).
     */
    fun <T> spatialExit(): SpringSpec<T> = spring(
        dampingRatio = DampingRatioNoBouncy,
        stiffness = StiffnessMediumLow
    )

    /**
     * Press Down Compression: Rapid, critically damped compression under finger (zeta=1.0f, k=800f).
     */
    fun <T> pressDown(): SpringSpec<T> = spring(
        dampingRatio = DampingRatioNoBouncy,
        stiffness = 800f
    )

    /**
     * Press Release Settling: Playful bounce (zeta=0.5f, k=400f) settling to 1.0f.
     */
    fun <T> pressRelease(): SpringSpec<T> = spring(
        dampingRatio = DampingRatioMediumBouncy,
        stiffness = StiffnessMediumLow
    )

    /**
     * Container Morphing: Spring spec for width/height/offset transforms.
     */
    fun <T> containerMorph(): SpringSpec<T> = spring(
        dampingRatio = DampingRatioLowBouncy,
        stiffness = 350f
    )

    /**
     * Predictive Back Gesture Cancel Settling (zeta=0.75f, k=320f).
     */
    fun <T> predictiveBackCancel(): SpringSpec<T> = spring(
        dampingRatio = DampingRatioLowBouncy,
        stiffness = 320f
    )

    /**
     * Predictive Back Commit Out (zeta=1.0f, k=300f).
     */
    fun <T> predictiveBackCommit(): SpringSpec<T> = spring(
        dampingRatio = DampingRatioNoBouncy,
        stiffness = 300f
    )

    /**
     * Corner Radius Morphing Spec (0dp -> 32dp).
     */
    val cornerRadiusMorph: SpringSpec<Dp> = spring(
        dampingRatio = 0.8f,
        stiffness = StiffnessMediumLow
    )

    /**
     * 2D Offset Motion Spec for Dragging & Gestural Tracking.
     */
    val spatialOffset: SpringSpec<Offset> = spring(
        dampingRatio = DampingRatioLowBouncy,
        stiffness = StiffnessMediumLow
    )

    val intOffsetSpatial: SpringSpec<IntOffset> = spring(
        dampingRatio = DampingRatioLowBouncy,
        stiffness = StiffnessMediumLow
    )
}

/**
 * =========================================================================================
 * DROP-IN EXPRESSIVE COMPOSE ENTER / EXIT TRANSITIONS
 * =========================================================================================
 */
object ExpressiveTransitions {

    fun enter(
        initialScale: Float = 0.92f,
        initialOffsetY: Int = 100
    ): EnterTransition {
        return scaleIn(
            animationSpec = ExpressiveMotionTokens.spatialEnter(),
            initialScale = initialScale
        ) + slideInVertically(
            animationSpec = ExpressiveMotionTokens.spatialEnter(),
            initialOffsetY = { initialOffsetY }
        ) + fadeIn(
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = ExpressiveMotionTokens.StiffnessMediumLow
            )
        )
    }

    fun exit(
        targetScale: Float = 0.94f,
        targetOffsetY: Int = 60
    ): ExitTransition {
        return scaleOut(
            animationSpec = ExpressiveMotionTokens.spatialExit(),
            targetScale = targetScale
        ) + slideOutVertically(
            animationSpec = ExpressiveMotionTokens.spatialExit(),
            targetOffsetY = { targetOffsetY }
        ) + fadeOut(
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = ExpressiveMotionTokens.StiffnessMediumLow
            )
        )
    }

    fun containerExpansionEnter(): EnterTransition {
        return scaleIn(
            animationSpec = ExpressiveMotionTokens.containerMorph(),
            initialScale = 0.88f
        ) + fadeIn(
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = ExpressiveMotionTokens.StiffnessMediumLow
            )
        )
    }

    fun containerExpansionExit(): ExitTransition {
        return scaleOut(
            animationSpec = ExpressiveMotionTokens.spatialExit(),
            targetScale = 0.90f
        ) + fadeOut(
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = ExpressiveMotionTokens.StiffnessMedium
            )
        )
    }
}

/**
 * =========================================================================================
 * EXPRESSIVE HAPTIC SPECIFICATION & DISPATCHER (Pixel 11 Pro XL Reference)
 * =========================================================================================
 */
enum class ExpressiveHapticType {
    /** Ultra-light micro tactile pulse for slider notches, list ticks */
    Tick,

    /** Crisp tactile confirmation for buttons, toggles, chips */
    Click,

    /** Heavy high-impact confirmation for biometric unlock, primary actions */
    HeavyClick,

    /** Deep low-frequency bass tick for boundary collision, overscroll */
    LowTick,

    /** Rotary sensation for dial wheel, PIN key tumbler */
    SpinDetent,

    /** Refined dual-stage rejection rumble for intruder alert, lock rejection */
    ErrorRumble,

    /** Affirmative double-pulse confirmation (light tap -> crisp snap) */
    SuccessDoublePulse,

    /** Subtle tactile threshold tick for predictive back detents (30%, 65%) */
    PredictiveBackThreshold,

    /** High-security biometric passkey verified signature */
    BiometricSuccess,

    /** Biometric auth failure or anti-tamper lockdown */
    BiometricFailure
}

/**
 * Unified Haptic Interface for BioKey Expressive UI.
 */
interface ExpressiveHapticFeedback {
    fun perform(type: ExpressiveHapticType)
    fun tick(amplitude: Float = 0.5f)
    fun click(amplitude: Float = 1.0f)
    fun heavyClick(amplitude: Float = 1.0f)
    fun lowTick(amplitude: Float = 0.8f)
    fun spinDetent(amplitude: Float = 0.7f)
    fun errorRumble()
    fun successDoublePulse()
    fun predictiveBackThreshold()
    fun biometricSuccess()
    fun biometricFailure()
}

/**
 * No-Op implementation for Compose Previews & Unit Tests.
 */
object NoOpExpressiveHapticFeedback : ExpressiveHapticFeedback {
    override fun perform(type: ExpressiveHapticType) {}
    override fun tick(amplitude: Float) {}
    override fun click(amplitude: Float) {}
    override fun heavyClick(amplitude: Float) {}
    override fun lowTick(amplitude: Float) {}
    override fun spinDetent(amplitude: Float) {}
    override fun errorRumble() {}
    override fun successDoublePulse() {}
    override fun predictiveBackThreshold() {}
    override fun biometricSuccess() {}
    override fun biometricFailure() {}
}

/**
 * Production Android implementation for Pixel 11 Pro XL (67080DLDV0028L)
 * dual-axis Linear Resonant Actuators. Leverages VibratorManager (API 31+)
 * and VibrationEffect.Composition primitives (API 31-37).
 */
class AndroidExpressiveHapticFeedback(
    private val context: Context,
    private val view: View? = null
) : ExpressiveHapticFeedback {

    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibratorManager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    private val hasVibrator: Boolean get() = vibrator?.hasVibrator() == true

    private val touchAttributes: VibrationAttributes by lazy {
        VibrationAttributes.Builder()
            .setUsage(VibrationAttributes.USAGE_TOUCH)
            .build()
    }

    override fun perform(type: ExpressiveHapticType) {
        when (type) {
            ExpressiveHapticType.Tick -> tick()
            ExpressiveHapticType.Click -> click()
            ExpressiveHapticType.HeavyClick -> heavyClick()
            ExpressiveHapticType.LowTick -> lowTick()
            ExpressiveHapticType.SpinDetent -> spinDetent()
            ExpressiveHapticType.ErrorRumble -> errorRumble()
            ExpressiveHapticType.SuccessDoublePulse -> successDoublePulse()
            ExpressiveHapticType.PredictiveBackThreshold -> predictiveBackThreshold()
            ExpressiveHapticType.BiometricSuccess -> biometricSuccess()
            ExpressiveHapticType.BiometricFailure -> biometricFailure()
        }
    }

    override fun tick(amplitude: Float) {
        val clampedAmp = amplitude.coerceIn(0.1f, 1.0f)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && vibrator != null) {
            if (isPrimitiveSupported(VibrationEffect.Composition.PRIMITIVE_TICK)) {
                val effect = VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, clampedAmp)
                    .compose()
                vibrateEffect(effect)
                return
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && vibrator != null) {
            vibrateEffect(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
        } else {
            view?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    override fun click(amplitude: Float) {
        val clampedAmp = amplitude.coerceIn(0.1f, 1.0f)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && vibrator != null) {
            if (isPrimitiveSupported(VibrationEffect.Composition.PRIMITIVE_CLICK)) {
                val effect = VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, clampedAmp)
                    .compose()
                vibrateEffect(effect)
                return
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && vibrator != null) {
            vibrateEffect(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
        } else {
            view?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    override fun heavyClick(amplitude: Float) {
        val clampedAmp = amplitude.coerceIn(0.1f, 1.0f)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && vibrator != null) {
            val composition = VibrationEffect.startComposition()
            var added = false

            if (isPrimitiveSupported(VibrationEffect.Composition.PRIMITIVE_CLICK)) {
                composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, clampedAmp)
                added = true
            }
            if (isPrimitiveSupported(VibrationEffect.Composition.PRIMITIVE_THUD)) {
                composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, clampedAmp * 0.85f, 10)
                added = true
            }

            if (added) {
                vibrateEffect(composition.compose())
                return
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && vibrator != null) {
            vibrateEffect(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
        } else {
            view?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    override fun lowTick(amplitude: Float) {
        val clampedAmp = amplitude.coerceIn(0.1f, 1.0f)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && vibrator != null) {
            if (isPrimitiveSupported(VibrationEffect.Composition.PRIMITIVE_LOW_TICK)) {
                val effect = VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, clampedAmp)
                    .compose()
                vibrateEffect(effect)
                return
            }
        }
        tick(clampedAmp * 0.7f)
    }

    override fun spinDetent(amplitude: Float) {
        val clampedAmp = amplitude.coerceIn(0.1f, 1.0f)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && vibrator != null) {
            if (isPrimitiveSupported(VibrationEffect.Composition.PRIMITIVE_SPIN)) {
                val effect = VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_SPIN, clampedAmp)
                    .compose()
                vibrateEffect(effect)
                return
            }
        }
        tick(clampedAmp * 0.8f)
    }

    override fun errorRumble() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && vibrator != null) {
            val comp = VibrationEffect.startComposition()
            var canCompose = false

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                isPrimitiveSupported(VibrationEffect.Composition.PRIMITIVE_LOW_TICK)
            ) {
                comp.addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.95f)
                comp.addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 1.0f, 45)
                if (isPrimitiveSupported(VibrationEffect.Composition.PRIMITIVE_THUD)) {
                    comp.addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 0.65f, 25)
                }
                canCompose = true
            } else if (isPrimitiveSupported(VibrationEffect.Composition.PRIMITIVE_CLICK)) {
                comp.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.9f)
                comp.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1.0f, 60)
                canCompose = true
            }

            if (canCompose) {
                vibrateEffect(comp.compose())
                return
            }
        }

        // Tier 3 Fallback: Waveform double pulse
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && vibrator != null) {
            val timings = longArrayOf(0, 18, 50, 24)
            val amplitudes = intArrayOf(0, 180, 0, 255)
            vibrateEffect(VibrationEffect.createWaveform(timings, amplitudes, -1))
        } else {
            view?.performHapticFeedback(HapticFeedbackConstants.REJECT)
        }
    }

    override fun successDoublePulse() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && vibrator != null) {
            if (isPrimitiveSupported(VibrationEffect.Composition.PRIMITIVE_CLICK)) {
                val comp = VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.55f)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1.0f, 65)
                vibrateEffect(comp.compose())
                return
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && vibrator != null) {
            val timings = longArrayOf(0, 12, 60, 20)
            val amplitudes = intArrayOf(0, 120, 0, 255)
            vibrateEffect(VibrationEffect.createWaveform(timings, amplitudes, -1))
        } else {
            view?.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        }
    }

    override fun predictiveBackThreshold() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            isPrimitiveSupported(VibrationEffect.Composition.PRIMITIVE_LOW_TICK)
        ) {
            val effect = VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.6f)
                .compose()
            vibrateEffect(effect)
        } else {
            tick(0.4f)
        }
    }

    override fun biometricSuccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && vibrator != null) {
            val comp = VibrationEffect.startComposition()
            var added = false
            if (isPrimitiveSupported(VibrationEffect.Composition.PRIMITIVE_CLICK)) {
                comp.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.6f)
                comp.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1.0f, 50)
                added = true
            }
            if (isPrimitiveSupported(VibrationEffect.Composition.PRIMITIVE_SPIN)) {
                comp.addPrimitive(VibrationEffect.Composition.PRIMITIVE_SPIN, 0.5f, 20)
                added = true
            }
            if (added) {
                vibrateEffect(comp.compose())
                return
            }
        }
        successDoublePulse()
    }

    override fun biometricFailure() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && vibrator != null) {
            val comp = VibrationEffect.startComposition()
            var added = false
            if (isPrimitiveSupported(VibrationEffect.Composition.PRIMITIVE_THUD)) {
                comp.addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 1.0f)
                added = true
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                isPrimitiveSupported(VibrationEffect.Composition.PRIMITIVE_LOW_TICK)
            ) {
                comp.addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 1.0f, 50)
                added = true
            }
            if (isPrimitiveSupported(VibrationEffect.Composition.PRIMITIVE_THUD)) {
                comp.addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 0.8f, 30)
                added = true
            }
            if (added) {
                vibrateEffect(comp.compose())
                return
            }
        }
        errorRumble()
    }

    private fun vibrateEffect(effect: VibrationEffect) {
        if (!hasVibrator) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                vibrator?.vibrate(effect, touchAttributes)
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(effect)
            }
        } catch (_: SecurityException) {
            // VIBRATE permission gracefully handled
        }
    }

    private fun isPrimitiveSupported(primitiveId: Int): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && vibrator != null) {
            return try {
                vibrator?.areAllPrimitivesSupported(primitiveId) == true
            } catch (_: Exception) {
                false
            }
        }
        return false
    }
}

val LocalExpressiveHaptics: ProvidableCompositionLocal<ExpressiveHapticFeedback> =
    staticCompositionLocalOf { NoOpExpressiveHapticFeedback }

@Composable
fun ProvideExpressiveHaptics(
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    val haptics = remember(context, view) {
        AndroidExpressiveHapticFeedback(context = context.applicationContext, view = view)
    }

    CompositionLocalProvider(LocalExpressiveHaptics provides haptics) {
        content()
    }
}

/**
 * Direct BioKey Haptic Dispatcher for static/non-composable contexts
 * (e.g. BroadcastReceivers, SecurityEngine, BiometricPrompt Callbacks).
 */
object BioKeyHaptics {
    fun dispatch(context: Context, type: ExpressiveHapticType) {
        try {
            val dispatcher = AndroidExpressiveHapticFeedback(context.applicationContext)
            dispatcher.perform(type)
        } catch (_: Exception) {}
    }

    fun onUnlockSuccess(context: Context) = dispatch(context, ExpressiveHapticType.BiometricSuccess)
    fun onIntruderDetected(context: Context) = dispatch(context, ExpressiveHapticType.BiometricFailure)
    fun onPinDigitEntered(context: Context) = dispatch(context, ExpressiveHapticType.Click)
    fun onPinCleared(context: Context) = dispatch(context, ExpressiveHapticType.LowTick)
    fun onShieldStateToggled(context: Context, activated: Boolean) =
        dispatch(context, if (activated) ExpressiveHapticType.HeavyClick else ExpressiveHapticType.Tick)
}


// =========================================================================================
// TACTILE TOUCH & SCALE-ON-PRESS MODIFIERS
// =========================================================================================

/**
 * Core Expressive Scale-On-Press Modifier.
 * Kinematics:
 * - Touch Down: Cushions firmly to [pressedScale] (default 0.965f) via [ExpressiveMotionTokens.pressDown].
 * - Release: Shoots to [releasePopScale] (default 1.02f) with playful bounce, then settles cleanly to 1.0f.
 * - Cancel: Smoothly returns to 1.0f without overshoot via [ExpressiveMotionTokens.spatialExit].
 *
 * Rendered strictly through [Modifier.graphicsLayer] on RenderNode for zero layout/recomposition cost.
 */
@Composable
fun Modifier.expressiveScaleOnPress(
    interactionSource: InteractionSource,
    pressedScale: Float = 0.965f,
    releasePopScale: Float = 1.02f,
    enabled: Boolean = true,
    hapticFeedbackEnabled: Boolean = true,
    customHaptics: ExpressiveHapticFeedback? = null
): Modifier {
    if (!enabled) return this

    val haptics = customHaptics ?: LocalExpressiveHaptics.current
    val scaleAnim = remember { Animatable(1.0f) }

    LaunchedEffect(interactionSource, enabled) {
        var isCurrentlyPressed = false
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> {
                    isCurrentlyPressed = true
                    if (hapticFeedbackEnabled) {
                        haptics.tick(amplitude = 0.5f)
                    }
                    scaleAnim.animateTo(
                        targetValue = pressedScale,
                        animationSpec = ExpressiveMotionTokens.pressDown()
                    )
                }

                is PressInteraction.Release -> {
                    if (isCurrentlyPressed) {
                        isCurrentlyPressed = false
                        if (hapticFeedbackEnabled) {
                            haptics.click(amplitude = 0.9f)
                        }
                        // Chained physical overshoot pop: 0.965f -> 1.02f -> 1.0f
                        scaleAnim.animateTo(
                            targetValue = releasePopScale,
                            animationSpec = spring(
                                dampingRatio = ExpressiveMotionTokens.DampingRatioMediumBouncy,
                                stiffness = 600f
                            )
                        )
                        scaleAnim.animateTo(
                            targetValue = 1.0f,
                            animationSpec = ExpressiveMotionTokens.pressRelease()
                        )
                    }
                }

                is PressInteraction.Cancel -> {
                    if (isCurrentlyPressed) {
                        isCurrentlyPressed = false
                        scaleAnim.animateTo(
                            targetValue = 1.0f,
                            animationSpec = ExpressiveMotionTokens.spatialExit()
                        )
                    }
                }
            }
        }
    }

    return this.graphicsLayer {
        scaleX = scaleAnim.value
        scaleY = scaleAnim.value
        transformOrigin = TransformOrigin.Center
    }
}

/**
 * Drop-in Touch Down Spring Compression Modifier.
 * Fully backward-compatible with BioKey existing screens, now upgraded with 0.965f squash,
 * 1.02f release pop, and synchronized tactile micro-haptics.
 * Operates on GPU RenderNode matrices via graphicsLayer with zero layout passes.
 * Uses pointerInput when interactionSource is null, guaranteeing fluid touch tracking
 * on Buttons, Surfaces, and custom UI components alike.
 */
fun Modifier.springPress(
    pressScale: Float = 0.965f,
    releasePopScale: Float = 1.02f,
    enabled: Boolean = true,
    hapticsEnabled: Boolean = true,
    interactionSource: InteractionSource? = null
): Modifier = composed {
    if (!enabled) return@composed this

    val haptics = LocalExpressiveHaptics.current
    val scaleAnim = remember { Animatable(1.0f) }

    if (interactionSource != null) {
        LaunchedEffect(interactionSource) {
            var isPressed = false
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> {
                        isPressed = true
                        if (hapticsEnabled) haptics.tick(amplitude = 0.5f)
                        scaleAnim.animateTo(
                            targetValue = pressScale,
                            animationSpec = ExpressiveMotionTokens.pressDown()
                        )
                    }
                    is PressInteraction.Release -> {
                        if (isPressed) {
                            isPressed = false
                            if (hapticsEnabled) haptics.click(amplitude = 0.9f)
                            scaleAnim.animateTo(
                                targetValue = releasePopScale,
                                animationSpec = spring(
                                    dampingRatio = ExpressiveMotionTokens.DampingRatioMediumBouncy,
                                    stiffness = 600f
                                )
                            )
                            scaleAnim.animateTo(
                                targetValue = 1.0f,
                                animationSpec = ExpressiveMotionTokens.pressRelease()
                            )
                        }
                    }
                    is PressInteraction.Cancel -> {
                        if (isPressed) {
                            isPressed = false
                            scaleAnim.animateTo(
                                targetValue = 1.0f,
                                animationSpec = ExpressiveMotionTokens.spatialExit()
                            )
                        }
                    }
                }
            }
        }
    } else {
        val gestureScope = rememberCoroutineScope()
        this.pointerInput(enabled) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (hapticsEnabled) haptics.tick(amplitude = 0.5f)
                gestureScope.launch {
                    scaleAnim.animateTo(
                        targetValue = pressScale,
                        animationSpec = ExpressiveMotionTokens.pressDown()
                    )
                }

                val upOrCancel = waitForUpOrCancellation()
                if (upOrCancel != null) {
                    if (hapticsEnabled) haptics.click(amplitude = 0.9f)
                    gestureScope.launch {
                        scaleAnim.animateTo(
                            targetValue = releasePopScale,
                            animationSpec = spring(
                                dampingRatio = ExpressiveMotionTokens.DampingRatioMediumBouncy,
                                stiffness = 600f
                            )
                        )
                        scaleAnim.animateTo(
                            targetValue = 1.0f,
                            animationSpec = ExpressiveMotionTokens.pressRelease()
                        )
                    }
                } else {
                    gestureScope.launch {
                        scaleAnim.animateTo(
                            targetValue = 1.0f,
                            animationSpec = ExpressiveMotionTokens.spatialExit()
                        )
                    }
                }
            }
        }
    }

    return@composed this.graphicsLayer {
        scaleX = scaleAnim.value
        scaleY = scaleAnim.value
        transformOrigin = TransformOrigin.Center
    }
}

/**
 * Drop-in Expressive Clickable Modifier combining spring scale squash/pop,
 * M3 ripple, and tactile haptic choreography.
 */
fun Modifier.expressiveBounceClickable(
    enabled: Boolean = true,
    onClickLabel: String? = null,
    role: Role? = null,
    pressedScale: Float = 0.965f,
    releasePopScale: Float = 1.02f,
    hapticFeedbackEnabled: Boolean = true,
    hapticType: ExpressiveHapticType = ExpressiveHapticType.Click,
    onClick: () -> Unit
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val haptics = LocalExpressiveHaptics.current

    this
        .expressiveScaleOnPress(
            interactionSource = interactionSource,
            pressedScale = pressedScale,
            releasePopScale = releasePopScale,
            enabled = enabled,
            hapticFeedbackEnabled = hapticFeedbackEnabled,
            customHaptics = haptics
        )
        .clickable(
            interactionSource = interactionSource,
            indication = ripple(bounded = true),
            enabled = enabled,
            onClickLabel = onClickLabel,
            role = role,
            onClick = {
                if (hapticFeedbackEnabled) {
                    haptics.perform(hapticType)
                }
                onClick()
            }
        )
}

/**
 * Expressive Clickable Modifier supporting BioKey's [ExpressiveTokens.HapticFeedbackStyle].
 */
fun Modifier.expressiveClickable(
    enabled: Boolean = true,
    hapticStyle: ExpressiveTokens.HapticFeedbackStyle = ExpressiveTokens.HapticFeedbackStyle.CLICK_SUBTLE,
    pressedScale: Float = 0.965f,
    releasePopScale: Float = 1.02f,
    onClick: () -> Unit
): Modifier = composed {
    val context = LocalContext.current
    val interactionSource = remember { MutableInteractionSource() }
    val haptics = LocalExpressiveHaptics.current

    val mappedType = when (hapticStyle) {
        ExpressiveTokens.HapticFeedbackStyle.TICK -> ExpressiveHapticType.Tick
        ExpressiveTokens.HapticFeedbackStyle.CLICK_SUBTLE -> ExpressiveHapticType.Click
        ExpressiveTokens.HapticFeedbackStyle.CONFIRM_SUCCESS -> ExpressiveHapticType.HeavyClick
        ExpressiveTokens.HapticFeedbackStyle.REJECT_ERROR -> ExpressiveHapticType.ErrorRumble
        ExpressiveTokens.HapticFeedbackStyle.TOGGLE_ON -> ExpressiveHapticType.HeavyClick
        ExpressiveTokens.HapticFeedbackStyle.TOGGLE_OFF -> ExpressiveHapticType.Tick
        ExpressiveTokens.HapticFeedbackStyle.HEAVY_SNAP -> ExpressiveHapticType.HeavyClick
    }

    this
        .expressiveScaleOnPress(
            interactionSource = interactionSource,
            pressedScale = pressedScale,
            releasePopScale = releasePopScale,
            enabled = enabled,
            hapticFeedbackEnabled = false,
            customHaptics = haptics
        )
        .clickable(
            interactionSource = interactionSource,
            indication = ripple(bounded = true),
            enabled = enabled,
            onClick = {
                ExpressiveTokens.triggerHaptic(context, hapticStyle)
                haptics.perform(mappedType)
                onClick()
            }
        )
}


// =========================================================================================
// PREDICTIVE BACK TRANSITIONS
// =========================================================================================

/**
 * Expressive Predictive Back Container Modifier.
 *
 * Kinematics & Mechanics:
 * 1. Non-linear quadratic resistance curve: f(p) = 1 - (1 - p)^2
 * 2. Spatial Scale: 1.0f -> 0.88f
 * 3. Continuous Corner Radius Morphing: 0.dp -> 32.dp
 * 4. Spatial Parallax Displacement: +/- 36.dp based on swipeEdge
 * 5. Alpha Attenuation: 1.0f -> 0.86f
 * 6. Dual Threshold Detent Haptics: Triggered at 30% and 65% progress.
 * 7. Cancellation Recovery: Recovers smoothly via [ExpressiveMotionTokens.predictiveBackCancel].
 * 8. Commit Navigation: Exits swiftly via [ExpressiveMotionTokens.predictiveBackCommit] and calls [onBackCommitted].
 */
@Composable
fun Modifier.expressivePredictiveBackContainer(
    enabled: Boolean = true,
    initialCornerRadius: Dp = 0.dp,
    maxCornerRadius: Dp = 32.dp,
    minScale: Float = 0.88f,
    maxAlphaDrop: Float = 0.14f,
    maxHorizontalDisplacement: Dp = 36.dp,
    onBackCommitted: () -> Unit
): Modifier {
    if (!enabled) return this

    val haptics = LocalExpressiveHaptics.current
    val density = LocalDensity.current

    val scaleAnim = remember { Animatable(1.0f) }
    val cornerRadiusAnim = remember { Animatable(initialCornerRadius.value) }
    val alphaAnim = remember { Animatable(1.0f) }
    val displacementXAnim = remember { Animatable(0.0f) }

    var swipeEdge by remember { mutableIntStateOf(BackEventCompat.EDGE_LEFT) }
    var lastHapticThreshold by remember { mutableFloatStateOf(0.0f) }

    PredictiveBackHandler(enabled = enabled) { progressFlow: Flow<BackEventCompat> ->
        try {
            lastHapticThreshold = 0.0f
            progressFlow.collect { backEvent ->
                swipeEdge = backEvent.swipeEdge
                val rawProgress = backEvent.progress // 0.0f to 1.0f

                // Non-linear elastic resistance curve: f(p) = 1 - (1 - p)^2
                val curvedProgress = 1.0f - (1.0f - rawProgress) * (1.0f - rawProgress)

                // Haptic detent ratchet pulses at 30% and 65% progress
                if (curvedProgress >= 0.30f && lastHapticThreshold < 0.30f) {
                    haptics.predictiveBackThreshold()
                    lastHapticThreshold = 0.30f
                } else if (curvedProgress >= 0.65f && lastHapticThreshold < 0.65f) {
                    haptics.predictiveBackThreshold()
                    lastHapticThreshold = 0.65f
                }

                val targetScale = 1.0f - (curvedProgress * (1.0f - minScale))
                val targetCorners = initialCornerRadius.value +
                        (curvedProgress * (maxCornerRadius.value - initialCornerRadius.value))
                val targetAlpha = 1.0f - (curvedProgress * maxAlphaDrop)

                val maxDisplacementPx = with(density) { maxHorizontalDisplacement.toPx() }
                val targetDisplacementPx = if (swipeEdge == BackEventCompat.EDGE_LEFT) {
                    curvedProgress * maxDisplacementPx
                } else {
                    -curvedProgress * maxDisplacementPx
                }

                // Snap directly during touch tracking for 0ms gesture latency
                scaleAnim.snapTo(targetScale)
                cornerRadiusAnim.snapTo(targetCorners)
                alphaAnim.snapTo(targetAlpha)
                displacementXAnim.snapTo(targetDisplacementPx)
            }

            // GESTURE COMMITTED: User crossed threshold and lifted finger
            haptics.heavyClick(amplitude = 1.0f)

            coroutineScope {
                launch {
                    scaleAnim.animateTo(
                        targetValue = minScale * 0.95f,
                        animationSpec = ExpressiveMotionTokens.predictiveBackCommit()
                    )
                }
                launch {
                    alphaAnim.animateTo(
                        targetValue = 0.0f,
                        animationSpec = ExpressiveMotionTokens.predictiveBackCommit()
                    )
                }
            }

            onBackCommitted()

        } catch (e: CancellationException) {
            // GESTURE CANCELLED: User dragged back to bezel edge
            haptics.tick(amplitude = 0.4f)

            coroutineScope {
                launch {
                    scaleAnim.animateTo(
                        targetValue = 1.0f,
                        animationSpec = ExpressiveMotionTokens.predictiveBackCancel()
                    )
                }
                launch {
                    cornerRadiusAnim.animateTo(
                        targetValue = initialCornerRadius.value,
                        animationSpec = ExpressiveMotionTokens.predictiveBackCancel()
                    )
                }
                launch {
                    alphaAnim.animateTo(
                        targetValue = 1.0f,
                        animationSpec = ExpressiveMotionTokens.predictiveBackCancel()
                    )
                }
                launch {
                    displacementXAnim.animateTo(
                        targetValue = 0.0f,
                        animationSpec = ExpressiveMotionTokens.predictiveBackCancel()
                    )
                }
            }
        }
    }

    return this
        .graphicsLayer {
            translationX = displacementXAnim.value
            scaleX = scaleAnim.value
            scaleY = scaleAnim.value
            alpha = alphaAnim.value
            shadowElevation = 16.dp.toPx()
        }
        .clip(RoundedCornerShape(cornerRadiusAnim.value.dp))
}

/**
 * Fullscreen Predictive Back Scaffold Surface for modal screens and sub-pages.
 */
@Composable
fun ExpressivePredictiveBackScaffold(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    initialCornerRadius: Dp = 0.dp,
    scrimColor: Color = Color.Black.copy(alpha = 0.45f),
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .drawWithContent {
                drawRect(scrimColor)
                drawContent()
            }
            .expressivePredictiveBackContainer(
                enabled = enabled,
                initialCornerRadius = initialCornerRadius,
                maxCornerRadius = 32.dp,
                minScale = 0.88f,
                onBackCommitted = onBack
            )
    ) {
        content()
    }
}
