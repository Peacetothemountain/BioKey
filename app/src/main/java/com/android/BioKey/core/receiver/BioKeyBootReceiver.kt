package com.android.BioKey.core.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager
import com.android.BioKey.core.security.HardwareKeyManager
import com.android.BioKey.core.security.ProtectionAlerts
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.feature.lock.BioKeyAccessibilityService
import com.android.BioKey.feature.lock.DeviceAdminHelper
import kotlin.concurrent.thread

/**
 * Warms up settings and Keystore keys after boot or an app update, so the first lock after a
 * restart doesn't pay the key-generation cost. Enforcement itself restarts with the
 * accessibility service, which the system rebinds automatically.
 */
class BioKeyBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> Unit
            else -> return
        }
        val appContext = context.applicationContext
        // Credential-encrypted storage and auth-bound keys aren't usable before the first unlock.
        if (appContext.getSystemService(UserManager::class.java)?.isUserUnlocked == false) return

        val pending = goAsync()
        thread(name = "biokey-boot-init") {
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
            } finally {
                pending.finish()
            }
        }
    }
}
