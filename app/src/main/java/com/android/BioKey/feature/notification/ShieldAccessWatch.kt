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
    /** The record of the access going, until it's back: then it says from when to when. */
    private const val KEY_OPEN_RECORD = "open_record"

    @VisibleForTesting
    internal var hasAccess: (Context) -> Boolean = BioKeyNotificationShieldService::isNotificationAccessGranted

    /** Whether the shield is on but can't work: home's card. */
    fun shieldWithoutAccess(context: Context): Boolean =
        (SecurityVault.getInstance(context).isNotificationShieldEnabled() && !hasAccess(context)).also { lastWithoutAccess = it }

    /** Whether BioKey had notification access, as last looked at ([check]); null before the first look. */
    @Volatile var lastKnownAccess: Boolean? = null
        private set
    /** [shieldWithoutAccess] as last worked out, for a screen's first frame; null before the first time. */
    @Volatile var lastWithoutAccess: Boolean? = null
        private set

    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "biokey-shield-access").apply { isDaemon = true } }

    /** [check], off the calling thread. */
    fun checkSoon(context: Context) {
        val app = context.applicationContext
        worker.execute { runCatching { check(app) } }
    }

    @Synchronized
    fun check(context: Context) {
        val app = context.applicationContext
        val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val shieldOn = SecurityVault.getInstance(app).isNotificationShieldEnabled()
        val access = hasAccess(app)
        lastKnownAccess = access
        lastWithoutAccess = shieldOn && !access
        val had = p.getBoolean(KEY_HAD_ACCESS, false)
        when {
            shieldOn && access -> if (!had) {
                p.getLong(KEY_OPEN_RECORD, 0L).takeIf { it > 0L }?.let { ProtectionGaps.endEvent(app, it) }
                p.edit { putBoolean(KEY_HAD_ACCESS, true).remove(KEY_OPEN_RECORD) }
            }
            shieldOn && had -> {
                val seq = ProtectionGaps.recordEvent(app, ProtectionGaps.Kind.SHIELD_ACCESS_REVOKED)
                p.edit {
                    putBoolean(KEY_HAD_ACCESS, false)
                    if (seq != null) putLong(KEY_OPEN_RECORD, seq)
                }
            }
            // Switched off in BioKey: the owner's choice, nothing to tell.
            !shieldOn -> if (had || p.contains(KEY_OPEN_RECORD)) {
                // An open record ends here: from when access went to when hiding was switched off.
                p.getLong(KEY_OPEN_RECORD, 0L).takeIf { it > 0L }?.let { ProtectionGaps.endEvent(app, it) }
                p.edit { putBoolean(KEY_HAD_ACCESS, false).remove(KEY_OPEN_RECORD) }
            }
        }
    }
}
