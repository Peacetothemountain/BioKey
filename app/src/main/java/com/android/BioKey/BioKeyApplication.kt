package com.android.BioKey

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.UserManager
import androidx.core.content.ContextCompat
import com.android.BioKey.core.intruder.IntruderManager
import com.android.BioKey.core.security.HardwareKeyManager
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.feature.lock.ScreenReceiver
import kotlin.concurrent.thread

class BioKeyApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        ScreenReceiver.register(this)

        val userManager = getSystemService(UserManager::class.java)
        val isUnlocked = userManager?.isUserUnlocked ?: true

        if (isUnlocked) {
            initCredentialProtectedComponents()
        } else {
            val filter = IntentFilter(Intent.ACTION_USER_UNLOCKED)
            ContextCompat.registerReceiver(
                this,
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        if (intent?.action == Intent.ACTION_USER_UNLOCKED) {
                            runCatching { this@BioKeyApplication.unregisterReceiver(this) }
                            initCredentialProtectedComponents()
                        }
                    }
                },
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }
    }

    private fun initCredentialProtectedComponents() {
        val app = this
        thread(name = "biokey-async-init") {
            HardwareKeyManager.initialize(app)
            IntruderManager.migrateLegacyPhotos(app)
            SecurityVault.getInstance(app)
        }
    }
}
