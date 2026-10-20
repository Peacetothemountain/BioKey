package com.android.BioKey.core.security

import android.app.Activity
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
import android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.util.Log
import com.android.BioKey.R
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
        const val ERROR_LOCKOUT_PERMANENT = BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT_PERMANENT
        const val ERROR_NO_BIOMETRICS = BiometricPrompt.BIOMETRIC_ERROR_NO_BIOMETRICS
        const val ERROR_NO_DEVICE_CREDENTIAL = BiometricPrompt.BIOMETRIC_ERROR_NO_DEVICE_CREDENTIAL
        const val ERROR_HW_UNAVAILABLE = BiometricPrompt.BIOMETRIC_ERROR_HW_UNAVAILABLE

        /** What every lock tells the user after [ERROR_LOCKOUT_PERMANENT]. */
        const val LOCKOUT_PERMANENT_MESSAGE =
            "Too many attempts. Lock your phone with the power button, then unlock it with your PIN, pattern or password to use biometrics again."

        fun isUserCancel(code: Int) = code == ERROR_USER_CANCELED || code == ERROR_CANCELED
    }

    private class Attempt(val packageName: String, val host: WeakReference<Activity>, val cancel: CancellationSignal)

    @Volatile private var active: Attempt? = null

    fun isAuthenticating(): Boolean = active != null

    fun isAuthenticatingFor(packageName: String): Boolean = active?.packageName == packageName

    /** The display of the screen showing the running prompt (a lock screen, BioKey), if one is and it is known. */
    fun promptDisplayId(): Int? = runCatching { active?.host?.get()?.display?.displayId }.getOrNull()

    /** Cancels the running prompt (only if it belongs to [forPackage], when given). */
    fun resetAuthenticatingState(forPackage: String? = null) {
        val attempt = active ?: return
        if (forPackage != null && attempt.packageName != forPackage) return
        active = null
        runCatching { attempt.cancel.cancel() }
    }

    /**
     * Cancels the running prompt only if [host] is showing it. A screen that is going away uses
     * this, so it can't cancel the prompt of a newer screen for the same app.
     */
    fun resetAuthenticatingStateFor(host: Activity) {
        val attempt = active ?: return
        if (attempt.host.get() !== host) return
        active = null
        runCatching { attempt.cancel.cancel() }
    }

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
        val name = appName.ifBlank { activity.getString(R.string.brand_name) }

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
            Log.e(TAG, "Failed starting BiometricPrompt: ${e.javaClass.simpleName}")
            if (active === attempt) active = null
            onError(ERROR_HW_UNAVAILABLE, "Authentication unavailable")
        }
    }
}
