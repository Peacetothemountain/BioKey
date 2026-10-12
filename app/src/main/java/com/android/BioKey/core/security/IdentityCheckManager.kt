package com.android.BioKey.core.security

import android.app.Activity
import android.content.Context
import android.os.Build
import android.util.Log
import android.view.MotionEvent
import android.view.WindowManager
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInteropFilter

/**
 * BioKey Identity Check & Window Security Manager.
 *
 * Provides:
 * 1. Biometric Strong Status verification (BIOMETRIC_STRONG).
 * 2. Identity Check policy evaluation via SecurityVault.
 * 3. BiometricPrompt PromptInfo builder for standard and strict biometric auth.
 * 4. System window protection:
 *    - Window protection: `setHideOverlayWindows(true)` (API 31+)
 *    - Surface security: `FLAG_SECURE`
 *    - Recents protection: `setRecentsScreenshotEnabled(false)` (API 33+)
 *    - Touch obscurity inspection: Detects `FLAG_WINDOW_IS_OBSCURED`
 */
object IdentityCheckManager {

    private const val TAG = "IdentityCheckManager"

    /**
     * Maximum Failed Biometric Attempts before lockout.
     */
    const val IDENTITY_CHECK_MAX_FAILED_ATTEMPTS = 5

    const val MOTION_FLAG_OBSCURED = MotionEvent.FLAG_WINDOW_IS_OBSCURED

    data class BiometricStrongStatus(
        val isAvailable: Boolean,
        val statusCode: Int,
        val description: String,
        val meetsClass3Requirement: Boolean
    )

    data class IdentityCheckPolicy(
        val isEnforced: Boolean,
        val reason: String,
        val allowsDeviceCredential: Boolean = false,
        val maxFailedAttempts: Int = IDENTITY_CHECK_MAX_FAILED_ATTEMPTS
    )

    fun checkBiometricStrongStatus(context: Context): BiometricStrongStatus {
        val bm = BiometricManager.from(context)
        val code = bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        return when (code) {
            BiometricManager.BIOMETRIC_SUCCESS -> {
                BiometricStrongStatus(
                    isAvailable = true,
                    statusCode = code,
                    description = "Strong Biometrics verified and enrolled.",
                    meetsClass3Requirement = true
                )
            }
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> {
                BiometricStrongStatus(
                    isAvailable = false,
                    statusCode = code,
                    description = "Hardware supported, but no biometric is currently enrolled.",
                    meetsClass3Requirement = true
                )
            }
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> {
                BiometricStrongStatus(
                    isAvailable = false,
                    statusCode = code,
                    description = "No biometric hardware present on device.",
                    meetsClass3Requirement = false
                )
            }
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> {
                BiometricStrongStatus(
                    isAvailable = false,
                    statusCode = code,
                    description = "Biometric hardware temporarily unavailable.",
                    meetsClass3Requirement = true
                )
            }
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> {
                BiometricStrongStatus(
                    isAvailable = false,
                    statusCode = code,
                    description = "Biometric security update required before usage.",
                    meetsClass3Requirement = false
                )
            }
            else -> {
                BiometricStrongStatus(
                    isAvailable = false,
                    statusCode = code,
                    description = "Biometric status code: $code",
                    meetsClass3Requirement = false
                )
            }
        }
    }

    fun getIdentityCheckPolicy(context: Context, packageName: String): IdentityCheckPolicy {
        val vault = SecurityVault.getInstance(context)
        val isGloballyEnforced = vault.isIdentityCheckEnabled()
        val bmStatus = checkBiometricStrongStatus(context)

        if (!bmStatus.meetsClass3Requirement && bmStatus.statusCode == BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE) {
            return IdentityCheckPolicy(
                isEnforced = false,
                reason = "No biometric hardware present on device - device credential fallback permitted.",
                allowsDeviceCredential = true
            )
        }

        if (isGloballyEnforced) {
            return IdentityCheckPolicy(
                isEnforced = true,
                reason = "Identity Check active - strict biometrics enforced.",
                allowsDeviceCredential = false
            )
        }

        return IdentityCheckPolicy(
            isEnforced = false,
            reason = "Standard protection active - device PIN/pattern fallback permitted.",
            allowsDeviceCredential = true
        )
    }

    fun buildIdentityCheckPromptInfo(
        title: String,
        subtitle: String = "Biometrics Enabled",
        description: String = "Identity Check is on and requires a biometric",
        negativeButtonText: String = "Cancel"
    ): BiometricPrompt.PromptInfo {
        return BiometricPrompt.PromptInfo.Builder()
            .setTitle(title.ifBlank { "Identity Check" })
            .setSubtitle(subtitle)
            .setDescription(description)
            .setConfirmationRequired(false)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText(negativeButtonText)
            .build()
    }

    fun buildStandardCredentialPromptInfo(
        title: String,
        subtitle: String = "Biometrics Enabled",
        description: String = "Enter device PIN, pattern, or password"
    ): BiometricPrompt.PromptInfo {
        return BiometricPrompt.PromptInfo.Builder()
            .setTitle(title.ifBlank { "Unlock Application" })
            .setSubtitle(subtitle)
            .setDescription(description)
            .setConfirmationRequired(false)
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()
    }

    fun applyWindowProtections(activity: Activity) {
        val window = activity.window

        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                window.setHideOverlayWindows(true)
            } catch (e: Exception) {
                Log.w(TAG, "Failed setHideOverlayWindows: ${e.message}")
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                activity.setRecentsScreenshotEnabled(false)
            } catch (e: Exception) {
                Log.w(TAG, "Failed setRecentsScreenshotEnabled(false): ${e.message}")
            }
        }

        window.decorView.filterTouchesWhenObscured = true
    }

    fun isMotionEventObscured(event: MotionEvent): Boolean {
        val flags = event.flags
        val isObscured = (flags and MotionEvent.FLAG_WINDOW_IS_OBSCURED != 0)

        if (isObscured) {
            Log.e(
                TAG,
                "Tapjacking detected: MotionEvent rejected (isObscured=$isObscured)"
            )
            return true
        }
        return false
    }

    @OptIn(ExperimentalComposeUiApi::class)
    fun Modifier.rejectObscuredTouches(
        onObscuredDetected: () -> Unit = {}
    ): Modifier = this.pointerInteropFilter { motionEvent ->
        if (isMotionEventObscured(motionEvent)) {
            onObscuredDetected()
            true
        } else {
            false
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.rejectObscuredTouches(
    onObscuredDetected: () -> Unit = {}
): Modifier = IdentityCheckManager.run { rejectObscuredTouches(onObscuredDetected) }
