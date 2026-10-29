package com.android.BioKey.feature.notification

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.core.content.edit
import com.android.BioKey.core.security.ProtectionGaps
import com.android.BioKey.core.security.SecurityVault

/**
 * Notification access taken away while "Hide notification content" is on: locked apps'
 * notifications show in full again, with no word from Android. Recorded like the other ways
 * protection stops ([ProtectionGaps.Kind.SHIELD_ACCESS_REVOKED]) and told at the next unlock;
 * home says so until it's back. Looked at whenever the phone is unlocked, BioKey opens, or the
 * shield's listener is unbound.
 */
object ShieldAccessWatch {

    private const val PREFS = "biokey_shield_access"
    /** The shield had its access, last time this looked: losing it now is news. */
    private const val KEY_HAD_ACCESS = "had_access"

    @VisibleForTesting
    internal var hasAccess: (Context) -> Boolean = BioKeyNotificationShieldService::isNotificationAccessGranted

    /** Whether the shield is on but can't work: home's card. */
    fun shieldWithoutAccess(context: Context): Boolean =
        SecurityVault.getInstance(context).isNotificationShieldEnabled() && !hasAccess(context)

    fun check(context: Context) {
        val app = context.applicationContext
        val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val shieldOn = SecurityVault.getInstance(app).isNotificationShieldEnabled()
        val access = hasAccess(app)
        val had = p.getBoolean(KEY_HAD_ACCESS, false)
        when {
            shieldOn && access -> if (!had) p.edit { putBoolean(KEY_HAD_ACCESS, true) }
            shieldOn && had -> {
                p.edit { putBoolean(KEY_HAD_ACCESS, false) }
                ProtectionGaps.recordEvent(app, ProtectionGaps.Kind.SHIELD_ACCESS_REVOKED)
            }
            // Switched off in BioKey: the owner's choice, nothing to tell.
            !shieldOn && had -> p.edit { putBoolean(KEY_HAD_ACCESS, false) }
        }
    }
}
