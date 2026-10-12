package com.android.BioKey.feature.lock

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.provider.Settings
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import com.android.BioKey.R
import com.android.BioKey.core.security.LockStateTracker
import com.android.BioKey.feature.main.openSystemSettings

/**
 * Device administrator activation. BioKey holds no admin policies; the admin only exists so that
 * uninstalling BioKey takes an extra, self-protected step (deactivate first, then uninstall).
 */
object DeviceAdminHelper {
    private const val TAG = "DeviceAdminHelper"

    fun getAdminComponent(context: Context): ComponentName =
        ComponentName(context, BioKeyDeviceAdminReceiver::class.java)

    fun isAdminActive(context: Context): Boolean {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        return dpm?.isAdminActive(getAdminComponent(context)) == true
    }

    private fun getAddDeviceAdminIntent(context: Context): Intent =
        Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, getAdminComponent(context))
            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, context.getString(R.string.device_admin_description))
            // Never add FLAG_ACTIVITY_NEW_TASK: DeviceAdminAdd finishes immediately when started
            // in a new task, without ever showing its UI.
        }

    fun launchActivation(context: Context, launcher: ActivityResultLauncher<Intent>? = null) {
        val intent = getAddDeviceAdminIntent(context)
        // Activating an admin isn't a guarded page, so nothing needs pre-authorising.

        if (launcher != null) {
            try {
                launcher.launch(intent)
                LockStateTracker.beginExternalNavigation(preAuthorizeSelfProtection = true)
                return
            } catch (e: Exception) {
                Log.w(TAG, "Activation via launcher failed: ${e.message}")
            }
        }

        val activity = context.findActivity()
        if (activity != null) {
            try {
                activity.startActivity(intent)
                LockStateTracker.beginExternalNavigation(preAuthorizeSelfProtection = true)
                return
            } catch (e: Exception) {
                Log.w(TAG, "Activation via activity failed: ${e.message}")
            }
        }

        // Last resort: the security settings page, where "Device admin apps" lives on most devices.
        context.openSystemSettings(Intent(Settings.ACTION_SECURITY_SETTINGS))
    }

    private fun Context.findActivity(): Activity? {
        var ctx = this
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }
}
