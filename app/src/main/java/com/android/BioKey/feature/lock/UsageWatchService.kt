package com.android.BioKey.feature.lock

import android.app.AppOpsManager
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.android.BioKey.R
import com.android.BioKey.core.data.PasswordManager
import com.android.BioKey.core.diagnostics.DiagnosticLog
import com.android.BioKey.core.diagnostics.EngineTrace
import com.android.BioKey.core.diagnostics.LatencyMarks
import com.android.BioKey.core.security.LockStateTracker
import com.android.BioKey.core.security.ProtectionAlerts
import com.android.BioKey.core.security.ProtectionEngine
import com.android.BioKey.core.security.SecurityEngine
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.feature.lock.UsageReader.Batch
import com.android.BioKey.feature.main.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

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
        /** How long an app come forward with the keyguard up is watched for being paused behind it. */
        private const val KEYGUARD_SETTLE_MS = 450L
        /** ActivityInfo's flag for android:showWhenLocked (hidden from apps, set in every Android since 8.1). */
        private const val FLAG_SHOW_WHEN_LOCKED = 0x800000
        private const val CHANNEL_ID = "biokey_compatibility_mode"
        private const val NOTIFICATION_ID = 2001
        /** How often the foreground app is checked while the screen is on. */
        private const val POLL_MS = 150L
        /**
         * A poll that found nothing, while nothing waits on the clock ([isQuiet]), isn't handed to
         * the main thread, except every this many (about 0.6 s): those keep the checks that only
         * look again (an uncovered locked app, a timed pause ending) running all the same.
         */
        private const val QUIET_HEARTBEAT_POLLS = 4
        /**
         * How much of the time the screen was off is read when it comes back on: generous, as the
         * broadcast can arrive after what happened just before it.
         */
        private const val WAKE_LOOKBACK_MS = 30_000L
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
                // Refused in the background (the accessibility engine handing over as it stops,
                // say): Android 15 and later let an app holding "display over other apps" start
                // one only while an overlay of its own shows. Once more, with one up.
                if (Build.VERSION.SDK_INT >= 35 && Settings.canDrawOverlays(context)) {
                    startUnderOverlay(context.applicationContext)
                    return
                }
                startPending = false
                throw e
            }
        }

        /**
         * Puts up a 1-pixel, see-through overlay that takes no touches and, once it's on screen,
         * starts the service; takes it down a moment later. Failing that, nothing protects apps:
         * said as a failed start is ([ProtectionEngine.refresh]).
         */
        private fun startUnderOverlay(app: Context) {
            val main = Handler(Looper.getMainLooper())
            main.post {
                val windows = app.getSystemService(WindowManager::class.java)
                val view = View(app)
                val params = WindowManager.LayoutParams(
                    1, 1,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT
                ).apply { gravity = Gravity.TOP or Gravity.START }
                val shown = windows != null && runCatching { windows.addView(view, params) }.isSuccess
                fun failed(why: String) {
                    startPending = false
                    Log.w(TAG, "Compatibility mode couldn't start in the background: $why")
                    DiagnosticLog.log("Compatibility mode couldn't start in the background")
                    ProtectionAlerts.serviceStopped(app)
                }
                if (!shown) return@post failed("no overlay")
                // Drawn, so Android counts it as showing: a moment can pass before it does, so a
                // refusal is tried again, a few times, while it stays up.
                fun attempt(left: Int) {
                    try {
                        ContextCompat.startForegroundService(app, Intent(app, UsageWatchService::class.java))
                        DiagnosticLog.log("Compatibility mode started under an overlay")
                        main.postDelayed({ runCatching { windows?.removeViewImmediate(view) } }, OVERLAY_START_HOLD_MS)
                    } catch (e: Exception) {
                        if (left > 0) {
                            main.postDelayed({ attempt(left - 1) }, OVERLAY_RETRY_MS)
                        } else {
                            failed(e.javaClass.simpleName)
                            runCatching { windows?.removeViewImmediate(view) }
                        }
                    }
                }
                main.postDelayed({ attempt(OVERLAY_RETRIES) }, OVERLAY_DRAWN_MS)
            }
        }

        /** How long the start overlay is given to be drawn, and kept up after the start. */
        private const val OVERLAY_DRAWN_MS = 100L
        private const val OVERLAY_START_HOLD_MS = 1_000L
        /** Further tries while the start overlay is up, this far apart (about a second and a half in all). */
        private const val OVERLAY_RETRIES = 5
        private const val OVERLAY_RETRY_MS = 250L

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
    /** Reads the screen on [worker]: see [UsageReader]. */
    private val reader by lazy { UsageReader(this, usage, vault, epoch = { epoch }, deviceLockedNow = ::deviceLockedNow) }

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

    /** Quiet polls not handed over since the last one that was ([shouldHandOver]). */
    private var quietPolls = 0
    private var handedOverLocked = false
    private var handedOverIdle = false

    /**
     * Set on the main thread after each batch: nothing it knows needs looking at again unless a
     * poll finds something ([isQuiet]). Cleared by anything else that changes what it knows.
     */
    @Volatile private var quiet = false

    private val pollTask = object : Runnable {
        override fun run() {
            if (!polling) return
            val batch = reader.read()
            if (EngineTrace.enabled) traceBatch(batch)
            if (shouldHandOver(batch)) handler.post { onBatch(batch) }
            workerHandler.postDelayed(this, POLL_MS)
        }
    }

    /** Events already in the trace, and when they happened (worker thread; see [traceBatch]). */
    private val tracedEvents = LinkedHashMap<String, Long>()

    /**
     * Debug builds: a poll that read something for the first time, as EngineTrace's `c.poll`:
     * whether it was a look back, then each event new to it (type, package, class, and how long
     * before the read it happened). A replay adds those and polls at the same moment.
     */
    private fun traceBatch(batch: Batch) {
        val firstSeen = (batch.older + batch.events).filter { e -> tracedEvents.put("${e.time}|${e.type}|${e.activity}", e.time) == null }
        tracedEvents.values.removeAll { it < batch.readAt - 2 * UsageReader.OVERLAP_MS }
        if (firstSeen.isEmpty() && !batch.fresh) return
        val fields = firstSeen.map { e -> listOf(e.type, e.pkg, e.cls.orEmpty(), batch.readAt - e.time).joinToString(",") { EngineTrace.escape(it.toString()) } }
        EngineTrace.record("c.poll", if (batch.fresh) 1 else 0, *fields.toTypedArray())
    }

    /** Whether [batch] goes to the main thread: anything new, or a heartbeat ([QUIET_HEARTBEAT_POLLS]). */
    private fun shouldHandOver(batch: Batch): Boolean {
        val news = batch.events.isNotEmpty() || batch.older.isNotEmpty() || batch.fresh || batch.rebasedFrom != null ||
            batch.missing != null || batch.deviceLocked != handedOverLocked || batch.idle != handedOverIdle
        if (!news && quiet && ++quietPolls < QUIET_HEARTBEAT_POLLS) return false
        quietPolls = 0
        handedOverLocked = batch.deviceLocked
        handedOverIdle = batch.idle
        return true
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            EngineTrace.record("d.bc", intent.action)
            stir()
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
                    // Ended now, whatever order the broadcast reaches BioKey's receivers in.
                    LockStateTracker.onScreenTurnedOff()
                    stopPolling()
                    // The app it came over, if locked: its lock screen goes up behind the
                    // keyguard now, so the screensaver ending never shows it.
                    // A lock screen already up: the app it's for.
                    appInFront()?.let { coverBehindKeyguard(it, "on screen as a screensaver started") }
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
                    workerHandler.post { reader.rebase() }
                }
            }
        }
    }

    private val onLockAll: () -> Unit = {
        // Every session just ended (protection resumed, lock-all) while a locked app may be on
        // screen: cover it now, not when it next opens. Not with the screen off, a screensaver or
        // the keyguard up, where nothing shows and the lock screen would only end up over them.
        handler.post {
            stir()
            if (!isRunning || !polling || dreaming) return@post
            if (keyguardLocked()) {
                // The phone locked with the screen on: the app on screen gets its lock screen
                // behind the keyguard, so the keyguard going (a foldable unfolded) never shows it.
                if (isInteractive()) appInFront()?.let { coverBehindKeyguard(it, "on screen as the phone locked") }
                return@post
            }
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
        handler.post {
            stir()
            if (deferred == pkg) deferred = null
        }
        // Not covered again during the cooldown: once it ends, look again, in case the app is
        // still (or again) in front.
        handler.postDelayed({
            stir()
            if (isRunning && polling) recheck(lazy { deviceLockedNow() || keyguardLocked() })
        }, LockStateTracker.abandonCooldownRemainingMs(pkg) + 50L)
    }

    private val opListener = AppOpsManager.OnOpChangedListener { _, pkg ->
        // Called on a binder thread, for grants as well as withdrawals.
        if (pkg == packageName) handler.post {
            stir()
            if (isRunning) checkPermissions()
        }
    }

    private fun isInteractive() = power.isInteractive.also { EngineTrace.answer("interactive", it) }

    // What the engine asks of the keyguard, recorded for a replay (EngineTrace) when it changes.
    private fun keyguardLocked() = keyguard.isKeyguardLocked.also { EngineTrace.answer("keyguard", it) }

    private fun deviceLockedNow() = keyguard.isDeviceLocked.also { EngineTrace.answer("deviceLocked", it) }

    /** Home, as the engine sends the user there to fail closed. Recorded for a replay (EngineTrace). */
    private fun sendHome() {
        EngineTrace.record("o.home")
        goHome()
    }

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
            goHome = { sendHome() },
            onTimeout = ::onShieldTimeout,
            keepCoverWhile = ::isExposed,
            takeFocus = true,
            holdWhile = ::isHeldOpen
        )
        AppInfoCache.preWarm(this, vault.getLockedPackages())
        AppInfoCache.follow(vault)
        AppInfoCache.onConfiguration(resources.configuration)
        // Read now, in the background, rather than on the main thread at the first app switch.
        ForegroundRules.warmUp(this)
        worker = HandlerThread("biokey-usage").apply { start() }
        workerHandler = Handler(worker.looper)
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_DREAMING_STARTED)
                addAction(Intent.ACTION_DREAMING_STOPPED)
                addAction(Intent.ACTION_TIME_CHANGED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        registerForUnlocks(screenReceiver)
        LockStateTracker.addLockAllListener(onLockAll)
        LockStateTracker.addLockAbandonedListener(onLockAbandoned)
        runCatching {
            appOps.startWatchingMode(AppOpsManager.OPSTR_GET_USAGE_STATS, packageName, opListener)
            appOps.startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, packageName, opListener)
        }
        isRunning = true
        pauseWatch.launch {
            SecurityVault.getInstance(this@UsageWatchService).globalLockEnabledFlow.drop(1).collect {
                if (isRunning) runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification()) }
            }
        }
        ProtectionAlerts.serviceRestored(this)
        startEngineTrace(this, vault, "compatibility")
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
        if (::vault.isInitialized && AppInfoCache.onConfiguration(newConfig)) AppInfoCache.reload(this, vault.getLockedPackages())
    }

    override fun onDestroy() {
        val wasRunning = isRunning
        isRunning = false
        pauseWatch.cancel()
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
            if (lookBack) reader.lookBackNext()
            // The first poll asks whether the phone is locked, and is handed over whatever it finds.
            reader.askLockStateNext()
            quietPolls = QUIET_HEARTBEAT_POLLS
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
        workerHandler.post { reader.readAgainFrom(System.currentTimeMillis() - WAKE_LOOKBACK_MS) }
        startPolling()
    }

    /** The app in front: with BioKey's lock screen in front, the app it's for. */
    private fun appInFront(): String? =
        if (foreground == packageName) lockScreenTarget.takeIf { foregroundClass == LOCK_SCREEN_CLASS } else foreground

    /**
     * [pkg] was in front as a screensaver came over it, or as the phone locked with the screen on
     * (Lockdown, a foldable folded): locked, its lock screen starts behind the keyguard now (no
     * cover: that would show over the screensaver), as the accessibility engine's.
     */
    private fun coverBehindKeyguard(pkg: String, why: String) {
        if (pkg == packageName || ForegroundRules.isLauncher(this, pkg) || !vault.isAppLocked(pkg) ||
            LockStateTracker.isAppUnlocked(pkg, vault.getTimeoutMode(pkg))
        ) return
        DiagnosticLog.log("Covered $pkg ($why)")
        LockStateTracker.setRegularLockPending(pkg)
        LockLauncher.launch(this, pkg, isSelfProtection = false, behindKeyguard = true)
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
    // Deciding (main thread)
    // -------------------------------------------------------------------------------------------

    private fun onBatch(batch: Batch) {
        applyBatch(batch)
        quiet = isRunning && isQuiet()
    }

    /**
     * Nothing known waits on the clock or needs looking at again by itself: no locked app beside
     * the one in front, paused behind it or held, no cover or lock screen up, and the app in front
     * isn't a locked one without a session. Then a poll that finds nothing changes nothing.
     */
    private fun isQuiet(): Boolean {
        if (sideVisible.isNotEmpty() || pausedBehind.isNotEmpty() || pausedShowing.isNotEmpty()) return false
        if (held != null || expanding != null || deferred != null || shield.isShowing || LockScreenActivity.isLockScreenResumed) return false
        val fg = foreground ?: return true
        return fg == packageName || !vault.isAppLocked(fg) || LockStateTracker.isAppUnlocked(fg, vault.getTimeoutMode(fg))
    }

    /** Something other than a poll changed what is known: the next poll is handed over whatever it finds. */
    private fun stir() {
        quiet = false
    }

    /** The latest an event has been seen after it happened (debug builds), to tell how much [UsageReader.OVERLAP_MS] is needed. */
    private var latestSeen = 0L

    private fun noteLateness(batch: Batch, e: UsageEvent) {
        val late = batch.readAt - e.time
        if (late <= latestSeen) return
        latestSeen = late
        Log.d(TAG, "An activity event was first read $late ms after it happened (the latest so far)")
    }

    private fun applyBatch(batch: Batch) {
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
        // The apps those events from before a look back's window are about (see below).
        val olderApps = LinkedHashSet<String>()
        if (batch.fresh) {
            forgetScreen()
            handledUntil = Long.MIN_VALUE
            // What locked apps' activities did before the look back's window: one left paused
            // (in picture-in-picture, say) is showing, and is covered like any found so; one left
            // resumed is on screen beside the app in front (see below). Nothing here came to the
            // front just now, so none of it is handled as an app opening. A process that died
            // while paused, since the screen last came on, leaves that state behind: its app is
            // then covered until the owner unlocks it, which fails closed.
            for (e in batch.older) {
                val before = activities.lastTypeOf(e)
                if (activities.apply(e)) notePaused(e, before, fresh = true)
                e.app?.let(olderApps::add)
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
                if (!batch.fresh && batch.rebasedFrom == null) noteLateness(batch, e)
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
        val screenLocked = lazy(LazyThreadSafetyMode.NONE) { batch.deviceLocked || keyguardLocked() }
        // A look back from behind the keyguard is about before the phone was locked: nothing to cover.
        if (batch.fresh && screenLocked.value) return
        // An unlocked app back on screen is in use before anything else is looked at: one that
        // hopped to BioKey's lock screen from beside the app in front (split screen) comes back
        // with that app, which may well be the newer resume, and mustn't have its hop ended, as
        // if it had gone elsewhere, by that app coming to the front first.
        for (pkg in resumed) {
            if (vault.isAppLocked(pkg) && activities.isResumed(pkg) && LockStateTracker.isAppUnlocked(pkg, vault.getTimeoutMode(pkg))) {
                LockStateTracker.onAppVisible(pkg)
            }
        }
        newest?.let(::handleForeground)
        for (pkg in resumed) if (pkg != newest?.app) handleOtherResume(pkg)
        // A look back: a locked app that last resumed before its window (beside another app in
        // split screen, in a desktop window, on another display) says nothing more while it stays
        // there, yet is on screen and usable. It is handled as one that came to the screen beside
        // the app in front, which covers it if it has no session.
        for (pkg in olderApps) if (pkg != newest?.app && pkg !in resumed && activities.isResumed(pkg)) handleOtherResume(pkg)
        reconcile(screenLocked)
        resumeDeferred()
        releaseHeld()
    }

    /**
     * Keeps [pausedShowing] up to date with [e], news for its activity, whose event before was
     * [before] (null: none known). Paused straight from stopped, or from nothing seen, is showing
     * without having come to the front; so is anything left paused in a [fresh] look back, where
     * nothing older is known. Anything else about the activity ends that.
     *
     * The same as [before] is what the activity already did, read again: after a clock change
     * every event re-read counts as news ([ActivityStates.rebase]), and a window waiting to be
     * covered (paused, in picture-in-picture) must not be forgotten for it, as it says nothing more.
     */
    private fun notePaused(e: UsageEvent, before: Int?, fresh: Boolean) {
        val app = e.app ?: return
        if (before == e.type) return
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
        if (pkg == packageName && activityClass == LOCK_SCREEN_CLASS) {
            // BioKey brought its lock screen up over them: an app beside the one in front that has
            // a session hops there, as the one in front does (below), and once that lock screen
            // closes onto them it is back (onBatch) rather than having left.
            for (entry in sideVisible.entries) {
                if (vault.isAppLocked(entry.key) && LockStateTracker.isAppUnlocked(entry.key, vault.getTimeoutMode(entry.key))) entry.setValue(true)
            }
        }
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
            // Back in front over its own lock screen, which it displaced (opening its main screen
            // from its launch one, as it starts): covered again. Left on purpose: Home.
            if (LockStateTracker.coverAgainAfterDisplaced(pkg)) {
                lock(pkg, "came back over its lock screen")
                return
            }
            hideShieldUnlessLocking(pkg)
            sendHome()
            return
        }
        if (LockScreenActivity.isLockScreenResumed && LockScreenActivity.activeLockedTarget == pkg) return
        // Also while its prompt or a launch is still on its way: a lock screen that has just gone to
        // the background is about to give up, and nothing would cover the app again.
        LatencyMarks.triggerAtWallClock("compatibility", pkg, handledUntil)
        lock(pkg)
    }

    /**
     * [pkg], another app, came to the front. BioKey's own session survives only the trips it
     * starts itself, and only as far as they go: its backup file picker, the settings pages it
     * opened, or another app's lock screen it brought up over itself ([launchLockScreen]), which
     * no other app is part of. Anything else ends them, and the session with them, as in the
     * accessibility engine. That lock screen closing onto a split screen BioKey is half of isn't
     * anything else: the app it was for, or the other half, can be the newer resume, and BioKey's
     * own screen, back beside it, ends that trip itself ([LockStateTracker.onOwnAppResumed]).
     */
    private fun endOwnTrips(pkg: String) {
        if (LockStateTracker.isOwnPickerActive() && pkg !in ForegroundRules.DOCUMENTS_UI) LockStateTracker.lockOwnApp()
        if (!SelfProtectionDetector.isHostPackage(pkg) && LockStateTracker.isExternalNavigationActive()) LockStateTracker.lockOwnApp()
        if (!LockStateTracker.isOwnLockScreenHopFor(pkg) && !isOwnScreenResumed() && LockStateTracker.isOwnLockScreenHopActive()) {
            LockStateTracker.lockOwnApp()
        }
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
     *
     * Back within its abandon cooldown (its lock screen went Home a moment ago, as one that can't
     * have the whole screen does, and the pair was switched straight back to), it isn't covered
     * yet, but it is kept track of as on screen, so it is once the cooldown ends ([recheck],
     * [reconcile]): nothing else would look at it again while it stays there.
     */
    private fun handleOtherResume(pkg: String) {
        if (pkg == packageName || pkg == foreground || !vault.isAppLocked(pkg)) return
        if (!activities.isVisible(pkg)) return // Came and went (a trampoline): nothing showed.
        if (LockStateTracker.isAppUnlocked(pkg, vault.getTimeoutMode(pkg))) {
            LockStateTracker.onAppVisible(pkg)
            sideVisible[pkg] = hopTo(foreground, foregroundClass, pkg) != null
            return
        }
        if (!mayCover(pkg)) {
            if (LockStateTracker.isRecentlyAbandoned(pkg)) {
                // Beside the app in front, or paused behind it: covered as such once the cooldown ends.
                if (activities.isResumed(pkg)) {
                    sideVisible.putIfAbsent(pkg, false)
                } else {
                    pausedBehind.getOrPut(pkg) { SystemClock.elapsedRealtime() }
                }
            }
            return
        }
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
            } else if (activities.isVisible(pkg) && vault.isAppLocked(pkg) && LockStateTracker.isAppUnlocked(pkg, vault.getTimeoutMode(pkg))) {
                // Still showing, unlocked: in use, as in the accessibility engine (SessionRules).
                // An app unlocked as it went into picture-in-picture over its own lock screen,
                // which then closed onto Home, never comes to the front: without this its session
                // would end as never on screen. Nothing ages meanwhile (it hasn't left), and it
                // leaves once its activities stop (above).
                LockStateTracker.onAppVisible(pkg)
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
        val shown = shield.show(pkg) { launchLockScreen(pkg, reprompt = true) }
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
     * it. Over a locked app (its session, or its lock screen), BioKey, or a helper or screen an
     * unlocked app is hopping to (a sign-in tab it opened, say), only [pkg]'s lock screen comes
     * up: opening [pkg] there would count as the user leaving that app (or ending its hop), which
     * would then ask again although they never left it. The same beside an ordinary app in front,
     * with an unlocked app, or BioKey's own unlocked screen, as the other half of a split screen
     * ([isInUseBeside]): opened, [pkg] would push that one off screen too. Going to BioKey's lock
     * screen is a hop, so the app keeps its session and is back once [pkg] is unlocked; BioKey's
     * own session survives it too ([launchLockScreen]). [pkg] then stays in picture-in-picture,
     * under the held cover until it is unlocked. Anywhere else (Home, an ordinary app on its own)
     * [pkg] is opened, to bring it out of picture-in-picture.
     */
    private fun askForShowingPaused(pkg: String) {
        LockStateTracker.setRegularLockPending(pkg)
        val front = foreground
        val overInUse = front != null && front != pkg &&
            (front == packageName || vault.isAppLocked(front) || hopTo(front, foregroundClass, pkg) != null)
        if (overInUse || isInUseBeside(pkg)) {
            if (!launchLockScreen(pkg)) sendHome()
            return
        }
        expanding = pkg
        LockLauncher.launchApp(this, PasswordManager.launchPackage(pkg))
        handler.postDelayed({
            stir()
            onExpandWaitOver(pkg)
        }, PIP_EXPAND_WAIT_MS)
    }

    /**
     * Something other than [pkg] is in use beside the app in front, the other half of a split
     * screen: a locked app with a session, or BioKey's own screen with BioKey unlocked.
     */
    private fun isInUseBeside(pkg: String): Boolean =
        sideVisible.keys.any {
            it != pkg && activities.isResumed(it) && vault.isAppLocked(it) && LockStateTracker.isAppUnlocked(it, vault.getTimeoutMode(it))
        } || (isOwnScreenShowing() && LockStateTracker.hasActiveOwnerSession())

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
        if (!launchLockScreen(pkg)) sendHome()
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
            if (!launchLockScreen(pkg)) sendHome()
            return
        }
        // The overlay goes up first and hides the app. The lock screen may start from the
        // background because the user let BioKey display over other apps.
        if (!shield.show(pkg) { launchLockScreen(pkg) }) {
            if (!checkPermissions()) return
            DiagnosticLog.log("Cover for $pkg couldn't be shown")
        }
        // Couldn't even ask for the lock screen: leave the app. The cover stays up and retries.
        if (!launchLockScreen(pkg)) sendHome()
    }

    /**
     * Asks for [pkg]'s lock screen (and, with [reprompt], its prompt again). Over BioKey's own
     * screen, unlocked, in front or beside the app in front ([isOwnScreenShowing]), that is a trip
     * BioKey starts itself: the lock screen closes back onto BioKey, which then keeps its session
     * rather than asking again although the owner never left it
     * ([LockStateTracker.beginOwnLockScreenHop]). Anything else coming to the front ends that
     * ([endOwnTrips]), as does the lock screen stopping anywhere but over BioKey.
     */
    private fun launchLockScreen(pkg: String, reprompt: Boolean = false): Boolean {
        if (isOwnScreenShowing()) LockStateTracker.beginOwnLockScreenHop(pkg)
        // The keyguard up with the screen on: compatibility mode can't see windows. An app whose
        // screen is made to show over the keyguard (in its manifest) has its cover there; another
        // is over it only if it stays visible (the camera, made to in its code; paused by its
        // cover, not stopped): one started behind the keyguard is stopped at once, and its cover
        // waits there, unseen, for the unlock.
        if (keyguardLocked() && isInteractive() && !showsOverKeyguard(pkg, foregroundClass)) {
            // One wait at a time for an app (its cover's checks ask again meanwhile).
            if (!keyguardWaits.add(pkg)) return true
            handler.postDelayed({
                keyguardWaits -= pkg
                // Gone, unlocked or left meanwhile (the phone unlocked and another app opened):
                // looked at as usual from here.
                if (!isRunning || foreground != pkg || !vault.isProtectionActive() || !vault.isAppLocked(pkg) ||
                    LockStateTracker.isAppUnlocked(pkg, vault.getTimeoutMode(pkg))
                ) return@postDelayed
                val behind = keyguardLocked() && !activities.isVisible(pkg)
                if (behind) DiagnosticLog.log("Covered $pkg (opened behind the keyguard)")
                if (!LockLauncher.launch(this, pkg, isSelfProtection = false, reprompt = reprompt, behindKeyguard = behind) && !behind) sendHome()
            }, KEYGUARD_SETTLE_MS)
            return true
        }
        return LockLauncher.launch(this, pkg, isSelfProtection = false, reprompt = reprompt)
    }

    /** Apps come forward with the keyguard up, being watched for whether they're over it ([launchLockScreen]). */
    private val keyguardWaits = HashSet<String>()

    /** Whether [pkg]'s screen [cls] is made to show over the keyguard (android:showWhenLocked). */
    private fun showsOverKeyguard(pkg: String, cls: String?): Boolean = runCatching {
        val name = cls?.takeIf { it.isNotEmpty() && foreground == pkg } ?: return false
        packageManager.getActivityInfo(ComponentName(pkg, name), 0).flags and FLAG_SHOW_WHEN_LOCKED != 0
    }.getOrDefault(false)

    /**
     * BioKey's own screen (not its lock screen) is in front, or resumed beside the app in front
     * (the other half of a split screen): a lock screen brought up now comes up over it.
     */
    private fun isOwnScreenShowing(): Boolean =
        (foreground == packageName && foregroundClass != LOCK_SCREEN_CLASS) || isOwnScreenResumed()

    /** Some screen of BioKey's own other than its lock screen is resumed, as usage access last said. */
    private fun isOwnScreenResumed(): Boolean = activities.isResumed(packageName, except = "$packageName/$LOCK_SCREEN_CLASS")

    private fun onShieldTimeout(target: String, attempt: Int): Boolean {
        stir()
        if (!isExposed(target)) return false
        // Still being brought out of picture-in-picture: its lock screen follows then (onExpandWaitOver).
        // Or waiting for another app's lock screen to go: its own follows then (resumeDeferred).
        if (target == expanding || target == deferred) return true
        if (attempt > 0) DiagnosticLog.log("Lock screen for $target not up yet; asking again ($attempt)")
        LockStateTracker.setRegularLockPending(target)
        return launchLockScreen(target)
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

    /** Whether apps are being locked now (not paused); read at start, before anything else is set up. */
    private fun protecting() = runCatching { SecurityVault.getInstance(this).isProtectionActive() }.getOrDefault(true)

    /** The notification says whether apps are locked or paused: as that changes, it does. */
    private val pauseWatch = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        // Created, or its name and description updated (Android keeps the owner's importance).
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.compat_channel_name), NotificationManager.IMPORTANCE_MIN).apply {
                description = getString(R.string.compat_channel_description)
                setShowBadge(false)
            }
        )
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_biokey_mark)
            .setContentTitle(getString(if (protecting()) R.string.compat_notification_title else R.string.compat_notification_title_paused))
            .setContentText(getString(if (protecting()) R.string.compat_notification_text else R.string.home_paused_body))
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}
