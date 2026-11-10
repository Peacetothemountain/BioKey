package com.android.BioKey.core.security

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.android.BioKey.R
import com.android.BioKey.core.FeatureLinks
import com.android.BioKey.core.diagnostics.DiagnosticLog

/**
 * Which detection engine protects locked apps.
 *
 * - **Accessibility** (preferred): instant, covers launches from the tap, and powers
 *   self-protection.
 * - **Compatibility**: usage access + display over other apps, for when accessibility is
 *   unavailable, as under Android 17's Advanced Protection, which reserves accessibility for
 *   genuine accessibility tools. These are narrower permissions Advanced Protection doesn't
 *   restrict, so BioKey keeps working without claiming to be something it isn't.
 */
object ProtectionEngine {
    private const val TAG = "ProtectionEngine"

    enum class Mode {
        /** The accessibility service is on and running. */
        ACCESSIBILITY,
        /**
         * The accessibility service is switched on but isn't running: it crashed (Android stops
         * restarting one that keeps crashing), or hasn't been connected yet. Nothing covers apps,
         * and compatibility mode stays off while the switch is on.
         */
        ACCESSIBILITY_NOT_RUNNING,
        /**
         * Compatibility mode is running or starting (before setup is complete: its permissions are
         * granted, so it is the engine that will run).
         */
        COMPATIBILITY,
        /**
         * Advanced Protection blocks accessibility and compatibility mode isn't running: it still
         * needs its permissions (or couldn't start in the background, until BioKey is opened).
         */
        NEEDS_COMPATIBILITY_SETUP,
        /** Nothing is watching: the accessibility service is off. */
        OFF
    }

    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        val op = AppOpsManager.OPSTR_GET_USAGE_STATS
        val mode = if (Build.VERSION.SDK_INT >= 36) {
            appOps.checkOpNoThrow(op, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.unsafeCheckOpNoThrow(op, Process.myUid(), context.packageName)
        }
        // MODE_DEFAULT (e.g. granted by a device policy) defers to the permission itself.
        return mode == AppOpsManager.MODE_ALLOWED ||
            (mode == AppOpsManager.MODE_DEFAULT &&
                context.checkSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED)
    }

    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun compatibilityPermissionsGranted(context: Context): Boolean = hasUsageAccess(context) && canDrawOverlays(context)

    /** What compatibility mode lacks, in the user's words and language ("usage access"), or null if nothing. */
    fun missingCompatibilityPermissions(context: Context): String? {
        val usage = hasUsageAccess(context)
        val overlay = canDrawOverlays(context)
        return when {
            !usage && !overlay -> context.getString(
                R.string.permission_both, context.getString(R.string.permission_usage_access), context.getString(R.string.permission_overlay)
            )
            !usage -> context.getString(R.string.permission_usage_access)
            !overlay -> context.getString(R.string.permission_overlay)
            else -> null
        }
    }

    /** Compatibility mode is the engine to run: BioKey is set up, accessibility is off, its permissions are granted. */
    fun shouldRunCompatibility(context: Context): Boolean =
        SecurityVault.getInstance(context).isSetupCompleted() &&
            !FeatureLinks.engines.accessibilityEnabled(context) &&
            compatibilityPermissionsGranted(context)

    /** Tests of what depends on the mode, without a running engine. */
    @VisibleForTesting
    internal var overrideModeForTests: Mode? = null

    fun mode(context: Context): Mode = overrideModeForTests ?:
        accessibilityMode(FeatureLinks.engines.accessibilityEnabled(context), FeatureLinks.engines.accessibilityRunning) ?: when {
            compatibilityPermissionsGranted(context) && compatibilityUp(context) -> Mode.COMPATIBILITY
            AdvancedProtection.blocksBioKey(context) -> Mode.NEEDS_COMPATIBILITY_SETUP
            else -> Mode.OFF
        }

    /**
     * What the accessibility switch ([switchedOn]) and the service itself ([running]) say, or null
     * while the switch is off (compatibility mode decides then). A switch left on doesn't mean
     * protected: the tile, the home screen and Settings all answer from this.
     */
    internal fun accessibilityMode(switchedOn: Boolean, running: Boolean): Mode? = when {
        running -> Mode.ACCESSIBILITY
        switchedOn -> Mode.ACCESSIBILITY_NOT_RUNNING
        else -> null
    }

    /** A failed start (refused in the background) must not read as protecting. */
    private fun compatibilityUp(context: Context): Boolean =
        !SecurityVault.getInstance(context).isSetupCompleted() || FeatureLinks.engines.compatibilityRunning || FeatureLinks.engines.compatibilityStartPending

    /** Whether locked apps are being watched by an engine, in [mode]. */
    fun isProtecting(mode: Mode): Boolean = mode == Mode.ACCESSIBILITY || mode == Mode.COMPATIBILITY

    /** Whether locked apps are currently being watched by either engine. */
    fun isProtecting(context: Context): Boolean = isProtecting(mode(context))

    /**
     * Starts compatibility mode when it's the engine that should run, stops it otherwise. Returns
     * whether compatibility mode is running or on its way. Safe to call often (app start, resume,
     * boot, Advanced Protection changes, service stop).
     */
    fun refresh(context: Context): Boolean {
        val shouldRun = shouldRunCompatibility(context)
        try {
            if (shouldRun) FeatureLinks.engines.startCompatibility(context) else FeatureLinks.engines.stopCompatibility(context)
        } catch (e: Exception) {
            // Android limits starting foreground services from the background; the next time
            // BioKey is opened it starts from the foreground. Until then nothing covers apps: say so.
            Log.w(TAG, "Couldn't ${if (shouldRun) "start" else "stop"} compatibility mode: ${e.message}")
            if (shouldRun && !FeatureLinks.engines.compatibilityRunning) {
                DiagnosticLog.log("Compatibility mode couldn't start in the background")
                ProtectionAlerts.serviceStopped(context)
            }
        }
        return shouldRun && (FeatureLinks.engines.compatibilityRunning || FeatureLinks.engines.compatibilityStartPending)
    }
}
