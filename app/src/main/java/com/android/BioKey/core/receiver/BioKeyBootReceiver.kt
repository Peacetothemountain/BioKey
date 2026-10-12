package com.android.BioKey.core.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager
import android.util.Log
import com.android.BioKey.core.security.HardwareKeyManager
import com.android.BioKey.core.security.ProtectionAlerts
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.feature.lock.BioKeyAccessibilityService
import com.android.BioKey.feature.lock.DeviceAdminHelper
import java.util.concurrent.Executors

/**
 * Warms up settings and Keystore keys after boot or an app update, so the first lock after a
 * restart doesn't pay the key-generation cost. Enforcement itself restarts with the
 * accessibility service, which the system rebinds automatically.
 */
class BioKeyBootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BioKeyBootReceiver"
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
        // Credential-encrypted storage and auth-bound keys aren't usable before the first unlock.
        val userManager = appContext.getSystemService(UserManager::class.java)
        if (userManager?.isUserUnlocked == false) {
            Log.d(TAG, "Ignoring boot broadcast: user is locked in Direct Boot mode")
            return
        }

        val pending = goAsync()
        bootExecutor.execute {
            try {
                val vault = SecurityVault.getInstance(appContext)
                HardwareKeyManager.initialize(appContext)
                if (vault.isSetupCompleted()) {
                    if (!BioKeyAccessibilityService.isServiceEnabled(appContext)) {
                        ProtectionAlerts.serviceStopped(appContext)
                    }
                    if (!DeviceAdminHelper.isAdminActive(appContext)) {
                        ProtectionAlerts.adminRemoved(appContext)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to complete boot initialization", e)
            } finally {
                pending.finish()
            }
        }
    }
}
