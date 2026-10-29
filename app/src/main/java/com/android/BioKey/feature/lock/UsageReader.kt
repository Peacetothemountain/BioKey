package com.android.BioKey.feature.lock

import android.app.usage.UsageEvents
import android.app.usage.UsageEventsQuery
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.android.BioKey.core.data.PasswordManager
import com.android.BioKey.core.security.ProtectionEngine
import com.android.BioKey.core.security.SecurityVault

/**
 * Compatibility mode's look at the screen, on [UsageWatchService]'s worker thread: Android's
 * activity events since the last look (a look back when there's nothing to go on), and whether
 * the phone is locked, as one [Batch] per poll. It holds the read cursor, which only the worker
 * thread touches; deciding what to do with a batch is the service's, on the main thread.
 */
internal class UsageReader(
    private val context: Context,
    private val usage: UsageStatsManager,
    private val vault: SecurityVault,
    /** Bumped on the main thread whenever what a look on its way found no longer applies. */
    private val epoch: () -> Int,
    /** Whether the phone is locked, asked now (a binder call). */
    private val deviceLockedNow: () -> Boolean
) {
    /** Wall-clock end of the last query: each poll reads only from here (minus [OVERLAP_MS]) on. */
    private var queriedUntil = 0L
    private var lookBackNext = false
    private var rebaseNext = false
    private var wasIdle = false
    private var pollsSincePermissionCheck = 0
    /** Polls since the phone's lock state was last asked, and what it was ([deviceLocked]). */
    private var pollsSinceLockCheck = LOCK_CHECK_POLLS
    private var lastDeviceLocked = false

    /** The clock was changed: the next look reads afresh from now. */
    fun rebase() {
        rebaseNext = true
    }

    /** The next look is a look back: what was known about the screen no longer holds. */
    fun lookBackNext() {
        lookBackNext = true
    }

    /** The next look asks whether the phone is locked, whatever it finds. */
    fun askLockStateNext() {
        pollsSinceLockCheck = LOCK_CHECK_POLLS
    }

    /** The next look reads from [time] at the earliest (the screen coming back: what happened shortly before too). */
    fun readAgainFrom(time: Long) {
        queriedUntil = maxOf(queriedUntil, time)
    }

    /**
     * What one look at the screen found ([read]). Whether the keyguard is up as well is
     * asked only where a cover depends on it (the service's onBatch): another binder call, needless on the
     * many looks that find nothing to act on.
     */
    class Batch(
        val epoch: Int,
        val deviceLocked: Boolean,
        /** What compatibility mode lacks, when this look checked and found something missing. */
        val missing: String?,
        /** Nothing to protect (paused, or no app locked): nothing was read. */
        val idle: Boolean = false,
        /** A look back: what was known about the screen no longer holds. */
        val fresh: Boolean = false,
        /** The clock was changed: earlier times can't be compared with these; acted on from here. */
        val rebasedFrom: Long? = null,
        /** Where the read began. */
        val from: Long = 0L,
        val events: List<UsageEvent> = emptyList(),
        /** In a look back, locked apps' events from before [from]: what their activities did last ([lockedAppsBefore]). */
        val older: List<UsageEvent> = emptyList(),
        /** When the read was made (wall clock, as event times are). */
        val readAt: Long = 0L
    )

    /** One look at the screen: the device's lock state and the activity events since the last look. */
    fun read(): Batch {
        val current = epoch()
        val missing = if (++pollsSincePermissionCheck < PERMISSION_CHECK_POLLS) null else {
            pollsSincePermissionCheck = 0
            ProtectionEngine.missingCompatibilityPermissions(context)
        }
        val now = System.currentTimeMillis()
        // Nothing to protect: only the lock state matters, and a fresh look once there is again.
        // (The switch itself: a pause that ran out is ended on the main thread, see onBatch.)
        if (!vault.globalLockEnabledFlow.value || vault.getLockedPackages().isEmpty()) {
            wasIdle = true
            queriedUntil = now
            return Batch(current, deviceLocked(force = false), missing, idle = true)
        }
        if (lookBackNext || wasIdle) {
            lookBackNext = false
            wasIdle = false
            val floor = lookBackFloor(now)
            val (from, events) = lookBack(now, floor)
            val older = if (from > floor) lockedAppsBefore(floor, from, now) else emptyList()
            queriedUntil = now
            return Batch(current, deviceLocked(force = true), missing, fresh = true, from = from, events = events, older = older, readAt = now)
        }
        // The clock was changed, or went backwards (by hand, or corrected): nothing would look new any more.
        var rebasedFrom: Long? = null
        if (rebaseNext || now < queriedUntil) {
            rebaseNext = false
            queriedUntil = now
            rebasedFrom = now - OVERLAP_MS
        }
        val from = queriedUntil - OVERLAP_MS
        val events = query(from, now)
        queriedUntil = now
        // Asked at once when something happened: a cover may depend on it (onBatch).
        val deviceLocked = deviceLocked(force = events.isNotEmpty() || rebasedFrom != null)
        return Batch(current, deviceLocked, missing, rebasedFrom = rebasedFrom, from = from, events = events, readAt = now)
    }

    /**
     * Whether the phone is locked (a binder call), asked every [LOCK_CHECK_POLLS] polls, or now
     * with [force]; in between, as last asked. Locking the phone must end every session.
     */
    private fun deviceLocked(force: Boolean): Boolean {
        if (force || ++pollsSinceLockCheck >= LOCK_CHECK_POLLS) {
            pollsSinceLockCheck = 0
            lastDeviceLocked = deviceLockedNow()
        }
        return lastDeviceLocked
    }

    /**
     * How far back a look back may go: [STARTUP_LOOKBACK_MS], and never before this boot (usage
     * history survives reboots; only this boot's events say what's on screen now).
     */
    private fun lookBackFloor(now: Long): Long = maxOf(now - STARTUP_LOOKBACK_MS, now - SystemClock.elapsedRealtime())

    /**
     * The activity events from far enough back to include the newest app-resumed one: the last
     * 10 s first (usually enough), then further, down to [floor].
     */
    private fun lookBack(now: Long, floor: Long): Pair<Long, List<UsageEvent>> {
        var span = FIRST_LOOKBACK_MS
        while (true) {
            val from = maxOf(now - span, floor)
            val events = query(from, now)
            if (from == floor || events.any { it.type == UsageEvents.Event.ACTIVITY_RESUMED }) return from to events
            span *= LOOKBACK_GROWTH
        }
    }

    /**
     * Locked apps' activity events from [floor] to [to], where a look back's own window begins: a
     * window left in picture-in-picture, or resumed beside another app (split screen, a desktop
     * window), says nothing more until it changes, so only these tell a look back (after a pause,
     * when the engine starts or takes over) that it still shows. They only say what each activity
     * did last (the service's onBatch), never what is in front.
     *
     * Only those since the screen last came on (up to [now]): the screen going off stops every
     * activity, so a window floating now has been paused again since. An older pause is an
     * activity whose process died without saying so, which would cover the screen for nothing.
     */
    private fun lockedAppsBefore(floor: Long, to: Long, now: Long): List<UsageEvent> {
        val locked = vault.getLockedPackages()
        val apps = PasswordManager.expand(locked).toMutableSet()
        if (apps.isEmpty()) return emptyList()
        // Google Password Manager's screens are Play services' activities.
        if (PasswordManager.VIRTUAL_PACKAGE in apps) apps += PasswordManager.GMS
        val events = read(floor, now, screenOnToo = true, packages = apps + DEVICE_EVENTS_PACKAGE) ?: return emptyList()
        val found = ArrayList<UsageEvent>()
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when {
                event.eventType == UsageEvents.Event.SCREEN_INTERACTIVE -> found.clear()
                // From [to] on, the look back's own window has them.
                event.timeStamp < to && event.eventType in ACTIVITY_EVENTS && event.packageName in apps ->
                    usageEvent(event, locked)?.let(found::add)
            }
        }
        return found
    }

    /** Activity events (resumed, paused, stopped) logged from [from] to [to], oldest first. */
    private fun query(from: Long, to: Long): List<UsageEvent> {
        val events = read(from, to) ?: return emptyList()
        val found = ArrayList<UsageEvent>()
        val event = UsageEvents.Event()
        val locked = vault.getLockedPackages()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType !in ACTIVITY_EVENTS) continue
            usageEvent(event, locked)?.let(found::add)
        }
        return found
    }

    /**
     * Usage events logged from [from] to [to]: activity events (and the screen coming on, with
     * [screenOnToo]) of [packages], where Android can be asked for just those; the caller skips
     * the rest. Null if they can't be read.
     */
    private fun read(from: Long, to: Long, screenOnToo: Boolean = false, packages: Set<String>? = null): UsageEvents? {
        val begin = from.coerceIn(0L, to)
        return try {
            if (Build.VERSION.SDK_INT >= 35) {
                // Only the events wanted: far less for Android to gather and send every poll.
                val wanted = UsageEventsQuery.Builder(begin, to)
                if (screenOnToo) wanted.setEventTypes(*ACTIVITY_AND_SCREEN_ON_EVENTS) else wanted.setEventTypes(*ACTIVITY_EVENTS)
                if (packages != null) wanted.setPackageNames(*packages.toTypedArray())
                usage.queryEvents(wanted.build())
            } else {
                @Suppress("DEPRECATION")
                usage.queryEvents(begin, to)
            }
        } catch (_: Exception) {
            null
        }
    }

    /** [event], an activity event, as the engine sees it; [locked] are the apps locked now. */
    private fun usageEvent(event: UsageEvents.Event, locked: Set<String>): UsageEvent? {
        val pkg = event.packageName ?: return null
        val lockedOverlay = pkg in ForegroundRules.SYSTEM_OVERLAYS && pkg in locked
        return UsageEvent(event.eventType, pkg, event.className, event.timeStamp, lockedOverlay)
    }

    companion object {
        /**
         * Re-read a little of the past each time, so no event slips between two polls: Android can
         * log an event after a poll that ends later than it happened. Generous, as how late events
         * really come hasn't been measured on enough phones; debug builds log the latest seen
         * (UsageWatchService's noteLateness), so it can be brought down once it has.
         */
        internal const val OVERLAP_MS = 2_000L
        /**
         * Whether the phone is locked is asked every this many polls (about 0.45 s), and at once
         * whenever a poll found something: the keyguard is up over anything a lock with the screen
         * on (Lockdown) leaves, so ending the sessions that much later shows nothing.
         */
        private const val LOCK_CHECK_POLLS = 3
        /** How far back to look for the app already in front when the engine starts. */
        private const val STARTUP_LOOKBACK_MS = 6 * 60 * 60_000L
        /** That look starts this far back and reaches [LOOKBACK_GROWTH] times further each time it finds nothing. */
        private const val FIRST_LOOKBACK_MS = 10_000L
        private const val LOOKBACK_GROWTH = 6
        /** Permissions are checked again every this many polls (about 2 s), in case a change was missed. */
        private const val PERMISSION_CHECK_POLLS = 13
        private val ACTIVITY_EVENTS = intArrayOf(
            UsageEvents.Event.ACTIVITY_RESUMED,
            UsageEvents.Event.ACTIVITY_PAUSED,
            UsageEvents.Event.ACTIVITY_STOPPED
        )
        /** [ACTIVITY_EVENTS], and the screen coming on ([lockedAppsBefore]). */
        private val ACTIVITY_AND_SCREEN_ON_EVENTS = intArrayOf(
            UsageEvents.Event.ACTIVITY_RESUMED,
            UsageEvents.Event.ACTIVITY_PAUSED,
            UsageEvents.Event.ACTIVITY_STOPPED,
            UsageEvents.Event.SCREEN_INTERACTIVE
        )
        /** Device-wide events (the screen coming on, say) are logged as this package's. */
        private const val DEVICE_EVENTS_PACKAGE = "android"
    }
}
