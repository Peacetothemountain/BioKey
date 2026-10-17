package com.android.BioKey.ui.theme

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Surface
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.launch

/**
 * BioKey's Material 3 theme: the wallpaper-based dynamic palette, M3 type and shapes.
 *
 * Everything is drawn on a root [Surface], which sets the page colour *and* the matching content
 * colour, so text and icons without an explicit colour are always readable (never default black
 * on a dark page). Short messages ([BioKeyMessages]) appear over it, at the bottom.
 */
@Composable
fun BioKeyTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val colors = remember(context, dark) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    }
    // Material 3 Expressive: the dynamic palette with expressive spring motion for components.
    MaterialExpressiveTheme(colorScheme = colors, motionScheme = MotionScheme.expressive()) {
        Surface(color = colors.surface, contentColor = colors.onSurface) {
            Box(Modifier.fillMaxSize()) {
                content()
                // BioKey's messages, above whichever screen is open.
                BioKeyMessageHost(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .imePadding()
                )
            }
        }
    }
}

/**
 * False when the user turned on "Remove animations" (animator duration scale 0). Decorative
 * looping animations should then hold still.
 */
@Composable
fun animationsEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        android.provider.Settings.Global.getFloat(
            context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f
        ) != 0f
    }
}

/**
 * System haptics. These follow the user's vibration settings and the device's tuned effects,
 * and need no VIBRATE permission.
 */
object Haptics {
    fun toggle(view: View, on: Boolean) {
        val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (on) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.TOGGLE_OFF
        } else {
            HapticFeedbackConstants.CLOCK_TICK
        }
        view.performHapticFeedback(effect)
    }

    fun confirm(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    }
}

/**
 * Squishes a component while it's pressed and springs it back on release, with a light tick.
 *
 * Observes touches without consuming them (the component's own click handling is untouched) and
 * animates scale on the render layer, so pressing never recomposes or re-lays out anything.
 */
fun Modifier.springPress(pressedScale: Float = 0.94f): Modifier = this then SpringPressElement(pressedScale)

private data class SpringPressElement(val pressedScale: Float) : ModifierNodeElement<SpringPressNode>() {
    override fun create() = SpringPressNode(pressedScale)
    override fun update(node: SpringPressNode) {
        node.pressedScale = pressedScale
    }
}

private class SpringPressNode(var pressedScale: Float) :
    Modifier.Node(), PointerInputModifierNode, LayoutModifierNode, CompositionLocalConsumerModifierNode {

    private val scale = Animatable(1f)
    private var pressed = false

    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
        if (pass != PointerEventPass.Initial) return
        when (pointerEvent.type) {
            PointerEventType.Press -> if (!pressed) {
                pressed = true
                currentValueOf(LocalView).performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                animateTo(pressedScale, Spring.StiffnessHigh)
            }
            PointerEventType.Release -> if (pointerEvent.changes.none { it.pressed }) release()
        }
    }

    override fun onCancelPointerInput() = release()

    private fun release() {
        if (!pressed) return
        pressed = false
        animateTo(1f, Spring.StiffnessMedium, Spring.DampingRatioMediumBouncy)
    }

    private fun animateTo(target: Float, stiffness: Float, damping: Float = Spring.DampingRatioNoBouncy) {
        if (!isAttached) return
        coroutineScope.launch { scale.animateTo(target, spring(dampingRatio = damping, stiffness = stiffness)) }
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            placeable.placeWithLayer(0, 0) {
                scaleX = scale.value
                scaleY = scale.value
            }
        }
    }
}
