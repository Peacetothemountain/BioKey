package com.android.BioKey.feature.lock

import android.app.AppOpsManager
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageEventsQuery
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.android.BioKey.R
import com.android.BioKey.core.data.PasswordManager
import com.android.BioKey.core.diagnostics.DiagnosticLog
import com.android.BioKey.core.security.LockStateTracker
import com.android.BioKey.core.security.ProtectionAlerts
import com.android.BioKey.core.security.ProtectionEngine
import com.android.BioKey.core.security.SecurityEngine
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.feature.main.MainActivity

/**
 * Compatibility-mode engine: covers locked apps without the accessibility API.
 *
 * Reads which activities open, pause and stop from usage access (on this device only), covers a
 * locked app that shows up with an application overlay, and shows the same [LockScreenActivity]
 * and system prompt as the accessibility engine. Sessions follow the same rules via
 * [LockStateTracker] and [ForegroundRules]; an app has left once its activities have stopped, so
 * one beside another in split screen, or in picture-in-picture, is still in use. A locked app
 * showing without a session and without coming to the front (picture-in-picture once the phone
 * is unlocked) is covered too, by the whole screen if need be ([coverShowingPaused]). Polls, on
 * its own thread, only while the screen is on and no screensaver is up.
 *
 * Runs as a foreground service with a low-priority notification, which Android requires for
 * work like this. If usage access or display over other apps is withdrawn it stops, ends every
 * session and alerts, rather than keep a notification that says it protects.
 */
class UsageWatchService : Service() {

    companion object {
        private const val TAG = "UsageWatchService"
        private const val CHANNEL_ID = "biokey_compatibility_mode"
        private const val NOTIFICATION_ID = 2001
        /** How often the foreground app is checked while the screen is on. */
        private const val POLL_MS = 150L
        /** Re-read a little of the past each time, so no event slips between two polls. */
        private const val OVERLAP_MS = 2_000L
        /** How far back to look for the app already in front when the engine starts. */
        private const val STARTUP_LOOKBACK_MS = 6 * 60 * 60_000L
        /** That look starts this far back and reaches [LOOKBACK_GROWTH] times further each time it finds nothing. */
        private const val FIRST_LOOKBACK_MS = 10_000L
        private const val LOOKBACK_GROWTH = 6
        /**
         * How much of the time the screen was off is read when it comes back on: generous, as the
         * broadcast can arrive after what happened just before it.
         */
        private const val WAKE_LOOKBACK_MS = 30_000L
        /** Permissions are checked again every this many polls (about 2 s), in case a change was missed. */
        private const val PERMISSION_CHECK_POLLS = 13
        /**
         * A locked app that opened and was paused at once behind another one is still showing if it
         * hasn't stopped by then (the one in front is see-through): it is covered.
         */
        private const val PAUSED_SETTLE_MS = 1_000L
        /**
         * How long a locked app found showing behind the one in front (in picture-in-picture, say)
         * has to come to the front once opened, before its lock screen goes up under the cover.
         */
        private const val PIP_EXPAND_WAIT_MS = 1_000L
        /** Nothing new for this long: the app in front is what usage access last said. */
        private const val RECHECK_SETTLE_MS = 300L
        /** Play Store's review and update sheets float, see-through, over the app that asked for them. */
        private const val PKG_PLAY_STORE = "com.android.vending"
        private val LOCK_SCREEN_CLASS = LockScreenActivity::class.java.name
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

        @Volatile
        var isRunning: Boolean = false
            private set

        /** A start was asked for and the service hasn't been created yet. */
        @Volatile
        var startPending: Boolean = false
            private set

        @Volatile
        private var stopRequested = false

        fun start(context: Context) {
            // A stop still on its way (onDestroy hasn't run yet) mustn't swallow this start, or
            // neither engine would be left.
            if (isRunning && !stopRequested) return
            // Set first: called off the main thread, the service can be created before this returns.
            startPending = true
            try {
                ContextCompat.startForegroundService(context, Intent(context, UsageWatchService::class.java))
            } catch (e: Exception) {
                startPending = false
                throw e
            }
        }

        fun stop(context: Context) {
            // One still starting checks for itself whether it should run: stopping it before it
            // reaches the foreground would crash BioKey.
            if (!isRunning) return
            stopRequested = true
            context.stopService(Intent(context, UsageWatchService::class.java))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    /** Usage queries are binder calls that can take a while: they run here, not where covers are drawn. */
    private lateinit var worker: HandlerThread
    private lateinit var workerHandler: Handler
    private lateinit var vault: SecurityVault
    private lateinit var usage: UsageStatsManager
    private lateinit var keyguard: KeyguardManager
    private lateinit var power: PowerManager
    private lateinit var appOps: AppOpsManager
    private lateinit var shield: InstantShieldOverlay

    // Main thread: what is on screen.

    /** App in front, as last reported (windows floating over apps never are). */
    private var foreground: String? = null
    private var foregroundClass: String? = null
    /** Timestamp of the newest app-resumed event already acted on. */
    private var handledUntil = Long.MIN_VALUE
    private val activities = ActivityStates()
    /**
     * Locked apps still showing beside the one in front: split screen, picture-in-picture, on their
     * way out. True while every app in front since has been a hop target, so leaving can be a hop.
     */
    private val sideVisible = HashMap<String, Boolean>()
    /** Locked apps without a session that opened and were paused at once behind another app, and since when. */
    private val pausedBehind = HashMap<String, Long>()
    /**
     * Activities ([UsageEvent.activity]) that are showing without having come to the front: they
     * came back paused, from stopped or from nothing known, never resumed. That is a window in
     * picture-in-picture once the phone is unlocked (it floats above every app and never
     * resumes), or an app under a see-through one. Each with its app, and since when. Also the
     * paused activities of apps beside the one in front whose sessions ended. See [coverShowingPaused].
     */
    private val pausedShowing = HashMap<String, Pair<String, Long>>()
    /** The app and the activity of it the cover is held over ([coverShowingPaused], [isHeldOpen]). */
    private var held: Pair<String, String>? = null
    /** The app being brought out of picture-in-picture, until [PIP_EXPAND_WAIT_MS] has passed. */
    private var expanding: String? = null
    /**
     * The app the cover is held over while another app's lock screen or prompt is up, until they
     * have gone and its own lock screen can follow ([coverShowingPaused], [resumeDeferred]).
     */
    private var deferred: String? = null
    /** The app in front lets the one below show: a floating window, or one that didn't stop it. */
    private var frontSeeThrough = false
    /** The app BioKey's lock screen was last brought up for. */
    private var lockScreenTarget: String? = null
    /** When a look last found something new (elapsed realtime). */
    private var lastChangeAt = 0L
    @Volatile private var polling = false
    /** Bumped whenever what a poll on its way found no longer applies (screen off, a screensaver). */
    @Volatile private var epoch = 0
    private var dreaming = false
    private var idle = false
    private var permissionLost = false

    // Worker thread: the read cursor.

    /** Wall-clock end of the last query: each poll reads only from here (minus [OVERLAP_MS]) on. */
    private var queriedUntil = 0L
    private var lookBackNext = false
    private var rebaseNext = false
    private var wasIdle = false
    private var pollsSincePermissionCheck = 0

    private val pollTask = object : Runnable {
        override fun run() {
            if (!polling) return
            val batch = readScreen()
            handler.post { onBatch(batch) }
            workerHandler.postDelayed(this, POLL_MS)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    stopPolling()
                    forgetScreen()
                    shield.hide()
                }
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    dreaming = false
                    if (checkPermissions()) wake()
                }
                // A screensaver covers everything and ends every session (ScreenReceiver): there
                // is nothing to watch until it ends, and nothing to cover over it.
                Intent.ACTION_DREAMING_STARTED -> {
                    dreaming = true
                    stopPolling()
                    // The app in front is there again when it ends: kept, so it is covered then
                    // even if it doesn't report opening again (see recheck).
                    forgetScreen(keepFront = true)
                    shield.hide()
                }
                Intent.ACTION_DREAMING_STOPPED -> {
                    dreaming = false
                    wake()
                }
                // Usage events carry wall-clock times: after the clock is set, read afresh from
                // now. They are UTC, so a time zone change moves nothing and isn't listened for.
                Intent.ACTION_TIME_CHANGED -> {
                    DiagnosticLog.log("Clock changed: re-reading app launches from now")
                    workerHandler.post { rebaseNext = true }
                }
            }
        }
    }

    private val onLockAll: () -> Unit = {
        // Every session just ended (protection resumed, lock-all) while a locked app may be on
        // screen: cover it now, not when it next opens. Not with the screen off, a screensaver or
        // the keyguard up, where nothing shows and the lock screen would only end up over them.
        handler.post {
            if (!isRunning || !polling || dreaming || keyguard.isKeyguardLocked) return@post
            foreground?.let { fg ->
                if (!activities.knows(fg) || activities.isVisible(fg)) handleForeground(fg, foregroundClass)
            }
            for (pkg in sideVisible.keys.toList()) {
                if (activities.isResumed(pkg)) {
                    if (!shield.isShowing) recover(pkg, "on screen when every session ended")
                } else {
                    // Paused beside it (picture-in-picture, say), which a lock screen can't cover.
                    noteShowingPaused(pkg)
                }
            }
        }
    }

    private val onLockAbandoned: (String) -> Unit = { pkg ->
        // Cancelled on its held cover: its lock screen waits for a tap on Unlock, as it does
        // whenever the owner cancels one, rather than coming up by itself.
        handler.post { if (deferred == pkg) deferred = null }
        // Not covered again during the cooldown: once it ends, look again, in case the app is
        // still (or again) in front.
        handler.postDelayed({
            if (isRunning && polling) recheck(lazy { keyguard.isDeviceLocked || keyguard.isKeyguardLocked })
        }, LockStateTracker.abandonCooldownRemainingMs(pkg) + 50L)
    }

    private val opListener = AppOpsManager.OnOpChangedListener { _, pkg ->
        // Called on a binder thread, for grants as well as withdrawals.
        if (pkg == packageName) handler.post { if (isRunning) checkPermissions() }
    }

    private fun isInteractive() = power.isInteractive

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startPending = false
        // A service started this way must be in the foreground within seconds, even one about to
        // stop again, or Android ends BioKey: this comes first.
        try {
            // "Special use" is a type from Android 14 on; before that the manifest's own type is
            // asked for, which is the same one.
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
            }
            startForeground(NOTIFICATION_ID, buildNotification(), type)
        } catch (e: Exception) {
            // Refused (restarted by Android from the background, say): nothing can cover apps.
            Log.e(TAG, "Compatibility mode couldn't start: ${e.javaClass.simpleName}")
            DiagnosticLog.log("Compatibility engine couldn't start in the background")
            ProtectionAlerts.serviceStopped(this)
            stopSelf()
            return
        }
        vault = SecurityVault.getInstance(this)
        // A stop, or the accessibility engine taking over, can come before this service exists.
        if (BioKeyAccessibilityService.isRunning || !ProtectionEngine.shouldRunCompatibility(this)) {
            if (vault.isSetupCompleted() && !BioKeyAccessibilityService.isServiceEnabled(this)) {
                ProtectionEngine.missingCompatibilityPermissions(this)?.let { ProtectionAlerts.compatibilityStopped(this, it) }
            }
            stopSelf()
            return
        }
        usage = getSystemService(UsageStatsManager::class.java)
        keyguard = getSystemService(KeyguardManager::class.java)
        power = getSystemService(PowerManager::class.java)
        appOps = getSystemService(AppOpsManager::class.java)
        shield = InstantShieldOverlay(
            this,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            goHome = { goHome() },
            onTimeout = ::onShieldTimeout,
            keepCoverWhile = ::isExposed,
            takeFocus = true,
            holdWhile = ::isHeldOpen
        )
        AppInfoCache.preWarm(this, vault.getLockedPackages())
        AppInfoCache.follow(vault)
        worker = HandlerThread("biokey-usage").apply { start() }
        workerHandler = Handler(worker.looper)
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_DREAMING_STARTED)
                addAction(Intent.ACTION_DREAMING_STOPPED)
                addAction(Intent.ACTION_TIME_CHANGED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        LockStateTracker.addLockAllListener(onLockAll)
        LockStateTracker.addLockAbandonedListener(onLockAbandoned)
        runCatching {
            appOps.startWatchingMode(AppOpsManager.OPSTR_GET_USAGE_STATS, packageName, opListener)
            appOps.startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, packageName, opListener)
        }
        isRunning = true
        ProtectionAlerts.serviceRestored(this)
        // A locked app may already be in front (after a reboot or an update): look back for it first.
        if (isInteractive()) startPolling(lookBack = true)
        Log.i(TAG, "Compatibility mode started")
        DiagnosticLog.log("Compatibility engine started")
        // A permission withdrawn just before the watch above began would wait for the next check.
        checkPermissions()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        if (isRunning) START_STICKY else START_NOT_STICKY

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Dark theme, wallpaper colours or rotation: the cover must keep matching the lock screen's.
        if (::shield.isInitialized) shield.onConfigurationChanged()
    }

    override fun onDestroy() {
        val wasRunning = isRunning
        isRunning = false
        startPending = false
        stopRequested = false
        if (wasRunning) {
            stopPolling()
            handler.removeCallbacksAndMessages(null)
            runCatching { appOps.stopWatchingMode(opListener) }
            LockStateTracker.removeLockAllListener(onLockAll)
            LockStateTracker.removeLockAbandonedListener(onLockAbandoned)
            runCatching { unregisterReceiver(screenReceiver) }
            worker.quitSafely()
            shield.destroy()
            if (!permissionLost) {
                // Nothing enforces sessions without an engine, so none may outlive it.
                if (!BioKeyAccessibilityService.isRunning) LockStateTracker.lockAll(reason = "compatibility engine stopped")
                // Stopped by BioKey because a permission went before this engine noticed: say so.
                if (vault.isSetupCompleted() && !BioKeyAccessibilityService.isServiceEnabled(this)) {
                    ProtectionEngine.missingCompatibilityPermissions(this)?.let { ProtectionAlerts.compatibilityStopped(this, it) }
                }
            }
            Log.i(TAG, "Compatibility mode stopped")
        }
        super.onDestroy()
    }

    private fun startPolling(lookBack: Boolean = false) {
        polling = true
        // Rescheduled on the worker, so a poll running there right now can't leave a second chain
        // of polls behind (the screen coming on, then the phone being unlocked).
        workerHandler.post {
            if (lookBack) lookBackNext = true
            workerHandler.removeCallbacks(pollTask)
            pollTask.run()
        }
    }

    private fun stopPolling() {
        polling = false
        epoch++
        workerHandler.removeCallbacks(pollTask)
    }

    /** The screen is back (on, unlocked, out of a screensaver): look now, at what happened shortly before too. */
    private fun wake() {
        if (!isInteractive()) return
        workerHandler.post { queriedUntil = maxOf(queriedUntil, System.currentTimeMillis() - WAKE_LOOKBACK_MS) }
        startPolling()
    }

    /** Nothing known about the screen holds any more, except, with [keepFront], which app is in front. */
    private fun forgetScreen(keepFront: Boolean = false) {
        if (!keepFront) {
            foreground = null
            foregroundClass = null
            lockScreenTarget = null
        }
        activities.clear()
        sideVisible.clear()
        pausedBehind.clear()
        pausedShowing.clear()
        held = null
        expanding = null
        deferred = null
        frontSeeThrough = false
    }

    // -------------------------------------------------------------------------------------------
    // Reading (worker thread)
    // -------------------------------------------------------------------------------------------

    /**
     * What one look at the screen found ([readScreen]). Whether the keyguard is up as well is
     * asked only where a cover depends on it ([onBatch]): another binder call, needless on the
     * many looks that find nothing to act on.
     */
    private class Batch(
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
        val older: List<UsageEvent> = emptyList()
    )

    /** One look at the screen: the device's lock state and the activity events since the last look. */
    private fun readScreen(): Batch {
        val current = epoch
        // Every look: locking the phone must end every session.
        val deviceLocked = keyguard.isDeviceLocked
        val missing = if (++pollsSincePermissionCheck < PERMISSION_CHECK_POLLS) null else {
            pollsSincePermissionCheck = 0
            ProtectionEngine.missingCompatibilityPermissions(this)
        }
        val now = System.currentTimeMillis()
        // Nothing to protect: only the lock state matters, and a fresh look once there is again.
        // (The switch itself: a pause that ran out is ended on the main thread, see onBatch.)
        if (!vault.globalLockEnabledFlow.value || vault.getLockedPackages().isEmpty()) {
            wasIdle = true
            queriedUntil = now
            return Batch(current, deviceLocked, missing, idle = true)
        }
        if (lookBackNext || wasIdle) {
            lookBackNext = false
            wasIdle = false
            val floor = lookBackFloor(now)
            val (from, events) = lookBack(now, floor)
            val older = if (from > floor) lockedAppsBefore(floor, from, now) else emptyList()
            queriedUntil = now
            return Batch(current, deviceLocked, missing, fresh = true, from = from, events = events, older = older)
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
        return Batch(current, deviceLocked, missing, rebasedFrom = rebasedFrom, from = from, events = events)
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
     * window left in picture-in-picture says nothing more until it changes, so only these tell a
     * look back (after a pause, when the engine starts or takes over) that it still shows. They
     * only say what each activity did last ([onBatch]), never what is in front.
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

    // -------------------------------------------------------------------------------------------
    // Deciding (main thread)
    // -------------------------------------------------------------------------------------------

    private fun onBatch(batch: Batch) {
        if (batch.epoch != epoch || !isRunning || permissionLost) return
        LockStateTracker.onDeviceLockState(batch.deviceLocked)
        batch.missing?.let {
            onPermissionLost(it)
            return
        }
        if (batch.idle) {
            if (!idle) {
                forgetScreen()
                // Nothing to protect (paused, or nothing locked): no cover either, held or not.
                shield.hide()
            }
            idle = true
            // Ends a timed pause that has run out (its own timer doesn't count deep sleep).
            vault.isProtectionActive()
            return
        }
        idle = false
        if (batch.fresh) {
            forgetScreen()
            handledUntil = Long.MIN_VALUE
            // What locked apps' activities did before the look back's window: one left paused
            // (in picture-in-picture, say) is showing, and is covered like any found so. Nothing
            // here came to the front just now, so none of it is handled as an app opening. A
            // process that died while paused, since the screen last came on, leaves that state
            // behind: its app is then covered until the owner unlocks it, which fails closed.
            for (e in batch.older) {
                val before = activities.lastTypeOf(e)
                if (activities.apply(e)) notePaused(e, before, fresh = true)
            }
        } else if (batch.rebasedFrom != null) {
            // What each activity last did still holds (a window in picture-in-picture, and the
            // cover held over it): only its time is no longer comparable.
            activities.rebase()
            handledUntil = batch.rebasedFrom
        }
        // Every app that came to the screen since the last look, not only the newest: split screen
        // and app pairs bring two at once, and a dialog can open over an app straight away.
        val resumed = LinkedHashSet<String>()
        var newest: UsageEvent? = null
        for (e in batch.events) {
            val before = activities.lastTypeOf(e)
            val isNew = activities.apply(e)
            if (isNew) {
                lastChangeAt = SystemClock.elapsedRealtime()
                notePaused(e, before, batch.fresh)
            }
            if (e.type != UsageEvents.Event.ACTIVITY_RESUMED) continue
            if (isNew) e.app?.let(resumed::add)
            if (e.time > handledUntil && e.time >= (newest?.time ?: Long.MIN_VALUE)) newest = e
        }
        if (newest != null) {
            handledUntil = newest.time
            frontSeeThrough = isFloating(newest)
        }
        activities.prune(batch.from)
        // Whether the keyguard is up is asked only once something would be covered (a binder call).
        val screenLocked = lazy(LazyThreadSafetyMode.NONE) { batch.deviceLocked || keyguard.isKeyguardLocked }
        // A look back from behind the keyguard is about before the phone was locked: nothing to cover.
        if (batch.fresh && screenLocked.value) return
        newest?.let(::handleForeground)
        for (pkg in resumed) if (pkg != newest?.app) handleOtherResume(pkg)
        reconcile(screenLocked)
        resumeDeferred()
        releaseHeld()
    }

    /**
     * Keeps [pausedShowing] up to date with [e], news for its activity, whose event before was
     * [before] (null: none known). Paused straight from stopped, or from nothing seen, is showing
     * without having come to the front; so is anything left paused in a [fresh] look back, where
     * nothing older is known. Anything else about the activity ends that.
     */
    private fun notePaused(e: UsageEvent, before: Int?, fresh: Boolean) {
        val app = e.app ?: return
        val cameBackPaused = e.type == UsageEvents.Event.ACTIVITY_PAUSED &&
            (fresh || before == null || before == UsageEvents.Event.ACTIVITY_STOPPED)
        if (cameBackPaused) {
            pausedShowing.putIfAbsent(e.activity, app to SystemClock.elapsedRealtime())
        } else {
            pausedShowing.remove(e.activity)
        }
    }

    /**
     * [pkg] is still showing beside the app in front, but only paused (picture-in-picture, say),
     * and its session just ended: its paused activities are covered like any found showing so.
     */
    private fun noteShowingPaused(pkg: String) {
        if (!lockedWithoutSession(pkg)) return
        val now = SystemClock.elapsedRealtime()
        for (activity in activities.pausedActivitiesOf(pkg)) pausedShowing.putIfAbsent(activity, pkg to now)
    }

    private fun lockedWithoutSession(pkg: String): Boolean =
        pkg != packageName && vault.isAppLocked(pkg) && !LockStateTracker.isAppUnlocked(pkg, vault.getTimeoutMode(pkg))

    /**
     * A window that floats over the app below: a system dialog or sheet, a sign-in sheet, a Play
     * sheet. Not Play once the owner has locked it: then it is an app like any other, and covered
     * as one, even when a lock screen closes onto it.
     */
    private fun isFloating(event: UsageEvent) =
        event.app == null || (event.pkg == PKG_PLAY_STORE && !vault.isAppLocked(PKG_PLAY_STORE))

    private fun handleForeground(event: UsageEvent) = handleForeground(event.app, event.cls, isFloating(event))

    private fun handleForeground(pkg: String?, activityClass: String?, floating: Boolean = false) {
        if (pkg != null && pkg != packageName) endOwnTrips(pkg)
        if (!vault.isProtectionActive()) {
            if (pkg != null) {
                foreground = pkg
                foregroundClass = activityClass
            }
            shield.hide()
            return
        }
        val unlockedBehind = unlockedBehindLockScreen(pkg, activityClass, floating)
        if (floating && unlockedBehind != null) {
            foreground = unlockedBehind
            foregroundClass = null
            return
        }
        // Permission dialogs, share and sign-in sheets etc. float over the current app: not an app switch.
        if (pkg == null) return
        // Behind a helper it had open (see above), the app just unlocked hops to it rather than leaving.
        val previous = unlockedBehind ?: foreground
        foreground = pkg
        foregroundClass = activityClass
        sideVisible -= pkg
        pausedBehind -= pkg
        for (entry in sideVisible.entries) {
            if (entry.value && hopTo(pkg, activityClass, entry.key) == null) entry.setValue(false)
        }

        if (previous != null && previous != pkg && previous != packageName && vault.isAppLocked(previous)) {
            val hop = hopTo(pkg, activityClass, previous)
            if (activities.isVisible(previous)) {
                // Still showing: beside this one in split screen, in picture-in-picture, or on its
                // way out. It has left once its activities have stopped (see reconcile).
                sideVisible[previous] = hop != null
            } else {
                LockStateTracker.onAppLeft(previous, vault.getTimeoutMode(previous), hop)
            }
        }
        LockStateTracker.onForegroundChanged(pkg, vault::getTimeoutMode) { ForegroundRules.isHopTarget(this, it, activityClass) }

        // BioKey's own screens (its lock screen, its main UI) authenticate on their own.
        if (pkg == packageName) {
            if (activityClass == LOCK_SCREEN_CLASS) {
                lockScreenTarget = LockScreenActivity.activeLockedTarget
            } else {
                // Not a lock screen on its way: a cover kept up after the retries (isExposed) goes.
                hideShieldUnlessLocking(pkg)
            }
            return
        }

        if (!vault.isAppLocked(pkg)) {
            hideShieldUnlessLocking(pkg)
            return
        }
        if (LockStateTracker.isAppUnlocked(pkg, vault.getTimeoutMode(pkg))) {
            LockStateTracker.onAppVisible(pkg)
            hideShieldUnlessLocking(pkg)
            return
        }
        if (LockStateTracker.isRecentlyAbandoned(pkg)) {
            hideShieldUnlessLocking(pkg)
            goHome()
            return
        }
        if (LockScreenActivity.isLockScreenResumed && LockScreenActivity.activeLockedTarget == pkg) return
        // Also while its prompt or a launch is still on its way: a lock screen that has just gone to
        // the background is about to give up, and nothing would cover the app again.
        lock(pkg)
    }

    /**
     * [pkg], another app, came to the front. BioKey's own session survives only the trips it
     * starts itself, and only as far as they go: its backup file picker, or the settings pages it
     * opened. Anything else ends them, and the session with them, as in the accessibility engine.
     */
    private fun endOwnTrips(pkg: String) {
        if (LockStateTracker.isOwnPickerActive() && pkg !in ForegroundRules.DOCUMENTS_UI) LockStateTracker.lockOwnApp()
        if (!SelfProtectionDetector.isHostPackage(pkg) && LockStateTracker.isExternalNavigationActive()) LockStateTracker.lockOwnApp()
    }

    /**
     * BioKey's lock screen just closed onto something ([pkg]) the app it unlocked had open over
     * itself: a dialog or sheet ([floating]), such as a permission request, or a helper, such as a
     * Play services sign-in screen. The app is right behind it, so its session has arrived (or it
     * would end, as never on screen, while the user is still busy there). Returns that app, if so.
     */
    private fun unlockedBehindLockScreen(pkg: String?, activityClass: String?, floating: Boolean): String? {
        if (foreground != packageName || foregroundClass != LOCK_SCREEN_CLASS || pkg == packageName) return null
        val target = lockScreenTarget?.takeIf { it != pkg } ?: return null
        if (!floating && hopTo(pkg, activityClass, target) == null) return null
        if (!vault.isAppLocked(target) || !LockStateTracker.isAppUnlocked(target, vault.getTimeoutMode(target))) return null
        LockStateTracker.onAppVisible(target)
        return target
    }

    /**
     * [pkg] came to the screen since the last look, but isn't the newest: the other half of a split
     * screen, or an app a dialog or sheet opened over straight away. It is showing too.
     */
    private fun handleOtherResume(pkg: String) {
        if (pkg == packageName || pkg == foreground || !vault.isAppLocked(pkg)) return
        if (!activities.isVisible(pkg)) return // Came and went (a trampoline): nothing showed.
        if (LockStateTracker.isAppUnlocked(pkg, vault.getTimeoutMode(pkg))) {
            LockStateTracker.onAppVisible(pkg)
            sideVisible[pkg] = hopTo(foreground, foregroundClass, pkg) != null
            return
        }
        if (!mayCover(pkg)) return
        when {
            activities.isResumed(pkg) -> lock(pkg, "on screen beside another app")
            frontSeeThrough -> lock(pkg, "opened under a dialog")
            // Paused behind another app: covered unless it stops, which says the one in front hides it.
            else -> pausedBehind.getOrPut(pkg) { SystemClock.elapsedRealtime() }
        }
    }

    /**
     * After each look: apps beside the one in front that have now left, apps left showing under
     * another or found showing without coming to the front, and a cover that went missing.
     * [screenLocked] (the phone locked, or the keyguard up) is only asked once something would be
     * covered.
     */
    private fun reconcile(screenLocked: Lazy<Boolean>) {
        for ((pkg, mayHop) in sideVisible.toList()) {
            if (pkg == foreground) {
                sideVisible -= pkg
            } else if (activities.isGone(pkg)) {
                sideVisible -= pkg
                if (vault.isAppLocked(pkg)) {
                    val hop = if (mayHop) hopTo(foreground, foregroundClass, pkg) else null
                    LockStateTracker.onAppLeft(pkg, vault.getTimeoutMode(pkg), hop)
                }
            }
        }
        val now = SystemClock.elapsedRealtime()
        for ((pkg, since) in pausedBehind.toList()) {
            if (pkg == foreground || !activities.isVisible(pkg)) {
                pausedBehind -= pkg // Back in front (handled there), or stopped: nothing was left showing.
            } else if (now - since >= PAUSED_SETTLE_MS) {
                pausedBehind -= pkg
                frontSeeThrough = true
                if (!screenLocked.value) recover(pkg, "still showing under another app")
            }
        }
        for ((activity, entry) in pausedShowing.toList()) {
            val (pkg, since) = entry
            if (!activities.isPaused(activity) || !lockedWithoutSession(pkg)) {
                pausedShowing -= activity // Resumed (and covered there), stopped, or unlocked.
            } else if (now - since >= PAUSED_SETTLE_MS && mayCoverShowingPaused(pkg) && !screenLocked.value) {
                // Kept until then: under the keyguard nothing shows, and the cover waits for its turn.
                pausedShowing -= activity
                coverShowingPaused(pkg, activity)
            }
        }
        recheck(screenLocked)
    }

    /**
     * Nothing new came to the front, yet a locked app may be showing uncovered: a lock screen on its
     * way out took its cover down, or it came back during the abandon cooldown. Covers it again,
     * once usage access has had [RECHECK_SETTLE_MS] to report anything newer in front.
     */
    private fun recheck(screenLocked: Lazy<Boolean>) {
        if (!polling || dreaming || shield.isShowing) return
        if (SystemClock.elapsedRealtime() - lastChangeAt < RECHECK_SETTLE_MS) return
        val fg = foreground
        if (fg != null && (!activities.knows(fg) || activities.isVisible(fg)) && recover(fg, "still in front, uncovered", screenLocked)) return
        for (pkg in sideVisible.keys.toList()) {
            if (activities.isResumed(pkg)) {
                if (recover(pkg, "still on screen, uncovered", screenLocked)) return
            } else {
                // Its session ended while it was paused beside the app in front (in picture-in-picture, say).
                noteShowingPaused(pkg)
            }
        }
    }

    /**
     * Covers [pkg] if it is locked, has no session, nothing is covering or asking for it yet, and
     * [screenLocked] (when given) says the phone is unlocked. True if it did.
     */
    private fun recover(pkg: String, why: String, screenLocked: Lazy<Boolean>? = null): Boolean {
        if (pkg == packageName || !vault.isAppLocked(pkg)) return false
        if (LockStateTracker.isAppUnlocked(pkg, vault.getTimeoutMode(pkg)) || !mayCover(pkg)) return false
        if (screenLocked?.value == true) return false
        lock(pkg, why)
        return true
    }

    /** Whether [pkg], found showing without being in front, may be covered now ([coverShowingPaused]). */
    private fun mayCoverShowingPaused(pkg: String): Boolean {
        if (LockStateTracker.isRecentlyAbandoned(pkg)) return false
        // Its lock screen may already be up, under its picture-in-picture window: the cover goes over both.
        if (LockScreenActivity.isLockScreenResumed && LockScreenActivity.activeLockedTarget == pkg) return held?.first != pkg
        if (LockStateTracker.isRegularLockPending(pkg)) return false
        return !shield.isShowing
    }

    /**
     * [pkg] shows, without a session, without being in front: in picture-in-picture, which floats
     * above every app, its lock screen included, or under a see-through app. The cover goes up over
     * everything first, then the app is opened: where that brings it out of picture-in-picture (it
     * is its app's own task), it is in front and locked as usual. Where [activity] still floats
     * after [PIP_EXPAND_WAIT_MS] (a call screen in a task of its own, say), its lock screen comes up
     * under the cover, the prompt over both. Either way the cover stays while [activity] shows and
     * the app has no session ([isHeldOpen]): compatibility mode can't see where that window is,
     * nor close it, so until the owner authenticates it covers the whole screen (fail closed).
     *
     * Over another app's lock screen or prompt (the app in front, asked for as the phone was
     * unlocked), nothing is started until they have gone ([resumeDeferred]): any start now would
     * take that prompt away, as BioKey has one lock screen at a time.
     */
    private fun coverShowingPaused(pkg: String, activity: String) {
        DiagnosticLog.log("Covered $pkg (showing behind the app in front, as in picture-in-picture)")
        held = pkg to activity
        pausedBehind -= pkg
        // A tap on the cover asks for the prompt again, as its lock screen may be up under it.
        val shown = shield.show(pkg) { LockLauncher.launch(this, pkg, isSelfProtection = false, reprompt = true) }
        if (!shown && !checkPermissions()) return
        if (LockScreenActivity.isLockScreenResumed && LockScreenActivity.activeLockedTarget == pkg) {
            // Its lock screen is up already, under the window, and won't ask the cover to go: it
            // is held from now on (Unlock and Cancel on it whenever no prompt is up).
            shield.hide()
            return
        }
        if (isAnotherLockUp(pkg)) {
            // Held from now on, without the checks that would start its lock screen: that other
            // lock screen can't take it down (it only lowers its own app's cover).
            deferred = pkg
            shield.hide()
            return
        }
        askForShowingPaused(pkg)
    }

    /**
     * Another app's lock screen is up, or a prompt for anything but [pkg] is (BioKey's own unlock,
     * say): starting a lock screen or an app now would take it away mid-authentication.
     */
    private fun isAnotherLockUp(pkg: String): Boolean {
        val engine = SecurityEngine.INSTANCE
        return (LockScreenActivity.isLockScreenResumed && LockScreenActivity.activeLockedTarget != pkg) ||
            (engine.isAuthenticating() && !engine.isAuthenticatingFor(pkg))
    }

    /**
     * The cover is over [pkg], which shows without being in front ([coverShowingPaused]): asks for
     * it. Over a locked app (its session, or its lock screen) or BioKey, only [pkg]'s lock screen
     * comes up: opening [pkg] there would count as the user leaving that app, which would then ask
     * again although they never left it. Going to BioKey's lock screen is a hop, so it keeps its
     * session and is back in front once [pkg] is unlocked. Anywhere else [pkg] is opened, to bring
     * it out of picture-in-picture.
     */
    private fun askForShowingPaused(pkg: String) {
        LockStateTracker.setRegularLockPending(pkg)
        val front = foreground
        if (front != null && front != pkg && (front == packageName || vault.isAppLocked(front))) {
            if (!LockLauncher.launch(this, pkg, isSelfProtection = false)) goHome()
            return
        }
        expanding = pkg
        LockLauncher.launchApp(this, PasswordManager.launchPackage(pkg))
        handler.postDelayed({ onExpandWaitOver(pkg) }, PIP_EXPAND_WAIT_MS)
    }

    /**
     * After each look: the cover held over [deferred] while another lock screen or prompt was up
     * asks for its own app once they have gone, unless the window went, the app was unlocked, or
     * the owner cancelled it on the cover meanwhile (its Unlock is then theirs to tap).
     */
    private fun resumeDeferred() {
        val pkg = deferred ?: return
        if (!isHeldOpen(pkg) || LockStateTracker.isRecentlyAbandoned(pkg)) {
            deferred = null
            return
        }
        if (isAnotherLockUp(pkg)) return
        deferred = null
        // Its own lock screen is up (Unlock tapped on the cover): nothing to ask for.
        if (LockScreenActivity.isLockScreenResumed && LockScreenActivity.activeLockedTarget == pkg) return
        askForShowingPaused(pkg)
    }

    /** [pkg] had its time to come out of picture-in-picture ([coverShowingPaused]). */
    private fun onExpandWaitOver(pkg: String) {
        if (!isRunning || expanding != pkg) return
        expanding = null
        // In front now (and locked as usual), gone, or unlocked: nothing more to do.
        if (!isHeldOpen(pkg)) return
        if (LockScreenActivity.isLockScreenResumed && LockScreenActivity.activeLockedTarget == pkg) return
        if (isAnotherLockUp(pkg)) {
            // Another app's lock screen or prompt came up meanwhile: held until it has gone, as
            // in coverShowingPaused.
            deferred = pkg
            if (shield.targetPackage == pkg) shield.hide()
            return
        }
        // Still floating: its lock screen goes up under the cover, which stays over both.
        LockStateTracker.setRegularLockPending(pkg)
        if (!LockLauncher.launch(this, pkg, isSelfProtection = false)) goHome()
    }

    /**
     * The cover must stay up for [pkg]: its [held] activity still shows without being in front,
     * and the app has no session. Asked by the cover before anything takes it down.
     */
    private fun isHeldOpen(pkg: String): Boolean {
        val (app, activity) = held ?: return false
        return app == pkg && isRunning && !permissionLost && vault.isProtectionActive() &&
            activities.isPaused(activity) && lockedWithoutSession(pkg)
    }

    /**
     * The app the cover was held over needs it no more: unlocked, gone, or in front (and covered
     * there as usual). The cover goes too, unless that app is still exposed and its lock screen
     * isn't up yet.
     *
     * Unlocked with its window still showing, the app never came to the front (its lock screen
     * closed onto Home or the app below, the window still in picture-in-picture): nothing else
     * would ever see its session reach the screen, which would then end as never on screen and
     * ask again once the window is expanded. It is on screen, beside the app in front, and leaves
     * once that window stops (see [reconcile]).
     */
    private fun releaseHeld() {
        val (pkg, activity) = held ?: return
        if (isHeldOpen(pkg)) return
        if (activities.isPaused(activity) && vault.isAppLocked(pkg) && LockStateTracker.isAppUnlocked(pkg, vault.getTimeoutMode(pkg))) {
            LockStateTracker.onAppVisible(pkg)
            if (pkg != foreground) sideVisible.putIfAbsent(pkg, false)
        }
        held = null
        if (!shield.isShowing || shield.targetPackage != pkg) return
        val lockScreenUp = LockScreenActivity.isLockScreenResumed && LockScreenActivity.activeLockedTarget == pkg
        if (lockScreenUp || !isExposed(pkg)) shield.hide()
    }

    /** Nothing is already covering or asking for [pkg], and it wasn't just walked away from. */
    private fun mayCover(pkg: String): Boolean =
        !LockStateTracker.isRecentlyAbandoned(pkg) &&
            !LockStateTracker.isRegularLockPending(pkg) &&
            !(LockScreenActivity.isLockScreenResumed && LockScreenActivity.activeLockedTarget == pkg)

    /** Where [left] went, if going there keeps its session as a hop: a helper or a screen it opened, not Home. */
    private fun hopTo(front: String?, frontClass: String?, left: String): String? = front?.takeIf {
        it != left && !ForegroundRules.isLauncher(this, it) && ForegroundRules.isHopTarget(this, it, frontClass)
    }

    /** Hides the cover unless it is up for another app whose lock screen is on its way. */
    private fun hideShieldUnlessLocking(pkg: String) {
        val target = shield.targetPackage ?: return
        if (target == pkg || !LockStateTracker.isRegularLockPending(target)) shield.hide()
    }

    /** [pkg] is in front, beside it, under something that lets it show, or showing where only the cover hides it. */
    private fun isOnScreen(pkg: String): Boolean =
        pkg == foreground || activities.isResumed(pkg) || (frontSeeThrough && activities.isVisible(pkg)) || isHeldOpen(pkg)

    private fun lock(pkg: String, why: String = "opened") {
        DiagnosticLog.log("Covered $pkg ($why)")
        LockStateTracker.setRegularLockPending(pkg)
        pausedBehind -= pkg
        // The cover is held over another app's window (see isHeldOpen), and already hides this
        // one: its lock screen comes up under it, its prompt over it, and the cover stays.
        val heldFor = held?.first
        if (heldFor != null && heldFor != pkg && shield.isShowing && shield.targetPackage == heldFor && isHeldOpen(heldFor)) {
            if (!LockLauncher.launch(this, pkg, isSelfProtection = false)) goHome()
            return
        }
        // The overlay goes up first and hides the app. The lock screen may start from the
        // background because the user let BioKey display over other apps.
        if (!shield.show(pkg) { LockLauncher.launch(this, pkg, isSelfProtection = false) }) {
            if (!checkPermissions()) return
            DiagnosticLog.log("Cover for $pkg couldn't be shown")
        }
        // Couldn't even ask for the lock screen: leave the app. The cover stays up and retries.
        if (!LockLauncher.launch(this, pkg, isSelfProtection = false)) goHome()
    }

    private fun onShieldTimeout(target: String, attempt: Int): Boolean {
        if (!isExposed(target)) return false
        // Still being brought out of picture-in-picture: its lock screen follows then (onExpandWaitOver).
        // Or waiting for another app's lock screen to go: its own follows then (resumeDeferred).
        if (target == expanding || target == deferred) return true
        if (attempt > 0) DiagnosticLog.log("Lock screen for $target not up yet; asking again ($attempt)")
        LockStateTracker.setRegularLockPending(target)
        return LockLauncher.launch(this, target, isSelfProtection = false)
    }

    /** [target] still shows without a session, and compatibility mode can still cover it. */
    private fun isExposed(target: String): Boolean =
        checkPermissions() && isOnScreen(target) && !LockStateTracker.isAppUnlocked(target, vault.getTimeoutMode(target))

    /** Whether compatibility mode still has its permissions. Once it hasn't, it stops, with an alert. */
    private fun checkPermissions(): Boolean {
        if (permissionLost) return false
        val missing = ProtectionEngine.missingCompatibilityPermissions(this) ?: return true
        onPermissionLost(missing)
        return false
    }

    /**
     * Usage access or display over other apps was withdrawn. Without the first nothing is seen;
     * without the second nothing can be covered (Android just hides the cover). Stop rather than
     * pretend, end every session and say so. The app on screen right now can't be covered.
     */
    private fun onPermissionLost(missing: String) {
        if (permissionLost) return
        permissionLost = true
        Log.w(TAG, "Compatibility mode lost $missing")
        DiagnosticLog.log("Compatibility mode lost $missing: stopped")
        stopPolling()
        // Ending the sessions below mustn't try to cover anything.
        LockStateTracker.removeLockAllListener(onLockAll)
        shield.hide()
        LockStateTracker.lockAll(reason = "compatibility mode lost $missing")
        ProtectionAlerts.compatibilityStopped(this, missing)
        stopSelf()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Compatibility mode", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "Shown while BioKey locks apps without the accessibility service"
                    setShowBadge(false)
                }
            )
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_lock_key)
            .setContentTitle("BioKey is protecting your apps")
            .setContentText("Compatibility mode: works with Advanced Protection")
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}

/**
 * One activity event from usage access. [app] is what it means for locking: the package, Google
 * Password Manager's virtual one ([PasswordManager.resolve]), or null for a window that floats over
 * whichever app is below (a system dialog or sheet, a sign-in or passkey sheet). [lockedOverlay]:
 * [pkg] floats like those but the user locked it (Digital Wellbeing, with its icon turned on), so
 * every screen of it is that app's.
 */
internal class UsageEvent(val type: Int, val pkg: String, val cls: String?, val time: Long, lockedOverlay: Boolean = false) {
    val app: String? = if (pkg in ForegroundRules.SYSTEM_OVERLAYS && !lockedOverlay) null else PasswordManager.resolve(pkg, cls)
    /** Which activity this is about. */
    val activity: String get() = pkg + '/' + cls.orEmpty()
}

/**
 * Each activity's latest event, so the engine knows what is still showing: resumed, or paused
 * (under a floating window, in picture-in-picture, on its way out) and not yet stopped. Fed every
 * poll's events, re-read ones included, which change nothing. Floating windows aren't apps and
 * aren't kept.
 */
internal class ActivityStates {

    private companion object {
        const val RESUMED = UsageEvents.Event.ACTIVITY_RESUMED
        const val PAUSED = UsageEvents.Event.ACTIVITY_PAUSED
        const val STOPPED = UsageEvents.Event.ACTIVITY_STOPPED
    }

    private class State(val app: String, var type: Int, var time: Long)

    private val byActivity = HashMap<String, State>()

    /** Records [event]. True if it is news, not an event already recorded. */
    fun apply(event: UsageEvent): Boolean {
        val app = event.app ?: return false
        val key = event.activity
        val known = byActivity[key]
        if (known == null) {
            byActivity[key] = State(app, event.type, event.time)
            return true
        }
        // Within one millisecond, resumed, paused and stopped follow each other in that order.
        if (event.time < known.time || (event.time == known.time && order(event.type) <= order(known.type))) return false
        known.type = event.type
        known.time = event.time
        return true
    }

    /** The latest event recorded for [event]'s activity (resumed, paused or stopped), or null if none is. */
    fun lastTypeOf(event: UsageEvent): Int? = byActivity[event.activity]?.type

    /** Whether [activity] ([UsageEvent.activity]) is paused: showing, but not in front. */
    fun isPaused(activity: String): Boolean = byActivity[activity]?.type == PAUSED

    /** [app]'s activities that are paused. */
    fun pausedActivitiesOf(app: String): List<String> = byActivity.filter { (_, s) -> s.app == app && s.type == PAUSED }.keys.toList()

    fun isResumed(app: String): Boolean = byActivity.values.any { it.app == app && it.type == RESUMED }

    /** Some activity of [app] is resumed or paused: it is (or may still be) on screen. */
    fun isVisible(app: String): Boolean = byActivity.values.any { it.app == app && it.type != STOPPED }

    fun knows(app: String): Boolean = byActivity.values.any { it.app == app }

    /** Every activity of [app] seen has stopped. False for an app nothing is known of. */
    fun isGone(app: String): Boolean = knows(app) && !isVisible(app)

    /** Forgets activities that stopped before [time]: no re-read reaches back that far any more. */
    fun prune(time: Long) {
        byActivity.values.removeAll { it.type == STOPPED && it.time < time }
    }

    /**
     * The clock was changed: what each activity did last still holds, but its time can't be
     * compared with new ones. Each keeps its state, timed before anything on the new clock, so
     * whatever is read next applies; stopped ones then go at the next [prune].
     */
    fun rebase() {
        for (state in byActivity.values) state.time = Long.MIN_VALUE
    }

    fun clear() = byActivity.clear()

    private fun order(type: Int) = when (type) {
        RESUMED -> 0
        PAUSED -> 1
        else -> 2
    }
}
