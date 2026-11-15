package com.android.BioKey.core.security

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
import android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.util.Log
import androidx.annotation.VisibleForTesting
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
        /**
         * BioKey's own, not Android's: the owner chose "Use passphrase" on the prompt, or the prompt
         * wasn't shown because the passphrase is the only way in just now ([passphraseStandsIn]).
         */
        const val ERROR_USE_PASSPHRASE = -1001
        /** BioKey's own: Android reported a match the phone's hardware didn't back up ([UnlockProof]). */
        const val ERROR_UNPROVEN = -1002


        fun isUserCancel(code: Int) = code == ERROR_USER_CANCELED || code == ERROR_CANCELED ||
            code == ERROR_NEGATIVE_BUTTON

        /**
         * BiometricManager.BIOMETRIC_ERROR_LOCKOUT, which Android keeps to itself but lists among
         * canAuthenticate's answers: too many misses, for now. Only a face or fingerprint that is
         * set up can be locked out, and the prompt says so itself, so it reads as one that works:
         * a few wrong fingers must neither look like every fingerprint removed (a record, and a
         * day's hold) nor let the screen lock in under "Face or fingerprint only".
         */
        private const val STATUS_LOCKED_OUT = 7

        /** Android's answer about a strong face or fingerprint; tests give their own. */
        @androidx.annotation.VisibleForTesting
        @Volatile
        internal var canAuthenticateStrong: (Context) -> Int? = { context ->
            context.getSystemService(BiometricManager::class.java)?.canAuthenticate(BIOMETRIC_STRONG)
        }

        private fun strongStatus(context: Context): Int? = runCatching { canAuthenticateStrong(context) }.getOrNull()
            .let { if (it == STATUS_LOCKED_OUT) BiometricManager.BIOMETRIC_SUCCESS else it }.also { lastStrongStatus = it }

        /**
         * Tests only: handed each prompt as it would be started (what it was armed with, and its
         * callback) in place of Android's, which a test can't answer. Null outside tests.
         */
        @androidx.annotation.VisibleForTesting
        @Volatile
        internal var promptsForTests: ((UnlockProof.Armed?, BiometricPrompt.AuthenticationCallback) -> Unit)? = null

        /** The last answer Android gave ([strongStatus]): for a screen's first frame, before it asks again. */
        @Volatile private var lastStrongStatus: Int? = null

        /** [hasStrongBiometric] as last asked, without asking; null if never asked in this process. */
        fun lastKnownStrongBiometric(): Boolean? = lastStrongStatus?.let { it == BiometricManager.BIOMETRIC_SUCCESS }

        /** [hasEnrolledStrongBiometric] as last asked, without asking; null if never asked in this process. */
        fun lastKnownEnrolledStrongBiometric(): Boolean? = lastStrongStatus?.let(::isEnrolled)

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
        fun hasEnrolledStrongBiometric(context: Context): Boolean = isEnrolled(strongStatus(context))

        /**
         * Set up, usable or not: a sensor fault, an update pending, or (Android 15+) "Use for apps"
         * switched off in Android's settings, which mustn't be a way round BioKey's records and gates.
         */
        @SuppressLint("InlinedApi")
        private fun isEnrolled(status: Int?): Boolean =
            status == BiometricManager.BIOMETRIC_SUCCESS || status == BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE ||
                status == BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED || status == BiometricManager.BIOMETRIC_ERROR_NOT_ENABLED_FOR_APPS

        /**
         * Faces and fingerprints are set up but switched off for apps in Android's settings
         * (Android 15 and later, "Use for apps"). Unlike a sensor that can't be used, that is
         * somebody's doing, with the screen lock: it is recorded as what it is.
         */
        @SuppressLint("InlinedApi")
        fun strongSwitchedOffForApps(context: Context): Boolean =
            strongStatus(context) == BiometricManager.BIOMETRIC_ERROR_NOT_ENABLED_FOR_APPS

        /** [hasEnrolledStrongBiometric], as [ownerProven] asks it; tests set their own. */
        @androidx.annotation.VisibleForTesting
        @Volatile
        internal var enrolledForProof: (Context) -> Boolean = { hasEnrolledStrongBiometric(it) }

        /**
         * What's done in BioKey now is the owner's own doing, as far as BioKey can tell: it was
         * opened with a face or fingerprint, or the phone has none set up (then the screen lock is
         * all there is). Otherwise it may be someone who only knows the PIN.
         */
        fun ownerProven(context: Context, openedWithBiometric: Boolean = INSTANCE.ownAppOpenedWithBiometric): Boolean =
            // Within a day of a face or fingerprint added or removed, nothing proves the owner: the
            // new one may be whoever made the change, and with all of them removed, the screen lock
            // is all anyone needs ([UnlockAttempts.holding]).
            !UnlockAttempts.holding(context) && (!enrolledForProof(context) || openedWithBiometric)

        /**
         * "Face or fingerprint only" is on and can be honoured: then prompts don't offer the screen
         * lock. With no strong biometric set up (any more), the screen lock is allowed again, or
         * the owner would be locked out of their own apps.
         */
        fun biometricOnly(context: Context): Boolean =
            SecurityVault.getInstance(context).isBiometricOnly() && hasStrongBiometric(context)

        /**
         * "Face or fingerprint only" is on and the owner has set a passphrase: that, not the screen
         * lock, is the other way into a locked app. Offered on the prompt, and what is asked for
         * when no face or fingerprint can be used at all.
         */
        fun passphraseStandsIn(context: Context): Boolean =
            SecurityVault.getInstance(context).isBiometricOnly() && OwnerPassphrase.isSet(context)
    }

    private class Attempt(val packageName: String, val host: WeakReference<Activity>, val cancel: CancellationSignal) {
        /** The failed-unlock record this prompt's misses went to, last ([UnlockAttempts]). Main thread only. */
        var record: Long? = null
        /** How many of each record's misses this prompt saw: its own, if a face or fingerprint then matches. */
        val ownMisses = mutableMapOf<Long, Int>()
    }

    /** Tests: a prompt up for [packageName] (no window of its own: the phone's display), or none with null. */
    @VisibleForTesting
    internal fun setPromptForTests(packageName: String?) {
        if (packageName == null) activeConfirmation = null
        active = packageName?.let { Attempt(it, WeakReference(null), CancellationSignal()) }
    }

    @Volatile private var active: Attempt? = null
        set(value) {
            // The prompt coming and going is what the engines are told, for a replay (EngineTrace).
            if (value !== field) EngineTrace.record(if (value != null) "p.up" else "p.down", value?.packageName ?: field?.packageName)
            field = value
        }

    @Volatile private var activeConfirmation: Pair<WeakReference<Activity>, CancellationSignal>? = null

    fun isAuthenticating(): Boolean = active != null || activeConfirmation != null

    fun isConfirmingOwnerFor(host: Activity): Boolean = activeConfirmation?.first?.get() === host

    fun isAuthenticatingFor(packageName: String): Boolean = active?.packageName == packageName

    /** The display of the screen showing the running prompt (a lock screen, BioKey), if one is and it is known. */
    fun promptDisplayId(): Int? = runCatching { (active?.host ?: activeConfirmation?.first)?.get()?.display?.displayId }.getOrNull()

    /** Cancels the running prompt (only if it belongs to [forPackage], when given). */
    fun resetAuthenticatingState(forPackage: String? = null) {
        if (forPackage == null) {
            activeConfirmation?.let {
                activeConfirmation = null
                runCatching { it.second.cancel() }
            }
        }
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
        activeConfirmation?.takeIf { it.first.get() === host }?.let {
            activeConfirmation = null
            runCatching { it.second.cancel() }
        }
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
    fun confirmWithBiometric(
        activity: Activity,
        title: String,
        onRefused: (String) -> Unit = {},
        /** For what erases or hides nothing (sharing the log): the screen lock when the sensor can't be used. */
        screenLockIfSensorUnusable: Boolean = false,
        /**
         * A match the phone's hardware didn't prove ([UnlockProof]), where the owner's passphrase
         * stands in: the caller asks for that instead. Without it, [onRefused] says to try again.
         */
        onUnproven: (() -> Unit)? = null,
        onConfirmed: () -> Unit
    ) {
        val app = activity.applicationContext
        val strong = hasStrongBiometric(activity)
        // Where the passphrase stands in for a face or fingerprint, the screen lock never does:
        // whoever asks is sent to the passphrase first ([ERROR_USE_PASSPHRASE]'s counterpart inside
        // BioKey), and nothing here falls back to it.
        if (!strong && passphraseStandsIn(activity)) {
            onRefused(activity.getString(R.string.prompt_biometric_unavailable))
            return
        }
        // Set up but unusable right now (a fault, an update pending): not the screen lock instead.
        // Nothing here needs doing this minute, and the PIN mustn't be enough to erase records.
        if (!strong && hasEnrolledStrongBiometric(activity) && !screenLockIfSensorUnusable) {
            onRefused(activity.getString(R.string.prompt_biometric_unavailable))
            return
        }
        val brand = activity.getString(R.string.brand_name)
        var record: Long? = null
        val ownMisses = mutableMapOf<Long, Int>()
        val cancel = CancellationSignal()
        resetAuthenticatingStateFor(activity)
        val entry = WeakReference(activity) to cancel
        activeConfirmation = entry
        val proof = UnlockProof.arm(app)
        // As on a lock screen: where the passphrase stands in and there is no proof to ask for,
        // the passphrase is asked for, not a match taken on Android's word.
        if (proof == null && onUnproven != null && passphraseStandsIn(app)) {
            if (activeConfirmation === entry) activeConfirmation = null
            UnlockProof.saidInstead(app)
            onUnproven()
            return
        }
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                // An answer for a check since cancelled (its screen left, another asked in its
                // place) confirms nothing and goes on to nothing.
                val current = activeConfirmation === entry
                if (current) activeConfirmation = null
                // A match with nothing from the phone's hardware behind it confirms nothing.
                if (!UnlockProof.confirmed(app, proof)) {
                    if (!current) return
                    if (onUnproven != null && passphraseStandsIn(app)) onUnproven()
                    else onRefused(activity.getString(R.string.prompt_unproven))
                    return
                }
                // Misses this prompt saw, then a match: the owner's, gone like on any prompt.
                UnlockAttempts.onUnlocked(
                    app, activity.packageName, brand,
                    biometric = result.authenticationType == BiometricPrompt.AUTHENTICATION_RESULT_TYPE_BIOMETRIC,
                    ownMisses = ownMisses
                )
                // Told even for a check already cancelled, as on a lock screen: a match is a match.
                if (!current) return
                if (result.authenticationType == BiometricPrompt.AUTHENTICATION_RESULT_TYPE_BIOMETRIC &&
                    LockStateTracker.isOwnAppUnlocked()
                ) {
                    ownAppOpenedWithBiometric = true
                }
                onConfirmed()
            }

            override fun onAuthenticationFailed() {
                UnlockAttempts.onBiometricFailed(app, activity.packageName, brand, record)?.let { id ->
                    record = id
                    ownMisses[id] = (ownMisses[id] ?: 0) + 1
                }
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (activeConfirmation === entry) activeConfirmation = null
                if (errorCode == BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT || errorCode == ERROR_LOCKOUT_PERMANENT) {
                    UnlockAttempts.onLockedOut(app, activity.packageName, brand, record)
                }
                if (!isUserCancel(errorCode)) onRefused(if (errorCode == ERROR_LOCKOUT_PERMANENT) activity.getString(R.string.prompt_lockout_permanent) else errString.toString())
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
                .authenticate(proof, cancel, activity, callback)
        } catch (e: Exception) {
            if (activeConfirmation === entry) activeConfirmation = null
            onRefused(activity.getString(R.string.prompt_unavailable))
        }
    }

    /**
     * How BioKey itself was last opened: true with a face or fingerprint. A pause made after it
     * was opened with the screen lock is recorded ([ProtectionGaps.Kind.CHANGED_WITH_SCREEN_LOCK]).
     */
    @Volatile var ownAppOpenedWithBiometric = false
        @androidx.annotation.VisibleForTesting internal set

    /**
     * BioKey was opened with the owner's passphrase: as much the owner's own doing as a face or
     * fingerprint ([ownerProven]), since knowing the phone's screen lock isn't knowing it.
     */
    fun ownAppOpenedWithPassphrase() {
        ownAppOpenedWithBiometric = true
    }

    /** BioKey locked itself: whatever it was opened with no longer proves anything ([ownerProven]). */
    fun forgetOwnProof() {
        ownAppOpenedWithBiometric = false
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
        promptTitle: String? = null,
        /** Whether this screen can ask for the owner's passphrase itself ([ERROR_USE_PASSPHRASE]). */
        passphraseEntry: Boolean = false
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
        val passphrase = passphraseEntry && passphraseStandsIn(activity)
        BiometricEnrollment.checkSoon(activity)
        // "Face or fingerprint only" with a passphrase set, and no face or fingerprint that can be
        // used right now: the passphrase is asked for, not the screen lock, which is exactly what
        // someone who had removed the fingerprints would know.
        if (passphrase && !faceOrFingerOnly) {
            active = null
            onError(ERROR_USE_PASSPHRASE, "")
            return
        }
        // "Face or fingerprint only" is on, but the face or fingerprint can't be used right now (a
        // sensor fault, an update pending, or switched off for apps in Android's settings): the
        // screen lock is allowed so the owner isn't locked out, and that's recorded, as which it was.
        if (!faceOrFingerOnly && SecurityVault.getInstance(activity).isBiometricOnly() && hasEnrolledStrongBiometric(activity)) {
            ProtectionGaps.recordOncePerProcess(
                activity,
                if (strongSwitchedOffForApps(activity)) ProtectionGaps.Change.BIOMETRIC_OFF_FOR_APPS else ProtectionGaps.Change.BIOMETRIC_UNAVAILABLE
            )
        } else if (faceOrFingerOnly) {
            // Usable again: switched off (or failing) another time is recorded another time.
            ProtectionGaps.passed(ProtectionGaps.Change.BIOMETRIC_OFF_FOR_APPS, ProtectionGaps.Change.BIOMETRIC_UNAVAILABLE)
        }

        val proof = UnlockProof.arm(app)
        // Where the passphrase stands in, nothing opens on Android's word alone: with no proof to
        // ask the phone's hardware for (its key can't be made or used), the passphrase is asked for.
        if (proof == null && passphrase) {
            active = null
            UnlockProof.saidInstead(app)
            onError(ERROR_USE_PASSPHRASE, "")
            return
        }
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                // A match with nothing from the phone's hardware behind it opens nothing.
                if (!UnlockProof.confirmed(app, proof)) {
                    if (active !== attempt) return
                    active = null
                    onError(ERROR_UNPROVEN, activity.getString(R.string.prompt_unproven))
                    return
                }
                // Only the prompt still asked for says what opened BioKey: not one since superseded.
                if (packageName == app.packageName && active === attempt) {
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
                        if (passphrase) {
                            // In Cancel's place (Back still cancels): the other way in.
                            setOtherWayIn(activity.getString(R.string.prompt_use_passphrase), activity) {
                                callback.onAuthenticationError(ERROR_USE_PASSPHRASE, "")
                            }
                        } else {
                            setNegativeButton(activity.getString(R.string.prompt_cancel), activity.mainExecutor) { _, _ ->
                                callback.onAuthenticationError(ERROR_NEGATIVE_BUTTON, "Cancel")
                            }
                        }
                    } else {
                        setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
                    }
                }
                .setConfirmationRequired(false)
                .build()
                .authenticate(proof, attempt.cancel, activity, callback)
            LatencyMarks.mark("prompt", packageName)
        } catch (e: Exception) {
            Log.e(TAG, "Failed starting BiometricPrompt: ${e.javaClass.simpleName}")
            if (active === attempt) active = null
            onError(ERROR_HW_UNAVAILABLE, activity.getString(R.string.prompt_unavailable))
        }
    }

    /** Starts the prompt, with [proof]'s challenge for the phone's hardware to sign if there is one. */
    private fun BiometricPrompt.authenticate(
        proof: UnlockProof.Armed?,
        cancel: CancellationSignal,
        activity: Activity,
        callback: BiometricPrompt.AuthenticationCallback
    ) {
        promptsForTests?.let { return it(proof, callback) }
        if (proof != null) authenticate(proof.crypto, cancel, activity.mainExecutor, callback)
        else authenticate(cancel, activity.mainExecutor, callback)
    }

    /**
     * Another way in, where the prompt's Cancel would be. From Android 16 QPR2 the prompt has a
     * place of its own for one (a "fallback option", shown with a key icon); before that it is the
     * prompt's one button, a use Android's own documentation gives it.
     */
    private fun BiometricPrompt.Builder.setOtherWayIn(text: String, activity: Activity, onChosen: () -> Unit) {
        if (Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1) {
            val added = runCatching {
                addFallbackOption(text, BiometricManager.ICON_TYPE_PASSWORD, activity.mainExecutor) { _, _ -> onChosen() }
            }.isSuccess
            if (added) return
        }
        setNegativeButton(text, activity.mainExecutor) { _, _ -> onChosen() }
    }
}
