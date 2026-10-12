package com.android.BioKey.core.security

import android.content.Context
import android.text.format.DateFormat
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.android.BioKey.core.intruder.IntruderManager
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs every BioKey authentication.
 *
 * Success on biometric paths is bound to the Android Keystore: the prompt is given a [BiometricPrompt.CryptoObject]
 * whose cipher only works after a genuine, fresh authentication, and success is only reported
 * once that cipher has actually executed ([HardwareKeyManager.verify]). A hooked or spoofed
 * callback therefore can't unlock anything on its own.
 *
 * Fallback to device PIN/pattern/password is supported on standard paths via [BiometricPrompt] credential authentication.
 *
 * Modes:
 * - Standard: Class 3 biometric, with the device PIN/pattern/password as a fallback.
 * - Identity Check (strict): Class 3 biometric only, no credential fallback.
 *
 * If the enrolled biometrics change, the biometric key is invalidated by the platform; the owner
 * must confirm with the device credential before a new key is issued, and the event is logged.
 * Under Identity Check that confirmation only becomes possible after a 30-minute security delay.
 */
class SecurityEngine private constructor() {

    companion object {
        private const val TAG = "SecurityEngine"

        /** Failed biometric matches in one prompt before BioKey cancels it and treats it as a lockout. */
        const val MAX_FAILED_ATTEMPTS = 5

        /** Failed matches before the intruder camera fires (the first miss is usually the owner). */
        private const val INTRUDER_CAPTURE_THRESHOLD = 2

        val INSTANCE: SecurityEngine by lazy { SecurityEngine() }
    }

    private val authenticating = AtomicBoolean(false)
    @Volatile private var authenticatingPackage: String? = null
    @Volatile private var currentSession: Session? = null
    @Volatile private var activePrompt: BiometricPrompt? = null

    fun isAuthenticating(): Boolean = authenticating.get()

    fun isAuthenticatingFor(packageName: String): Boolean =
        authenticating.get() && authenticatingPackage == packageName

    fun resetAuthenticatingState() {
        val promptToCancel = activePrompt
        activePrompt = null
        currentSession = null
        authenticating.set(false)
        authenticatingPackage = null
        try {
            promptToCancel?.cancelAuthentication()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cancel active prompt: ${e.message}")
        }
    }

    /** A late callback from an abandoned session (e.g. before a rotation) must not end its successor. */
    private fun endSession(session: Session) {
        if (currentSession === session) resetAuthenticatingState()
    }

    /** Whether authentication is possible under the current policy (a [BiometricManager] status code). */
    fun canAuthenticate(context: Context): Int {
        val strict = SecurityVault.getInstance(context).isIdentityCheckEnabled()
        return BiometricManager.from(context).canAuthenticate(
            if (strict) BIOMETRIC_STRONG else BIOMETRIC_STRONG or DEVICE_CREDENTIAL
        )
    }

    fun showBiometricPrompt(
        activity: FragmentActivity,
        appName: String,
        packageName: String,
        onSuccess: () -> Unit,
        onFailure: () -> Unit = {},
        onError: (Int, CharSequence) -> Unit = { _, _ -> },
        promptTitle: String? = null
    ) {
        if (!authenticating.compareAndSet(false, true)) {
            Log.d(TAG, "Prompt already showing; ignoring duplicate request")
            return
        }
        authenticatingPackage = packageName

        try {
            val policy = IdentityCheckManager.getIdentityCheckPolicy(activity)
            val name = appName.ifBlank { "BioKey" }
            val session = Session(
                activity, name, packageName, strict = !policy.allowsDeviceCredential,
                title = promptTitle ?: "Unlock $name", onSuccess = onSuccess, onFailure = onFailure, onError = onError
            )
            currentSession = session

            when (val crypto = HardwareKeyManager.prepareBiometricCrypto(activity)) {
                is HardwareKeyManager.CryptoResult.Ready -> session.promptBiometric(crypto)
                HardwareKeyManager.CryptoResult.BiometricsChanged ->
                    if (session.strict) session.identityCheckDelay(policy.biometricsEnrolled) else session.confirmBiometricChange()
                is HardwareKeyManager.CryptoResult.Unavailable -> when {
                    session.strict && !policy.biometricsEnrolled -> session.identityCheckDelay(biometricsEnrolled = false)
                    session.strict -> session.fail(
                        BiometricPrompt.ERROR_HW_UNAVAILABLE,
                        "Biometric unlock is unavailable right now. Try again in a moment."
                    )
                    else -> session.promptCredential()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize authentication prompt: ${e.message}", e)
            resetAuthenticatingState()
            onError(BiometricPrompt.ERROR_HW_UNAVAILABLE, "Authentication unavailable")
        }
    }

    private inner class Session(
        val activity: FragmentActivity,
        val appName: String,
        val packageName: String,
        val strict: Boolean,
        val title: String,
        val onSuccess: () -> Unit,
        val onFailure: () -> Unit,
        val onError: (Int, CharSequence) -> Unit
    ) {
        private val finished = AtomicBoolean(false)
        private val executor = ContextCompat.getMainExecutor(activity)
        private val appContext = activity.applicationContext

        fun succeed() {
            if (!finished.compareAndSet(false, true)) return
            endSession(this)
            LockStateTracker.clearFailedAttempts(packageName)
            if (packageName == activity.packageName) {
                SecurityEventLog.record(appContext, SecurityEventLog.Type.UNLOCK_SUCCESS, "BioKey Settings unlocked")
            }
            if (com.android.BioKey.BuildConfig.DEBUG) {
                Log.i(TAG, "Authentication succeeded for $packageName")
            }
            onSuccess()
        }

        fun fail(code: Int, message: CharSequence) {
            if (!finished.compareAndSet(false, true)) return
            endSession(this)
            if (LockStateTracker.getFailedAttempts(packageName) >= INTRUDER_CAPTURE_THRESHOLD) {
                IntruderManager.captureIntruderPhotoNow(appContext, packageName)
            }
            onError(code, message)
        }

        private fun onBiometricMismatch(prompt: BiometricPrompt) {
            val attempts = LockStateTracker.recordFailedAttempt(packageName)
            Log.w(TAG, "Biometric mismatch for $packageName (attempt $attempts)")
            if (attempts == INTRUDER_CAPTURE_THRESHOLD) {
                IntruderManager.captureIntruderPhoto(appContext, packageName)
                SecurityEventLog.record(appContext, SecurityEventLog.Type.INTRUDER_CAPTURED, "Triggered on ${label()} (attempt $attempts)")
            } else {
                SecurityEventLog.record(appContext, SecurityEventLog.Type.UNLOCK_FAILED, "Attempt $attempts on ${label()}")
            }
            if (attempts >= MAX_FAILED_ATTEMPTS) {
                fail(BiometricPrompt.ERROR_LOCKOUT, "Too many failed attempts.")
                prompt.cancelAuthentication()
                return
            }
            onFailure()
        }

        private fun label() = if (packageName == activity.packageName) "BioKey" else appName

        fun promptBiometric(crypto: HardwareKeyManager.CryptoResult.Ready) {
            val prompt = BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (HardwareKeyManager.verify(result, crypto.challenge)) {
                        succeed()
                    } else {
                        Log.e(TAG, "Biometric success without a valid hardware auth token for $packageName")
                        fail(BiometricPrompt.ERROR_VENDOR, "Hardware verification failed. Try again.")
                    }
                }

                override fun onAuthenticationFailed() {
                    activePrompt?.let { onBiometricMismatch(it) }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (finished.get()) return
                    val wantsFallback = errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                        errorCode == BiometricPrompt.ERROR_LOCKOUT ||
                        errorCode == BiometricPrompt.ERROR_LOCKOUT_PERMANENT ||
                        errorCode == BiometricPrompt.ERROR_NO_BIOMETRICS ||
                        errorCode == BiometricPrompt.ERROR_HW_UNAVAILABLE ||
                        errorCode == BiometricPrompt.ERROR_HW_NOT_PRESENT

                    if (wantsFallback && !strict) {
                        promptCredential()
                    } else {
                        fail(errorCode, errString)
                    }
                }
            })
            activePrompt = prompt

            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(if (strict) "Identity Check: biometric required" else "Verify it's you")
                .setAllowedAuthenticators(BIOMETRIC_STRONG)
                .setNegativeButtonText(if (strict) "Cancel" else "Use PIN or pattern")
                .setConfirmationRequired(false)
                .build()

            try {
                prompt.authenticate(info, crypto.cryptoObject)
            } catch (e: Exception) {
                Log.w(TAG, "Biometric prompt failed to start: ${e.message}")
                if (strict) fail(BiometricPrompt.ERROR_HW_UNAVAILABLE, "Biometric prompt unavailable") else promptCredential()
            }
        }

        /**
         * Device credential fallback prompt (PIN, pattern, password).
         * Note: Android explicitly prohibits passing a CryptoObject when DEVICE_CREDENTIAL is set.
         */
        fun promptCredential(
            title: String = this.title,
            description: String? = null,
            credentialOnly: Boolean = false,
            onVerified: () -> Unit = ::succeed
        ) {
            val prompt = BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onVerified()
                }

                override fun onAuthenticationFailed() {
                    activePrompt?.let { onBiometricMismatch(it) }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (!finished.get()) fail(errorCode, errString)
                }
            })
            activePrompt = prompt

            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle("Verify it's you")
                .apply { if (description != null) setDescription(description) }
                .setAllowedAuthenticators(if (credentialOnly) DEVICE_CREDENTIAL else BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
                .setConfirmationRequired(false)
                .build()

            try {
                prompt.authenticate(info)
            } catch (e: Exception) {
                Log.e(TAG, "Credential prompt failed to start: ${e.message}")
                fail(BiometricPrompt.ERROR_HW_UNAVAILABLE, "Authentication unavailable on this device")
            }
        }

        /**
         * The platform invalidated the biometric key because fingerprints/faces were added or
         * removed. Someone who knows the PIN could have enrolled their own finger, so require the
         * device credential (biometrics are not accepted here) before issuing a new key.
         */
        fun confirmBiometricChange() {
            SecurityEventLog.record(appContext, SecurityEventLog.Type.BIOMETRICS_CHANGED, "Detected while unlocking ${label()}")
            promptCredential(
                title = "Biometrics changed",
                description = "Fingerprints or face data on this device changed since BioKey last checked. " +
                    "Confirm with your device PIN, pattern, or password to continue.",
                credentialOnly = true,
                onVerified = {
                    executor.execute {
                        HardwareKeyManager.resetBiometricKey(appContext)
                    }
                    SecurityVault.getInstance(appContext).clearIdentityHold()
                    SecurityEventLog.record(appContext, SecurityEventLog.Type.BIOMETRICS_CHANGED, "Confirmed with device credential")
                    succeed()
                }
            )
        }

        fun identityCheckDelay(biometricsEnrolled: Boolean) {
            val vault = SecurityVault.getInstance(appContext)
            val remaining = vault.identityHoldRemaining()
            if (remaining > 0L) {
                val until = DateFormat.getTimeFormat(appContext).format(Date(System.currentTimeMillis() + remaining))
                fail(
                    BiometricPrompt.ERROR_LOCKOUT,
                    "Biometrics on this device changed. For your security, Identity Check keeps apps locked until $until."
                )
                return
            }
            if (biometricsEnrolled) {
                confirmBiometricChange()
                return
            }
            promptCredential(
                title = "Turn off Identity Check",
                description = "No fingerprint or face is enrolled any more, so Identity Check can't work. " +
                    "Confirm with your device PIN, pattern, or password to turn it off and continue.",
                credentialOnly = true,
                onVerified = {
                    vault.endIdentityCheckAfterHold()
                    succeed()
                }
            )
        }
    }
}
