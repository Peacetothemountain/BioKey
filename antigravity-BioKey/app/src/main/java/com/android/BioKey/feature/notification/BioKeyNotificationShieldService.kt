package com.android.BioKey.feature.notification

import android.app.ActivityOptions
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.android.BioKey.R
import com.android.BioKey.core.security.LockStateTracker
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.feature.lock.LockScreenActivity

/**
 * BioKey Notification Privacy Shield
 *
 * Replicates and enhances Google's native Android Canary (ZP11) App Lock notification privacy.
 * Automatically intercepts incoming notifications from locked applications.
 * If the application is currently in a locked state, this service intercepts the sensitive notification,
 * suppresses raw text, OTP codes, and previews, and replaces it with a privacy-preserving sanitized badge.
 */
class BioKeyNotificationShieldService : NotificationListenerService() {

    companion object {
        private const val TAG = "BioKeyNotifShield"
        private const val CHANNEL_ID = "biokey_privacy_shield_channel"
        private const val CHANNEL_NAME = "BioKey Privacy Shield"

        var isConnected: Boolean = false
            private set

        fun isNotificationAccessGranted(context: Context): Boolean {
            val enabledPackages = NotificationManagerCompat.getEnabledListenerPackages(context)
            return enabledPackages.contains(context.packageName)
        }

        fun getNotificationSettingsIntent(): Intent {
            return Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        }
    }

    private lateinit var securityVault: SecurityVault

    override fun onListenerConnected() {
        super.onListenerConnected()
        isConnected = true
        securityVault = SecurityVault.getInstance(applicationContext)
        Log.i(TAG, "BioKey Notification Privacy Shield connected and active.")
    }

    override fun onListenerDisconnected() {
        isConnected = false
        super.onListenerDisconnected()
        Log.i(TAG, "BioKey Notification Privacy Shield disconnected.")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return
        val pkg = sbn.packageName ?: return

        // Never intercept BioKey's own notifications or core system alerts
        if (pkg == packageName || pkg == "com.android.systemui" || pkg == "android") return

        if (!::securityVault.isInitialized) {
            securityVault = SecurityVault.getInstance(applicationContext)
        }

        if (!securityVault.isGlobalLockEnabled()) return
        if (!securityVault.isNotificationShieldEnabled()) return

        // Check if the package is protected by BioKey
        if (securityVault.isAppLocked(pkg)) {
            // Check if the app is currently in an active unlocked session
            if (!LockStateTracker.isAppUnlocked(pkg, securityVault.getTimeoutMode())) {
                try {
                    // Censor original sensitive notification
                    cancelNotification(sbn.key)
                    Log.d(TAG, "Redacted sensitive notification from locked package: $pkg")
                    postSanitizedNotification(pkg, sbn.id)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed masking notification for $pkg: ${e.message}")
                }
            }
        }
    }

    private fun postSanitizedNotification(lockedPackage: String, originalId: Int) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Privacy-shielded notifications for locked applications"
                setShowBadge(true)
            }
            nm.createNotificationChannel(channel)
        }

        val pm = packageManager
        val appLabel = try {
            val appInfo = pm.getApplicationInfo(lockedPackage, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            "Protected Application"
        }

        // Tap action immediately prompts BioKey unlock screen for the target package
        val unlockIntent = Intent(this, LockScreenActivity::class.java).apply {
            putExtra(LockScreenActivity.EXTRA_LOCKED_PACKAGE, lockedPackage)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val options = ActivityOptions.makeBasic()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            @Suppress("DEPRECATION")
            options.setPendingIntentCreatorBackgroundActivityStartMode(
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            )
        }
        val pi = PendingIntent.getActivity(
            this,
            lockedPackage.hashCode(),
            unlockIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            options.toBundle()
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_lock_key)
            .setContentTitle(appLabel)
            .setContentText("Sensitive notification content hidden")
            .setSubText("BioKey Shield")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setLocalOnly(true)
            .build()

        try {
            nm.notify(lockedPackage, originalId, notification)
        } catch (e: Exception) {
            Log.w(TAG, "Failed posting sanitized notification for $lockedPackage: ${e.message}")
        }
    }
}
