package com.android.BioKey.core.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.os.UserManager
import android.util.Log
import com.android.BioKey.core.FeatureLinks
import com.android.BioKey.core.diagnostics.DiagnosticLog
import com.android.BioKey.core.security.ProtectionAlerts
import com.android.BioKey.core.security.ProtectionEngine
import com.android.BioKey.core.security.SecurityVault
import java.util.concurrent.Executors

/**
 * Warms up settings after boot or an app update.
 */
class BioKeyBootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BioKeyBootReceiver"
        /** How long an engine gets to come up, well inside the broadcast's time limit. */
        private const val START_WAIT_MS = 5_000L
        private const val START_POLL_MS = 200L
        private val bootExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "biokey-boot-worker").apply { isDaemon = true }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> Unit
            else -> return
        }
        val appContext = context.applicationContext
        val userManager = appContext.getSystemService(UserManager::class.java)
        if (userManager?.isUserUnlocked == false) {
            Log.d(TAG, "Ignoring boot broadcast: user is locked in Direct Boot mode")
            return
        }

        val pending = goAsync()
        bootExecutor.execute {
            try {
                val vault = SecurityVault.getInstance(appContext)
                if (vault.isSetupCompleted()) {
                    // Compatibility mode may start here (boot is allowed to start it).
                    ProtectionEngine.refresh(appContext)
                    // Either engine running is protecting: the one expected may hand over to the
                    // other meanwhile (the accessibility service switched off as compatibility mode
                    // starts, say).
                    val anyRunning = { FeatureLinks.engines.accessibilityRunning || FeatureLinks.engines.compatibilityRunning }
                    val protecting = when (ProtectionEngine.mode(appContext)) {
                        ProtectionEngine.Mode.ACCESSIBILITY -> true
                        // Switched on: Android connects it moments after a boot or an update. An alert
                        // only if it still isn't running then, withdrawn as soon as it is.
                        ProtectionEngine.Mode.ACCESSIBILITY_NOT_RUNNING -> awaitRunning("Accessibility engine", anyRunning)
                        // Its permissions don't mean it's running: the start can still fail.
                        ProtectionEngine.Mode.COMPATIBILITY -> awaitRunning("Compatibility engine", anyRunning)
                        else -> false
                    }
                    if (!protecting) ProtectionAlerts.serviceStopped(appContext, now = true)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to complete boot initialization", e)
            } finally {
                pending.finish()
            }
        }
    }

    /** Whether an engine ([engine], for the log) is up within [START_WAIT_MS], as [isRunning] tells. */
    private fun awaitRunning(engine: String, isRunning: () -> Boolean): Boolean {
        val until = SystemClock.elapsedRealtime() + START_WAIT_MS
        while (!isRunning()) {
            if (SystemClock.elapsedRealtime() >= until) {
                DiagnosticLog.log("$engine didn't start after boot or update")
                return false
            }
            Thread.sleep(START_POLL_MS)
        }
        return true
    }
}
