package com.android.BioKey.core.security

import android.content.Context
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Standard native authentication engine for BioKey.
 * Backed directly by Android's BiometricPrompt (BIOMETRIC_STRONG or DEVICE_CREDENTIAL).
 * 100% reliable across all Android devices, without custom cipher or slot exhaustion issues.
 */
class SecurityEngine private constructor() {

    companion object {
        private const val TAG = "SecurityEngine"
        val INSTANCE: SecurityEngine by lazy { SecurityEngine() }
    }

    private val authenticating = AtomicBoolean(false)
    @Volatile private var authenticatingPackage: String? = null
    @Volatile private var activePrompt: BiometricPrompt? = null
    @Volatile private var activeHostActivity: java.lang.ref.WeakReference<FragmentActivity>? = null

    fun isAuthenticating(): Boolean = authenticating.get()

    fun isAuthenticatingFor(packageName: String): Boolean =
        authenticating.get() && authenticatingPackage == packageName

    fun resetAuthenticatingState(forPackage: String? = null) {
        if (forPackage != null && authenticatingPackage != forPackage) {
            return
        }
        val prompt = activePrompt
        activePrompt = null
        activeHostActivity = null
        authenticating.set(false)
        authenticatingPackage = null
        try {
            prompt?.cancelAuthentication()
        } catch (_: Exception) {}
    }

    fun canAuthenticate(context: Context): Int =
        BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)

    fun showBiometricPrompt(
        activity: FragmentActivity,
        appName: String,
        packageName: String,
        onSuccess: () -> Unit,
        onFailure: () -> Unit = {},
        onError: (Int, CharSequence) -> Unit = { _, _ -> },
        promptTitle: String? = null
    ) {
        val currentHost = activeHostActivity?.get()
        if (
            authenticating.get() &&
            authenticatingPackage == packageName &&
            activePrompt != null &&
            currentHost === activity &&
            !activity.isFinishing &&
            !activity.isDestroyed
        ) {
            Log.d(TAG, "Prompt already active for $packageName; ignoring duplicate request")
            return
        }
        val stalePrompt = activePrompt
        activePrompt = null
        activeHostActivity = null
        if (stalePrompt != null && currentHost !== activity) {
            runCatching { stalePrompt.cancelAuthentication() }
        }
        authenticating.set(true)
        authenticatingPackage = packageName
        activeHostActivity = java.lang.ref.WeakReference(activity)
        val name = appName.ifBlank { "BioKey" }
        val title = promptTitle ?: "Unlock $name"

        try {
            val executor = ContextCompat.getMainExecutor(activity)
            var currentPrompt: BiometricPrompt? = null
            val prompt = BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (activePrompt === currentPrompt) {
                        activePrompt = null
                        activeHostActivity = null
                        authenticating.set(false)
                        authenticatingPackage = null
                    }
                    onSuccess()
                }

                override fun onAuthenticationFailed() {
                    if (activePrompt === currentPrompt) {
                        onFailure()
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (activePrompt !== currentPrompt) {
                        Log.d(TAG, "Ignoring stale biometric error ($errorCode) from superseded prompt")
                        return
                    }
                    activePrompt = null
                    activeHostActivity = null
                    authenticating.set(false)
                    authenticatingPackage = null
                    onError(errorCode, errString)
                }
            })
            currentPrompt = prompt
            activePrompt = prompt

            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle("Verify it's you")
                .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
                .setConfirmationRequired(false)
                .build()

            prompt.authenticate(promptInfo)
        } catch (e: Exception) {
            Log.e(TAG, "Failed starting BiometricPrompt: ${e.message}")
            resetAuthenticatingState(packageName)
            onError(BiometricPrompt.ERROR_HW_UNAVAILABLE, "Authentication unavailable")
        }
    }
}
