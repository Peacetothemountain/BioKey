package com.android.BioKey.core.security

import android.app.Activity
import android.os.Build
import android.content.Context
import android.util.Log
import android.view.MotionEvent
import android.view.WindowManager
import androidx.biometric.BiometricManager
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInteropFilter

/**
 * Identity Check policy (biometric-only unlocking) and window hardening shared by BioKey's
 * sensitive screens.
 */
object IdentityCheckManager {

    private const val TAG = "IdentityCheckManager"

    data class BiometricStrongStatus(
        val isAvailable: Boolean,
        val statusCode: Int,
        val description: String,
        val meetsClass3Requirement: Boolean
    )

    data class IdentityCheckPolicy(
        val isEnforced: Boolean,
        val reason: String,
        val allowsDeviceCredential: Boolean,
        /** False when Identity Check is on but every fingerprint and face was removed. */
        val biometricsEnrolled: Boolean = true
    )

    fun checkBiometricStrongStatus(context: Context): BiometricStrongStatus {
        val code = BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        return when (code) {
            BiometricManager.BIOMETRIC_SUCCESS ->
                BiometricStrongStatus(true, code, "Strong biometrics enrolled and ready.", true)
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ->
                BiometricStrongStatus(false, code, "Supported, but no fingerprint or face is enrolled.", true)
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE ->
                BiometricStrongStatus(false, code, "No Class 3 biometric hardware on this device.", false)
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE ->
                BiometricStrongStatus(false, code, "Biometric hardware is temporarily unavailable.", true)
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED ->
                BiometricStrongStatus(false, code, "A security update is required before biometrics can be used.", false)
            else ->
                BiometricStrongStatus(false, code, "Biometrics unavailable (status $code).", false)
        }
    }

    /**
     * Identity Check stays strict whenever the device has Class 3 biometric hardware. A
     * temporarily unavailable sensor means "try again", not a downgrade. Removing every biometric
     * needs only the device PIN, which is exactly what Identity Check assumes an attacker may know,
     * so that stays strict too and [SecurityEngine] applies the security delay before anything
     * falls back to the PIN. Only devices that can't use a strong biometric at all are downgraded.
     */
    fun getIdentityCheckPolicy(context: Context): IdentityCheckPolicy {
        if (!SecurityVault.getInstance(context).isIdentityCheckEnabled()) {
            return IdentityCheckPolicy(false, "Standard protection: biometric or device credential.", allowsDeviceCredential = true)
        }
        return when (BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)) {
            BiometricManager.BIOMETRIC_SUCCESS,
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE ->
                IdentityCheckPolicy(true, "Identity Check: Class 3 biometric required.", allowsDeviceCredential = false)
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ->
                IdentityCheckPolicy(
                    true,
                    "Identity Check: biometrics were removed; the security delay applies.",
                    allowsDeviceCredential = false,
                    biometricsEnrolled = false
                )
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED ->
                IdentityCheckPolicy(true, "Identity Check: biometric security update required.", allowsDeviceCredential = false)
            else ->
                IdentityCheckPolicy(false, "Identity Check is on but this device can't use a strong biometric.", allowsDeviceCredential = true)
        }
    }

    /**
     * FLAG_SECURE (no screenshots/recording/recents thumbnails), hides other apps' overlays
     * while this window is visible (needs HIDE_OVERLAY_WINDOWS), and drops obscured touches.
     */
    fun applyWindowProtections(activity: Activity) {
        val window = activity.window
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        try {
            window.setHideOverlayWindows(true)
        } catch (e: Exception) {
            Log.w(TAG, "setHideOverlayWindows failed: ${e.message}")
        }
        // FLAG_SECURE already blanks the recents thumbnail; this also skips taking it (API 33+).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) activity.setRecentsScreenshotEnabled(false)
        window.decorView.filterTouchesWhenObscured = true
    }

    fun isMotionEventObscured(event: MotionEvent): Boolean {
        val obscured = (event.flags and (
            MotionEvent.FLAG_WINDOW_IS_OBSCURED or MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED
        )) != 0
        if (obscured) Log.w(TAG, "Rejected touch from an obscured or partially obscured window (possible tapjacking)")
        return obscured
    }
}

/** Consumes touches delivered while another app's window covers this one. */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.rejectObscuredTouches(onObscuredDetected: () -> Unit = {}): Modifier =
    pointerInteropFilter { motionEvent ->
        if (IdentityCheckManager.isMotionEventObscured(motionEvent)) {
            onObscuredDetected()
            true
        } else {
            false
        }
    }
