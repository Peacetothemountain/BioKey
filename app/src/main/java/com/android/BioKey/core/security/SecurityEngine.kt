package com.android.BioKey.core.security

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
import android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.util.Log
import com.android.BioKey.R
import com.android.BioKey.core.diagnostics.EngineTrace
import com.android.BioKey.core.diagnostics.LatencyMarks
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
        /** The prompt's Cancel, when it has one (face or fingerprint only): Android's code 13. */
        const val ERROR_NEGATIVE_BUTTON = 13

        /** What every lock tells the user after [ERROR_LOCKOUT_PERMANENT]. */
        const val LOCKOUT_PERMANENT_MESSAGE =
            "Too many attempts. Lock your phone with the power button, then unlock it with your PIN, pattern or password to use biometrics again."

        fun isUserCancel(code: Int) = code == ERROR_USER_CANCELED || code == ERROR_CANCELED ||
            code == ERROR_NEGATIVE_BUTTON

        private fun strongStatus(context: Context): Int? = runCatching {
            context.getSystemService(BiometricManager::class.java)?.canAuthenticate(BIOMETRIC_STRONG)
        }.getOrNull()

        /**
         * A strong (Class 3) face or fingerprint can be used right now: what prompts and "Face or
         * fingerprint only" go by. A sensor unavailable or awaiting an update can't be, so the
         * screen lock is allowed then, or the owner would be locked out of everything.
         */
        fun hasStrongBiometric(context: Context): Boolean = strongStatus(context) == BiometricManager.BIOMETRIC_SUCCESS

        /**
         * A strong face or fingerprint is set up, usable or not: what records of changes go by. A
         * sensor briefly unavailable still has its faces and fingerprints, and mustn't read as
         * their removal.
         */
        @SuppressLint("InlinedApi")
        fun hasEnrolledStrongBiometric(context: Context): Boolean = strongStatus(context).let {
            it == BiometricManager.BIOMETRIC_SUCCESS || it == BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE ||
                it == BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED ||
                // Android 15+: "Use for apps" switched off in Android's settings. Still set up: that
                // switch mustn't be a way round BioKey's records and gates.
                it == BiometricManager.BIOMETRIC_ERROR_NOT_ENABLED_FOR_APPS
        }

        /**
         * What's done in BioKey now is the owner's own doing, as far as BioKey can tell: it was
         * opened with a face or fingerprint, or the phone has none set up (then the screen lock is
         * all there is). Otherwise it may be someone who only knows the PIN.
         */
        fun ownerProven(context: Context): Boolean =
            INSTANCE.ownAppOpenedWithBiometric || !hasEnrolledStrongBiometric(context)

        /**
         * "Face or fingerprint only" is on and can be honoured: then prompts don't offer the screen
         * lock. With no strong biometric set up (any more), the screen lock is allowed again, or
         * the owner would be locked out of their own apps.
         */
        fun biometricOnly(context: Context): Boolean =
            SecurityVault.getInstance(context).isBiometricOnly() && hasStrongBiometric(context)
    }

    private class Attempt(val packageName: String, val host: WeakReference<Activity>, val cancel: CancellationSignal) {
        /** The failed-unlock record this prompt's misses went to, last ([UnlockAttempts]). Main thread only. */
        var record: Long? = null
        /** How many of each record's misses this prompt saw: its own, if a face or fingerprint then matches. */
        val ownMisses = mutableMapOf<Long, Int>()
    }

    @Volatile private var active: Attempt? = null
        set(value) {
            // The prompt coming and going is what the engines are told, for a replay (EngineTrace).
            if (value !== field) EngineTrace.record(if (value != null) "p.up" else "p.down", value?.packageName ?: field?.packageName)
            field = value
        }

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
     * A face or fingerprint before something that removes protection or its record (clearing
     * security activity, switching alerts or the log off, restoring a backup that would): someone
     * who only knows the screen lock can't do it. With no strong face or fingerprint set up, the
     * screen lock is asked for instead (and the removal itself is recorded, [BiometricEnrollment]),
     * never nothing. Misses here count as failed unlocks of BioKey; [onRefused] says why it didn't
     * go through, unless the owner just cancelled.
     */
    fun confirmWithBiometric(activity: Activity, title: String, onRefused: (String) -> Unit = {}, onConfirmed: () -> Unit) {
        val app = activity.applicationContext
        val strong = hasStrongBiometric(activity)
        // Set up but unusable right now (a fault, an update pending): not the screen lock instead.
        // Nothing here needs doing this minute, and the PIN mustn't be enough to erase records.
        if (!strong && hasEnrolledStrongBiometric(activity)) {
            onRefused(activity.getString(R.string.prompt_biometric_unavailable))
            return
        }
        val brand = activity.getString(R.string.brand_name)
        var record: Long? = null
        val ownMisses = mutableMapOf<Long, Int>()
        val cancel = CancellationSignal()
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                // Misses this prompt saw, then a match: the owner's, gone like on any prompt.
                UnlockAttempts.onUnlocked(
                    app, activity.packageName, brand,
                    biometric = result.authenticationType == BiometricPrompt.AUTHENTICATION_RESULT_TYPE_BIOMETRIC,
                    ownMisses = ownMisses
                )
                onConfirmed()
            }

            override fun onAuthenticationFailed() {
                UnlockAttempts.onBiometricFailed(app, activity.packageName, brand, record)?.let { id ->
                    record = id
                    ownMisses[id] = (ownMisses[id] ?: 0) + 1
                }
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (errorCode == BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT || errorCode == ERROR_LOCKOUT_PERMANENT) {
                    UnlockAttempts.onLockedOut(app, activity.packageName, brand, record)
                }
                if (!isUserCancel(errorCode)) onRefused(if (errorCode == ERROR_LOCKOUT_PERMANENT) LOCKOUT_PERMANENT_MESSAGE else errString.toString())
            }
        }
        try {
            BiometricPrompt.Builder(activity)
                .setTitle(title)
                .apply {
                    if (strong) {
                        setSubtitle(activity.getString(R.string.prompt_use_biometric))
                        setAllowedAuthenticators(BIOMETRIC_STRONG)
                        setNegativeButton(activity.getString(R.string.prompt_cancel), activity.mainExecutor) { _, _ ->
                            callback.onAuthenticationError(ERROR_NEGATIVE_BUTTON, "")
                        }
                    } else {
                        setSubtitle(activity.getString(R.string.prompt_verify))
                        setAllowedAuthenticators(DEVICE_CREDENTIAL)
                    }
                }
                .setConfirmationRequired(false)
                .build()
                .authenticate(cancel, activity.mainExecutor, callback)
        } catch (e: Exception) {
            onRefused(activity.getString(R.string.prompt_unavailable))
        }
    }

    /**
     * How BioKey itself was last opened: true with a face or fingerprint. A pause made after it
     * was opened with the screen lock is recorded ([ProtectionGaps.Kind.CHANGED_WITH_SCREEN_LOCK]).
     */
    @Volatile var ownAppOpenedWithBiometric = false
        private set

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
        val app = activity.applicationContext
        val name = appName.ifBlank { activity.getString(R.string.brand_name) }
        val faceOrFingerOnly = biometricOnly(activity)
        BiometricEnrollment.checkSoon(activity)
        // "Face or fingerprint only" is on, but the face or fingerprint can't be used right now (a
        // sensor fault, an update pending): the screen lock is allowed so the owner isn't locked out,
        // and that's recorded.
        if (!faceOrFingerOnly && SecurityVault.getInstance(activity).isBiometricOnly() && hasEnrolledStrongBiometric(activity)) {
            ProtectionGaps.recordOncePerProcess(activity, "Face or fingerprint unavailable, so the screen lock was allowed")
        }

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                if (packageName == app.packageName) {
                    ownAppOpenedWithBiometric = result.authenticationType == BiometricPrompt.AUTHENTICATION_RESULT_TYPE_BIOMETRIC
                }
                // Told even for a prompt already superseded (a fold restarted it): a match is a match.
                // A face or fingerprint proves this prompt's misses were the owner's; the screen lock doesn't.
                UnlockAttempts.onUnlocked(
                    app, packageName, name,
                    biometric = result.authenticationType == BiometricPrompt.AUTHENTICATION_RESULT_TYPE_BIOMETRIC,
                    ownMisses = attempt.ownMisses
                )
                if (active !== attempt) return
                active = null
                onSuccess()
            }

            // Each miss is kept as it happens: a prompt can go without an error (its screen
            // killed), and the misses before it are the ones that matter.
            override fun onAuthenticationFailed() {
                UnlockAttempts.onBiometricFailed(app, packageName, name, attempt.record)?.let { id ->
                    attempt.record = id
                    attempt.ownMisses[id] = (attempt.ownMisses[id] ?: 0) + 1
                }
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (errorCode == BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT || errorCode == ERROR_LOCKOUT_PERMANENT) {
                    UnlockAttempts.onLockedOut(app, packageName, name, attempt.record)
                }
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
                .setTitle(promptTitle ?: activity.getString(R.string.prompt_unlock, name))
                .setSubtitle(activity.getString(if (faceOrFingerOnly) R.string.prompt_use_biometric else R.string.prompt_verify))
                .apply {
                    if (faceOrFingerOnly) {
                        // No "Use PIN": someone who knows the screen lock can't use it here.
                        setAllowedAuthenticators(BIOMETRIC_STRONG)
                        setNegativeButton(activity.getString(R.string.prompt_cancel), activity.mainExecutor) { _, _ ->
                            callback.onAuthenticationError(ERROR_NEGATIVE_BUTTON, "Cancel")
                        }
                    } else {
                        setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
                    }
                }
                .setConfirmationRequired(false)
                .build()
                .authenticate(attempt.cancel, activity.mainExecutor, callback)
            LatencyMarks.mark("prompt", packageName)
        } catch (e: Exception) {
            Log.e(TAG, "Failed starting BiometricPrompt: ${e.javaClass.simpleName}")
            if (active === attempt) active = null
            onError(ERROR_HW_UNAVAILABLE, "Authentication unavailable")
        }
    }
}
