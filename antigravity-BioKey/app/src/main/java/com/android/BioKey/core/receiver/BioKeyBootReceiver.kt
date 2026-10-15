package com.android.BioKey.core.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager
import android.util.Log
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.core.security.TitanM3SecurityManager

/**
 * BioKey System Boot & Package Update Receiver
 *
 * Fully aligned with Android SDK 35-37+ background execution specifications.
 * Ensures the BioKey defense engine and Titan M3 hardware security are immediately
 * armed upon device boot or after package updates.
 */
class BioKeyBootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BioKeyBootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.i(TAG, "Received system broadcast: $action")

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                try {
                    val appContext = context.applicationContext
                    val userManager = appContext.getSystemService(Context.USER_SERVICE) as? UserManager
                    val isUnlocked = userManager?.isUserUnlocked ?: true

                    if (!isUnlocked) {
                        Log.w(TAG, "Device in Direct Boot state. Postponing CE storage and KeyStore initialization until first user unlock.")
                        return
                    }

                    // Warm up SecurityVault preferences & state
                    val vault = SecurityVault.getInstance(appContext)

                    // Verify and initialize hardware security keys
                    TitanM3SecurityManager.INSTANCE.initializeTitanM3Protocols(appContext)

                    Log.i(TAG, "BioKey defense engine armed post-boot/update. MasterShield: ${vault.isGlobalLockEnabled()}")
                } catch (e: Exception) {
                    Log.e(TAG, "Error re-arming BioKey post-boot: ${e.message}", e)
                }
            }
        }
    }
}
