package com.android.BioKey.feature.main

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import com.android.BioKey.R
import com.android.BioKey.core.security.BiometricEnrollment
import com.android.BioKey.core.security.LockStateTracker
import com.android.BioKey.core.security.OwnerPassphrase
import com.android.BioKey.core.security.ProtectionGaps
import com.android.BioKey.core.security.SecurityEngine
import com.android.BioKey.core.security.UnlockAttempts
import com.android.BioKey.ui.format.attemptTime
import com.android.BioKey.ui.theme.BioKeyMessages
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Runs [action] once the owner has proved it's them with a face or fingerprint: for what removes
 * protection or its record. Where none can be used, the owner's passphrase is asked for if it
 * stands in for them ("Face or fingerprint only" on, and one set: [PassphraseConfirm]), and
 * otherwise the screen lock where none is set up. If it doesn't go through, BioKey says why (unless
 * the owner just cancelled).
 */
internal fun confirmOwner(
    activity: Activity?,
    title: String,
    silencesRecords: Boolean = false,
    /** Nothing it does can't be undone or hides anything: the screen lock does when the sensor can't be used. */
    screenLockIfSensorUnusable: Boolean = false,
    action: () -> Unit
) {
    if (activity == null) return
    // Within a day of a face or fingerprint added or removed, the new one may be whoever made the
    // change: nothing that erases or silences BioKey's records, whoever asks.
    if (silencesRecords) {
        val until = UnlockAttempts.heldUntil(activity)
        if (until != null) {
            BioKeyMessages.show(heldRefused(activity, until), Icons.Default.ErrorOutline)
            return
        }
    }
    val generation = LockStateTracker.lockGeneration()
    val whenConfirmed = {
        // A face or fingerprint change made while the prompt was up is looked for now (not left
        // to the background look), off the main thread, so the action sees it.
        val app = activity.applicationContext
        appScope.launch {
            val until = withContext(Dispatchers.Default) {
                BiometricEnrollment.checkNow(app)
                if (silencesRecords) UnlockAttempts.heldUntil(app) else null
            }
            when {
                until != null -> BioKeyMessages.show(heldRefused(app, until), Icons.Default.ErrorOutline)
                activity.isFinishing || activity.isDestroyed ||
                    LockStateTracker.lockGeneration() != generation ||
                    !LockStateTracker.isOwnAppUnlocked() ->
                    BioKeyMessages.show(app.getString(R.string.confirm_try_again), Icons.Default.ErrorOutline)
                else -> action()
            }
        }
        Unit
    }
    // No face or fingerprint to ask for, where the passphrase stands in for them: it is asked for,
    // never the screen lock, which is all that someone who had removed the fingerprints would know.
    if (SecurityEngine.passphraseStandsIn(activity) && !SecurityEngine.hasStrongBiometric(activity)) {
        PassphraseConfirm.ask(activity, title, whenConfirmed)
        return
    }
    SecurityEngine.INSTANCE.confirmWithBiometric(
        activity,
        title,
        screenLockIfSensorUnusable = screenLockIfSensorUnusable,
        onRefused = { reason -> BioKeyMessages.show(reason, Icons.Default.ErrorOutline) },
        onConfirmed = whenConfirmed
    )
}

/**
 * The owner's passphrase asked for inside BioKey, in a face or fingerprint check's place
 * ([confirmOwner]): one question at a time, drawn by [PassphraseConfirmDialog] on BioKey's main
 * screen. Wrong tries are counted and recorded as on a lock screen.
 */
internal object PassphraseConfirm {

    /** What the dialog shows. */
    val state = PassphraseConfirmState()

    /** One question: what a right answer goes on to do, and the wrong tries it saw. */
    private class Asked(val whenConfirmed: () -> Unit) {
        var record: Long? = null
        val misses = mutableMapOf<Long, Int>()
    }

    private var asked: Asked? = null
    private val main = Handler(Looper.getMainLooper())
    private val checks = Executors.newSingleThreadExecutor { r -> Thread(r, "biokey-confirm-passphrase").apply { isDaemon = true } }

    fun ask(context: Context, title: String, whenConfirmed: () -> Unit) {
        asked = Asked(whenConfirmed)
        state.busy = false
        state.error = null
        val wait = OwnerPassphrase.waitRemainingMs(context)
        state.waitUntilElapsed = if (wait > 0L) SystemClock.elapsedRealtime() + wait else 0L
        state.title = title
    }

    /** Backed out of, or BioKey locked: a check still running for it confirms nothing. */
    fun dismiss() {
        asked = null
        state.title = null
        state.busy = false
        state.error = null
    }

    /** Checks what was typed, off the main thread, and goes on only on a match, for the question it was typed for. */
    fun submit(context: Context, typed: CharArray) {
        val current = asked ?: return typed.fill('\u0000')
        state.busy = true
        val app = context.applicationContext
        val brand = app.getString(R.string.brand_name)
        checks.execute {
            val outcome = try {
                OwnerPassphrase.verify(app, typed)
            } catch (_: Exception) {
                OwnerPassphrase.Outcome.Mismatch(0L)
            } finally {
                typed.fill('\u0000')
            }
            main.post {
                // Counted and recorded whatever became of the question meanwhile.
                when (outcome) {
                    is OwnerPassphrase.Outcome.Mismatch -> UnlockAttempts.onBiometricFailed(app, app.packageName, brand, current.record, passphrase = true)?.let { id ->
                        current.record = id
                        current.misses[id] = (current.misses[id] ?: 0) + 1
                    }
                    OwnerPassphrase.Outcome.Match ->
                        UnlockAttempts.onUnlocked(app, app.packageName, brand, biometric = false, ownMisses = current.misses, passphrase = true)
                    else -> Unit
                }
                if (asked !== current) return@post
                state.busy = false
                when (outcome) {
                    OwnerPassphrase.Outcome.Match -> {
                        dismiss()
                        // As a face or fingerprint check does: what's done in BioKey from here is the owner's own doing.
                        if (LockStateTracker.isOwnAppUnlocked()) SecurityEngine.INSTANCE.ownAppOpenedWithPassphrase()
                        current.whenConfirmed()
                    }
                    is OwnerPassphrase.Outcome.Mismatch -> {
                        state.error = app.getString(R.string.lock_passphrase_wrong)
                        if (outcome.waitMs > 0L) state.waitUntilElapsed = SystemClock.elapsedRealtime() + outcome.waitMs
                    }
                    is OwnerPassphrase.Outcome.Wait -> state.waitUntilElapsed = SystemClock.elapsedRealtime() + outcome.waitMs
                    // Removed meanwhile: nothing to ask for any more; the change is asked for again.
                    OwnerPassphrase.Outcome.NotSet -> {
                        dismiss()
                        BioKeyMessages.show(app.getString(R.string.confirm_try_again), Icons.Default.ErrorOutline)
                    }
                }
            }
        }
    }
}

/** Why something waits until [until]: a face or fingerprint change, or BioKey reset ([UnlockAttempts.heldBy]). */
internal fun heldRefused(context: Context, until: Long): String = context.getString(
    if (UnlockAttempts.heldBy(context) == ProtectionGaps.Kind.DATA_CLEARED) R.string.held_refused_reset else R.string.held_refused,
    attemptTime(context, until)
)
