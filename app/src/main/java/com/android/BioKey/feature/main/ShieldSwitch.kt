package com.android.BioKey.feature.main

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsOff
import com.android.BioKey.R
import com.android.BioKey.core.security.ProtectionGaps
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.feature.notification.ShieldAccessWatch
import com.android.BioKey.ui.theme.BioKeyMessages

/**
 * Hiding switched off, from home or Settings alike: recorded from a screen-lock session, and the
 * record of its access going (if open) ends now. Undo switches it back on and reopens that record;
 * the switch-off's own record stays, since notifications may have been read meanwhile.
 */
internal fun switchShieldOff(context: Context, vault: SecurityVault, onUndo: () -> Unit = {}) {
    val res = context.resources
    vault.setNotificationShield(false)
    ProtectionGaps.recordIfScreenLock(context, ProtectionGaps.Change.SHIELD_OFF)
    ShieldAccessWatch.checkSoon(context)
    BioKeyMessages.show(
        res.getString(R.string.home_shield_turned_off), Icons.Default.NotificationsOff,
        actionLabel = res.getString(R.string.common_undo)
    ) {
        vault.setNotificationShield(true)
        ShieldAccessWatch.undoSwitchOffSoon(context)
        onUndo()
    }
}
