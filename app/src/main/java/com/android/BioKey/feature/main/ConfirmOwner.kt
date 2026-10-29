package com.android.BioKey.feature.main

import android.app.Activity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import com.android.BioKey.R
import com.android.BioKey.core.security.SecurityEngine
import com.android.BioKey.core.security.UnlockAttempts
import com.android.BioKey.ui.theme.BioKeyMessages

/**
 * Runs [action] once the owner has proved it's them with a face or fingerprint (the screen lock
 * where none is set up): for what removes protection or its record. If it doesn't go through,
 * BioKey says why (unless the owner just cancelled).
 */
internal fun confirmOwner(activity: Activity?, title: String, silencesRecords: Boolean = false, action: () -> Unit) {
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
        onRefused = { reason -> BioKeyMessages.show(reason, Icons.Default.ErrorOutline) },
        onConfirmed = {
            // A face or fingerprint change recorded while the prompt was up refuses it too.
            val until = if (silencesRecords) UnlockAttempts.heldUntil(activity) else null
            if (until != null) {
                BioKeyMessages.show(activity.getString(R.string.held_refused, attemptTime(activity, until)), Icons.Default.ErrorOutline)
            } else {
                action()
            }
        }
    )
}
