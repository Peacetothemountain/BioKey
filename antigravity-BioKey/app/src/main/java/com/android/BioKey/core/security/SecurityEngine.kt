package com.android.BioKey.core.security

import android.content.Context
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.android.BioKey.core.intruder.IntruderManager
import java.util.concurrent.atomic.AtomicBoolean

/**
 * BioKey Security Engine.
 *
 * Implements Android 18 / Next-Gen Google Security architecture:
 * 1. Google Identity Check Integration: Strictly enforces Class 3 Strong Biometrics
 *    (BIOMETRIC_STRONG) without device PIN/pattern fallback (DEVICE_CREDENTIAL) outside
 *    trusted locations or for protected vaults.
 * 2. Titan M3 StrongBox Hardware Cryptographic Authorization:
 *    Generates fresh 256-bit cryptographic nonces per-operation, binds them to
 *    StrongBox KeyMint via BiometricPrompt.CryptoObject (authTimeout = 0), and verifies
 *    Hardware Auth Token (HAT) execution upon success.
 * 3. Lockout & Anti-Brute-Force Enforcement:
 *    Terminates authentication and engages lockdown upon reaching 5 consecutive failures.
 * 4. Zero-Trust Fallback Prohibition:
 *    Refuses PIN fallback when Identity Check is mandated.
 */
class SecurityEngine private constructor() {

    companion object {
        private const val TAG = "SecurityEngine"
        val INSTANCE: SecurityEngine by lazy { SecurityEngine() }
    }

    private val authenticating = AtomicBoolean(false)

    fun isAuthenticating(): Boolean = authenticating.get()

    fun resetAuthenticatingState() {
        authenticating.set(false)
    }

    fun initialize(context: Context) {
        TitanM3SecurityManager.INSTANCE.initializeTitanM3Protocols(context)
    }

    /**
     * Determines whether authentication is possible under current policy.
     */
    fun canAuthenticate(context: Context): Int {
        val bm = BiometricManager.from(context)
        val vault = SecurityVault.getInstance(context)
        val authenticators = if (vault.isIdentityCheckEnabled()) {
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        } else {
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        }
        return bm.canAuthenticate(authenticators)
    }

    /**
     * Dispatches biometric prompt enforcing Google Identity Check & Titan M3 StrongBox hardware auth.
     */
    fun showBiometricPrompt(
        activity: FragmentActivity,
        appName: String,
        packageName: String,
        onSuccess: () -> Unit,
        onFailure: () -> Unit = {},
        onError: (Int, CharSequence) -> Unit = { _, _ -> }
    ) {
        if (authenticating.get()) {
            Log.d(TAG, "BiometricPrompt already visible, skipping duplicate request.")
            return
        }

        val executor = ContextCompat.getMainExecutor(activity)
        val identityPolicy = Android18IdentityCheckManager.getIdentityCheckPolicy(activity, packageName)
        val isIdentityCheck = identityPolicy.isEnforced

        // Issue fresh per-operation cryptographic challenge for Titan M3 StrongBox
        val challengeNonce = TitanM3SecurityManager.INSTANCE.generateAuthenticationChallenge()
        val cryptoObject = TitanM3SecurityManager.INSTANCE.createBiometricCryptoObject(activity, challengeNonce)

        var biometricPromptRef: BiometricPrompt? = null

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                authenticating.set(false)

                // Cryptographic validation: Verify Titan M3 StrongBox Hardware Key authorization
                val cryptoProof = TitanM3SecurityManager.INSTANCE.verifyBiometricCryptoResult(result, challengeNonce)
                if (isIdentityCheck || cryptoObject != null) {
                    if (!cryptoProof.isHardwareVerified) {
                        Log.e(TAG, "CRITICAL SECURITY ALERT: Titan M3 hardware auth token validation failed! Tampering suspected.")
                        IntruderManager.captureIntruderPhotoNow(activity.applicationContext, packageName)
                        onError(
                            BiometricPrompt.ERROR_SECURITY_UPDATE_REQUIRED,
                            "Hardware security token mismatch: ${cryptoProof.errorMessage}"
                        )
                        return
                    }
                    Log.i(TAG, "Titan M3 StrongBox Hardware Cryptographic match confirmed! Proof Hash: ${cryptoProof.verificationHash}")
                } else {
                    Log.i(TAG, "Biometric authentication confirmed for $packageName (Standard mode)")
                }

                LockStateTracker.clearFailedAttempts(packageName)
                LockStateTracker.onAppUnlocked(packageName)
                Log.i(TAG, "Authentication succeeded for $packageName (IdentityCheck: $isIdentityCheck)")
                onSuccess()
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                val attempts = LockStateTracker.recordFailedAttempt(packageName)
                Log.w(TAG, "Authentication failed for $packageName (attempt $attempts)")

                // Google Identity Check / Permanent Lockout: 5 consecutive failures lock device permanently
                if (attempts >= Android18IdentityCheckManager.IDENTITY_CHECK_MAX_FAILED_ATTEMPTS) {
                    Log.e(TAG, "Permanent Lockout Threshold Reached: $attempts attempts exceeded for $packageName! Triggering immediate camera trap.")
                    IntruderManager.captureIntruderPhotoNow(activity.applicationContext, packageName)
                    biometricPromptRef?.cancelAuthentication()
                    authenticating.set(false)
                    onError(BiometricPrompt.ERROR_LOCKOUT_PERMANENT, "Identity Check: $attempts failed biometric attempts. Device Locked.")
                    return
                }

                if (attempts >= 1) {
                    IntruderManager.captureIntruderPhoto(activity.applicationContext, packageName)
                }

                onFailure()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                authenticating.set(false)
                Log.w(TAG, "Authentication error ($errorCode): $errString for $packageName")

                // If Identity Check is active: NEVER allow PIN/Pattern fallback on cancellation or negative button
                if (isIdentityCheck) {
                    val failedCount = LockStateTracker.getFailedAttempts(packageName)
                    if (failedCount >= 1 || errorCode == BiometricPrompt.ERROR_LOCKOUT || errorCode == BiometricPrompt.ERROR_LOCKOUT_PERMANENT) {
                        Log.i(TAG, "Identity Check prompt dismissed or locked out after $failedCount failure(s). Firing camera trap.")
                        IntruderManager.captureIntruderPhotoNow(activity.applicationContext, packageName)
                    }
                    onError(errorCode, errString)
                    return
                }

                // Standard Mode: Allow user to fallback to device PIN / Pattern
                if (errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                    val attempts = LockStateTracker.getFailedAttempts(packageName)
                    if (attempts >= 1) {
                        IntruderManager.captureIntruderPhotoNow(activity.applicationContext, packageName)
                    }
                    showCredentialFallback(activity, appName, packageName, onSuccess, onFailure, onError)
                    return
                }

                val failedCount = LockStateTracker.getFailedAttempts(packageName)
                if (failedCount >= 1 || errorCode == BiometricPrompt.ERROR_LOCKOUT || errorCode == BiometricPrompt.ERROR_LOCKOUT_PERMANENT) {
                    Log.i(TAG, "BiometricPrompt dismissed after $failedCount failure(s) (errorCode: $errorCode). Firing camera trap!")
                    IntruderManager.captureIntruderPhotoNow(activity.applicationContext, packageName)
                }
                onError(errorCode, errString)
            }
        }

        val biometricPrompt = BiometricPrompt(activity, executor, callback)
        biometricPromptRef = biometricPrompt

        if (isIdentityCheck) {
            // Google Identity Check Mode: Strictly enforce BIOMETRIC_STRONG with ZERO PIN fallback
            val promptTitle = if (appName.isNotBlank()) "Unlock $appName" else "Identity Check"
            val promptInfo = Android18IdentityCheckManager.buildIdentityCheckPromptInfo(
                title = promptTitle,
                subtitle = "Biometrics Enabled",
                description = "Identity Check is on and requires a biometric",
                negativeButtonText = "Cancel"
            )

            // Identity Check mandates Titan M3 StrongBox hardware crypto object
            val activeCrypto = cryptoObject ?: TitanM3SecurityManager.INSTANCE.createBiometricCryptoObject(activity, challengeNonce)
            if (activeCrypto == null) {
                Log.e(TAG, "Identity Check requires Titan M3 hardware CryptoObject, but key initialization failed.")
                onError(BiometricPrompt.ERROR_HW_UNAVAILABLE, "Hardware security module unavailable for Identity Check")
                return
            }

            authenticating.set(true)
            try {
                biometricPrompt.authenticate(promptInfo, activeCrypto)
                Log.d(TAG, "Identity Check BiometricPrompt displayed for $packageName (StrongBox backed)")
                return
            } catch (e: Exception) {
                Log.w(TAG, "Identity Check BiometricPrompt failed: ${e.message}")
                authenticating.set(false)
                onError(-1, e.message ?: "Identity Check initialization failed")
                return
            }
        }

        // Standard Mode: Attempt hardware crypto prompt with PIN fallback
        if (cryptoObject != null) {
            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle(if (appName.isNotBlank()) "Unlock $appName" else "Unlock Application")
                .setSubtitle("Biometrics Enabled")
                .setDescription("Face Unlock or Fingerprint")
                .setConfirmationRequired(false)
                .setNegativeButtonText("Use PIN / Pattern")
                .build()

            authenticating.set(true)
            try {
                biometricPrompt.authenticate(promptInfo, cryptoObject)
                Log.d(TAG, "Hardware CryptoObject BiometricPrompt displayed for $packageName")
                return
            } catch (e: Exception) {
                Log.w(TAG, "Hardware CryptoObject authenticate failed, falling back to credentials: ${e.message}")
                authenticating.set(false)
            }
        }

        showCredentialFallback(activity, appName, packageName, onSuccess, onFailure, onError)
    }

    private fun showCredentialFallback(
        activity: FragmentActivity,
        appName: String,
        packageName: String,
        onSuccess: () -> Unit,
        onFailure: () -> Unit,
        onError: (Int, CharSequence) -> Unit
    ) {
        // Strict Google Identity Check defense: unconditionally prohibit device PIN/pattern fallback
        val identityPolicy = Android18IdentityCheckManager.getIdentityCheckPolicy(activity, packageName)
        if (identityPolicy.isEnforced) {
            Log.w(TAG, "Refusing credential fallback: Identity Check is strictly enforced for $packageName (PIN/Pattern prohibited).")
            authenticating.set(false)
            onError(
                BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                "Identity Check is active. Device PIN, pattern, or password fallback is strictly prohibited."
            )
            return
        }

        val executor = ContextCompat.getMainExecutor(activity)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                authenticating.set(false)
                LockStateTracker.onAppUnlocked(packageName)
                Log.i(TAG, "Credential fallback succeeded for $packageName")
                onSuccess()
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                val attempts = LockStateTracker.recordFailedAttempt(packageName)
                Log.w(TAG, "Credential fallback failed for $packageName (attempt $attempts)")
                IntruderManager.captureIntruderPhotoNow(activity.applicationContext, packageName)
                onFailure()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                authenticating.set(false)
                val attempts = LockStateTracker.getFailedAttempts(packageName)
                if (attempts >= 1 || errorCode == BiometricPrompt.ERROR_LOCKOUT || errorCode == BiometricPrompt.ERROR_LOCKOUT_PERMANENT) {
                    IntruderManager.captureIntruderPhotoNow(activity.applicationContext, packageName)
                }
                onError(errorCode, errString)
            }
        }

        val biometricPrompt = BiometricPrompt(activity, executor, callback)
        val promptInfo = Android18IdentityCheckManager.buildStandardCredentialPromptInfo(
            title = if (appName.isNotBlank()) "Unlock $appName" else "Unlock Application",
            subtitle = "Biometrics Enabled",
            description = "Enter device PIN, pattern, or password"
        )

        authenticating.set(true)
        try {
            biometricPrompt.authenticate(promptInfo)
        } catch (e: Exception) {
            authenticating.set(false)
            onError(-1, e.message ?: "Authentication initialization failed")
        }
    }
}
