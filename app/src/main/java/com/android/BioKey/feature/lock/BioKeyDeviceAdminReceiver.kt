package com.android.BioKey.feature.lock

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.os.UserHandle
import com.android.BioKey.core.intruder.IntruderManager
import com.android.BioKey.core.security.ProtectionAlerts
import com.android.BioKey.core.security.SecurityEventLog
import com.android.BioKey.core.security.SecurityVault

/**
 * Device admin with no policies. Its job is to make uninstalling BioKey a two-step process
 * (deactivate the admin, then uninstall), and both steps happen on screens that self-protection
 * guards with a biometric challenge.
 */
class BioKeyDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        if (SecurityVault.getInstance(context).isSetupCompleted()) {
            ProtectionAlerts.adminRestored(context)
        }
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        if (SecurityVault.getInstance(context).isSetupCompleted() && !com.android.BioKey.core.security.LockStateTracker.hasActiveOwnerSession()) {
            val dpm = context.getSystemService(android.app.admin.DevicePolicyManager::class.java)
            runCatching { dpm?.lockNow() }
            SecurityEventLog.record(context, SecurityEventLog.Type.SETTINGS_TAMPERED, "Unauthorized admin deactivation attempt")
        }
        return "Deactivating BioKey's device admin lets BioKey be uninstalled without unlocking it first."
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        if (SecurityVault.getInstance(context).isSetupCompleted()) {
            SecurityEventLog.record(context, SecurityEventLog.Type.ADMIN_REMOVED)
            ProtectionAlerts.adminRemoved(context)
        }
    }

    override fun onPasswordFailed(context: Context, intent: Intent, user: UserHandle) {
        super.onPasswordFailed(context, intent, user)
        SecurityEventLog.record(context, SecurityEventLog.Type.UNLOCK_FAILED, "Device lock screen failure")
        if (SecurityVault.getInstance(context).isIntruderDetectionEnabled()) {
            IntruderManager.captureIntruderPhoto(context, IntruderManager.DEVICE_LOCK_SOURCE)
            SecurityEventLog.record(context, SecurityEventLog.Type.INTRUDER_CAPTURED, "Device lock screen failure")
        }
    }
}
