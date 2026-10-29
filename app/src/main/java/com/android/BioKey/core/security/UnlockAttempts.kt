package com.android.BioKey.core.security

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.hardware.biometrics.BiometricManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.UserManager
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
 * a locked app's prompt. Off until the owner turns it on (BioKey's home screen asks once).
 *
 * - **Who it was.** Only the prompt that saw the misses can say: a face or fingerprint that then
 *   matches on that same prompt was the owner retrying, and its misses go. Misses on an earlier
 *   prompt stay, even if the owner opens the app soon after: someone else may have tried and
 *   handed the phone back. They're marked "then opened with your face or fingerprint", shown but
 *   not notified. Misses followed by the screen lock are kept too ("opened with your screen
 *   lock"): someone may know the PIN.
 * - **Screen lock unlocks** (an extra, off by default): a locked app opened with the screen lock
 *   at all, on a phone with a face or fingerprint set up, is noted too, for owners who never use
 *   the PIN themselves. Shown, not notified.
 * - **When it tells.** Unanswered misses, and times app locking was off ([ProtectionGaps]), are
 *   notified the next time the phone is unlocked after them (whoever tried has likely handed it
 *   back by then), and never within [OWNER_RETRY_MS] of a miss. The notification doesn't name the
 *   app, and on the phone's lock screen says only "BioKey".
 * - **What it keeps.** Which app (package name and label), when, how many misses, and how it
 *   ended; in BioKey's private storage, at most [MAX_KEPT] from the last [MAX_AGE_MS], never
 *   backed up. Turning the alerts off in Settings deletes them (with Undo).
 *
 * Android doesn't tell apps about a wrong PIN, pattern or password: only biometrics count. Each
 * miss is saved as it happens, so a prompt that goes without a word (its screen killed) still
 * leaves its record; a prompt restarted mid-attempt (a rotation, a fold) adds to the same one.
 */
object UnlockAttempts {

    enum class Ending { NONE, BIOMETRIC, SCREEN_LOCK }

    data class Attempt(
        val packageName: String,
        val label: String,
        /** The first miss. */
        val at: Long,
        /** The latest miss, or the unlock that ended it. */
        val lastAt: Long,
        val failures: Int,
        val lockedOut: Boolean = false,
        val ending: Ending = Ending.NONE
    ) {
        /** Not followed by any unlock: notified, and shown as a warning. */
        val unanswered get() = ending == Ending.NONE
    }

    /** Never notified sooner after a miss than this: the owner may still be retrying. */
    const val OWNER_RETRY_MS = 2 * 60_000L
    /** Misses on one app this close together are one attempt (a prompt restarted mid-attempt). */
    private const val SAME_ATTEMPT_MS = 60_000L
    const val MAX_AGE_MS = 7 * 24 * 60 * 60_000L
    const val MAX_KEPT = 50

    private const val PREFS = "biokey_unlock_attempts"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_SCREEN_LOCK_UNLOCKS = "screen_lock_unlocks"
    /** The owner has answered BioKey's question (or used the switch in Settings). */
    private const val KEY_CHOSEN = "chosen"
    private const val KEY_ATTEMPTS = "attempts"
    /** Things up to this time have been seen on BioKey's home screen or notified. */
    private const val KEY_SEEN_UNTIL = "seen_until"
    /** The phone was last unlocked then. */
    private const val KEY_PRESENT_AT = "present_at"
    const val CHANNEL_ID = "biokey_security_activity"
    private const val NOTIFICATION_ID = 1101

    private val _attempts = MutableStateFlow<List<Attempt>>(emptyList())
    /** Newest first. */
    val attempts: StateFlow<List<Attempt>> = _attempts.asStateFlow()
    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    private val _screenLockUnlocks = MutableStateFlow(false)
    val screenLockUnlocks: StateFlow<Boolean> = _screenLockUnlocks.asStateFlow()
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
    /** Whether a strong face or fingerprint is set up; tests set their own. */
    @VisibleForTesting
    internal var hasStrongBiometric: (Context) -> Boolean = { context ->
        runCatching {
            context.getSystemService(BiometricManager::class.java)
                ?.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
        }.getOrDefault(false)
    }

    /**
     * Reads what's kept, once per process; null before the first unlock after a restart, when
     * BioKey's storage can't be read. BioKey's application starts this off the main thread.
     */
    fun load(context: Context): SharedPreferences? {
        prefs?.let { return it }
        if (context.getSystemService(UserManager::class.java)?.isUserUnlocked == false) return null
        synchronized(lock) {
            prefs?.let { return it }
            val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            _enabled.value = p.getBoolean(KEY_ENABLED, false)
            _screenLockUnlocks.value = p.getBoolean(KEY_SCREEN_LOCK_UNLOCKS, false)
            _chosen.value = p.getBoolean(KEY_CHOSEN, false)
            val kept = runCatching { parse(p.getString(KEY_ATTEMPTS, null)) }.getOrDefault(emptyList())
            prefs = p
            // What's past its time goes from the disk too, not only from what's shown.
            if (kept.fresh() != kept) save(p, kept.fresh()) else _attempts.value = kept
        }
        // An alert that was waiting when the process last stopped.
        scheduleNotification(context.applicationContext, 0L)
        return prefs
    }

    @VisibleForTesting
    internal fun resetForTests() {
        synchronized(lock) { prefs = null }
        main.removeCallbacksAndMessages(NOTIFY_TOKEN)
    }

    private fun List<Attempt>.fresh(): List<Attempt> {
        val now = clock()
        return filter { now - it.lastAt <= MAX_AGE_MS }.sortedByDescending { it.at }.take(MAX_KEPT)
    }

    /** Drops what's past its time; BioKey's home screen calls this as it shows them. */
    fun prune(context: Context) {
        val p = load(context) ?: return
        synchronized(lock) { _attempts.value.fresh().let { if (it != _attempts.value) save(p, it) } }
    }

    /** The attempt [packageName]'s prompt is adding to, if it's still the one at the top. */
    private fun current(list: List<Attempt>, packageName: String, now: Long): Attempt? = list.firstOrNull()?.takeIf {
        it.packageName in PasswordManager.aliasesOf(packageName) && it.unanswered && now - it.lastAt in 0..SAME_ATTEMPT_MS
    }

    /** A face or fingerprint didn't match on [packageName]'s prompt. */
    fun onBiometricFailed(context: Context, packageName: String, label: String) {
        val p = load(context) ?: return
        if (!_enabled.value) return
        val now = clock()
        synchronized(lock) {
            val list = _attempts.value
            val same = current(list, packageName, now)
            val updated = if (same != null) listOf(same.copy(lastAt = now, failures = same.failures + 1)) + list.drop(1)
            else listOf(Attempt(packageName, label, now, now, failures = 1)) + list
            save(p, updated.fresh())
        }
        DiagnosticLog.log("A face or fingerprint didn't match on $packageName")
        scheduleNotification(context.applicationContext, OWNER_RETRY_MS)
    }

    /** Too many misses: biometrics locked out on [packageName]'s prompt. */
    fun onLockedOut(context: Context, packageName: String, label: String) {
        val p = load(context) ?: return
        if (!_enabled.value) return
        val now = clock()
        synchronized(lock) {
            val list = _attempts.value
            val same = current(list, packageName, now)
            val updated = if (same != null) listOf(same.copy(lastAt = now, lockedOut = true)) + list.drop(1)
            else listOf(Attempt(packageName, label, now, now, failures = 0, lockedOut = true)) + list
            save(p, updated.fresh())
        }
        DiagnosticLog.log("Face and fingerprint locked out on $packageName")
        scheduleNotification(context.applicationContext, OWNER_RETRY_MS)
    }

    /**
     * [packageName] was unlocked, with a face or fingerprint ([biometric]) or the screen lock,
     * on a prompt that saw [missesHere] misses of its own before.
     */
    fun onUnlocked(context: Context, packageName: String, label: String, biometric: Boolean, missesHere: Int) {
        val p = load(context) ?: return
        if (!_enabled.value) return
        val names = PasswordManager.aliasesOf(packageName)
        val now = clock()
        synchronized(lock) {
            val list = _attempts.value
            val ending = if (biometric) Ending.BIOMETRIC else Ending.SCREEN_LOCK
            val updated = list.toMutableList()
            val recent = list.indices.filter { list[it].packageName in names && list[it].unanswered && now - list[it].lastAt in 0..OWNER_RETRY_MS }
            // The misses this very prompt saw were the owner's, when their face or finger then matched.
            if (biometric && missesHere > 0 && recent.firstOrNull() == 0) {
                val top = list[0]
                updated[0] = top.copy(failures = (top.failures - missesHere).coerceAtLeast(0))
            }
            // What's left was someone, maybe: kept, marked with how the app was then opened.
            for (i in recent) {
                val a = updated[i]
                updated[i] = if (a.failures == 0 && !a.lockedOut) a.copy(lastAt = -1L) else a.copy(ending = ending, lastAt = now)
            }
            updated.removeAll { it.lastAt == -1L }
            if (!biometric && recent.isEmpty() && _screenLockUnlocks.value && hasStrongBiometric(context)) {
                updated.add(0, Attempt(packageName, label, now, now, failures = 0, ending = Ending.SCREEN_LOCK))
                DiagnosticLog.log("$packageName opened with the screen lock")
            }
            if (updated != list) save(p, updated.fresh())
        }
        scheduleNotification(context.applicationContext, 0L)
    }

    /** The phone was just unlocked: whoever tried has likely handed it back. */
    fun onUserPresent(context: Context) {
        val p = load(context) ?: return
        p.edit { putLong(KEY_PRESENT_AT, clock()) }
        scheduleNotification(context.applicationContext, 0L)
    }

    /** Something else worth telling happened ([ProtectionGaps]). */
    fun onActivity(context: Context) = scheduleNotification(context.applicationContext, 0L)

    /** BioKey's home screen showed them: nothing left to notify. */
    fun markSeen(context: Context) {
        val p = load(context) ?: return
        val newest = maxOf(_attempts.value.maxOfOrNull { it.lastAt } ?: 0L, ProtectionGaps.gaps.value.maxOfOrNull { it.to } ?: 0L)
        if (newest > p.getLong(KEY_SEEN_UNTIL, 0L)) p.edit { putLong(KEY_SEEN_UNTIL, newest) }
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    /** Removes everything kept; returns it, for Undo. */
    fun clear(context: Context): List<Attempt> {
        val p = load(context) ?: return emptyList()
        val old = synchronized(lock) { _attempts.value.also { save(p, emptyList()) } }
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        return old
    }

    /** Puts back what [clear] removed (Undo), with anything recorded since. */
    fun restore(context: Context, attempts: List<Attempt>) {
        val p = load(context) ?: return
        synchronized(lock) { save(p, (_attempts.value + attempts).distinct().fresh()) }
    }

    /**
     * The owner's choice. Off deletes what's kept unless [keepRecords] (a restored backup saying
     * "off" mustn't delete this phone's records); returns what was deleted, for Undo.
     */
    fun setEnabled(context: Context, on: Boolean, keepRecords: Boolean = false): List<Attempt> {
        val p = load(context) ?: return emptyList()
        _enabled.value = on
        _chosen.value = true
        p.edit { putBoolean(KEY_ENABLED, on).putBoolean(KEY_CHOSEN, true) }
        if (on) ensureChannel(context)
        return if (!on && !keepRecords) clear(context) else emptyList()
    }

    fun setScreenLockUnlocks(context: Context, on: Boolean) {
        val p = load(context) ?: return
        _screenLockUnlocks.value = on
        p.edit { putBoolean(KEY_SCREEN_LOCK_UNLOCKS, on) }
    }

    private fun save(p: SharedPreferences, list: List<Attempt>) {
        _attempts.value = list
        p.edit { putString(KEY_ATTEMPTS, serialize(list)) }
    }

    private fun serialize(list: List<Attempt>): String = JSONArray().apply {
        list.forEach {
            put(
                JSONObject().put("p", it.packageName).put("n", it.label).put("t", it.at).put("u", it.lastAt)
                    .put("f", it.failures).put("l", it.lockedOut).put("e", it.ending.name)
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
                    it.optInt("f"), it.optBoolean("l"),
                    runCatching { Ending.valueOf(it.getString("e")) }.getOrDefault(Ending.NONE)
                )
            }
        }
    }

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.activity_channel_name), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.activity_channel_description)
            }
        )
    }

    /** Looks again in [delayMs]. */
    private fun scheduleNotification(app: Context, delayMs: Long) {
        main.removeCallbacksAndMessages(NOTIFY_TOKEN)
        main.postAtTime({ updateNotification(app) }, NOTIFY_TOKEN, SystemClock.uptimeMillis() + delayMs)
    }

    /**
     * Notifies what's unseen and old enough: misses no unlock followed, at least [OWNER_RETRY_MS]
     * old, and times app locking was off; each only once the phone has been unlocked since.
     */
    @VisibleForTesting
    internal fun updateNotification(app: Context) {
        val p = load(app) ?: return
        val now = clock()
        val seenUntil = p.getLong(KEY_SEEN_UNTIL, 0L)
        val presentAt = p.getLong(KEY_PRESENT_AT, 0L)
        val pendingAttempts = if (_enabled.value) _attempts.value.filter { it.unanswered && it.lastAt > seenUntil } else emptyList()
        val pendingGaps = ProtectionGaps.gaps.value.filter { it.to > seenUntil }
        // Not yet: the owner may still be retrying. Look again once they can't be.
        val retrying = pendingAttempts.filter { now - it.lastAt < OWNER_RETRY_MS }
        if (retrying.isNotEmpty()) scheduleNotification(app, retrying.maxOf { it.lastAt } + OWNER_RETRY_MS - now)
        val attempts = pendingAttempts.filter { now - it.lastAt >= OWNER_RETRY_MS && it.lastAt < presentAt }
        val gaps = pendingGaps.filter { it.to <= presentAt }
        val manager = NotificationManagerCompat.from(app)
        if (attempts.isEmpty() && gaps.isEmpty()) return
        if (!manager.areNotificationsEnabled()) return
        ensureChannel(app)
        val res = app.resources
        val title = when {
            gaps.isEmpty() -> res.getQuantityString(R.plurals.attempts_title, attempts.size, attempts.size)
            attempts.isEmpty() -> res.getString(R.string.gaps_title)
            else -> res.getString(R.string.activity_title)
        }
        val text = res.getString(if (attempts.isNotEmpty()) R.string.attempts_notification_text else R.string.gaps_notification_text)
        val open = PendingIntent.getActivity(
            app,
            NOTIFICATION_ID,
            Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // Which app is never said; on the phone's lock screen, not even that something happened.
        val public = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_biokey_mark)
            .setContentTitle(res.getString(R.string.brand_name))
            .build()
        val notification = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_biokey_mark)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(NOTIFICATION_ID, notification)
            val newest = maxOf(attempts.maxOfOrNull { it.lastAt } ?: 0L, gaps.maxOfOrNull { it.to } ?: 0L)
            p.edit { putLong(KEY_SEEN_UNTIL, maxOf(newest, seenUntil)) }
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS revoked between the check and notify.
        }
    }

    /** "3 tries didn't match", "locked out after 5 tries", "opened with your screen lock after 2 tries". */
    fun describe(context: Context, attempt: Attempt): String {
        val res = context.resources
        val tries = res.getQuantityString(R.plurals.attempts_tries, attempt.failures, attempt.failures)
        return when {
            attempt.ending == Ending.SCREEN_LOCK && attempt.failures == 0 && !attempt.lockedOut -> res.getString(R.string.attempts_screen_lock_only)
            attempt.ending == Ending.SCREEN_LOCK -> res.getString(R.string.attempts_opened_with_screen_lock, tries)
            attempt.ending == Ending.BIOMETRIC -> res.getString(R.string.attempts_opened_with_biometric, tries)
            attempt.lockedOut && attempt.failures > 0 -> res.getString(R.string.attempts_locked_out_after, tries)
            attempt.lockedOut -> res.getString(R.string.attempts_locked_out)
            else -> res.getString(R.string.attempts_missed, tries)
        }
    }
}
