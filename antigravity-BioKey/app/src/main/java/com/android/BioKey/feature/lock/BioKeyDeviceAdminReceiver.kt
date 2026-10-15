package com.android.BioKey.feature.lock

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.android.BioKey.core.intruder.IntruderManager
import com.android.BioKey.core.security.SecurityVault

class BioKeyDeviceAdminReceiver : DeviceAdminReceiver() {

    companion object {
        private const val TAG = "BioKeyDeviceAdmin"
    }

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "BioKey Device Administration enabled.")
        SecurityVault.getInstance(context).setSelfProtection(true)
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        return "Disabling BioKey device administration allows unauthorized users to uninstall or stop BioKey protection."
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.w(TAG, "BioKey Device Administration disabled.")
        SecurityVault.getInstance(context).setSelfProtection(false)
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onPasswordFailed(context: Context, intent: Intent) {
        super.onPasswordFailed(context, intent)
        Log.w(TAG, "Device lock screen authentication failed! Triggering intruder capture.")
        val vault = SecurityVault.getInstance(context)
        if (vault.isIntruderDetectionEnabled()) {
            IntruderManager.captureIntruderPhoto(context, "device_system_lock")
        }
    }

    override fun onPasswordFailed(context: Context, intent: Intent, user: android.os.UserHandle) {
        super.onPasswordFailed(context, intent, user)
        Log.w(TAG, "Device lock screen authentication failed for user $user! Triggering intruder capture.")
        val vault = SecurityVault.getInstance(context)
        if (vault.isIntruderDetectionEnabled()) {
            IntruderManager.captureIntruderPhoto(context, "device_system_lock")
        }
    }
}
