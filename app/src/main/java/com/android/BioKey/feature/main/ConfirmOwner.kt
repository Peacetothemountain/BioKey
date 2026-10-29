package com.android.BioKey.feature.main

import android.app.Activity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import com.android.BioKey.core.security.SecurityEngine
import com.android.BioKey.ui.theme.BioKeyMessages

/**
 * Runs [action] once the owner has proved it's them with a face or fingerprint (the screen lock
 * where none is set up): for what removes protection or its record. If it doesn't go through,
 * BioKey says why (unless the owner just cancelled).
 */
internal fun confirmOwner(activity: Activity?, title: String, action: () -> Unit) {
    if (activity == null) return
    SecurityEngine.INSTANCE.confirmWithBiometric(
        activity,
        title,
        onRefused = { reason -> BioKeyMessages.show(reason, Icons.Default.ErrorOutline) },
        onConfirmed = action
    )
}
