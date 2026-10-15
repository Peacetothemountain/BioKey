package com.android.BioKey.core.security

import android.app.Activity
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
import android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.util.Log
import java.lang.ref.WeakReference

/**
 * The single authentication path for BioKey: the system BiometricPrompt, exactly what Android's
 * own App Lock uses.
 *
 * Allowed authenticators are Class 3 (strong) biometrics plus the device credential, so the
 * system runs them in its own priority order: Class 3 face unlock starts immediately (no confirm
 * tap), fingerprint is accepted at the same time, and "Use PIN" (or pattern/password) is the
 * fallback, including after a biometric lockout. Class 2 (weak) face unlock is never accepted.
 */
class SecurityEngine private constructor() {

    companion object {
        private const val TAG = "SecurityEngine"
        val INSTANCE: SecurityEngine by lazy { SecurityEngine() }

        const val ERROR_CANCELED = BiometricPrompt.BIOMETRIC_ERROR_CANCELED
        const val ERROR_USER_CANCELED = BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED
        const val ERROR_LOCKOUT = BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT
        const val ERROR_LOCKOUT_PERMANENT = BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT_PERMANENT
        const val ERROR_NO_BIOMETRICS = BiometricPrompt.BIOMETRIC_ERROR_NO_BIOMETRICS
        const val ERROR_NO_DEVICE_CREDENTIAL = BiometricPrompt.BIOMETRIC_ERROR_NO_DEVICE_CREDENTIAL
        const val ERROR_HW_UNAVAILABLE = BiometricPrompt.BIOMETRIC_ERROR_HW_UNAVAILABLE

        fun isUserCancel(code: Int) = code == ERROR_USER_CANCELED || code == ERROR_CANCELED
    }

    private class Attempt(val packageName: String, val host: WeakReference<Activity>, val cancel: CancellationSignal)

    @Volatile private var active: Attempt? = null

    fun isAuthenticating(): Boolean = active != null

    fun isAuthenticatingFor(packageName: String): Boolean = active?.packageName == packageName

    /** Cancels the running prompt (only if it belongs to [forPackage], when given). */
    fun resetAuthenticatingState(forPackage: String? = null) {
        val attempt = active ?: return
        if (forPackage != null && attempt.packageName != forPackage) return
        active = null
        runCatching { attempt.cancel.cancel() }
    }

    fun canAuthenticate(context: Context): Int =
        context.getSystemService(BiometricManager::class.java)
            ?.canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
            ?: BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE

    /**
     * Shows the system prompt hosted by [activity], which must be resumed and on top: the system
     * cancels prompts whose host task leaves the foreground.
     */
    fun showBiometricPrompt(
        activity: Activity,
        appName: String,
        packageName: String,
        onSuccess: () -> Unit,
        onError: (Int, CharSequence) -> Unit = { _, _ -> },
        promptTitle: String? = null
    ) {
        val running = active
        if (running != null && running.packageName == packageName && running.host.get() === activity && !activity.isFinishing) {
            Log.d(TAG, "Prompt already showing for $packageName")
            return
        }
        resetAuthenticatingState()

        val attempt = Attempt(packageName, WeakReference(activity), CancellationSignal())
        active = attempt
        val name = appName.ifBlank { "BioKey" }

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                if (active !== attempt) return
                active = null
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (active !== attempt) {
                    Log.d(TAG, "Ignoring error $errorCode from a superseded prompt")
                    return
                }
                active = null
                onError(errorCode, errString)
            }
        }

        try {
            BiometricPrompt.Builder(activity)
                .setTitle(promptTitle ?: "Unlock $name")
                .setSubtitle("Verify it's you")
                .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
                .setConfirmationRequired(false)
                .build()
                .authenticate(attempt.cancel, activity.mainExecutor, callback)
        } catch (e: Exception) {
            Log.e(TAG, "Failed starting BiometricPrompt: ${e.message}")
            if (active === attempt) active = null
            onError(ERROR_HW_UNAVAILABLE, "Authentication unavailable")
        }
    }
}
