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
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.feature.main.MainActivity

/**
 * Compatibility-mode engine: covers locked apps without the accessibility API.
 *
 * Reads which activities open, pause and stop from usage access (on this device only), covers a
 * locked app that shows up with an application overlay, and shows the same [LockScreenActivity]
 * and system prompt as the accessibility engine. Sessions follow the same rules via
 * [LockStateTracker] and [ForegroundRules]; an app has left once its activities have stopped, so
 * one beside another in split screen, or in picture-in-picture, is still in use. Polls, on its
 * own thread, only while the screen is on and no screensaver is up.
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
            ContextCompat.startForegroundService(context, Intent(context, UsageWatchService::class.java))
            startPending = true
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
                    forgetScreen()
                    shield.hide()
                }
                Intent.ACTION_DREAMING_STOPPED -> {
                    dreaming = false
                    wake()
                }
                // Usage events carry wall-clock times: after a clock change, start afresh from now.
                Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> {
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
                if (!shield.isShowing && activities.isResumed(pkg)) recover(pkg, "on screen when every session ended")
            }
        }
    }

    private val onLockAbandoned: (String) -> Unit = { pkg ->
        // Not covered again during the cooldown: once it ends, look again, in case the app is
        // still (or again) in front.
        handler.postDelayed({
            if (isRunning && polling) recheck(keyguard.isDeviceLocked || keyguard.isKeyguardLocked)
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
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
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
            goHome = ::goHome,
            onTimeout = ::onShieldTimeout,
            keepCoverOnGiveUp = true,
            takeFocus = true
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
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
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
        workerHandler.removeCallbacks(pollTask)
        if (lookBack) workerHandler.post { lookBackNext = true }
        workerHandler.post(pollTask)
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

    /** Nothing known about the screen holds any more. */
    private fun forgetScreen() {
        foreground = null
        foregroundClass = null
        activities.clear()
        sideVisible.clear()
        pausedBehind.clear()
        frontSeeThrough = false
        lockScreenTarget = null
    }

    // -------------------------------------------------------------------------------------------
    // Reading (worker thread)
    // -------------------------------------------------------------------------------------------

    /** What one look at the screen found ([readScreen]). */
    private class Batch(
        val epoch: Int,
        val deviceLocked: Boolean,
        /** The device is locked or the keyguard is up. */
        val screenLocked: Boolean,
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
        val events: List<UsageEvent> = emptyList()
    )

    /** One look at the screen: the device's lock state and the activity events since the last look. */
    private fun readScreen(): Batch {
        val current = epoch
        val deviceLocked = keyguard.isDeviceLocked
        val screenLocked = deviceLocked || keyguard.isKeyguardLocked
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
            return Batch(current, deviceLocked, screenLocked, missing, idle = true)
        }
        if (lookBackNext || wasIdle) {
            lookBackNext = false
            wasIdle = false
            val (from, events) = lookBack(now)
            queriedUntil = now
            return Batch(current, deviceLocked, screenLocked, missing, fresh = true, from = from, events = events)
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
        return Batch(current, deviceLocked, screenLocked, missing, rebasedFrom = rebasedFrom, from = from, events = events)
    }

    /**
     * The activity events from far enough back to include the newest app-resumed one: the last
     * 10 s first (usually enough), then further, up to [STARTUP_LOOKBACK_MS] and never before this
     * boot (usage history survives reboots; only this boot's events say what's in front now).
     */
    private fun lookBack(now: Long): Pair<Long, List<UsageEvent>> {
        val floor = maxOf(now - STARTUP_LOOKBACK_MS, now - SystemClock.elapsedRealtime())
        var span = FIRST_LOOKBACK_MS
        while (true) {
            val from = maxOf(now - span, floor)
            val events = query(from, now)
            if (from == floor || events.any { it.type == UsageEvents.Event.ACTIVITY_RESUMED }) return from to events
            span *= LOOKBACK_GROWTH
        }
    }

    /** Activity events (resumed, paused, stopped) logged from [from] to [to], oldest first. */
    private fun query(from: Long, to: Long): List<UsageEvent> {
        val begin = from.coerceIn(0L, to)
        val events = try {
            if (Build.VERSION.SDK_INT >= 35) {
                // Only the events wanted: far less for Android to gather and send every poll.
                usage.queryEvents(UsageEventsQuery.Builder(begin, to).setEventTypes(*ACTIVITY_EVENTS).build())
            } else {
                @Suppress("DEPRECATION")
                usage.queryEvents(begin, to)
            }
        } catch (_: Exception) {
            null
        } ?: return emptyList()
        val found = ArrayList<UsageEvent>()
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType !in ACTIVITY_EVENTS) continue
            val pkg = event.packageName ?: continue
            found += UsageEvent(event.eventType, pkg, event.className, event.timeStamp)
        }
        return found
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
            if (!idle) forgetScreen()
            idle = true
            // Ends a timed pause that has run out (its own timer doesn't count deep sleep).
            vault.isGlobalLockEnabled()
            return
        }
        idle = false
        if (batch.fresh) {
            forgetScreen()
            handledUntil = Long.MIN_VALUE
        } else if (batch.rebasedFrom != null) {
            activities.clear()
            handledUntil = batch.rebasedFrom
        }
        // Every app that came to the screen since the last look, not only the newest: split screen
        // and app pairs bring two at once, and a dialog can open over an app straight away.
        val resumed = LinkedHashSet<String>()
        var newest: UsageEvent? = null
        for (e in batch.events) {
            val isNew = activities.apply(e)
            if (isNew) lastChangeAt = SystemClock.elapsedRealtime()
            if (e.type != UsageEvents.Event.ACTIVITY_RESUMED) continue
            if (isNew) e.app?.let(resumed::add)
            if (e.time > handledUntil && e.time >= (newest?.time ?: Long.MIN_VALUE)) newest = e
        }
        if (newest != null) {
            handledUntil = newest.time
            frontSeeThrough = isFloating(newest)
        }
        activities.prune(batch.from)
        // A look back from behind the keyguard is about before the phone was locked: nothing to cover.
        if (batch.fresh && batch.screenLocked) return
        newest?.let(::handleForeground)
        for (pkg in resumed) if (pkg != newest?.app) handleOtherResume(pkg)
        reconcile(batch.screenLocked)
    }

    /** A window that floats over the app below: a system dialog or sheet, a sign-in sheet, a Play sheet. */
    private fun isFloating(event: UsageEvent) = event.app == null || event.pkg == PKG_PLAY_STORE

    private fun handleForeground(event: UsageEvent) = handleForeground(event.app, event.cls, isFloating(event))

    private fun handleForeground(pkg: String?, activityClass: String?, floating: Boolean = false) {
        if (!vault.isGlobalLockEnabled()) {
            if (pkg != null) {
                foreground = pkg
                foregroundClass = activityClass
            }
            shield.hide()
            return
        }
        if (floating && backFromLockScreen(pkg)) return
        // Permission dialogs, share and sign-in sheets etc. float over the current app: not an app switch.
        if (pkg == null) return
        val previous = foreground
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
            if (activityClass == LOCK_SCREEN_CLASS) lockScreenTarget = LockScreenActivity.activeLockedTarget
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
     * BioKey's lock screen just closed onto a dialog or sheet ([pkg]) the app had open, such as a
     * permission request: the app, unlocked now, is right behind it, on screen, so its session has
     * arrived. True if so.
     */
    private fun backFromLockScreen(pkg: String?): Boolean {
        if (foreground != packageName || foregroundClass != LOCK_SCREEN_CLASS) return false
        val target = lockScreenTarget?.takeIf { it != pkg } ?: return false
        if (!vault.isAppLocked(target) || !LockStateTracker.isAppUnlocked(target, vault.getTimeoutMode(target))) return false
        foreground = target
        foregroundClass = null
        LockStateTracker.onAppVisible(target)
        return true
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
     * another, and a cover that went missing.
     */
    private fun reconcile(screenLocked: Boolean) {
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
                if (!screenLocked) recover(pkg, "still showing under another app")
            }
        }
        recheck(screenLocked)
    }

    /**
     * Nothing new came to the front, yet a locked app may be showing uncovered: a lock screen on its
     * way out took its cover down, or it came back during the abandon cooldown. Covers it again,
     * once usage access has had [RECHECK_SETTLE_MS] to report anything newer in front.
     */
    private fun recheck(screenLocked: Boolean) {
        if (screenLocked || !polling || dreaming || shield.isShowing) return
        if (SystemClock.elapsedRealtime() - lastChangeAt < RECHECK_SETTLE_MS) return
        val fg = foreground
        if (fg != null && (!activities.knows(fg) || activities.isVisible(fg)) && recover(fg, "still in front, uncovered")) return
        for (pkg in sideVisible.keys.toList()) {
            if (activities.isResumed(pkg) && recover(pkg, "still on screen, uncovered")) return
        }
    }

    /** Covers [pkg] if it is locked, has no session, and nothing is covering or asking for it yet. True if it did. */
    private fun recover(pkg: String, why: String): Boolean {
        if (pkg == packageName || !vault.isAppLocked(pkg)) return false
        if (LockStateTracker.isAppUnlocked(pkg, vault.getTimeoutMode(pkg)) || !mayCover(pkg)) return false
        lock(pkg, why)
        return true
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

    /** [pkg] is in front, beside it, or under something that lets it show. */
    private fun isOnScreen(pkg: String): Boolean =
        pkg == foreground || activities.isResumed(pkg) || (frontSeeThrough && activities.isVisible(pkg))

    private fun lock(pkg: String, why: String = "opened") {
        DiagnosticLog.log("Covered $pkg ($why)")
        LockStateTracker.setRegularLockPending(pkg)
        pausedBehind -= pkg
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
        if (!checkPermissions()) return false
        if (!isOnScreen(target) || LockStateTracker.isAppUnlocked(target, vault.getTimeoutMode(target))) return false
        if (attempt > 0) DiagnosticLog.log("Lock screen for $target not up yet; asking again ($attempt)")
        LockStateTracker.setRegularLockPending(target)
        return LockLauncher.launch(this, target, isSelfProtection = false)
    }

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

    private fun goHome() {
        runCatching {
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
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
 * whichever app is below (a system dialog or sheet, a sign-in or passkey sheet).
 */
internal class UsageEvent(val type: Int, val pkg: String, val cls: String?, val time: Long) {
    val app: String? = if (pkg in ForegroundRules.SYSTEM_OVERLAYS) null else PasswordManager.resolve(pkg, cls)
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
        val key = event.pkg + '/' + event.cls.orEmpty()
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

    fun clear() = byActivity.clear()

    private fun order(type: Int) = when (type) {
        RESUMED -> 0
        PAUSED -> 1
        else -> 2
    }
}
