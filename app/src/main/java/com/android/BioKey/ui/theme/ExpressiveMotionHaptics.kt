package com.android.BioKey.ui.theme

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.launch

object ExpressiveMotionTokens {
    const val DampingRatioMediumBouncy = 0.62f
    const val DampingRatioNoBouncy = 1.0f

    const val StiffnessMedium = 520f
    const val StiffnessFast = 850f
    const val StiffnessUltraFast = 1400f

    fun <T> spatialExit(visibilityThreshold: T? = null): SpringSpec<T> = spring(
        dampingRatio = DampingRatioNoBouncy,
        stiffness = StiffnessMedium,
        visibilityThreshold = visibilityThreshold
    )

    fun <T> pressDown(visibilityThreshold: T? = null): SpringSpec<T> = spring(
        dampingRatio = 0.72f,
        stiffness = StiffnessUltraFast,
        visibilityThreshold = visibilityThreshold
    )

    fun <T> pressRelease(visibilityThreshold: T? = null): SpringSpec<T> = spring(
        dampingRatio = DampingRatioMediumBouncy,
        stiffness = StiffnessFast,
        visibilityThreshold = visibilityThreshold
    )
}

enum class ExpressiveHapticType {
    Tick,
    Click,
    HeavyClick,
    ErrorRumble,
    SuccessDoublePulse,
    BiometricSuccess
}

interface ExpressiveHapticFeedback {
    fun perform(type: ExpressiveHapticType)
    fun tick(amplitude: Float = 0.5f)
    fun click(amplitude: Float = 1.0f)
    fun heavyClick(amplitude: Float = 1.0f)
    fun errorRumble()
    fun successDoublePulse()
    fun biometricSuccess()
}

object NoOpExpressiveHapticFeedback : ExpressiveHapticFeedback {
    override fun perform(type: ExpressiveHapticType) {}
    override fun tick(amplitude: Float) {}
    override fun click(amplitude: Float) {}
    override fun heavyClick(amplitude: Float) {}
    override fun errorRumble() {}
    override fun successDoublePulse() {}
    override fun biometricSuccess() {}
}

class AndroidExpressiveHapticFeedback(
    private val context: Context,
    @Suppress("UNUSED_PARAMETER") view: View? = null
) : ExpressiveHapticFeedback {

    private val vibrator: Vibrator? by lazy {
        val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        vibratorManager?.defaultVibrator
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
            ExpressiveHapticType.ErrorRumble -> errorRumble()
            ExpressiveHapticType.SuccessDoublePulse -> successDoublePulse()
            ExpressiveHapticType.BiometricSuccess -> biometricSuccess()
        }
    }

    override fun tick(amplitude: Float) {
        val clampedAmp = amplitude.coerceIn(0.1f, 1.0f)
        if (vibrator != null && isPrimitiveSupported(VibrationEffect.Composition.PRIMITIVE_TICK)) {
            val effect = VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, clampedAmp)
                .compose()
            vibrateEffect(effect)
            return
        }
        vibrateEffect(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
    }

    override fun click(amplitude: Float) {
        val clampedAmp = amplitude.coerceIn(0.1f, 1.0f)
        if (vibrator != null && isPrimitiveSupported(VibrationEffect.Composition.PRIMITIVE_CLICK)) {
            val effect = VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, clampedAmp)
                .compose()
            vibrateEffect(effect)
            return
        }
        vibrateEffect(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
    }

    override fun heavyClick(amplitude: Float) {
        val clampedAmp = amplitude.coerceIn(0.1f, 1.0f)
        if (vibrator != null) {
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
        vibrateEffect(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
    }

    override fun errorRumble() {
        if (vibrator != null) {
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
        val timings = longArrayOf(0, 18, 50, 24)
        val amplitudes = intArrayOf(0, 180, 0, 255)
        vibrateEffect(VibrationEffect.createWaveform(timings, amplitudes, -1))
    }

    override fun successDoublePulse() {
        if (vibrator != null && isPrimitiveSupported(VibrationEffect.Composition.PRIMITIVE_CLICK)) {
            val comp = VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.55f)
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1.0f, 65)
            vibrateEffect(comp.compose())
            return
        }
        val timings = longArrayOf(0, 12, 60, 20)
        val amplitudes = intArrayOf(0, 120, 0, 255)
        vibrateEffect(VibrationEffect.createWaveform(timings, amplitudes, -1))
    }

    override fun biometricSuccess() {
        if (vibrator != null) {
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
        return try {
            vibrator?.areAllPrimitivesSupported(primitiveId) == true
        } catch (_: Exception) {
            false
        }
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

object BioKeyHaptics {
    @Volatile
    private var cachedDispatcher: AndroidExpressiveHapticFeedback? = null

    fun dispatch(context: Context, type: ExpressiveHapticType) {
        try {
            val dispatcher = cachedDispatcher ?: synchronized(this) {
                cachedDispatcher ?: AndroidExpressiveHapticFeedback(context.applicationContext).also {
                    cachedDispatcher = it
                }
            }
            dispatcher.perform(type)
        } catch (_: Exception) {}
    }

    fun onUnlockSuccess(context: Context) = dispatch(context, ExpressiveHapticType.BiometricSuccess)
}

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

    val gestureModifier: Modifier = if (interactionSource != null) {
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
        Modifier
    } else {
        val gestureScope = rememberCoroutineScope()
        Modifier.pointerInput(pressScale, releasePopScale, hapticsEnabled) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
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

    return@composed gestureModifier.graphicsLayer {
        scaleX = scaleAnim.value
        scaleY = scaleAnim.value
        transformOrigin = TransformOrigin.Center
    }
}
