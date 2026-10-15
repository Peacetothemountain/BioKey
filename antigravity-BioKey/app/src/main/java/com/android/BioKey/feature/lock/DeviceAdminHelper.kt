package com.android.BioKey.feature.lock

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import com.android.BioKey.R

object DeviceAdminHelper {
    private const val TAG = "DeviceAdminHelper"

    fun getAdminComponent(context: Context): ComponentName {
        return ComponentName(context, BioKeyDeviceAdminReceiver::class.java)
    }

    fun isAdminActive(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        return dpm?.isAdminActive(getAdminComponent(context)) == true
    }

    fun getAddDeviceAdminIntent(context: Context): Intent {
        val adminComp = getAdminComponent(context)
        val explanation = context.getString(R.string.device_admin_description)
        return Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComp)
            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, explanation)
            // CRITICAL: NEVER add FLAG_ACTIVITY_NEW_TASK here.
            // In Android's DeviceAdminAdd, adding FLAG_ACTIVITY_NEW_TASK causes the activity
            // to immediately finish() and dismiss without ever rendering the UI.
        }
    }

    fun launchActivation(
        context: Context,
        launcher: ActivityResultLauncher<Intent>? = null
    ) {
        val intent = getAddDeviceAdminIntent(context)

        // Primary: Launch via ActivityResultLauncher if provided (inherits Activity stack correctly)
        if (launcher != null) {
            try {
                launcher.launch(intent)
                return
            } catch (e: Exception) {
                Log.w(TAG, "Primary launcher failed: ${e.message}, attempting direct activity launch", e)
            }
        }

        // Secondary: Find Activity and launch directly without FLAG_ACTIVITY_NEW_TASK
        val activity = context.findActivity()
        if (activity != null) {
            try {
                activity.startActivity(intent)
                return
            } catch (e: Exception) {
                Log.w(TAG, "Activity startActivity failed: ${e.message}, attempting fallbacks", e)
            }
        }

        // Fallback 1: Device Admin apps management page in Settings
        try {
            val adminSettingsIntent = Intent().apply {
                component = ComponentName("com.android.settings", "com.android.settings.Settings\$DeviceAdminSettingsActivity")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(adminSettingsIntent)
            Toast.makeText(context, "Select BioKey to activate Device Admin", Toast.LENGTH_SHORT).show()
            return
        } catch (e: Exception) {
            Log.w(TAG, "Settings DeviceAdminSettingsActivity fallback failed: ${e.message}", e)
        }

        // Fallback 2: Security Settings
        try {
            val securityIntent = Intent(Settings.ACTION_SECURITY_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(securityIntent)
            return
        } catch (e: Exception) {
            Log.w(TAG, "ACTION_SECURITY_SETTINGS fallback failed: ${e.message}", e)
        }

        // Fallback 3: General System Settings
        try {
            val generalSettingsIntent = Intent(Settings.ACTION_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(generalSettingsIntent)
        } catch (e: Exception) {
            Log.e(TAG, "All fallback intents failed: ${e.message}", e)
            Toast.makeText(context, "Unable to launch settings: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun deactivateAdmin(context: Context): Boolean {
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            dpm?.removeActiveAdmin(getAdminComponent(context))
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deactivate device admin", e)
            false
        }
    }

    fun Context.findActivity(): Activity? {
        var ctx = this
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }
}
