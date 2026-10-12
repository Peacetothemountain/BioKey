package com.android.BioKey.feature.main

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import com.android.BioKey.core.security.LockStateTracker

/**
 * Gate for BioKey's own settings: anything that reduces protection (unlocking an app, pausing
 * protection, lengthening the timeout, disabling a shield...) must go through [require]. While
 * the owner's session is active (see [LockStateTracker.hasActiveOwnerSession]) the action runs
 * immediately, otherwise a biometric prompt is shown first. [SecurityVault] independently refuses
 * such changes without an active session, so a missed call site fails closed.
 */
fun interface StepUpAuth {
    fun require(reason: String, onAuthorized: () -> Unit)
}

/** Fails closed (does nothing) if a screen is ever composed without a provider. */
val LocalStepUpAuth = staticCompositionLocalOf { StepUpAuth { _, _ -> } }

/**
 * Counts touches inside this element as activity for BioKey's idle timeout. MainActivity sees
 * touches on its own window; sheets run in separate windows, so their content applies this.
 * Observes without consuming anything.
 */
fun Modifier.reportsInteraction(): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        LockStateTracker.onOwnAppInteraction()
    }
}

/**
 * Opens a system settings screen from an authenticated BioKey screen. Keeps BioKey unlocked for
 * the round trip (and, right after a biometric check, pre-authorises self-protection for the page
 * the owner deliberately opened), and falls back gracefully on devices that don't implement [intent].
 */
fun Context.openSystemSettings(intent: Intent) {
    val candidates = listOf(
        intent,
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
        Intent(Settings.ACTION_SETTINGS)
    )
    for (candidate in candidates) {
        try {
            if (this !is android.app.Activity) candidate.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(candidate)
            val isBioKeyAppInfo = candidate.data?.schemeSpecificPart == packageName
            LockStateTracker.beginExternalNavigation(preAuthorizeSelfProtection = (candidate === intent) || isBioKeyAppInfo)
            return
        } catch (_: ActivityNotFoundException) {
            // Try the next, more generic screen.
        } catch (_: SecurityException) {
        }
    }
    Toast.makeText(this, "Couldn't open system settings on this device", Toast.LENGTH_SHORT).show()
}
