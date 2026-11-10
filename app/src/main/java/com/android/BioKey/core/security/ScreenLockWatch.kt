package com.android.BioKey.core.security

import android.app.KeyguardManager
import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.core.content.edit
import com.android.BioKey.core.diagnostics.DiagnosticLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The phone's screen lock (PIN, pattern or password) taken away after setup. Android's prompt
 * then has nothing to check: it fails with "no device credential", and every face and fingerprint
 * went with the screen lock. Google's App lock turns itself off for good; failing closed instead
 * would lock the owner out of their own apps (and anyone holding the phone can set a new screen
 * lock anyway, since there's none to ask for). So BioKey lets locked apps open without a check,
 * and never silently: it's recorded like the other times app locking was off
 * ([ProtectionGaps.Kind.NO_SCREEN_LOCK], from when BioKey noticed until it's back), told at the
 * next unlock, and home says so until a screen lock is set again.
 *
 * Looked at as BioKey's checks run ([check]: BioKey opening, the phone being unlocked, every
 * prompt), and by a prompt that has just failed for want of one ([letThroughWithoutScreenLock]).
 *
 * Also keeps whether home's "Add a face or fingerprint" card was put away: like Google's, that
 * comes back once the screen lock is removed, since every face and fingerprint goes with it.
 */
object ScreenLockWatch {

    private const val PREFS = "biokey_screen_lock"
    /** A screen lock was set, last time this looked: losing it now is news. */
    private const val KEY_HAD_SCREEN_LOCK = "had_screen_lock"
    /** The record of the screen lock going, until it's back: then it says from when to when. */
    private const val KEY_OPEN_RECORD = "open_record"
    /** Home's "Add a face or fingerprint" card was put away ("No thanks"). */
    private const val KEY_ADD_BIOMETRIC_DISMISSED = "add_biometric_dismissed"

    /** Whether the phone has a screen lock; tests set their own (Robolectric's phone has none). */
    @VisibleForTesting
    internal var hasScreenLock: (Context) -> Boolean = { context ->
        context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure ?: true
    }

    /** Whether the phone has a screen lock now ([hasScreenLock]); taken as yes if Android can't say. */
    fun screenLockSet(context: Context): Boolean = runCatching { hasScreenLock(context.applicationContext) }.getOrDefault(true)

    private val _missing = MutableStateFlow(false)
    /** The screen lock is gone and locked apps open without a check: home's card. */
    val missing: StateFlow<Boolean> = _missing.asStateFlow()

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @VisibleForTesting
    internal fun resetForTests(context: Context) {
        prefs(context).edit(commit = true) { clear() }
        _missing.value = false
        hasScreenLock = { it.getSystemService(KeyguardManager::class.java)?.isDeviceSecure ?: true }
    }

    /**
     * Looks at the screen lock and records what changed: gone since last time (or a record already
     * open), the record starts; back, it ends. A phone never seen with one (BioKey's own tests, or
     * a screen lock removed before this version) isn't news until a prompt fails for want of it.
     * Off the main thread: it may read BioKey's storage for the first time.
     */
    @Synchronized
    fun check(context: Context) {
        val app = context.applicationContext
        val p = prefs(app)
        val secure = screenLockSet(app)
        val open = p.getLong(KEY_OPEN_RECORD, 0L).takeIf { it > 0L }
        if (secure) {
            open?.let { ProtectionGaps.endEvent(app, it) }
            if (open != null) DiagnosticLog.log("Screen lock set again: locked apps need a check again")
            if (open != null || !p.getBoolean(KEY_HAD_SCREEN_LOCK, false)) p.edit { putBoolean(KEY_HAD_SCREEN_LOCK, true).remove(KEY_OPEN_RECORD) }
            _missing.value = false
            return
        }
        if (open != null || p.getBoolean(KEY_HAD_SCREEN_LOCK, false)) begin(app)
    }

    /**
     * A prompt for [packageName] just failed for want of a screen lock. If the phone really has
     * none, the caller lets it through without a check (recorded, [begin]) and this returns true;
     * otherwise false, and the prompt's error stands. Any thread.
     */
    @Synchronized
    fun letThroughWithoutScreenLock(context: Context, packageName: String): Boolean {
        val app = context.applicationContext
        if (screenLockSet(app)) return false
        begin(app)
        DiagnosticLog.log("Opened $packageName without a check: the phone has no screen lock")
        return true
    }

    /** The screen lock is gone: its record starts (once), home's card shows, and the face or fingerprint card may come back. */
    private fun begin(app: Context) {
        val p = prefs(app)
        _missing.value = true
        if (p.getLong(KEY_OPEN_RECORD, 0L) > 0L) return
        val seq = ProtectionGaps.recordEvent(app, ProtectionGaps.Kind.NO_SCREEN_LOCK)
        DiagnosticLog.log("The phone has no screen lock: locked apps open without a check until it's back")
        p.edit {
            putBoolean(KEY_HAD_SCREEN_LOCK, false)
            remove(KEY_ADD_BIOMETRIC_DISMISSED)
            if (seq != null) putLong(KEY_OPEN_RECORD, seq)
        }
    }

    /** Home's "Add a face or fingerprint" card was put away. */
    fun addBiometricDismissed(context: Context): Boolean = prefs(context).getBoolean(KEY_ADD_BIOMETRIC_DISMISSED, false)

    /** "No thanks" on home's "Add a face or fingerprint" card: away until the screen lock is next removed. */
    fun dismissAddBiometric(context: Context) {
        prefs(context).edit { putBoolean(KEY_ADD_BIOMETRIC_DISMISSED, true) }
    }
}
