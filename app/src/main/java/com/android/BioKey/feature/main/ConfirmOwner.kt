package com.android.BioKey.feature.main

import android.app.Activity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import com.android.BioKey.R
import com.android.BioKey.core.security.BiometricEnrollment
import com.android.BioKey.core.security.SecurityEngine
import com.android.BioKey.core.security.UnlockAttempts
import com.android.BioKey.ui.theme.BioKeyMessages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Runs [action] once the owner has proved it's them with a face or fingerprint (the screen lock
 * where none is set up): for what removes protection or its record. If it doesn't go through,
 * BioKey says why (unless the owner just cancelled).
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
            BioKeyMessages.show(activity.getString(R.string.held_refused, attemptTime(activity, until)), Icons.Default.ErrorOutline)
            return
        }
    }
    SecurityEngine.INSTANCE.confirmWithBiometric(
        activity,
        title,
        screenLockIfSensorUnusable = screenLockIfSensorUnusable,
        onRefused = { reason -> BioKeyMessages.show(reason, Icons.Default.ErrorOutline) },
        onConfirmed = {
            // A face or fingerprint change made while the prompt was up is looked for now (not left
            // to the background look), off the main thread, so the action sees it.
            val app = activity.applicationContext
            appScope.launch {
                val until = withContext(Dispatchers.Default) {
                    BiometricEnrollment.checkNow(app)
                    if (silencesRecords) UnlockAttempts.heldUntil(app) else null
                }
                when {
                    until != null -> BioKeyMessages.show(app.getString(R.string.held_refused, attemptTime(app, until)), Icons.Default.ErrorOutline)
                    activity.isDestroyed -> BioKeyMessages.show(app.getString(R.string.confirm_try_again), Icons.Default.ErrorOutline)
                    else -> action()
                }
            }
        }
    )
}
