package com.android.BioKey.core.security

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
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
 * - **Who it was.** Only the prompt that saw a miss can say: when a face or fingerprint then
 *   matches on that same prompt, the misses it saw were the owner retrying, and go ([onUnlocked]
 *   is told exactly which, per record). Misses on any other prompt stay, even if the owner opens
 *   the app soon after: someone else may have tried and handed the phone back. They're marked with
 *   how the app was then opened. Misses followed by the screen lock may be someone who knows it.
 * - **Screen lock unlocks** (an extra, off by default): a locked app opened with the screen lock
 *   at all, on a phone with a face or fingerprint set up, is noted too, for owners who never use
 *   the PIN themselves.
 * - **When it tells.** Misses nobody followed with a face or fingerprint (unanswered, or answered
 *   with the screen lock), and other security activity ([ProtectionGaps]), are notified the next
 *   time the phone is unlocked after them (whoever tried has likely handed it back by then), never
 *   within [OWNER_RETRY_MS] of a miss. The notification never names the app and isn't shown on
 *   the phone's lock screen at all. A tap doesn't dismiss it; swiped away, it comes back quietly at
 *   the next unlock; it goes when the owner opens Review in BioKey. If BioKey's notifications are
 *   off or muted, nothing counts as told, and home says the alerts are muted.
 * - **Clocks.** Every record carries a sequence number, and the time since the phone started:
 *   what's been seen, what happened before the last unlock and how long ago a miss was are judged
 *   on those, so changing the phone's date can neither hide a record nor delete it early. A record
 *   isn't deleted for its age until the owner has seen it.
 * - **What it keeps.** Which app (package name and label), when, how many misses, and how it
 *   ended; in BioKey's private storage, at most [MAX_KEPT], seen ones for [MAX_AGE_MS], never backed
 *   up. Turning the alerts off in Settings deletes them (with Undo).
 *
 * Android doesn't tell apps about a wrong PIN, pattern or password: only biometrics count. Each
 * miss is saved as it happens, so a prompt that goes without a word (its screen killed) still
 * leaves its record.
 */
object UnlockAttempts {

    enum class Ending { NONE, BIOMETRIC, SCREEN_LOCK }

    data class Attempt(
        val packageName: String,
        val label: String,
        /** The first miss (wall clock, for display). */
        val at: Long,
        /** The latest miss, or the unlock that ended it (wall clock, for display). */
        val lastAt: Long,
        val failures: Int,
        val lockedOut: Boolean = false,
        val ending: Ending = Ending.NONE,
        /** Order of events, whatever the clock says ([nextSeq]). */
        val seq: Long = 0L,
        /** [lastAt] on the clock since boot, and which boot. */
        val lastElapsed: Long = 0L,
        val boot: Int = -1
    ) {
        /** Not followed by any unlock: shown as a warning. */
        val unanswered get() = ending == Ending.NONE

        /** Worth telling: misses nobody's face or fingerprint followed. */
        val notifiable get() = ending != Ending.BIOMETRIC && (failures > 0 || lockedOut)
    }

    /** Never notified sooner after a miss than this: the owner may still be retrying. */
    const val OWNER_RETRY_MS = 2 * 60_000L
    /** Misses on one app this close together, from no known prompt, are one attempt. */
    private const val SAME_ATTEMPT_MS = 60_000L
    const val MAX_AGE_MS = 7 * 24 * 60 * 60_000L
    const val MAX_KEPT = 50
    /** Unseen records are never pushed out by seen ones; this only bounds the storage. */
    const val MAX_UNSEEN = 500

    private const val PREFS = "biokey_unlock_attempts"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_SCREEN_LOCK_UNLOCKS = "screen_lock_unlocks"
    /** The owner has answered BioKey's question (or used the switch in Settings). */
    private const val KEY_CHOSEN = "chosen"
    private const val KEY_ATTEMPTS = "attempts"
    /** The last sequence number handed out ([nextSeq]), for these records and [ProtectionGaps]'. */
    private const val KEY_SEQ = "seq"
    /** Records up to this sequence number have been seen in BioKey (Review): kept until they age. */
    private const val KEY_SEEN_SEQ = "seen_seq"
    /** Records up to this sequence number have been notified. */
    private const val KEY_NOTIFIED_SEQ = "notified_seq"
    /** The last sequence number handed out when the phone was last unlocked. */
    private const val KEY_PRESENT_SEQ = "present_seq"
    /** Show again, quietly, what was told but not reviewed, if its notification is gone. */
    private const val KEY_REPOST = "repost"
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

    /** When things happen ([TrustedClock]: the phone's date changed doesn't move it); tests set their own. */
    @VisibleForTesting
    internal var clock: () -> Long = TrustedClock::now
    @VisibleForTesting
    internal var elapsed: () -> Long = SystemClock::elapsedRealtime
    /** The phone's restart count: read once, it can't change while the process lives. */
    @Volatile private var cachedBoot: Int? = null
    @VisibleForTesting
    internal var bootCount: (Context) -> Int = { context ->
        cachedBoot ?: Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0).also { cachedBoot = it }
    }
    /** Whether a strong face or fingerprint is set up; tests set their own. */
    @VisibleForTesting
    internal var hasStrongBiometric: (Context) -> Boolean = SecurityEngine::hasEnrolledStrongBiometric

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
            // What's seen and past its time goes from the disk too, not only from what's shown.
            val fresh = kept.fresh(p)
            if (fresh != kept) save(p, fresh) else _attempts.value = kept
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

    /** The next number in the order of events, shared with [ProtectionGaps]. */
    internal fun nextSeq(context: Context): Long {
        val p = load(context) ?: return 0L
        synchronized(lock) {
            val next = p.getLong(KEY_SEQ, 0L) + 1
            p.edit { putLong(KEY_SEQ, next) }
            return next
        }
    }

    internal fun seenSeq(context: Context): Long = load(context)?.getLong(KEY_SEEN_SEQ, 0L) ?: 0L

    /**
     * Seen ones past their time go, aged on [TrustedClock] (a changed date doesn't age them);
     * unseen ones stay. Over the limit, seen ones go first: piling up new records can't push out
     * one the owner hasn't seen.
     */
    private fun List<Attempt>.fresh(p: SharedPreferences): List<Attempt> {
        val now = TrustedClock.now()
        val seen = p.getLong(KEY_SEEN_SEQ, 0L)
        val kept = filterNot { it.seq <= seen && now - it.lastAt > MAX_AGE_MS }.sortedByDescending { it.seq }
        val unseen = kept.filter { it.seq > seen }
        return (unseen.take(MAX_UNSEEN) + kept.filter { it.seq <= seen }.take((MAX_KEPT - unseen.size).coerceAtLeast(0)))
            .sortedByDescending { it.seq }
    }

    /**
     * The owner won't hear of security activity: BioKey's notifications, or its security-activity
     * channel, are switched off. BioKey's home screen says so.
     */
    fun alertsMuted(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return true
        return channelMuted(context)
    }

    /**
     * BioKey's notifications are allowed, but its security-activity or protection-alert channel is
     * switched off in Android's settings. (All of them off is home's "Alerts are off" row.)
     */
    private fun ownChannelMuted(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE

    /** Which of BioKey's alert channels is muted, if one is: its settings page is what to open. */
    fun mutedChannel(context: Context): String? {
        val nm = context.getSystemService(NotificationManager::class.java)
        return listOf(CHANNEL_ID, ProtectionAlerts.CHANNEL_ID).firstOrNull { nm.getNotificationChannel(it)?.importance == NotificationManager.IMPORTANCE_NONE }
    }

    fun channelMuted(context: Context): Boolean {
        val nm = context.getSystemService(NotificationManager::class.java)
        return listOf(CHANNEL_ID, ProtectionAlerts.CHANNEL_ID).any { nm.getNotificationChannel(it)?.importance == NotificationManager.IMPORTANCE_NONE }
    }

    /** Drops what's seen and past its time; BioKey's home screen calls this as it shows them. */
    fun prune(context: Context) {
        val p = load(context) ?: return
        synchronized(lock) { _attempts.value.fresh(p).let { if (it != _attempts.value) save(p, it) } }
    }

    /**
     * A face or fingerprint didn't match on [packageName]'s prompt. [promptRecord] is the record
     * this prompt added its last miss to, if any; returns the one it added this one to.
     */
    fun onBiometricFailed(context: Context, packageName: String, label: String, promptRecord: Long? = null): Long? {
        val p = load(context) ?: return null
        if (!_enabled.value) return null
        val now = clock()
        val since = elapsed()
        val boot = bootCount(context)
        val id: Long
        synchronized(lock) {
            val list = _attempts.value
            // The same prompt's record; else, for a prompt restarted mid-attempt, a fresh one on the same app.
            val same = list.firstOrNull { it.seq == promptRecord && it.unanswered }
                ?: list.firstOrNull()?.takeIf {
                    promptRecord == null && it.packageName in PasswordManager.aliasesOf(packageName) && it.unanswered &&
                        it.boot == boot && since - it.lastElapsed in 0..SAME_ATTEMPT_MS
                }
            val updated = if (same != null) {
                list.map { if (it === same) it.copy(lastAt = now, failures = it.failures + 1, lastElapsed = since) else it }
            } else {
                listOf(Attempt(packageName, label, now, now, failures = 1, seq = nextSeqLocked(p), lastElapsed = since, boot = boot)) + list
            }
            id = (same ?: updated.first()).seq
            save(p, updated.fresh(p))
        }
        DiagnosticLog.log("A face or fingerprint didn't match on $packageName")
        scheduleNotification(context.applicationContext, OWNER_RETRY_MS)
        return id
    }

    private fun nextSeqLocked(p: SharedPreferences): Long = (p.getLong(KEY_SEQ, 0L) + 1).also { p.edit { putLong(KEY_SEQ, it) } }

    /** Too many misses: biometrics locked out on [packageName]'s prompt. */
    fun onLockedOut(context: Context, packageName: String, label: String, promptRecord: Long?) {
        val p = load(context) ?: return
        if (!_enabled.value) return
        val now = clock()
        val since = elapsed()
        val boot = bootCount(context)
        synchronized(lock) {
            val list = _attempts.value
            val same = list.firstOrNull { it.seq == promptRecord && it.unanswered }
            val updated = if (same != null) list.map { if (it === same) it.copy(lastAt = now, lockedOut = true, lastElapsed = since) else it }
            else listOf(Attempt(packageName, label, now, now, failures = 0, lockedOut = true, seq = nextSeqLocked(p), lastElapsed = since, boot = boot)) + list
            save(p, updated.fresh(p))
        }
        DiagnosticLog.log("Face and fingerprint locked out on $packageName")
        scheduleNotification(context.applicationContext, OWNER_RETRY_MS)
    }

    /**
     * [packageName] was unlocked, with a face or fingerprint ([biometric]) or the screen lock.
     * [ownMisses]: the misses this very prompt saw, per record ([onBiometricFailed]'s ids).
     */
    fun onUnlocked(context: Context, packageName: String, label: String, biometric: Boolean, ownMisses: Map<Long, Int> = emptyMap()) {
        val p = load(context) ?: return
        if (!_enabled.value) return
        val names = PasswordManager.aliasesOf(packageName)
        val now = clock()
        val since = elapsed()
        val boot = bootCount(context)
        synchronized(lock) {
            val list = _attempts.value
            val ending = if (biometric) Ending.BIOMETRIC else Ending.SCREEN_LOCK
            fun recent(a: Attempt) = a.packageName in names && a.unanswered && a.boot == boot && since - a.lastElapsed in 0..OWNER_RETRY_MS
            val updated = list.mapNotNull { a ->
                // The misses this prompt saw were the owner's, when their face or finger then matched.
                val own = if (biometric) ownMisses[a.seq] ?: 0 else 0
                val left = a.copy(failures = (a.failures - own).coerceAtLeast(0))
                when {
                    !recent(a) && own == 0 -> a
                    left.failures == 0 && !left.lockedOut -> null
                    // What's left was someone, maybe: kept, marked with how the app was then opened.
                    else -> left.copy(ending = ending, lastAt = now, lastElapsed = since)
                }
            }.toMutableList()
            if (!biometric && list.none(::recent) && _screenLockUnlocks.value && hasStrongBiometric(context)) {
                updated.add(0, Attempt(packageName, label, now, now, failures = 0, ending = Ending.SCREEN_LOCK, seq = nextSeqLocked(p), lastElapsed = since, boot = boot))
                DiagnosticLog.log("$packageName opened with the screen lock")
            }
            if (updated != list) save(p, updated.fresh(p))
        }
        scheduleNotification(context.applicationContext, 0L)
    }

    /**
     * The phone was just unlocked: whoever tried has likely handed it back. What was already told
     * but not yet reviewed comes back (quietly) if its notification was swiped away.
     */
    fun onUserPresent(context: Context) {
        val p = load(context) ?: return
        p.edit { putLong(KEY_PRESENT_SEQ, p.getLong(KEY_SEQ, 0L)).putBoolean(KEY_REPOST, true) }
        scheduleNotification(context.applicationContext, 0L)
    }

    /** Something else worth telling happened ([ProtectionGaps]). */
    fun onActivity(context: Context) = scheduleNotification(context.applicationContext, 0L)

    /** The owner opened Review in BioKey: everything up to now has been seen. */
    fun markSeen(context: Context, upTo: Long = Long.MAX_VALUE) {
        val p = load(context) ?: return
        synchronized(lock) {
            // Up to the newest record actually there (and shown), not a number handed out a moment
            // ago to one another thread hasn't stored yet.
            val newest = maxOf(_attempts.value.maxOfOrNull { it.seq } ?: 0L, ProtectionGaps.gaps.value.maxOfOrNull { it.seq } ?: 0L)
            val seen = minOf(newest, upTo)
            if (seen > p.getLong(KEY_SEEN_SEQ, 0L)) p.edit { putLong(KEY_SEEN_SEQ, seen) }
            if (seen > p.getLong(KEY_NOTIFIED_SEQ, 0L)) p.edit { putLong(KEY_NOTIFIED_SEQ, seen) }
        }
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
        synchronized(lock) { save(p, (_attempts.value + attempts).distinctBy { it.seq }.fresh(p)) }
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
                    .put("q", it.seq).put("r", it.lastElapsed).put("b", it.boot)
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
                    runCatching { Ending.valueOf(it.getString("e")) }.getOrDefault(Ending.NONE),
                    it.optLong("q"), it.optLong("r"), it.optInt("b", -1)
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
                lockscreenVisibility = NotificationCompat.VISIBILITY_SECRET
            }
        )
    }

    /** Looks again in [delayMs]. */
    private fun scheduleNotification(app: Context, delayMs: Long) {
        main.removeCallbacksAndMessages(NOTIFY_TOKEN)
        main.postAtTime({ updateNotification(app) }, NOTIFY_TOKEN, SystemClock.uptimeMillis() + delayMs)
    }

    /**
     * Notifies what hasn't been: notifiable misses at least [OWNER_RETRY_MS] old, and times app
     * locking was off; each only once the phone has been unlocked since. A miss still inside its
     * retry window holds back nothing else, and is looked at again once it's out.
     */
    @VisibleForTesting
    internal fun updateNotification(app: Context) {
        val p = load(app) ?: return
        val since = elapsed()
        val boot = bootCount(app)
        val seen = p.getLong(KEY_SEEN_SEQ, 0L)
        val notified = p.getLong(KEY_NOTIFIED_SEQ, 0L)
        val presentSeq = p.getLong(KEY_PRESENT_SEQ, 0L)
        val repost = p.getBoolean(KEY_REPOST, false)
        fun retrying(a: Attempt) = a.boot == boot && since - a.lastElapsed < OWNER_RETRY_MS
        val notifiable = if (_enabled.value) _attempts.value.filter { it.notifiable && it.seq > seen } else emptyList()
        val retry = notifiable.filter { it.seq > notified && retrying(it) }
        if (retry.isNotEmpty()) scheduleNotification(app, OWNER_RETRY_MS - retry.minOf { since - it.lastElapsed } + 50L)
        // Ready to tell: not held back, and the phone unlocked since.
        val attempts = notifiable.filter { !(it.seq > notified && retrying(it)) && it.seq <= presentSeq }
        val gaps = ProtectionGaps.gaps.value.filter { it.seq > seen && it.seq <= presentSeq }
        val fresh = attempts.any { it.seq > notified } || gaps.any { it.seq > notified }
        val manager = NotificationManagerCompat.from(app)
        val showing = runCatching { app.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == NOTIFICATION_ID } }
            .getOrDefault(false)
        if (repost) p.edit { putBoolean(KEY_REPOST, false) }
        // Something new; or, at an unlock, the unreviewed one again if it was swiped away.
        if (!fresh && !(repost && !showing && (attempts.isNotEmpty() || gaps.isNotEmpty()))) return
        if (attempts.isEmpty() && gaps.isEmpty()) return
        if (!manager.areNotificationsEnabled() || ownChannelMuted(app)) return
        ensureChannel(app)
        val res = app.resources
        // Everything not yet reviewed, not just what's new: a later miss doesn't hide an earlier gap.
        val offs = gaps.filter { it.kind == ProtectionGaps.Kind.SWITCHED_OFF || it.kind == ProtectionGaps.Kind.FORCE_STOPPED }
        val title = when {
            gaps.isEmpty() -> res.getQuantityString(R.plurals.attempts_title, attempts.size, attempts.size)
            attempts.isEmpty() && offs.size == gaps.size -> res.getString(R.string.gaps_title)
            else -> res.getString(R.string.activity_title)
        }
        val text = res.getString(
            when {
                attempts.any { it.unanswered } -> R.string.attempts_notification_text
                attempts.isNotEmpty() -> R.string.attempts_screen_lock_notification_text
                offs.isNotEmpty() -> R.string.gaps_notification_text
                gaps.any { it.kind == ProtectionGaps.Kind.BIOMETRICS_CHANGED || it.kind == ProtectionGaps.Kind.BIOMETRICS_REMOVED } ->
                    R.string.biometrics_changed_notification_text
                else -> R.string.changes_notification_text
            }
        )
        val open = PendingIntent.getActivity(
            app,
            NOTIFICATION_ID,
            Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // Which app is never said, and nothing at all shows on the phone's lock screen. It stays
        // (a tap opens BioKey without dismissing it) until the owner opens Review.
        val notification = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_biokey_mark)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setContentIntent(open)
            .setAutoCancel(false)
            .setSilent(!fresh)
            .build()
        try {
            manager.notify(NOTIFICATION_ID, notification)
            // Up to the oldest still held back, so a held-back miss is never skipped.
            val newest = maxOf(attempts.maxOfOrNull { it.seq } ?: 0L, gaps.maxOfOrNull { it.seq } ?: 0L)
            val upTo = (retry.minOfOrNull { it.seq - 1 } ?: Long.MAX_VALUE).coerceAtMost(newest)
            if (upTo > notified) p.edit { putLong(KEY_NOTIFIED_SEQ, upTo) }
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS revoked between the check and notify.
        }
    }

    /** "3 tries didn't match", "locked out after 5 tries", "3 tries, then opened with your screen lock". */
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
