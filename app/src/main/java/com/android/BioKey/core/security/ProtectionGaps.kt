package com.android.BioKey.core.security

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.os.UserManager
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
 * service off, or force-stopped BioKey, while holding the unlocked phone. The Play build can't
 * stop them (Play allows blocking that only to parental-control and enterprise apps), so BioKey
 * shows the owner afterwards: "App locking was off, 3:02 to 3:10 PM", on its home screen until
 * cleared, and in a notification the next time the phone is unlocked ([UnlockAttempts]).
 *
 * - **Switched off** (the accessibility switch, a permission revoked): the engine says it stopped
 *   ([onEngineDown], after the engines' handover grace), and the gap runs until one runs again.
 * - **Force-stopped**: nothing is told at the time; when an engine comes back, Android's record of
 *   why BioKey's process ended ([ActivityManager.getHistoricalProcessExitReasons]) says so, and
 *   when. A stop by Android itself (low memory, a crash) or a restart of the phone isn't a gap
 *   anyone chose, and isn't kept; nor is the stop that comes with an update of BioKey (before
 *   Android 14 it's recorded the same way as a force stop). Some phone makers' battery managers
 *   force-stop apps just as a person would: those show too.
 *
 * A gap's length is measured on the clock since boot where it can be, and its order on
 * [UnlockAttempts]' sequence numbers, so changing the phone's clock neither shortens nor hides
 * it. A pause from BioKey itself isn't a gap: it's behind authentication. Kept in BioKey's
 * private storage: times only, at most [MAX_KEPT], seen ones for [UnlockAttempts.MAX_AGE_MS].
 * Someone who clears BioKey's storage deletes this too, but that also undoes BioKey's setup,
 * which the owner can't miss.
 */
object ProtectionGaps {

    enum class Kind { SWITCHED_OFF, FORCE_STOPPED }

    data class Gap(val from: Long, val to: Long, val kind: Kind, val seq: Long = 0L)

    /** Shorter than this, a stop is an engine restarting or handing over, not a person. */
    private const val MIN_GAP_MS = 10_000L
    /** A process exit this close to BioKey's own update is the update. */
    private const val UPDATE_WINDOW_MS = 120_000L
    private const val MAX_KEPT = 20

    private const val PREFS = "biokey_protection_gaps"
    private const val KEY_GAPS = "gaps"
    /** An engine said it stopped: when (wall clock), and when on the clock since [KEY_STOPPED_BOOT]'s boot. */
    private const val KEY_STOPPED_AT = "stopped_at"
    private const val KEY_STOPPED_ELAPSED = "stopped_elapsed"
    private const val KEY_STOPPED_BOOT = "stopped_boot"
    /** Process exits up to this time have been looked at. */
    private const val KEY_EXITS_SEEN_UNTIL = "exits_seen_until"

    private val _gaps = MutableStateFlow<List<Gap>>(emptyList())
    /** Newest first. */
    val gaps: StateFlow<List<Gap>> = _gaps.asStateFlow()

    @Volatile private var prefs: SharedPreferences? = null
    private val lock = Any()

    @VisibleForTesting
    internal var clock: () -> Long = System::currentTimeMillis
    @VisibleForTesting
    internal var elapsed: () -> Long = SystemClock::elapsedRealtime
    @VisibleForTesting
    internal var bootCount: (Context) -> Int = { Settings.Global.getInt(it.contentResolver, Settings.Global.BOOT_COUNT, 0) }
    /** When BioKey was last installed or updated. */
    @VisibleForTesting
    internal var updatedAt: (Context) -> Long = {
        runCatching { it.packageManager.getPackageInfo(it.packageName, 0).lastUpdateTime }.getOrDefault(0L)
    }
    /** BioKey's process exits Android remembers: (when, why). */
    @VisibleForTesting
    internal var exits: (Context) -> List<Pair<Long, Int>> = { context ->
        runCatching {
            context.getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(context.packageName, 0, 16)
                .map { it.timestamp to it.reason }
        }.getOrDefault(emptyList())
    }

    /** Null before the first unlock after a restart: BioKey's storage isn't readable yet. */
    fun load(context: Context): SharedPreferences? {
        prefs?.let { return it }
        if (context.getSystemService(UserManager::class.java)?.isUserUnlocked == false) return null
        synchronized(lock) {
            prefs?.let { return it }
            val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val kept = runCatching { parse(p.getString(KEY_GAPS, null)) }.getOrDefault(emptyList())
            prefs = p
            // What's seen and past its time goes from the disk too, not only from what's shown.
            val fresh = kept.fresh(context)
            if (fresh != kept) save(p, fresh) else _gaps.value = kept
            return p
        }
    }

    @VisibleForTesting
    internal fun resetForTests() {
        synchronized(lock) { prefs = null }
    }

    /** Seen ones past their time go; unseen ones stay, whatever the clock says. */
    private fun List<Gap>.fresh(context: Context): List<Gap> {
        val now = clock()
        val seen = UnlockAttempts.seenSeq(context)
        return filterNot { it.seq <= seen && now - it.to > UnlockAttempts.MAX_AGE_MS }.sortedByDescending { it.seq }.take(MAX_KEPT)
    }

    /** Drops what's seen and past its time; BioKey's home screen calls this as it shows them. */
    fun prune(context: Context) {
        val p = load(context) ?: return
        synchronized(lock) { _gaps.value.fresh(context).let { if (it != _gaps.value) save(p, it) } }
    }

    /** An engine is running (it connected, or started). */
    fun onEngineUp(context: Context) {
        val p = load(context) ?: return
        val now = clock()
        val setUp = SecurityVault.getInstance(context).isSetupCompleted()
        var recorded = false
        synchronized(lock) {
            val found = mutableListOf<Gap>()
            val stoppedAt = p.getLong(KEY_STOPPED_AT, 0L)
            var switchedOffFrom: Long? = null
            if (stoppedAt > 0L) {
                // Measured on the clock since boot when it's the same boot: a changed clock can't shorten it.
                val sameBoot = p.getInt(KEY_STOPPED_BOOT, -1) == bootCount(context)
                val length = if (sameBoot) elapsed() - p.getLong(KEY_STOPPED_ELAPSED, 0L) else now - stoppedAt
                if (length >= MIN_GAP_MS) switchedOffFrom = now - length
            }
            // Force stops: told by Android afterwards, never at the time. The earliest one not yet
            // counted starts the gap, unless the engine's own report of stopping came earlier.
            val seenUntil = p.getLong(KEY_EXITS_SEEN_UNTIL, -1L)
            val exits = exits(context)
            val update = updatedAt(context)
            val forceStopAt = if (seenUntil < 0L) null else exits
                .filter { (at, why) -> at > seenUntil && why == ApplicationExitInfo.REASON_USER_REQUESTED && kotlin.math.abs(at - update) > UPDATE_WINDOW_MS }
                .minOfOrNull { it.first }
            // Dated after "now": the clock was set back since. Still a stop, of a length that can't be
            // told, so it's kept whatever its length looks like.
            val clockMoved = forceStopAt != null && forceStopAt > now
            val forceStop = forceStopAt?.coerceAtMost(now)
            when {
                forceStop != null && (switchedOffFrom == null || forceStop < switchedOffFrom) && (clockMoved || now - forceStop >= MIN_GAP_MS) ->
                    found += Gap(forceStop, now, Kind.FORCE_STOPPED)
                switchedOffFrom != null -> found += Gap(switchedOffFrom, now, Kind.SWITCHED_OFF)
            }
            if (setUp && found.isNotEmpty()) {
                recorded = true
                save(p, (found.map { it.copy(seq = UnlockAttempts.nextSeq(context)) } + _gaps.value).fresh(context))
                found.forEach { DiagnosticLog.log("App locking was off for ${(it.to - it.from) / 1000} s (${it.kind.name.lowercase().replace('_', ' ')})") }
            }
            p.edit {
                remove(KEY_STOPPED_AT).remove(KEY_STOPPED_ELAPSED).remove(KEY_STOPPED_BOOT)
                putLong(KEY_EXITS_SEEN_UNTIL, maxOf(now, exits.maxOfOrNull { it.first } ?: 0L))
            }
        }
        // Told the next time the phone is unlocked, with any failed unlocks.
        if (recorded) UnlockAttempts.onActivity(context)
    }

    /** No engine is running any more (switched off, a permission revoked). */
    fun onEngineDown(context: Context) {
        val p = load(context) ?: return
        synchronized(lock) {
            if (p.getLong(KEY_STOPPED_AT, 0L) != 0L) return
            p.edit {
                putLong(KEY_STOPPED_AT, clock()).putLong(KEY_STOPPED_ELAPSED, elapsed()).putInt(KEY_STOPPED_BOOT, bootCount(context))
            }
        }
    }

    /** Removes the kept gaps; returns them, for Undo. */
    fun clear(context: Context): List<Gap> {
        val p = load(context) ?: return emptyList()
        return synchronized(lock) { _gaps.value.also { save(p, emptyList()) } }
    }

    fun restore(context: Context, gaps: List<Gap>) {
        val p = load(context) ?: return
        synchronized(lock) { save(p, (_gaps.value + gaps).distinctBy { it.seq }.fresh(context)) }
    }

    private fun save(p: SharedPreferences, list: List<Gap>) {
        _gaps.value = list
        p.edit {
            putString(KEY_GAPS, JSONArray().apply { list.forEach { put(JSONObject().put("f", it.from).put("t", it.to).put("k", it.kind.name).put("q", it.seq)) } }.toString())
        }
    }

    private fun parse(json: String?): List<Gap> {
        if (json.isNullOrEmpty()) return emptyList()
        val array = JSONArray(json)
        return (0 until array.length()).map { i ->
            array.getJSONObject(i).let {
                Gap(it.getLong("f"), it.getLong("t"), runCatching { Kind.valueOf(it.getString("k")) }.getOrDefault(Kind.SWITCHED_OFF), it.optLong("q"))
            }
        }
    }
}
