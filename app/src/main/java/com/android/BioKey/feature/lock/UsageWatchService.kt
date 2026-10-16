package com.android.BioKey.feature.lock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.android.BioKey.R
import com.android.BioKey.core.security.LockStateTracker
import com.android.BioKey.core.security.ProtectionAlerts
import com.android.BioKey.core.security.SecurityEngine
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.feature.main.MainActivity

/**
 * Compatibility-mode engine: covers locked apps without the accessibility API.
 *
 * Reads which app was just opened from usage access (app-resumed events, on this device only),
 * covers a locked one with an application overlay, and shows the same [LockScreenActivity] and
 * system prompt as the accessibility engine. Sessions follow the same rules via
 * [LockStateTracker] and [ForegroundRules]. Polls only while the screen is on.
 *
 * Runs as a foreground service with a low-priority notification, which Android requires for
 * work like this.
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

        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            if (isRunning) return
            ContextCompat.startForegroundService(context, Intent(context, UsageWatchService::class.java))
        }

        fun stop(context: Context) {
            if (!isRunning) return
            context.stopService(Intent(context, UsageWatchService::class.java))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var vault: SecurityVault
    private lateinit var usage: UsageStatsManager
    private lateinit var shield: InstantShieldOverlay

    /** App in front, as last reported. */
    private var foreground: String? = null
    /** Wall-clock end of the last query: each poll reads only from here (minus [OVERLAP_MS]) on. */
    private var queriedUntil = 0L
    /** Timestamp of the newest app-resumed event already acted on. */
    private var handledUntil = 0L
    private var polling = false

    private val poll = object : Runnable {
        override fun run() {
            if (!polling) return
            checkForeground()
            handler.postDelayed(this, POLL_MS)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    stopPolling()
                    foreground = null
                    shield.hide()
                }
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> startPolling()
                // Usage events carry wall-clock times: after a clock change, start afresh from now.
                Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> resetCursor()
            }
        }
    }

    private val onLockAll: () -> Unit = {
        // A locked app may be on screen right now: re-evaluate it. Not with the screen off, where
        // nothing shows and the lock screen would only end up over the keyguard.
        handler.post { if (isInteractive()) foreground?.let { handleForeground(it, null) } }
    }

    private fun isInteractive() = getSystemService(PowerManager::class.java)?.isInteractive == true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        vault = SecurityVault.getInstance(this)
        usage = getSystemService(UsageStatsManager::class.java)
        shield = InstantShieldOverlay(
            this,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            goHome = ::goHome,
            onTimeout = ::onShieldTimeout
        )
        AppInfoCache.preWarm(this, vault.getLockedPackages())
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        LockStateTracker.addLockAllListener(onLockAll)
        ProtectionAlerts.serviceRestored(this)
        // A locked app may already be in front (after a reboot or an update): find it first.
        coverAppAlreadyInFront()
        if (isInteractive()) startPolling()
        Log.i(TAG, "Compatibility mode started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        isRunning = false
        stopPolling()
        LockStateTracker.removeLockAllListener(onLockAll)
        runCatching { unregisterReceiver(screenReceiver) }
        if (::shield.isInitialized) shield.destroy()
        // Nothing enforces sessions without an engine, so none may outlive it.
        if (!BioKeyAccessibilityService.isRunning) LockStateTracker.lockAll()
        Log.i(TAG, "Compatibility mode stopped")
        super.onDestroy()
    }

    private fun startPolling() {
        if (polling) return
        polling = true
        handler.post(poll)
    }

    private fun stopPolling() {
        polling = false
        handler.removeCallbacks(poll)
    }

    private fun resetCursor() {
        val now = System.currentTimeMillis()
        queriedUntil = now
        handledUntil = now - OVERLAP_MS
    }

    private fun coverAppAlreadyInFront() {
        val now = System.currentTimeMillis()
        // Usage history survives reboots: only this boot's events say what's in front now.
        val bootedAt = now - SystemClock.elapsedRealtime()
        val newest = newestResume(maxOf(now - STARTUP_LOOKBACK_MS, bootedAt), now, after = 0L)
        resetCursor()
        if (newest != null && isInteractive()) {
            handledUntil = maxOf(handledUntil, newest.third)
            handleForeground(newest.first, newest.second)
        }
    }

    /**
     * Reads the app-resumed events since the last poll and acts on the newest. Each poll reads only
     * the last couple of seconds, however long the user stays in one app.
     */
    private fun checkForeground() {
        val now = System.currentTimeMillis()
        // The clock went backwards (changed by hand, or corrected): nothing would look new any more.
        if (now < queriedUntil) resetCursor()
        val newest = newestResume(queriedUntil - OVERLAP_MS, now, after = handledUntil)
        queriedUntil = now
        if (newest == null) return // No change since last time: keep what we know.
        handledUntil = newest.third
        handleForeground(newest.first, newest.second)
    }

    /** The newest app-resumed event in [from, to] that is later than [after]: package, class, time. */
    private fun newestResume(from: Long, to: Long, after: Long): Triple<String, String?, Long>? {
        val events = try { usage.queryEvents(from, to) } catch (_: Exception) { return null }
        var newest: Triple<String, String?, Long>? = null
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType != UsageEvents.Event.ACTIVITY_RESUMED) continue
            if (event.timeStamp <= after || event.timeStamp < (newest?.third ?: Long.MIN_VALUE)) continue
            newest = Triple(event.packageName, event.className, event.timeStamp)
        }
        return newest
    }

    private fun handleForeground(pkg: String, activityClass: String?) {
        if (!vault.isGlobalLockEnabled()) {
            foreground = pkg
            shield.hide()
            return
        }
        // Permission dialogs, share sheets etc. float over the current app: not an app switch.
        if (pkg in ForegroundRules.SYSTEM_OVERLAYS) return
        val previous = foreground
        foreground = pkg

        if (previous != null && previous != pkg && previous != packageName && vault.isAppLocked(previous)) {
            val hop = pkg.takeIf { !ForegroundRules.isLauncher(this, it) && ForegroundRules.isHopHelper(it, activityClass) }
            LockStateTracker.onAppLeft(previous, vault.getTimeoutMode(previous), hop)
        }
        LockStateTracker.onForegroundChanged(pkg, vault::getTimeoutMode)

        // BioKey's own screens (its lock screen, its main UI) authenticate on their own.
        if (pkg == packageName) return

        if (!vault.isAppLocked(pkg)) {
            if (!LockStateTracker.isRegularLockPending(shield.targetPackage.orEmpty())) shield.hide()
            return
        }
        if (LockStateTracker.isAppUnlocked(pkg, vault.getTimeoutMode(pkg))) {
            LockStateTracker.onAppVisible(pkg)
            shield.hide()
            return
        }
        if (LockStateTracker.isRecentlyAbandoned(pkg)) {
            shield.hide()
            goHome()
            return
        }
        if (LockScreenActivity.isLockScreenResumed && LockScreenActivity.activeLockedTarget == pkg) return
        if (SecurityEngine.INSTANCE.isAuthenticatingFor(pkg) && LockStateTracker.isRegularLockPending(pkg)) return
        lock(pkg)
    }

    private fun lock(pkg: String) {
        LockStateTracker.setRegularLockPending(pkg)
        // The overlay goes up first: it hides the app and, being visible, lets Android start the lock screen.
        shield.show(pkg) { LockLauncher.launch(this, pkg, isSelfProtection = false) }
        if (!LockLauncher.launch(this, pkg, isSelfProtection = false)) {
            goHome()
            shield.hide()
        }
    }

    private fun onShieldTimeout(target: String, attempt: Int): Boolean {
        if (foreground != target || LockStateTracker.isAppUnlocked(target, vault.getTimeoutMode(target))) return false
        LockStateTracker.setRegularLockPending(target)
        return LockLauncher.launch(this, target, isSelfProtection = false)
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
