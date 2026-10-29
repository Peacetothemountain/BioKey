package com.android.BioKey.feature.notification

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.android.BioKey.core.diagnostics.DiagnosticLog
import com.android.BioKey.core.security.ProtectionGaps
import com.android.BioKey.core.security.SecurityVault
import java.util.concurrent.Future

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

    /**
     * Hiding switched off: looked at now, off the calling thread. The result is the record that
     * switch-off ended (its access having gone), if any: what its Undo reopens, and nothing else.
     */
    fun switchOffSoon(context: Context): Future<Long?> {
        val app = context.applicationContext
        return worker.submit<Long?> {
            // A look queued before this one may already have ended it: then it's the one that did.
            val ended = runCatching { check(app) }
                .onFailure { DiagnosticLog.log("Notification access check failed: ${it.javaClass.simpleName}") }
                .getOrNull()
            // Taken either way: left behind, a later switch-off's Undo could reopen it.
            val stashed = endedBySwitchOff
            endedBySwitchOff = null
            ended ?: stashed
        }
    }

    /**
     * Hiding switched back on (Undo) still without access: the record its switch-off [ended] goes
     * on, as if it had never been switched off. Then [onDone], on the main thread, with whether
     * hiding is now on without access.
     */
    fun undoSwitchOffSoon(context: Context, ended: Future<Long?>, onDone: (withoutAccess: Boolean) -> Unit = {}) {
        val app = context.applicationContext
        worker.execute {
            runCatching {
                synchronized(this) {
                    // On again here, after the switch-off's own look: in order whatever was queued.
                    SecurityVault.getInstance(app).setNotificationShield(true)
                    val seq = ended.get()
                    if (seq != null && !hasAccess(app)) {
                        ProtectionGaps.reopenEvent(app, seq)
                        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putLong(KEY_OPEN_RECORD, seq) }
                    }
                    check(app)
                }
            }.onFailure { DiagnosticLog.log("Undoing hiding off failed: ${it.javaClass.simpleName}") }
            val withoutAccess = lastWithoutAccess ?: false
            ContextCompat.getMainExecutor(app).execute { onDone(withoutAccess) }
        }
    }

    /** Waits for the looks already queued ([checkSoon], [switchOffSoon], [undoSwitchOffSoon]). */
    @VisibleForTesting
    internal fun awaitForTests() {
        worker.submit {}.get()
    }

    /**
     * [check], on the one thread every look runs on (in order with [switchOffSoon] and its Undo):
     * returns when it's done, for callers that wait.
     */
    fun checkSoon(context: Context): Future<*> {
        val app = context.applicationContext
        return worker.submit {
            runCatching { check(app) }.onFailure { DiagnosticLog.log("Notification access check failed: ${it.javaClass.simpleName}") }
        }
    }

    /** The record the last switch-off ended, until its Undo (or a switch-on) takes it. Under [check]'s lock. */
    private var endedBySwitchOff: Long? = null

    /** Looks at the shield and its access, records what changed; returns the record it ended by hiding being off, if any. */
    @Synchronized
    fun check(context: Context): Long? {
        val app = context.applicationContext
        val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val shieldOn = SecurityVault.getInstance(app).isNotificationShieldEnabled()
        val access = hasAccess(app)
        lastKnownAccess = access
        lastWithoutAccess = shieldOn && !access
        val had = p.getBoolean(KEY_HAD_ACCESS, false)
        val open = p.getLong(KEY_OPEN_RECORD, 0L).takeIf { it > 0L }
        if (shieldOn) endedBySwitchOff = null
        when {
            // Access back (or first had): an open record ends. Hiding on: nothing left for an Undo.
            shieldOn && access -> if (!had || open != null) {
                open?.let { ProtectionGaps.endEvent(app, it) }
                p.edit { putBoolean(KEY_HAD_ACCESS, true).remove(KEY_OPEN_RECORD) }
            }
            shieldOn && had -> {
                val seq = ProtectionGaps.recordEvent(app, ProtectionGaps.Kind.SHIELD_ACCESS_REVOKED)
                p.edit {
                    putBoolean(KEY_HAD_ACCESS, false)
                    if (seq != null) putLong(KEY_OPEN_RECORD, seq)
                }
            }
            // Switched off in BioKey: an open record ends, from when access went to now.
            !shieldOn -> if (had || open != null) {
                open?.let { ProtectionGaps.endEvent(app, it) }
                p.edit { putBoolean(KEY_HAD_ACCESS, false).remove(KEY_OPEN_RECORD) }
                // For the switch-off's Undo, if a look queued before its own got here first.
                endedBySwitchOff = open
                return open
            }
        }
        return null
    }
}
