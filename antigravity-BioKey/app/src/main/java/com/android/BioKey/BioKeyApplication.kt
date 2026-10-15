package com.android.BioKey

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks2
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import com.android.BioKey.core.security.LockStateTracker
import com.android.BioKey.core.security.TitanM3SecurityManager
import com.android.BioKey.core.security.ZeroKnowledgeMemorySanitizer
import com.android.BioKey.feature.lock.ScreenReceiver

/**
 * Android 18 BioKey Hardened Application Architecture
 *
 * Implements platform-level defense-in-depth:
 * 1. Global Zero-Leak Recents Protection via ActivityLifecycleCallbacks:
 *    - Unconditionally binds `FLAG_SECURE`
 *    - Unconditionally calls `setRecentsScreenshotEnabled(false)` (Android 13+ / 16 / 17 / 18)
 * 2. Component Isolation & Memory Hygiene:
 *    - ComponentCallbacks2 integration: purges cryptographic tokens on TRIM_MEMORY_UI_HIDDEN
 *    - Hardened ScreenReceiver registration strictly using `RECEIVER_NOT_EXPORTED`
 */
class BioKeyApplication : Application(), ComponentCallbacks2 {

    companion object {
        private const val TAG = "BioKeyApp"
        lateinit var instance: BioKeyApplication
            private set
    }

    private var screenReceiver: ScreenReceiver? = null

    override fun onCreate() {
        super.onCreate()
        instance = this

        Log.i(TAG, "Initializing BioKey v4.5 with Android 18 Platform Hardening & Titan M3 StrongBox...")

        // 1. Hardware Security & Keystore initialization
        TitanM3SecurityManager.INSTANCE.initializeTitanM3Protocols(this)
        com.android.BioKey.core.security.SecurityVault.getInstance(this)

        // 2. Register Global Zero-Leak Activity Protection
        registerGlobalWindowProtection()

        // 3. Register hardened dynamic screen receiver with strict non-exported isolation
        registerScreenReceiver()
    }

    /**
     * Enforces dual-layer snapshot and recents protection globally across every activity.
     */
    private fun registerGlobalWindowProtection() {
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                applyDualLayerWindowProtection(activity)
            }

            override fun onActivityStarted(activity: Activity) {}

            override fun onActivityResumed(activity: Activity) {
                applyDualLayerWindowProtection(activity)
            }

            override fun onActivityPaused(activity: Activity) {
                // Ensure recents snapshot remains disabled
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    activity.setRecentsScreenshotEnabled(false)
                }
            }

            override fun onActivityStopped(activity: Activity) {
                // Invalidate decorView to prevent dirty surface retention
                activity.window?.decorView?.invalidate()
            }

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    private fun applyDualLayerWindowProtection(activity: Activity) {
        try {
            // Layer 1: Hardware-backed DRM surface protection (SurfaceFlinger)
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

            // Layer 2: Android 13+ / 16 / 17 / 18 System Overview/Recents Snapshot Disabler
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                activity.setRecentsScreenshotEnabled(false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed applying dual-layer window protection: ${e.message}")
        }
    }

    private fun registerScreenReceiver() {
        screenReceiver = ScreenReceiver.registerHardened(
            context = this,
            onScreenOff = {
                Log.d(TAG, "Screen off / sleep transition: zero-knowledge session purge initiated.")
                LockStateTracker.onScreenTurnedOff()
            },
            onUserPresent = {
                Log.d(TAG, "User present broadcast received.")
            }
        )
    }

    /**
     * ComponentCallbacks2: Sanitizes in-memory secrets when the OS reclaims resources
     * or when BioKey moves completely into the background.
     */
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Log.i(TAG, "onTrimMemory called with level: $level")

        when {
            // Severe memory pressure on device: full zero-knowledge purge
            level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE ||
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
                Log.w(TAG, "Critical memory warning ($level): performing full zero-knowledge purge.")
                ZeroKnowledgeMemorySanitizer.evictAllSessionsAndScrubMemory()
                System.gc()
            }

            // BioKey UI is completely hidden (user pressed Home or switched apps): scrub ephemeral UI buffers
            level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> {
                Log.i(TAG, "UI Hidden ($level): Scrubbing ephemeral UI memory buffers.")
                ZeroKnowledgeMemorySanitizer.trimUiMemory()
            }
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Log.w(TAG, "System low memory: Purging all session tokens and scrubbing memory.")
        ZeroKnowledgeMemorySanitizer.evictAllSessionsAndScrubMemory()
    }

    override fun onTerminate() {
        screenReceiver?.unregister(this)
        ZeroKnowledgeMemorySanitizer.evictAllSessionsAndScrubMemory()
        super.onTerminate()
    }
}
