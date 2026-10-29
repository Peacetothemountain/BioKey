package com.android.BioKey.core.security

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
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
 * Failed unlocks of locked apps, for the owner to see: a face or fingerprint that didn't match
 * (or so many that biometrics locked out) on a prompt that then closed without the app opening.
 *
 * The owner fumbling (a wet finger, then the PIN) isn't someone else trying: an attempt is
 * dropped once the same app is unlocked within [OWNER_RETRY_MS] of it. What's left shows on
 * BioKey's home screen and, when notifications are allowed, as a notification that doesn't name
 * the app on the phone's lock screen. Kept in BioKey's private storage for [MAX_AGE_MS] (never
 * backed up); switching the alerts off in Settings deletes them.
 *
 * Android doesn't tell apps about a wrong PIN, pattern or password: only biometrics count.
 */
object UnlockAttempts {

    data class Attempt(val packageName: String, val at: Long, val failures: Int, val lockedOut: Boolean)

    /** An unlock of the same app this soon after is the owner, retrying. */
    const val OWNER_RETRY_MS = 2 * 60_000L
    private const val MAX_AGE_MS = 7 * 24 * 60 * 60_000L
    private const val MAX_KEPT = 50

    private const val PREFS = "biokey_unlock_attempts"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_ATTEMPTS = "attempts"
    /** Attempts up to this time have been seen on BioKey's home screen: no notification for them. */
    private const val KEY_SEEN_UNTIL = "seen_until"
    private const val CHANNEL_ID = "biokey_unlock_attempts"
    private const val NOTIFICATION_ID = 1101

    private val _attempts = MutableStateFlow<List<Attempt>>(emptyList())
    /** Newest first. */
    val attempts: StateFlow<List<Attempt>> = _attempts.asStateFlow()
    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    @Volatile private var prefs: SharedPreferences? = null
    private val lock = Any()
    /** The wall clock; tests set their own. */
    internal var clock: () -> Long = System::currentTimeMillis

    /** Reads what's kept, once per process. Cheap enough for the main thread: a few lines of JSON. */
    fun load(context: Context): SharedPreferences {
        prefs?.let { return it }
        synchronized(lock) {
            prefs?.let { return it }
            val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            _enabled.value = p.getBoolean(KEY_ENABLED, true)
            _attempts.value = runCatching { parse(p.getString(KEY_ATTEMPTS, null)) }.getOrDefault(emptyList()).fresh()
            prefs = p
            return p
        }
    }

    private fun List<Attempt>.fresh(): List<Attempt> {
        val now = clock()
        return filter { now - it.at in 0..MAX_AGE_MS }.sortedByDescending { it.at }.take(MAX_KEPT)
    }

    /** A prompt for [packageName] closed without unlocking it, after [failures] mismatches. */
    fun record(context: Context, packageName: String, failures: Int, lockedOut: Boolean) {
        if (failures <= 0 && !lockedOut) return
        val p = load(context)
        if (!_enabled.value) return
        synchronized(lock) {
            save(p, (listOf(Attempt(packageName, clock(), failures, lockedOut)) + _attempts.value).fresh())
        }
        DiagnosticLog.log("Failed unlock of $packageName: $failures didn't match" + if (lockedOut) ", biometrics locked out" else "")
        updateNotification(context)
    }

    /** [packageName] was unlocked: its attempts of the last [OWNER_RETRY_MS] were the owner's. */
    fun onUnlocked(context: Context, packageName: String) {
        val p = load(context)
        val names = PasswordManager.aliasesOf(packageName)
        val now = clock()
        synchronized(lock) {
            val kept = _attempts.value.filterNot { it.packageName in names && now - it.at in 0..OWNER_RETRY_MS }
            if (kept.size == _attempts.value.size) return
            save(p, kept)
        }
        updateNotification(context)
    }

    /** BioKey's home screen showed them: the notification has done its job. */
    fun markSeen(context: Context) {
        val p = load(context)
        p.edit { putLong(KEY_SEEN_UNTIL, clock()) }
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    fun clear(context: Context) {
        val p = load(context)
        synchronized(lock) { save(p, emptyList()) }
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    fun setEnabled(context: Context, on: Boolean) {
        val p = load(context)
        _enabled.value = on
        p.edit { putBoolean(KEY_ENABLED, on) }
        if (!on) clear(context)
    }

    private fun save(p: SharedPreferences, list: List<Attempt>) {
        _attempts.value = list
        p.edit { putString(KEY_ATTEMPTS, serialize(list)) }
    }

    private fun serialize(list: List<Attempt>): String = JSONArray().apply {
        list.forEach { put(JSONObject().put("p", it.packageName).put("t", it.at).put("f", it.failures).put("l", it.lockedOut)) }
    }.toString()

    private fun parse(json: String?): List<Attempt> {
        if (json.isNullOrEmpty()) return emptyList()
        val array = JSONArray(json)
        return (0 until array.length()).map { i ->
            array.getJSONObject(i).let { Attempt(it.getString("p"), it.getLong("t"), it.optInt("f"), it.optBoolean("l")) }
        }
    }

    /** What the user sees for [packageName]: its name, or BioKey's for BioKey's own lock. */
    fun labelOf(context: Context, packageName: String): String {
        if (packageName == context.packageName) return context.getString(R.string.brand_name)
        return runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        }.getOrDefault(context.getString(R.string.brand_name))
    }

    private fun updateNotification(context: Context) {
        val app = context.applicationContext
        val p = load(app)
        val seenUntil = p.getLong(KEY_SEEN_UNTIL, 0L)
        val unseen = _attempts.value.filter { it.at > seenUntil }
        val manager = NotificationManagerCompat.from(app)
        if (unseen.isEmpty()) {
            manager.cancel(NOTIFICATION_ID)
            return
        }
        if (!manager.areNotificationsEnabled()) return
        val nm = app.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Failed unlock attempts", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "When a face or fingerprint that didn't match was tried on a locked app"
                }
            )
        }
        val title = if (unseen.size == 1) "Failed unlock attempt" else "${unseen.size} failed unlock attempts"
        val newest = unseen.first()
        val text = labelOf(app, newest.packageName) + ": " + describe(newest)
        val open = PendingIntent.getActivity(
            app,
            NOTIFICATION_ID,
            Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // On the phone's lock screen, which app isn't said.
        val public = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_biokey_mark)
            .setContentTitle(title)
            .setContentText("Open BioKey to see which app")
            .build()
        val notification = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_biokey_mark)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS revoked between the check and notify.
        }
    }

    /** "3 tries didn't match" / "biometrics locked out after 5 tries". */
    fun describe(attempt: Attempt): String {
        val tries = if (attempt.failures == 1) "1 try" else "${attempt.failures} tries"
        return when {
            attempt.lockedOut && attempt.failures > 0 -> "face or fingerprint locked out after $tries"
            attempt.lockedOut -> "face or fingerprint locked out"
            else -> "$tries didn't match"
        }
    }
}
