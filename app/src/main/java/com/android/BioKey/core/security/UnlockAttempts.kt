package com.android.BioKey.core.security

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import com.android.BioKey.R
import com.android.BioKey.core.data.PasswordManager
import com.android.BioKey.core.diagnostics.DiagnosticLog
import com.android.BioKey.feature.main.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Failed unlocks of locked apps, for the owner to see: faces or fingerprints that didn't match on
 * a locked app's prompt.
 *
 * - **Who it was.** A face or fingerprint that matches proves the owner: their own misses on that
 *   app in the [OWNER_RETRY_MS] before are dropped (a wet finger, then a dry one). The screen
 *   lock proves nothing (someone may know the PIN), so misses followed by a PIN unlock are kept,
 *   as "opened with your screen lock", and shown, but not notified: that's usually the owner too.
 * - **When it tells.** A miss that wasn't followed by an unlock is notified [OWNER_RETRY_MS]
 *   later, never while the owner may still be retrying, and without naming the app, so whoever
 *   holds the phone learns nothing new. BioKey's home screen lists them all until cleared.
 * - **What it keeps.** Which app (its package name and label), when, how many misses, and how it
 *   ended; in BioKey's private storage for [MAX_AGE_MS], never backed up. Off until the owner
 *   turns it on (BioKey's home screen asks once); turning it off deletes everything kept.
 *
 * Android doesn't tell apps about a wrong PIN, pattern or password: only biometrics count.
 * Each miss is saved as it happens, so a prompt that goes without a word (its screen killed) still
 * leaves its record; a prompt restarted mid-attempt (a rotation, a fold) adds to the same one.
 */
object UnlockAttempts {

    data class Attempt(
        val packageName: String,
        val label: String,
        /** The first miss. */
        val at: Long,
        /** The latest miss. */
        val lastAt: Long,
        val failures: Int,
        val lockedOut: Boolean = false,
        /** Then opened with the screen lock (PIN, pattern or password), not a face or fingerprint. */
        val openedWithScreenLock: Boolean = false
    ) {
        /** Notified, and shown as a warning: the misses weren't followed by any unlock. */
        val unanswered get() = !openedWithScreenLock
    }

    /** A matching face or fingerprint this soon after a miss means the miss was the owner's. */
    const val OWNER_RETRY_MS = 2 * 60_000L
    /** Misses on one app this close together are one attempt (a prompt restarted mid-attempt). */
    private const val SAME_ATTEMPT_MS = 60_000L
    const val MAX_AGE_MS = 7 * 24 * 60 * 60_000L
    private const val MAX_KEPT = 50

    private const val PREFS = "biokey_unlock_attempts"
    private const val KEY_ENABLED = "enabled"
    /** The owner has answered BioKey's question (or used the switch in Settings). */
    private const val KEY_CHOSEN = "chosen"
    private const val KEY_ATTEMPTS = "attempts"
    /** Unanswered attempts up to this time have been seen on BioKey's home screen or notified. */
    private const val KEY_SEEN_UNTIL = "seen_until"
    const val CHANNEL_ID = "biokey_unlock_attempts"
    private const val NOTIFICATION_ID = 1101

    private val _attempts = MutableStateFlow<List<Attempt>>(emptyList())
    /** Newest first. */
    val attempts: StateFlow<List<Attempt>> = _attempts.asStateFlow()
    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    private val _chosen = MutableStateFlow(true)
    /** False until the owner has said whether they want these alerts. */
    val chosen: StateFlow<Boolean> = _chosen.asStateFlow()

    @Volatile private var prefs: SharedPreferences? = null
    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())
    private val NOTIFY_TOKEN = Any()

    /** The wall clock; tests set their own. */
    @VisibleForTesting
    internal var clock: () -> Long = System::currentTimeMillis

    /**
     * Reads what's kept, once per process. BioKey's application starts this off the main thread,
     * so a prompt's first miss doesn't wait for the disk.
     */
    fun load(context: Context): SharedPreferences {
        prefs?.let { return it }
        synchronized(lock) {
            prefs?.let { return it }
            val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            _enabled.value = p.getBoolean(KEY_ENABLED, false)
            _chosen.value = p.getBoolean(KEY_CHOSEN, false)
            _attempts.value = runCatching { parse(p.getString(KEY_ATTEMPTS, null)) }.getOrDefault(emptyList()).fresh()
            prefs = p
        }
        // An alert that was waiting when the process last died.
        scheduleNotification(context.applicationContext, 0L)
        return prefs!!
    }

    @VisibleForTesting
    internal fun resetForTests() {
        synchronized(lock) { prefs = null }
        main.removeCallbacksAndMessages(NOTIFY_TOKEN)
    }

    private fun List<Attempt>.fresh(): List<Attempt> {
        val now = clock()
        return filter { now - it.lastAt in 0..MAX_AGE_MS }.sortedByDescending { it.at }.take(MAX_KEPT)
    }

    /** A face or fingerprint didn't match on [packageName]'s prompt. */
    fun onBiometricFailed(context: Context, packageName: String, label: String) {
        val p = load(context)
        if (!_enabled.value) return
        val now = clock()
        synchronized(lock) {
            val list = _attempts.value
            val same = list.firstOrNull()?.takeIf {
                it.packageName in PasswordManager.aliasesOf(packageName) && !it.openedWithScreenLock && now - it.lastAt in 0..SAME_ATTEMPT_MS
            }
            val updated = if (same != null) {
                listOf(same.copy(lastAt = now, failures = same.failures + 1)) + list.drop(1)
            } else {
                listOf(Attempt(packageName, label, now, now, failures = 1)) + list
            }
            save(p, updated.fresh())
        }
        DiagnosticLog.log("A face or fingerprint didn't match on $packageName")
        scheduleNotification(context.applicationContext, OWNER_RETRY_MS)
    }

    /** Too many misses: biometrics locked out on [packageName]'s prompt. */
    fun onLockedOut(context: Context, packageName: String, label: String) {
        val p = load(context)
        if (!_enabled.value) return
        val now = clock()
        synchronized(lock) {
            val list = _attempts.value
            val same = list.firstOrNull()?.takeIf { it.packageName in PasswordManager.aliasesOf(packageName) && now - it.lastAt in 0..SAME_ATTEMPT_MS }
            val updated = if (same != null) listOf(same.copy(lastAt = now, lockedOut = true)) + list.drop(1)
            else listOf(Attempt(packageName, label, now, now, failures = 0, lockedOut = true)) + list
            save(p, updated.fresh())
        }
        DiagnosticLog.log("Face and fingerprint locked out on $packageName")
        scheduleNotification(context.applicationContext, OWNER_RETRY_MS)
    }

    /**
     * [packageName] was unlocked. With a face or fingerprint ([biometric]), its recent misses were
     * the owner's and go; with the screen lock, they stay, marked as opened that way.
     */
    fun onUnlocked(context: Context, packageName: String, biometric: Boolean) {
        val p = load(context)
        val names = PasswordManager.aliasesOf(packageName)
        val now = clock()
        synchronized(lock) {
            val list = _attempts.value
            fun recent(a: Attempt) = a.packageName in names && now - a.lastAt in 0..OWNER_RETRY_MS
            if (list.none(::recent)) return
            save(p, if (biometric) list.filterNot(::recent) else list.map { if (recent(it)) it.copy(openedWithScreenLock = true) else it })
        }
        scheduleNotification(context.applicationContext, 0L)
    }

    /** BioKey's home screen showed them: nothing left to notify. */
    fun markSeen(context: Context) {
        val p = load(context)
        val newest = _attempts.value.firstOrNull()?.lastAt ?: return
        if (newest > p.getLong(KEY_SEEN_UNTIL, 0L)) p.edit { putLong(KEY_SEEN_UNTIL, newest) }
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    /** Removes everything kept; returns it, for Undo. */
    fun clear(context: Context): List<Attempt> {
        val p = load(context)
        val old = synchronized(lock) { _attempts.value.also { save(p, emptyList()) } }
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        return old
    }

    /** Puts back what [clear] removed (Undo), with anything recorded since. */
    fun restore(context: Context, attempts: List<Attempt>) {
        val p = load(context)
        synchronized(lock) { save(p, (_attempts.value + attempts).distinct().fresh()) }
    }

    fun setEnabled(context: Context, on: Boolean) {
        val p = load(context)
        _enabled.value = on
        _chosen.value = true
        p.edit { putBoolean(KEY_ENABLED, on).putBoolean(KEY_CHOSEN, true) }
        if (on) ensureChannel(context) else clear(context)
    }

    private fun save(p: SharedPreferences, list: List<Attempt>) {
        _attempts.value = list
        p.edit { putString(KEY_ATTEMPTS, serialize(list)) }
    }

    private fun serialize(list: List<Attempt>): String = JSONArray().apply {
        list.forEach {
            put(
                JSONObject().put("p", it.packageName).put("n", it.label).put("t", it.at).put("u", it.lastAt)
                    .put("f", it.failures).put("l", it.lockedOut).put("s", it.openedWithScreenLock)
            )
        }
    }.toString()

    private fun parse(json: String?): List<Attempt> {
        if (json.isNullOrEmpty()) return emptyList()
        val array = JSONArray(json)
        return (0 until array.length()).map { i ->
            array.getJSONObject(i).let {
                val at = it.getLong("t")
                Attempt(
                    it.getString("p"), it.optString("n").ifEmpty { it.getString("p") }, at, it.optLong("u", at),
                    it.optInt("f"), it.optBoolean("l"), it.optBoolean("s")
                )
            }
        }
    }

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.attempts_channel_name), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.attempts_channel_description)
            }
        )
    }

    /** Looks again in [delayMs]: an unanswered miss that old, not yet seen, is notified. */
    private fun scheduleNotification(app: Context, delayMs: Long) {
        main.removeCallbacksAndMessages(NOTIFY_TOKEN)
        main.postAtTime({ updateNotification(app) }, NOTIFY_TOKEN, android.os.SystemClock.uptimeMillis() + delayMs)
    }

    @VisibleForTesting
    internal fun updateNotification(app: Context) {
        val p = load(app)
        val now = clock()
        val seenUntil = p.getLong(KEY_SEEN_UNTIL, 0L)
        val unseen = _attempts.value.filter { it.unanswered && it.lastAt > seenUntil }
        val manager = NotificationManagerCompat.from(app)
        if (unseen.isEmpty()) {
            manager.cancel(NOTIFICATION_ID)
            return
        }
        // The newest is still within the owner's retry window: look again once it isn't.
        val wait = unseen.maxOf { it.lastAt } + OWNER_RETRY_MS - now
        if (wait > 0) {
            scheduleNotification(app, wait)
            return
        }
        if (!_enabled.value || !manager.areNotificationsEnabled()) return
        ensureChannel(app)
        val title = app.resources.getQuantityString(R.plurals.attempts_title, unseen.size, unseen.size)
        val open = PendingIntent.getActivity(
            app,
            NOTIFICATION_ID,
            Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // Which app is never said, here or on the lock screen: whoever holds the phone learns nothing.
        val notification = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_biokey_mark)
            .setContentTitle(title)
            .setContentText(app.getString(R.string.attempts_notification_text))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(NOTIFICATION_ID, notification)
            p.edit { putLong(KEY_SEEN_UNTIL, unseen.maxOf { it.lastAt }) }
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS revoked between the check and notify.
        }
    }

    /** "3 tries", "locked out after 5 tries", "opened with your screen lock after 2 tries". */
    fun describe(context: Context, attempt: Attempt): String {
        val res = context.resources
        val tries = res.getQuantityString(R.plurals.attempts_tries, attempt.failures, attempt.failures)
        return when {
            attempt.openedWithScreenLock -> res.getString(R.string.attempts_opened_with_screen_lock, tries)
            attempt.lockedOut && attempt.failures > 0 -> res.getString(R.string.attempts_locked_out_after, tries)
            attempt.lockedOut -> res.getString(R.string.attempts_locked_out)
            else -> res.getString(R.string.attempts_missed, tries)
        }
    }
}
