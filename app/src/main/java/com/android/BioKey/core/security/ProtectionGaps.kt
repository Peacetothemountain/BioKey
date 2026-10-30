package com.android.BioKey.core.security

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import com.android.BioKey.R
import com.android.BioKey.core.diagnostics.DiagnosticLog
import java.io.File
import java.nio.file.Files
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
 * Alongside the gaps, moments ([recordEvent]): a face or fingerprint added or removed, and changes
 * that weaken protection made from BioKey opened with the screen lock ([recordIfScreenLock]: a
 * pause, a lock or profile switched off, a longer delay, and the like).
 *
 * A gap's length is measured on the clock since boot where it can be, and its order on
 * [UnlockAttempts]' sequence numbers, so changing the phone's clock neither shortens nor hides
 * it. A pause from BioKey itself isn't a gap: it's behind authentication, and recorded as a change
 * when the screen lock allowed it. Kept in BioKey's private storage: times, and for changes what
 * changed; at most [MAX_KEPT], seen ones for [UnlockAttempts.MAX_AGE_MS] of network time.
 * Someone who clears BioKey's storage deletes this too, but that also undoes BioKey's setup,
 * which the owner can't miss.
 */
object ProtectionGaps {

    enum class Kind {
        SWITCHED_OFF,
        FORCE_STOPPED,
        /** A strong face or fingerprint was added or removed ([BiometricEnrollment]); a moment, not a span. */
        BIOMETRICS_CHANGED,
        /** The phone has no strong face or fingerprint any more. */
        BIOMETRICS_REMOVED,
        /** Notification access taken away while "Hide notification content" was on. */
        SHIELD_ACCESS_REVOKED,
        /**
         * Protection weakened (paused, an app's lock off, a profile off or deleted, the notification
         * shield off) from BioKey opened with the screen lock, not a face or
         * fingerprint, on a phone that has one: maybe someone who knows the PIN. [Gap.detail] says what.
         */
        CHANGED_WITH_SCREEN_LOCK,
        /**
         * BioKey's storage was cleared in Android's settings ([checkStorageCleared]): every lock and
         * record went with it. A span, from then until BioKey was set up again.
         */
        DATA_CLEARED;

        /**
         * Starts a day's hold ([UnlockAttempts.heldUntil]) and can't be cleared, only aged out: after
         * it nothing proves it's the owner (a face or fingerprint added, or BioKey forgetting which
         * were there).
         */
        val holds: Boolean get() = this == BIOMETRICS_CHANGED || this == BIOMETRICS_REMOVED || this == DATA_CLEARED
    }

    /**
     * What a [Kind.CHANGED_WITH_SCREEN_LOCK] record says changed. Stored as a code and its arguments
     * (an app's or profile's name, a delay), and worded only when shown, in the reader's language.
     */
    enum class Change(@StringRes val text: Int, val fromScreenLock: Boolean = true, @PluralsRes val plural: Int = 0) {
        PAUSED(R.string.change_paused),
        LOCK_OFF(R.string.change_lock_off),
        PROFILE_OFF(R.string.change_profile_off),
        MOVED_UNLOCKED(R.string.change_moved_unlocked),
        MOVED_LONGER_DELAY(R.string.change_moved_longer_delay),
        TAKEN_OUT(R.string.change_taken_out),
        ADDED_TO_OFF_PROFILE(R.string.change_added_to_off_profile),
        /** Arguments: how many, and the profile; worded with [plural]. */
        ADDED_MANY_TO_OFF_PROFILE(0, plural = R.plurals.change_added_many_to_off_profile),
        PROFILE_DELETED(R.string.change_profile_deleted),
        PROFILE_DELAY_LONGER(R.string.change_profile_delay_longer),
        /** Argument: the [com.android.BioKey.core.data.LockTimeoutMode] name. */
        DEFAULT_DELAY_LONGER(R.string.change_default_delay_longer),
        SHIELD_OFF(R.string.change_shield_off),
        BIOMETRIC_ONLY_OFF(R.string.change_biometric_only_off),
        REMINDERS_STOPPED(R.string.change_reminders_stopped),
        LOG_SHARED(R.string.change_log_shared),
        /** Not a change from a session: the sensor couldn't be used, so the screen lock was allowed. */
        BIOMETRIC_UNAVAILABLE(R.string.change_biometric_unavailable, fromScreenLock = false);

        fun encode(vararg args: String) = (listOf(CODE_MARK + name) + args).joinToString(SEPARATOR)

        /**
         * As [encode], for a change made during a hold ([UnlockAttempts.holding]): within a day of a
         * face or fingerprint change, or of BioKey being reset ([byReset]).
         */
        fun encodeDuringHold(vararg args: String, byReset: Boolean = false) = (if (byReset) RESET_MARK else HOLD_MARK) + encode(*args)

        companion object {
            private const val CODE_MARK = "#"
            private const val SEPARATOR = "\u001F"

            private const val HOLD_MARK = "!"
            private const val RESET_MARK = "~"

            /** Made during a hold, whatever opened BioKey: a face or fingerprint change's, or a reset's. */
            fun duringHold(detail: String) = detail.startsWith(HOLD_MARK + CODE_MARK) || duringReset(detail)

            /** Made within a day of BioKey being reset ([Kind.DATA_CLEARED]). */
            fun duringReset(detail: String) = detail.startsWith(RESET_MARK + CODE_MARK)

            /** A stored detail's code and arguments; null for a record written as plain text (older ones). */
            fun decode(detail: String): Pair<Change, List<String>>? {
                val coded = detail.removePrefix(HOLD_MARK).removePrefix(RESET_MARK)
                if (!coded.startsWith(CODE_MARK)) return null
                val parts = coded.removePrefix(CODE_MARK).split(SEPARATOR)
                val change = entries.find { it.name == parts.first() } ?: return null
                return change to parts.drop(1)
            }
        }
    }

    data class Gap(
        val from: Long,
        val to: Long,
        val kind: Kind,
        val seq: Long = 0L,
        val detail: String = "",
        /** [to] on the clock since boot, which boot, and whether [to] is on the network's time: for ageing. */
        val toElapsed: Long = 0L,
        val boot: Int = -1,
        val net: Boolean = true,
        /** A force stop whose start can't be told (the phone's clock was set back since): [from] is only "by". */
        val unknownStart: Boolean = false
    )

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
    /** Trusted time minus the phone's clock, while an engine last ran: for reading Android's exit times. */
    private const val KEY_CLOCK_OFFSET = "clock_offset"
    /** Trusted time and the phone's clock drifting apart by more than this: the clock was changed. */
    private const val CLOCK_CHANGE_MS = 60_000L
    /** BioKey's last update time as an engine last saw it: an update since is what excuses a stop. */
    private const val KEY_LAST_UPDATE = "last_update"
    /** The process exits already looked at, by their time: Android keeps the last few. */
    private const val KEY_EXITS_SEEN = "exits_seen"
    /** How many looked-at exits are remembered: well beyond the 16 Android is asked for. */
    private const val MAX_EXITS_SEEN = 64
    /** This storage has been looked at once ([checkStorageCleared]). */
    private const val KEY_STORAGE_SEEN = "storage_seen"
    /** When BioKey's storage was cleared, on BioKey's clock, until it's set up again and recorded. */
    private const val KEY_CLEARED_AT = "cleared_at"
    /** ... and whether that time can't be told (the phone's clock was set back since). */
    private const val KEY_CLEARED_UNKNOWN = "cleared_unknown"

    private fun cleared(p: SharedPreferences) =
        if (p.contains(KEY_CLEARED_AT)) Cleared(p.getLong(KEY_CLEARED_AT, 0L), known = !p.getBoolean(KEY_CLEARED_UNKNOWN, false)) else null

    /** BioKey's storage was cleared some time after [after] (BioKey's clock), or at a time that can't be told ([known] false). */
    data class Cleared(val after: Long, val known: Boolean = true)

    private val _cleared = MutableStateFlow<Cleared?>(null)
    /** BioKey's storage was cleared ([checkStorageCleared]), until that's recorded; else null. */
    val cleared: StateFlow<Cleared?> = _cleared.asStateFlow()

    private val _gaps = MutableStateFlow<List<Gap>>(emptyList())
    /** Newest first. */
    val gaps: StateFlow<List<Gap>> = _gaps.asStateFlow()

    @Volatile private var prefs: SharedPreferences? = null
    private val lock = Any()

    @VisibleForTesting
    internal var clock: () -> Long = TrustedClock::now
    @VisibleForTesting
    internal var elapsed: () -> Long = SystemClock::elapsedRealtime
    @Volatile private var cachedBoot: Int? = null
    @VisibleForTesting
    internal var bootCount: (Context) -> Int = { context ->
        cachedBoot ?: Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0).also { cachedBoot = it }
    }
    @VisibleForTesting
    internal var sdk: () -> Int = { Build.VERSION.SDK_INT }

    /** The phone's own (user-set) clock, which Android's exit records use. */
    @VisibleForTesting
    internal var wallNow: () -> Long = System::currentTimeMillis

    /** When BioKey was last installed or updated. */
    @VisibleForTesting
    internal var updatedAt: (Context) -> Long = {
        runCatching { it.packageManager.getPackageInfo(it.packageName, 0).lastUpdateTime }.getOrDefault(0L)
    }
    /**
     * Anything in BioKey's storage written before this process started: storage from before (an
     * update keeps it), not one cleared since. A clearing empties it, and everything after is
     * this process's own.
     */
    @VisibleForTesting
    internal var storageFromBefore: (Context) -> Boolean = { context ->
        val started = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime())
        hasFileOlderThan(context.dataDir, started - STORAGE_AGE_SLACK_MS)
    }

    /**
     * Any file under [dir] last written before [time], on the phone's clock. Links aren't followed
     * (what they point at isn't BioKey's storage). Anything that can't be read, [dir] itself
     * included, counts as older: a failure can miss a clearing, never claim one.
     */
    @VisibleForTesting
    internal fun hasFileOlderThan(
        dir: File,
        time: Long,
        isLink: (File) -> Boolean = { Files.isSymbolicLink(it.toPath()) },
        /** What a folder holds; null if it can't be read. */
        list: (File) -> Array<File>? = File::listFiles
    ): Boolean = runCatching {
        if (!dir.isDirectory || list(dir) == null) return@runCatching true
        var unreadable = false
        val older = dir.walkTopDown()
            // Links aren't entered; a subfolder that can't be read is counted, not skipped.
            .onEnter { (it == dir || !isLink(it)) && (list(it) != null || run { unreadable = true; false }) }
            .onFail { _, _ -> unreadable = true }
            .any { it.isFile && !isLink(it) && it.lastModified() < time }
        older || unreadable
    }.getOrDefault(true)

    /** Files a little older than this process (written as it started) are still its own. */
    private const val STORAGE_AGE_SLACK_MS = 10_000L

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
            _cleared.value = cleared(p)
            // What's seen and past its time goes from the disk too, not only from what's shown.
            val fresh = kept.fresh(context)
            if (fresh != kept) save(p, fresh) else _gaps.value = kept
            return p
        }
    }

    @VisibleForTesting
    internal fun resetForTests() {
        synchronized(lock) {
            prefs = null
            _gaps.value = emptyList()
            _cleared.value = null
        }
        recordedOnce.clear()
    }

    /**
     * Seen ones past their time go, aged on clocks a changed date doesn't move ([UnlockAttempts.Ageing]);
     * unseen ones stay. Over the limit, seen ones go first: piling up new records can't push out
     * one the owner hasn't seen.
     */
    private fun List<Gap>.fresh(context: Context): List<Gap> {
        val seen = UnlockAttempts.seenSeq(context)
        // On this object's own clocks, the ones its records were stamped with.
        val age = UnlockAttempts.Ageing(bootCount(context), elapsed())
        val kept = filterNot { it.seq <= seen && age.past(it.to, it.net, it.toElapsed, it.boot) }.sortedByDescending { it.seq }
        val unseen = kept.filter { it.seq > seen }
        // An unseen face or fingerprint change is never pushed out: a flood of other records mustn't
        // be a way to end its hold.
        val (biometric, others) = unseen.partition { it.kind.holds }
        val unseenKept = biometric + others.take((UnlockAttempts.MAX_UNSEEN - biometric.size).coerceAtLeast(0))
        return (unseenKept + kept.filter { it.seq <= seen }.take((MAX_KEPT - unseenKept.size).coerceAtLeast(0)))
            .sortedByDescending { it.seq }
    }

    /** Something that happened at one moment ([Kind.BIOMETRICS_CHANGED], a pause): kept and told like a gap. */
    fun recordEvent(context: Context, kind: Kind, detail: String = ""): Long? {
        val p = load(context) ?: return null
        if (!SecurityVault.getInstance(context).isSetupCompleted()) return null
        val now = clock()
        val seq = UnlockAttempts.nextSeq(context)
        synchronized(lock) {
            save(p, (listOf(Gap(now, now, kind, seq, detail).stamped(context)) + _gaps.value).fresh(context))
        }
        DiagnosticLog.log("Security activity: ${kind.name.lowercase().replace('_', ' ')}")
        UnlockAttempts.onActivity(context)
        return seq
    }

    /** The state record [seq] began ([recordEvent]) is over: it ends now, and says from when to when. */
    fun endEvent(context: Context, seq: Long) {
        val p = load(context) ?: return
        val now = clock()
        synchronized(lock) {
            val list = _gaps.value
            if (list.none { it.seq == seq }) return
            save(p, list.map { if (it.seq == seq) it.copy(to = now).stamped(context) else it })
        }
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
            // looked at starts the gap, unless the engine's own report of stopping came earlier.
            // Looked-at exits are remembered one by one, not as "up to a time": a changed date
            // can't make a new one look old.
            val seenExits = p.getStringSet(KEY_EXITS_SEEN, null)
            val exits = exits(context)
            val update = updatedAt(context)
            // Only a real update excuses a stop near it: the time it happened changed since an engine
            // last ran. (A date set to the last update's, then a force stop, isn't one.)
            val updatedSince = !p.contains(KEY_LAST_UPDATE) || p.getLong(KEY_LAST_UPDATE, 0L) != update
            val forceStopAt = if (seenExits == null) null else exits
                .filter { (at, why) ->
                    at.toString() !in seenExits && why == ApplicationExitInfo.REASON_USER_REQUESTED &&
                        // Android 14+ gives an update its own reason; before, it looks like a force stop.
                        (sdk() >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE || !updatedSince || kotlin.math.abs(at - update) > UPDATE_WINDOW_MS)
                }
                .minOfOrNull { it.first }
            // Dated after "now": the clock was set back since. Still a stop, of a length that can't be
            // told, so it's kept whatever its length looks like.
            val clockMoved = forceStopAt != null && forceStopAt > wallNow()
            // Android stamps exits on the phone's clock; "now" is BioKey's trusted one. Converted with
            // the difference between the two in force when an engine last ran ([onClockChanged]) or
            // now, the earliest result kept: the phone's clock moved before the stop, after it, or
            // both, can only lengthen the gap, never shorten it.
            val kept = if (p.contains(KEY_CLOCK_OFFSET)) p.getLong(KEY_CLOCK_OFFSET, 0L) else null
            val current = now - wallNow()
            val converted = forceStopAt?.let { at -> listOfNotNull(current, kept).minOf { at + it } }
            val forceStop = converted?.coerceAtMost(now)
            // The phone's clock changed while BioKey was stopped (or just before, unheard): which of
            // the two differences the stop was stamped with can't be told, so neither can its start.
            val clockChanged = kept != null && kotlin.math.abs(current - kept) > CLOCK_CHANGE_MS
            when {
                forceStop != null && (switchedOffFrom == null || forceStop < switchedOffFrom) &&
                    (clockMoved || clockChanged || now - forceStop >= MIN_GAP_MS) ->
                    // Converted to "now" or later (the clock was set back), or the clock changed since:
                    // when it stopped can't be told.
                    found += Gap(forceStop, now, Kind.FORCE_STOPPED, unknownStart = clockChanged || (clockMoved && converted >= now - MIN_GAP_MS))
                switchedOffFrom != null -> found += Gap(switchedOffFrom, now, Kind.SWITCHED_OFF)
            }
            // Its storage was cleared ([checkStorageCleared]): nothing locked from then until set up again.
            cleared(p)?.takeIf { setUp }?.let { found += Gap(it.after.coerceAtMost(now), now, Kind.DATA_CLEARED, unknownStart = !it.known) }
            if (setUp && found.isNotEmpty()) {
                recorded = true
                save(p, (found.map { it.copy(seq = UnlockAttempts.nextSeq(context)).stamped(context) } + _gaps.value).fresh(context))
                found.forEach {
                    val length = if (it.unknownStart) "a time that can't be told" else "${(it.to - it.from) / 1000} s"
                    DiagnosticLog.log("App locking was off for $length (${it.kind.name.lowercase().replace('_', ' ')})")
                }
            }
            p.edit {
                remove(KEY_STOPPED_AT).remove(KEY_STOPPED_ELAPSED).remove(KEY_STOPPED_BOOT)
                putLong(KEY_CLOCK_OFFSET, now - wallNow())
                putLong(KEY_LAST_UPDATE, update)
                // Added to, not replaced: Android lists only the newest exits, and after a restart it
                // can have lost its newest, so an older one seen before comes back into the list.
                // Those listed now are always kept (a clock set back can make a new one look
                // oldest); of the rest, the latest are, up to the limit.
                val listed = exits.map { it.first }.distinct()
                val earlier = seenExits.orEmpty().mapNotNull { it.toLongOrNull() }.filter { it !in listed }
                    .sortedDescending().take((MAX_EXITS_SEEN - listed.size).coerceAtLeast(0))
                val seen = listed + earlier
                putStringSet(KEY_EXITS_SEEN, seen.mapTo(HashSet()) { it.toString() })
                if (setUp) remove(KEY_CLEARED_AT).remove(KEY_CLEARED_UNKNOWN)
            }
            if (setUp) _cleared.value = null
        }
        // Told the next time the phone is unlocked, with any failed unlocks.
        if (recorded) UnlockAttempts.onActivity(context)
    }

    /**
     * Once per storage, as BioKey's process starts: was it cleared (Settings > Apps > BioKey >
     * Clear storage), taking every lock and record with it? Yes when BioKey isn't set up, nothing in
     * its storage is older than this process ([storageFromBefore]: an update keeps the old files,
     * a clearing leaves none), and Android remembers BioKey running before (any exit but an
     * update's own; a fresh install has none, as an uninstall takes Android's record with it).
     * Ages, not the update's time, so neither a clock set back nor an update after the clearing
     * hides one. When it was cleared can't be told, only that it was after BioKey last ran: that's
     * the time kept, or "can't be told" if that's dated after now (the clock was set back since).
     *
     * Said on the setup screen, and as an alert where Android still allows one
     * ([ProtectionAlerts.remindIfStorageCleared]): a clearing also switches the accessibility
     * service off and, from Android 13, takes back the notification permission (seen on Android
     * 17, which keeps the exit record, "stop … due to clear data"). Once set up again, recorded
     * ([Kind.DATA_CLEARED]), which starts a day's hold like a face or fingerprint change: a
     * clearing also forgets which faces and fingerprints were there.
     */
    // REASON_PACKAGE_UPDATED is only read on Android 14+, where it exists ([sdk]).
    @SuppressLint("InlinedApi")
    fun checkStorageCleared(context: Context) {
        val app = context.applicationContext
        val p = load(app) ?: return
        val cleared = synchronized(lock) {
            if (p.contains(KEY_STORAGE_SEEN)) return@synchronized false
            // Set up already, or anything in it older than this process (a version from before this
            // check, updated): not cleared. Its files' ages, not the update's time, so neither a
            // clock set back nor an update after the clearing can hide one.
            val last = if (SecurityVault.getInstance(app).isSetupCompleted() || storageFromBefore(app)) null
                else exits(app)
                    // An update's own stop (Android 14+ says so) isn't one.
                    .filterNot { sdk() >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && it.second == ApplicationExitInfo.REASON_PACKAGE_UPDATED }
                    .maxOfOrNull { it.first }
            // Dated at or after the phone's "now": its clock was set back since, and when can't be told.
            val unknown = last != null && last >= wallNow()
            p.edit {
                putBoolean(KEY_STORAGE_SEEN, true)
                // Android's exit times are on the phone's clock: converted to BioKey's.
                if (last != null) {
                    putLong(KEY_CLEARED_AT, if (unknown) clock() else (last + clock() - wallNow()).coerceAtMost(clock()))
                    putBoolean(KEY_CLEARED_UNKNOWN, unknown)
                }
            }
            _cleared.value = cleared(p)
            last != null
        }
        if (cleared) {
            DiagnosticLog.log("BioKey's storage was cleared since it last ran")
            ProtectionAlerts.remindIfStorageCleared(app)
            // Also without the accessibility service, said again every so often until set up (where
            // it can be: from Android 13 a clearing takes back the notification permission too).
            if (NotificationManagerCompat.from(app).areNotificationsEnabled()) ProtectionCheckJob.schedule(app)
        }
    }

    /** Cleared ([cleared]) and not set up again: what the setup note and the alert are for. */
    fun awaitingSetupAfterClear(context: Context): Boolean =
        clearedAt(context) != null && !SecurityVault.getInstance(context).isSetupCompleted()

    /** When, as [cleared] has it, its storage read first. */
    fun clearedAt(context: Context): Long? {
        load(context)
        return _cleared.value?.after
    }

    /**
     * Set up again: a clearing before is recorded now, ending now, and its alert goes. With no
     * engine running yet (compatibility mode still to start), it's recorded when one starts, ending
     * then: locked apps weren't locked until it did.
     */
    fun onSetUp(context: Context) {
        val app = context.applicationContext
        recorder.execute {
            if (ProtectionEngine.isProtecting(app)) onEngineUp(app)
            ProtectionAlerts.cancelStorageCleared(app)
        }
    }

    /**
     * [what] was done from BioKey opened with the screen lock, on a phone with a strong face or
     * fingerprint: recorded ([Kind.CHANGED_WITH_SCREEN_LOCK]) and told at the next unlock. Done after
     * opening BioKey with a face or fingerprint, or on a phone without one, it's the owner's own.
     */
    fun recordIfScreenLock(context: Context, change: Change, vararg args: String): java.util.concurrent.Future<Long?> {
        val app = context.applicationContext
        // How BioKey was opened, as it is now: by the time the job runs it may have locked itself.
        val openedWithBiometric = SecurityEngine.INSTANCE.ownAppOpenedWithBiometric
        return recorder.submit<Long?> {
            when {
                SecurityEngine.ownerProven(app, openedWithBiometric) -> null
                UnlockAttempts.holding(app) -> recordEvent(
                    app, Kind.CHANGED_WITH_SCREEN_LOCK,
                    change.encodeDuringHold(*args, byReset = UnlockAttempts.heldBy(app) == Kind.DATA_CLEARED)
                )
                else -> recordEvent(app, Kind.CHANGED_WITH_SCREEN_LOCK, change.encode(*args))
            }
        }
    }

    /** The state record [seq] ended ([endEvent]) goes on: its switch-off was undone. */
    fun reopenEvent(context: Context, seq: Long) {
        val p = load(context) ?: return
        synchronized(lock) {
            val list = _gaps.value
            if (list.none { it.seq == seq }) return
            save(p, list.map { if (it.seq == seq) it.copy(to = it.from).stamped(context) else it })
        }
    }

    /** Where screen-lock changes are recorded: in order, off the main thread. */
    @VisibleForTesting
    internal var recorder: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "biokey-records").apply { isDaemon = true } }

    private val recordedOnce = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    /** [change], recorded at most once per run of BioKey's process (a state, not each prompt it affects). */
    fun recordOncePerProcess(context: Context, change: Change) {
        if (recordedOnce.add(change.name)) recordEvent(context, Kind.CHANGED_WITH_SCREEN_LOCK, change.encode())
    }

    /**
     * The phone's clock was changed. While an engine runs, the difference between trusted time and
     * the phone's clock is kept, the latest: the one in force when a force stop came, whose time
     * Android stamps on the phone's clock ([onEngineUp]). A clock moved ahead before the stop and
     * back afterwards can't shorten the gap (the stop is read with the clock it was stamped on),
     * nor one moved ahead and back long before it lengthen it to years.
     */
    fun onClockChanged(context: Context) {
        val p = load(context) ?: return
        synchronized(lock) {
            if (p.getLong(KEY_STOPPED_AT, 0L) != 0L) return
            p.edit { putLong(KEY_CLOCK_OFFSET, clock() - wallNow()) }
        }
    }

    /** Its end, as of now, on the clocks it's aged by ([UnlockAttempts.Ageing]). */
    private fun Gap.stamped(context: Context) =
        copy(toElapsed = elapsed(), boot = bootCount(context), net = TrustedClock.onNetworkTime())

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

    /**
     * Removes the kept records; returns them, for Undo. A face or fingerprint added or removed
     * stays until it ages out: whoever added one could otherwise use it to clear the record of
     * doing so.
     */
    fun clear(context: Context, upTo: Long = Long.MAX_VALUE): List<Gap> {
        val p = load(context) ?: return emptyList()
        val limit = UnlockAttempts.clearableUpTo(context, upTo)
        return synchronized(lock) {
            val (stays, goes) = _gaps.value.partition {
                it.kind.holds || it.seq > limit
            }
            save(p, stays)
            goes
        }
    }

    fun restore(context: Context, gaps: List<Gap>) {
        val p = load(context) ?: return
        synchronized(lock) { save(p, (_gaps.value + gaps).distinctBy { it.seq }.fresh(context)) }
    }

    private fun save(p: SharedPreferences, list: List<Gap>) {
        _gaps.value = list
        p.edit {
            putString(KEY_GAPS, JSONArray().apply {
                list.forEach {
                    put(
                        JSONObject().put("f", it.from).put("t", it.to).put("k", it.kind.name).put("q", it.seq).put("d", it.detail)
                            .put("r", it.toElapsed).put("b", it.boot).put("w", it.net).put("u", it.unknownStart)
                    )
                }
            }.toString())
        }
    }

    private fun parse(json: String?): List<Gap> {
        if (json.isNullOrEmpty()) return emptyList()
        val array = JSONArray(json)
        return (0 until array.length()).map { i ->
            array.getJSONObject(i).let {
                Gap(
                    it.getLong("f"), it.getLong("t"), runCatching { Kind.valueOf(it.getString("k")) }.getOrDefault(Kind.SWITCHED_OFF),
                    it.optLong("q"), it.optString("d"), it.optLong("r"), it.optInt("b", -1), it.optBoolean("w", true), it.optBoolean("u")
                )
            }
        }
    }
}
