package com.android.BioKey.feature.lock

import android.app.ActivityOptions
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.OutcomeReceiver
import android.os.PowerManager
import android.util.Log
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import com.android.BioKey.core.data.PasswordManager
import com.android.BioKey.core.diagnostics.DiagnosticLog
import com.android.BioKey.core.security.LockStateTracker
import com.android.BioKey.core.security.SecurityEngine
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.core.security.WindowProtection
import com.android.BioKey.feature.notification.BioKeyNotificationShieldService
import java.lang.ref.WeakReference

/**
 * Covers a locked app (or a BioKey system-settings page) and hosts the system prompt until the
 * owner authenticates.
 *
 * Kept deliberately light (a [ComponentActivity] drawing [LockCoverView], no Compose, no fragments)
 * because it sits on the critical path: the prompt is requested the moment this window's first
 * frame is committed, which is also when the [InstantShieldOverlay] above it is removed (once the
 * window has the whole display: see [onCoverShown]).
 *
 * Not exported: only BioKey's own components can start it.
 */
class LockScreenActivity : ComponentActivity() {

    companion object {
        private const val TAG = "LockScreenActivity"
        const val EXTRA_LOCKED_PACKAGE = "extra_locked_package"
        const val EXTRA_SELF_PROTECTION = "extra_self_protection"
        /** Launch the locked app after a successful unlock (used by redacted notifications). */
        const val EXTRA_LAUNCH_AFTER_UNLOCK = "extra_launch_after_unlock"
        /** How long a cover in a window waits to be given the whole display before failing closed. */
        private const val FULLSCREEN_WAIT_MS = 1_500L
        /**
         * How long a shield outlives a cover that sent the user Home: long enough for Home to be in
         * front, and well inside [LockStateTracker.ABANDONED_LOCK_COOLDOWN_MS], so it is still ours.
         */
        private const val HOME_SETTLE_MS = 500L

        @Volatile
        var isLockScreenResumed: Boolean = false
            private set

        @Volatile
        var activeLockedTarget: String? = null
            private set

        /**
         * The newest lock screen. A new one can start while the last one is still finishing: only
         * the newest may change the state above or lower the shield.
         */
        @Volatile private var current: WeakReference<LockScreenActivity>? = null

        /**
         * What to send once [pkg] is unlocked from a stand-in: the link of its newest hidden
         * notification, if that is the app's own link to one of its screens. Null means opening
         * the app itself: a link made by another app, or by one BioKey can't tell, is never sent,
         * and one that starts a broadcast or a service may put nothing on screen, while the new
         * session must reach it ([LockStateTracker.ARRIVAL_GRACE_MS]).
         */
        internal fun deepLinkFor(
            pkg: String,
            intents: Map<String, BioKeyNotificationShieldService.CachedPendingIntent>?
        ): PendingIntent? {
            val target = intents?.values?.filter { !it.isSummary }?.maxByOrNull { it.postTime }
                ?: intents?.values?.maxByOrNull { it.postTime }
            val pending = target?.pendingIntent ?: return null
            val creator = pending.creatorPackage ?: return null
            val ownLink = creator == realPackageOf(pkg) || creator == pkg ||
                (pkg == PasswordManager.VIRTUAL_PACKAGE && creator == PasswordManager.GMS)
            return pending.takeIf { ownLink && it.isActivity }
        }

        /** The package that opens [pkg]: Google Password Manager's virtual one is Credential Manager. */
        private fun realPackageOf(pkg: String) =
            if (pkg == PasswordManager.VIRTUAL_PACKAGE) PasswordManager.CREDENTIAL_MANAGER else pkg
    }

    private val engine get() = SecurityEngine.INSTANCE
    private lateinit var vault: SecurityVault
    private lateinit var cover: LockCoverView

    private var lockedPackage = ""
    private var isSelfProtection = false
    private var launchAfterUnlock = false
    /** The prompt opens by itself once per showing; after a cancel the user taps Unlock. */
    private var promptAutoLaunched = false
    private var firstFrameCommitted = false
    private var isPermanentLockout = false
    /** Left without unlocking: a shield an engine raised since is newer than this lock (see [onStop]). */
    private var leftWithoutUnlock = false
    /** See [showOverKeyguard]. */
    private var showsOverKeyguard = false
    /** Asked for the whole display ([askForWholeDisplay]); the shield stays up until it comes. */
    private var awaitingFullscreen = false

    private val isCurrent get() = current?.get() === this

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (WindowProtection.isMotionEventObscured(ev)) return true
        return super.dispatchTouchEvent(ev)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        WindowProtection.applyWindowProtections(this)
        noTransition(open = true)
        super.onCreate(savedInstanceState)
        current = WeakReference(this)
        vault = SecurityVault.getInstance(this)

        if (!readIntent(intent)) {
            finishSilently()
            return
        }
        showOverKeyguard(isKeyguardLocked())

        cover = LockCoverView(this).apply {
            onUnlock = ::showPrompt
            onCancel = ::goHomeAndFinish
            onLockDevice = ::lockDeviceOrHome
        }
        bindCover()
        setContentView(cover)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goHomeAndFinish()
        })
        LockStateTracker.addLockAllListener(onLockAll)

        // The shield above us can go as soon as our identical cover is actually on screen.
        cover.viewTreeObserver.registerFrameCommitCallback {
            firstFrameCommitted = true
            onCoverShown()
        }
    }

    /** Applies an intent's target. Returns false if there is nothing left to protect. */
    private fun readIntent(intent: Intent): Boolean {
        val pkg = intent.getStringExtra(EXTRA_LOCKED_PACKAGE).orEmpty()
        val selfProtection = intent.getBooleanExtra(EXTRA_SELF_PROTECTION, false)
        if (pkg.isBlank()) return false
        if (!selfProtection && isTargetUnlocked(pkg)) {
            // Nothing to unlock; still honour a tap on a hidden-notification stand-in.
            if (intent.getBooleanExtra(EXTRA_LAUNCH_AFTER_UNLOCK, false)) sendPendingDeepLink(pkg)
            return false
        }
        lockedPackage = pkg
        activeLockedTarget = pkg
        isSelfProtection = selfProtection
        launchAfterUnlock = intent.getBooleanExtra(EXTRA_LAUNCH_AFTER_UNLOCK, false)
        promptAutoLaunched = false
        isPermanentLockout = false
        return true
    }

    private fun isTargetUnlocked(pkg: String) =
        !vault.isGlobalLockEnabled() || LockStateTracker.isAppUnlocked(pkg, vault.getTimeoutMode(pkg))

    private fun bindCover() {
        if (isSelfProtection) {
            cover.bind("BioKey", null, isBioKey = true)
        } else {
            val pkg = lockedPackage
            val cached = AppInfoCache.get(pkg)
            cover.bind(cached?.label ?: AppInfoCache.quickLabel(this, pkg), cached?.icon, isBioKey = false)
            // Never load on the way to the prompt: the icon follows as soon as it's ready.
            if (cached == null) AppInfoCache.load(this, pkg) { info ->
                if (!isFinishing && lockedPackage == pkg) cover.setIcon(info.icon)
            }
        }
        cover.setActionsVisible(false)
    }

    override fun onResume() {
        super.onResume()
        current = WeakReference(this)
        isLockScreenResumed = true
        activeLockedTarget = lockedPackage
        if (!isSelfProtection && isTargetUnlocked(lockedPackage)) {
            finishSilently()
            return
        }
        maybeAutoPrompt()
    }

    override fun onPause() {
        if (isCurrent) isLockScreenResumed = false
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Already leaving: if the app is still in front, the engine's shield brings a new lock.
        if (isFinishing) return
        showOverKeyguard(isKeyguardLocked())
        val newPkg = intent.getStringExtra(EXTRA_LOCKED_PACKAGE).orEmpty()
        val newSelfProt = intent.getBooleanExtra(EXTRA_SELF_PROTECTION, false)
        if (newPkg == lockedPackage && newSelfProt == isSelfProtection) {
            // Same target brought to front again (e.g. over a trampoline): keep the running prompt.
            launchAfterUnlock = launchAfterUnlock || intent.getBooleanExtra(EXTRA_LAUNCH_AFTER_UNLOCK, false)
            if (firstFrameCommitted) whenCoverDrawn()
            return
        }
        engine.resetAuthenticatingStateFor(this)
        setIntent(intent)
        if (!readIntent(intent)) {
            finishSilently()
            return
        }
        bindCover()
        if (firstFrameCommitted) whenCoverDrawn()
    }

    override fun onStop() {
        if (isCurrent) isLockScreenResumed = false
        super.onStop()
        if (isFinishing || isChangingConfigurations) return
        engine.resetAuthenticatingStateFor(this)
        // The screen went off, or the keyguard came up over the cover (Lockdown with the screen
        // on): keep the cover, behind the keyguard, so nothing of the app shows once the phone is
        // unlocked, and prompt again then.
        if (isScreenOff() || (isKeyguardLocked() && !showsOverKeyguard)) {
            promptAutoLaunched = false
            isPermanentLockout = false
            showOverKeyguard(false)
            return
        }
        val unlocked = if (isSelfProtection) LockStateTracker.isSelfProtectionGranted() else isTargetUnlocked(lockedPackage)
        if (!unlocked) {
            abandon()
            leftWithoutUnlock = true
            // An engine may have covered the app again since we were paused (it came straight
            // back, with the quick-switch gesture, say): that shield is newer than this lock and
            // stays, and its own checks bring the lock screen back. Only a shield still waiting
            // for our first frame is ours to lower.
            if (!firstFrameCommitted) dismissShield()
            finish()
            noTransition(open = false)
        }
    }

    override fun onDestroy() {
        LockStateTracker.removeLockAllListener(onLockAll)
        if (::cover.isInitialized) cover.removeCallbacks(fullscreenTimeout)
        if (isCurrent) {
            isLockScreenResumed = false
            if (activeLockedTarget == lockedPackage) activeLockedTarget = null
            if (!isChangingConfigurations && !leftWithoutUnlock) dismissShield()
            current = null
        }
        engine.resetAuthenticatingStateFor(this)
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::cover.isInitialized) cover.refreshPalette()
        onWindowingChanged()
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        onWindowingChanged()
    }

    /** Calls [onCoverShown] once the cover's next frame is on screen. */
    private fun whenCoverDrawn() {
        cover.viewTreeObserver.registerFrameCommitCallback(::onCoverShown)
        cover.invalidate()
    }

    /**
     * Our cover is on screen. If it has the whole display, the shield above it can go and the
     * prompt come up. In split screen or a freeform or desktop window, the locked app may be on
     * screen beside it: the shield stays while the cover asks for the whole display.
     */
    private fun onCoverShown() {
        if (isFinishing) return
        if (!hasWholeDisplay()) {
            askForWholeDisplay()
            return
        }
        dismissShield()
        maybeAutoPrompt()
    }

    /** Full screen, or a window as big as the display: nothing else can be on screen beside it. */
    private fun hasWholeDisplay() = !isInMultiWindowMode ||
        windowManager.currentWindowMetrics.bounds == windowManager.maximumWindowMetrics.bounds

    /**
     * Asks to turn this window into a full-screen one (freeform and desktop windows, Android 14
     * and later). Where that isn't possible, or not granted in time, fails closed: see [leaveExposed].
     */
    private fun askForWholeDisplay() {
        if (awaitingFullscreen) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            leaveExposed()
            return
        }
        awaitingFullscreen = true
        cover.postDelayed(fullscreenTimeout, FULLSCREEN_WAIT_MS)
        requestFullscreenMode(FULLSCREEN_MODE_REQUEST_ENTER, object : OutcomeReceiver<Void?, Throwable> {
            // Granted: the resize that follows ends the wait (onWindowingChanged).
            override fun onResult(result: Void?) = Unit
            override fun onError(error: Throwable) = runOnUiThread(::leaveExposed)
        })
    }

    private val fullscreenTimeout = Runnable { if (awaitingFullscreen) leaveExposed() }

    /**
     * Resized, or into or out of multi-window. Given the whole display, carry on; losing it once
     * the shield is down (the user made the cover a window, with the prompt up) fails closed.
     */
    private fun onWindowingChanged() {
        if (!firstFrameCommitted || isFinishing) return
        if (!hasWholeDisplay()) {
            if (!awaitingFullscreen) leaveExposed()
        } else if (awaitingFullscreen) {
            awaitingFullscreen = false
            cover.removeCallbacks(fullscreenTimeout)
            whenCoverDrawn()
        }
    }

    /**
     * The locked app may be on screen beside the cover: fail closed by going Home, which takes it
     * off screen. A shield still up stays until Home is in front.
     */
    private fun leaveExposed() {
        if (isFinishing) return
        DiagnosticLog.log("Lock screen for $lockedPackage didn't get the whole screen; went Home")
        abandon()
        leftWithoutUnlock = true
        goHome()
        val pkg = lockedPackage
        Handler(Looper.getMainLooper()).postDelayed({ InstantShieldOverlay.dismiss(pkg) }, HOME_SETTLE_MS)
        finish()
        noTransition(open = false)
    }

    private fun maybeAutoPrompt() {
        if (promptAutoLaunched || !firstFrameCommitted || awaitingFullscreen || isPermanentLockout || isFinishing) return
        if (!isLockScreenResumed || engine.isAuthenticatingFor(lockedPackage)) return
        promptAutoLaunched = true
        showPrompt()
    }

    private fun showPrompt() {
        val pkg = lockedPackage
        val selfProtection = isSelfProtection
        // A device lock while the prompt is up ends every session, this one included.
        val generation = LockStateTracker.lockGeneration()
        cover.setStatus(null, permanentLockout = false)
        cover.setActionsVisible(false)
        engine.showBiometricPrompt(
            activity = this,
            appName = if (selfProtection) "BioKey" else AppInfoCache.quickLabel(this, pkg),
            packageName = pkg,
            onSuccess = { onAuthenticated(pkg, selfProtection, generation) },
            onError = { code, message ->
                if (!SecurityEngine.isUserCancel(code)) DiagnosticLog.log("Unlock prompt for $pkg: error $code")
                isPermanentLockout = code == SecurityEngine.ERROR_LOCKOUT_PERMANENT
                val text = when {
                    SecurityEngine.isUserCancel(code) -> null
                    isPermanentLockout -> SecurityEngine.LOCKOUT_PERMANENT_MESSAGE
                    code == SecurityEngine.ERROR_NO_DEVICE_CREDENTIAL -> "Set a screen lock in Settings to unlock apps."
                    else -> message.toString()
                }
                cover.setStatus(text, isPermanentLockout)
                cover.setActionsVisible(true)
            }
        )
    }

    private fun onAuthenticated(pkg: String, selfProtection: Boolean, generation: Int) {
        if (selfProtection) {
            if (LockStateTracker.lockGeneration() != generation) return unlockVoided()
            LockStateTracker.setSelfProtectionGranted(granted = true, visitedGuardedScreen = true)
        } else {
            // Taken first: the unlock lets the notification shield restore (and forget) them.
            val deepLinks = if (launchAfterUnlock) BioKeyNotificationShieldService.cachedPendingIntents.remove(pkg) else null
            if (!LockStateTracker.onAppUnlocked(pkg, generation)) {
                deepLinks?.let { BioKeyNotificationShieldService.cachedPendingIntents.putIfAbsent(pkg, it) }
                return unlockVoided()
            }
            if (launchAfterUnlock) sendPendingDeepLink(pkg, deepLinks)
        }
        LockStateTracker.clearRegularLockPending(pkg)
        finishSilently()
    }

    /**
     * Every session ended while the prompt was up (the phone was locked, say), so this unlock
     * doesn't count: the app stays covered until the owner unlocks it again.
     */
    private fun unlockVoided() {
        cover.setStatus(null, permanentLockout = false)
        cover.setActionsVisible(true)
    }

    private fun lockDeviceOrHome() {
        abandon()
        if (!BioKeyAccessibilityService.lockDevice()) goHome()
        finishSilently()
    }

    private fun launchApp(pkg: String) {
        packageManager.getLaunchIntentForPackage(realPackageOf(pkg))?.let { launch ->
            runCatching { startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    private fun sendPendingDeepLink(
        pkg: String,
        preExtractedMap: Map<String, BioKeyNotificationShieldService.CachedPendingIntent>? = null
    ) {
        val pending = deepLinkFor(pkg, preExtractedMap ?: BioKeyNotificationShieldService.cachedPendingIntents.remove(pkg))
        if (pending == null) {
            launchApp(pkg)
            return
        }
        // BioKey's lock screen is on screen while it forwards the app's own link: grant the
        // launch only while visible, never an open-ended grant.
        val options = ActivityOptions.makeBasic().apply {
            if (Build.VERSION.SDK_INT >= 36) {
                pendingIntentBackgroundActivityStartMode = ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                @Suppress("DEPRECATION")
                pendingIntentBackgroundActivityStartMode = ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            }
        }.toBundle()
        runCatching { pending.send(this, 0, null, null, null, null, options) }.onFailure {
            Log.w(TAG, "Deep link for $pkg failed, launching instead: ${it.message}")
            launchApp(pkg)
        }
    }

    /** Bookkeeping when the user walks away from the lock without authenticating. */
    private fun abandon() {
        DiagnosticLog.log("Lock screen for $lockedPackage left without unlocking")
        LockStateTracker.markLockAbandoned(lockedPackage)
        if (isSelfProtection) LockStateTracker.clearSelfProtection()
    }

    private fun goHome() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun goHomeAndFinish() {
        abandon()
        if (!isSelfProtection || !BioKeyAccessibilityService.dismissSelfProtection()) goHome()
        finishSilently()
    }

    private fun finishSilently() {
        dismissShield()
        finish()
        noTransition(open = false)
    }

    /** Lowers the shield over our target, unless a newer lock screen has taken over from us. */
    private fun dismissShield() {
        if (isCurrent) InstantShieldOverlay.dismiss(lockedPackage)
    }

    /**
     * Whether the cover may show over the keyguard: only while covering a locked app that is
     * itself over the keyguard (the camera, say). Anywhere else the keyguard stays on top, so
     * whoever picks up the phone sees neither the app's name nor a prompt for it, and the cover
     * waits behind the keyguard until the phone is unlocked.
     */
    private fun showOverKeyguard(show: Boolean) {
        showsOverKeyguard = show
        setShowWhenLocked(show)
    }

    /**
     * Every session just ended. If that is because the phone was locked with the screen on
     * (Lockdown), the keyguard must come up over the cover. Can run on any thread.
     */
    private val onLockAll: () -> Unit = {
        runOnUiThread { if (!isDestroyed && isKeyguardLocked()) showOverKeyguard(false) }
    }

    private fun isKeyguardLocked() = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

    private fun isScreenOff() = getSystemService(PowerManager::class.java)?.isInteractive == false

    private fun noTransition(open: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(if (open) OVERRIDE_TRANSITION_OPEN else OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }
}
