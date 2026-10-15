package com.android.BioKey.feature.lock

import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import android.app.ActivityOptions
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.view.MotionEvent
import com.android.BioKey.core.security.WindowProtection
import com.android.BioKey.core.security.LockStateTracker
import com.android.BioKey.core.security.SecurityEngine
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.feature.notification.BioKeyNotificationShieldService

/**
 * Covers a locked app (or a BioKey system-settings page) and hosts the system prompt until the
 * owner authenticates.
 *
 * Kept deliberately light (a [ComponentActivity] drawing [LockCoverView], no Compose, no fragments)
 * because it sits on the critical path: the prompt is requested the moment this window's first
 * frame is committed, which is also when the [InstantShieldOverlay] above it is removed.
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

        @Volatile
        var isLockScreenResumed: Boolean = false
            private set

        @Volatile
        var activeLockedTarget: String? = null
            private set
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

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (WindowProtection.isMotionEventObscured(ev)) return true
        return super.dispatchTouchEvent(ev)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        WindowProtection.applyWindowProtections(this)
        noTransition(open = true)
        super.onCreate(savedInstanceState)
        vault = SecurityVault.getInstance(this)

        if (!readIntent(intent)) {
            finishSilently()
            return
        }

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

        // The shield above us can go as soon as our identical cover is actually on screen.
        cover.viewTreeObserver.registerFrameCommitCallback {
            firstFrameCommitted = true
            InstantShieldOverlay.dismiss(lockedPackage)
            maybeAutoPrompt()
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
            val cached = AppInfoCache.get(lockedPackage) ?: AppInfoCache.loadAndCache(this, packageManager, lockedPackage)
            cover.bind(cached?.label ?: AppInfoCache.quickLabel(this, lockedPackage), cached?.icon, isBioKey = false)
        }
        cover.setActionsVisible(false)
    }

    override fun onResume() {
        super.onResume()
        isLockScreenResumed = true
        activeLockedTarget = lockedPackage
        if (!isSelfProtection && isTargetUnlocked(lockedPackage)) {
            finishSilently()
            return
        }
        maybeAutoPrompt()
    }

    override fun onPause() {
        isLockScreenResumed = false
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val newPkg = intent.getStringExtra(EXTRA_LOCKED_PACKAGE).orEmpty()
        val newSelfProt = intent.getBooleanExtra(EXTRA_SELF_PROTECTION, false)
        if (newPkg == lockedPackage && newSelfProt == isSelfProtection) {
            // Same target brought to front again (e.g. over a trampoline): keep the running prompt.
            launchAfterUnlock = launchAfterUnlock || intent.getBooleanExtra(EXTRA_LAUNCH_AFTER_UNLOCK, false)
            if (firstFrameCommitted) InstantShieldOverlay.dismiss(lockedPackage)
            maybeAutoPrompt()
            return
        }
        engine.resetAuthenticatingState(lockedPackage)
        setIntent(intent)
        if (!readIntent(intent)) {
            finishSilently()
            return
        }
        bindCover()
        if (firstFrameCommitted) {
            cover.post { InstantShieldOverlay.dismiss(lockedPackage) }
        }
        maybeAutoPrompt()
    }

    override fun onStop() {
        isLockScreenResumed = false
        super.onStop()
        if (isFinishing || isChangingConfigurations) return
        engine.resetAuthenticatingState(lockedPackage)
        // Screen went off with the cover up: keep it so nothing of the app shows on wake, and
        // prompt again when the user comes back.
        if (getSystemService(PowerManager::class.java)?.isInteractive == false) {
            promptAutoLaunched = false
            return
        }
        val unlocked = if (isSelfProtection) LockStateTracker.isSelfProtectionGranted() else isTargetUnlocked(lockedPackage)
        if (!unlocked) {
            abandon()
            finishSilently()
        }
    }

    override fun onDestroy() {
        isLockScreenResumed = false
        if (activeLockedTarget == lockedPackage) activeLockedTarget = null
        if (!isChangingConfigurations) InstantShieldOverlay.dismiss(lockedPackage)
        engine.resetAuthenticatingState(lockedPackage)
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::cover.isInitialized) cover.refreshPalette()
    }

    private fun maybeAutoPrompt() {
        if (promptAutoLaunched || !firstFrameCommitted || isPermanentLockout || isFinishing) return
        if (!isLockScreenResumed || engine.isAuthenticatingFor(lockedPackage)) return
        promptAutoLaunched = true
        showPrompt()
    }

    private fun showPrompt() {
        val pkg = lockedPackage
        val selfProtection = isSelfProtection
        cover.setStatus(null, permanentLockout = false)
        cover.setActionsVisible(false)
        engine.showBiometricPrompt(
            activity = this,
            appName = if (selfProtection) "BioKey" else AppInfoCache.quickLabel(this, pkg),
            packageName = pkg,
            onSuccess = { onAuthenticated(pkg, selfProtection) },
            onError = { code, message ->
                isPermanentLockout = code == SecurityEngine.ERROR_LOCKOUT_PERMANENT
                val text = when {
                    SecurityEngine.isUserCancel(code) -> null
                    isPermanentLockout -> "Too many attempts. Lock your phone, then unlock it with your PIN, pattern or password to use biometrics again."
                    code == SecurityEngine.ERROR_NO_DEVICE_CREDENTIAL -> "Set a screen lock in Settings to unlock apps."
                    else -> message.toString()
                }
                cover.setStatus(text, isPermanentLockout)
                cover.setActionsVisible(true)
            }
        )
    }

    private fun onAuthenticated(pkg: String, selfProtection: Boolean) {
        if (selfProtection) {
            LockStateTracker.setSelfProtectionGranted(granted = true, visitedGuardedScreen = true)
        } else {
            val deepLinks = if (launchAfterUnlock) BioKeyNotificationShieldService.cachedPendingIntents.remove(pkg) else null
            LockStateTracker.onAppUnlocked(pkg)
            if (launchAfterUnlock) sendPendingDeepLink(pkg, deepLinks)
        }
        LockStateTracker.clearRegularLockPending(pkg)
        finishSilently()
    }

    private fun lockDeviceOrHome() {
        abandon()
        if (!BioKeyAccessibilityService.lockDevice()) goHome()
        finishSilently()
    }

    private fun launchApp(pkg: String) {
        val realPkg = if (pkg == BioKeyAccessibilityService.VIRTUAL_PKG_GOOGLE_PASSWORDS) BioKeyAccessibilityService.PKG_CREDENTIAL_MANAGER else pkg
        packageManager.getLaunchIntentForPackage(realPkg)?.let { launch ->
            runCatching { startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    private fun sendPendingDeepLink(
        pkg: String,
        preExtractedMap: Map<String, BioKeyNotificationShieldService.CachedPendingIntent>? = null
    ) {
        val intentsMap = preExtractedMap ?: BioKeyNotificationShieldService.cachedPendingIntents.remove(pkg)
        val target = intentsMap?.values?.filter { !it.isSummary }?.maxByOrNull { it.postTime }
            ?: intentsMap?.values?.maxByOrNull { it.postTime }
        val pending = target?.pendingIntent
        val realPkg = if (pkg == BioKeyAccessibilityService.VIRTUAL_PKG_GOOGLE_PASSWORDS) BioKeyAccessibilityService.PKG_CREDENTIAL_MANAGER else pkg
        val creator = pending?.creatorPackage
        val validCreator = creator == null || creator == realPkg || creator == pkg ||
            (pkg == BioKeyAccessibilityService.VIRTUAL_PKG_GOOGLE_PASSWORDS && creator == "com.google.android.gms")

        if (pending == null || !validCreator) {
            launchApp(pkg)
            return
        }
        // BioKey's lock screen is on screen while it forwards the app's own link: grant the
        // launch only while visible (Android 17's hardened mode), never an open-ended grant.
        val options = ActivityOptions.makeBasic().apply {
            if (Build.VERSION.SDK_INT >= 37) {
                pendingIntentBackgroundActivityStartMode = ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE
            } else if (Build.VERSION.SDK_INT >= 36) {
                pendingIntentBackgroundActivityStartMode = ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
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
        InstantShieldOverlay.dismiss(lockedPackage)
        finish()
        noTransition(open = false)
    }

    private fun noTransition(open: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(if (open) OVERRIDE_TRANSITION_OPEN else OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }
}
