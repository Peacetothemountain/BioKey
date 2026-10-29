package com.android.BioKey.core.security

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import androidx.annotation.VisibleForTesting
import androidx.core.content.edit
import com.android.BioKey.core.diagnostics.DiagnosticLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * When app locking stopped and came back: the trace left by someone who switched BioKey's
 * service off, or force-stopped it, while holding the unlocked phone. The Play build can't stop
 * them (Play allows blocking that only to parental-control and enterprise apps), so BioKey shows
 * the owner afterwards: "Protection was off, 3:02 to 3:10 PM" on its home screen until dismissed.
 *
 * - **Switched off** (accessibility switch, a permission revoked): the engine says it stopped, and
 *   the gap runs from then until an engine runs again.
 * - **Stopped without a word** (force stop): nothing is told, so when the engine comes back the
 *   gap runs from the last moment BioKey knew it was running ([touch]: at most a minute apart
 *   while the phone is in use, and at every screen on and off) until now. Not when the screen
 *   was off at that moment (nobody was using the phone; Android stopped it), after a restart of
 *   the phone, or after an update of BioKey itself.
 *
 * Gaps shorter than [MIN_GAP_MS] (Android restarting a crashed service, one engine handing over
 * to the other) aren't kept. A pause from BioKey itself isn't a gap: it's behind authentication.
 * Kept in BioKey's private storage for [UnlockAttempts.MAX_AGE_MS]; times only, nothing else.
 */
object ProtectionGaps {

    data class Gap(val from: Long, val to: Long, val unannounced: Boolean)

    private const val MIN_GAP_MS = 90_000L
    private const val MAX_KEPT = 20
    /** [touch] writes at most this often. */
    private const val TOUCH_EVERY_MS = 60_000L

    private const val PREFS = "biokey_protection_gaps"
    private const val KEY_GAPS = "gaps"
    /** An engine is running, since this boot ([bootCount]). */
    private const val KEY_UP_BOOT = "up_boot"
    /** The last time an engine was known to be running. */
    private const val KEY_ALIVE_AT = "alive_at"
    /** Whether the screen was on at [KEY_ALIVE_AT]. */
    private const val KEY_ALIVE_SCREEN_ON = "alive_screen_on"
    /** BioKey's own install or update time when the engine came up: a newer one is an update, not a kill. */
    private const val KEY_UP_INSTALL = "up_install"
    /** An engine said it stopped, then. */
    private const val KEY_STOPPED_AT = "stopped_at"

    private val _gaps = MutableStateFlow<List<Gap>>(emptyList())
    /** Newest first. */
    val gaps: StateFlow<List<Gap>> = _gaps.asStateFlow()

    @Volatile private var prefs: SharedPreferences? = null
    private val lock = Any()
    @Volatile private var lastTouch = 0L

    @VisibleForTesting
    internal var clock: () -> Long = System::currentTimeMillis
    @VisibleForTesting
    internal var bootCount: (Context) -> Int = { Settings.Global.getInt(it.contentResolver, Settings.Global.BOOT_COUNT, 0) }
    @VisibleForTesting
    internal var installTime: (Context) -> Long = {
        runCatching { it.packageManager.getPackageInfo(it.packageName, 0).lastUpdateTime }.getOrDefault(0L)
    }

    fun load(context: Context): SharedPreferences {
        prefs?.let { return it }
        synchronized(lock) {
            prefs?.let { return it }
            val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            _gaps.value = runCatching { parse(p.getString(KEY_GAPS, null)) }.getOrDefault(emptyList()).fresh()
            prefs = p
            return p
        }
    }

    @VisibleForTesting
    internal fun resetForTests() {
        synchronized(lock) { prefs = null }
        lastTouch = 0L
    }

    private fun List<Gap>.fresh(): List<Gap> {
        val now = clock()
        return filter { now - it.to in 0..UnlockAttempts.MAX_AGE_MS }.sortedByDescending { it.to }.take(MAX_KEPT)
    }

    /** An engine is running (it connected, or BioKey's process started with one). */
    fun onEngineUp(context: Context) {
        val p = load(context)
        val now = clock()
        val boot = bootCount(context)
        val installed = installTime(context)
        synchronized(lock) {
            val stoppedAt = p.getLong(KEY_STOPPED_AT, 0L)
            val wasUp = p.getInt(KEY_UP_BOOT, -1)
            val aliveAt = p.getLong(KEY_ALIVE_AT, 0L)
            val gap = when {
                stoppedAt > 0L -> Gap(stoppedAt, now, unannounced = false)
                // Up before, in this same boot and this same install, in use, and never said it
                // stopped: someone stopped it.
                wasUp == boot && p.getLong(KEY_UP_INSTALL, 0L) == installed && aliveAt > 0L &&
                    p.getBoolean(KEY_ALIVE_SCREEN_ON, false) -> Gap(aliveAt, now, unannounced = true)
                else -> null
            }
            if (gap != null && gap.to - gap.from >= MIN_GAP_MS && SecurityVault.getInstance(context).isSetupCompleted()) {
                save(p, (listOf(gap) + _gaps.value).fresh())
                DiagnosticLog.log("App locking was off for ${(gap.to - gap.from) / 1000} s" + if (gap.unannounced) " (stopped without notice)" else "")
            }
            p.edit {
                putInt(KEY_UP_BOOT, boot).putLong(KEY_UP_INSTALL, installed).putLong(KEY_ALIVE_AT, now)
                    .putBoolean(KEY_ALIVE_SCREEN_ON, true).remove(KEY_STOPPED_AT)
            }
        }
        lastTouch = now
    }

    /** No engine is running any more (switched off, a permission revoked). */
    fun onEngineDown(context: Context) {
        val p = load(context)
        synchronized(lock) {
            if (p.getLong(KEY_STOPPED_AT, 0L) == 0L) p.edit { putLong(KEY_STOPPED_AT, clock()).remove(KEY_UP_BOOT) }
        }
    }

    /**
     * An engine is still running now. Engines call this on what they see (cheap: it writes at
     * most once a minute); the screen turning on or off ([screenOn]) is always written.
     */
    fun touch(context: Context, screenOn: Boolean? = null) {
        val now = clock()
        if (screenOn == null && now - lastTouch < TOUCH_EVERY_MS) return
        lastTouch = now
        val p = load(context)
        if (p.getInt(KEY_UP_BOOT, -1) < 0) return
        p.edit {
            putLong(KEY_ALIVE_AT, now)
            putBoolean(KEY_ALIVE_SCREEN_ON, screenOn ?: true)
        }
    }

    /** Removes the kept gaps; returns them, for Undo. */
    fun clear(context: Context): List<Gap> {
        val p = load(context)
        return synchronized(lock) { _gaps.value.also { save(p, emptyList()) } }
    }

    fun restore(context: Context, gaps: List<Gap>) {
        val p = load(context)
        synchronized(lock) { save(p, (_gaps.value + gaps).distinct().fresh()) }
    }

    private fun save(p: SharedPreferences, list: List<Gap>) {
        _gaps.value = list
        p.edit { putString(KEY_GAPS, JSONArray().apply { list.forEach { put(JSONObject().put("f", it.from).put("t", it.to).put("u", it.unannounced)) } }.toString()) }
    }

    private fun parse(json: String?): List<Gap> {
        if (json.isNullOrEmpty()) return emptyList()
        val array = JSONArray(json)
        return (0 until array.length()).map { i -> array.getJSONObject(i).let { Gap(it.getLong("f"), it.getLong("t"), it.optBoolean("u")) } }
    }
}
