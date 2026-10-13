package com.android.BioKey.feature.setup

import android.app.AppOpsManager
import android.content.Context
import android.os.Build

/**
 * Helper to detect if Android 13+ (API 33+) Restricted Settings (ACCESS_RESTRICTED_SETTINGS)
 * or Android 15+ Enhanced Confirmation Mode (ECM) is actively blocking BioKey's sensitive
 * capabilities (Accessibility and Notification Listener).
 */
object RestrictedSettingsHelper {
    private const val OPSTR_ACCESS_RESTRICTED_SETTINGS = "android:access_restricted_settings"

    /**
     * Returns true if Android 13+ restricted settings are actively blocking BioKey.
     * Returns false if the OS is < API 33, if installed via official store, or if the user has approved restricted settings.
     */
    @Suppress("DEPRECATION")
    fun isRestrictedSettingsEnforced(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val installSource = context.packageManager.getInstallSourceInfo(context.packageName)
                val installer = installSource.installingPackageName
                if (installer == "com.android.vending") return false
                if (installSource.packageSource == android.content.pm.PackageInstaller.PACKAGE_SOURCE_STORE) {
                    return false
                }
            }
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
                ?: return false
            val mode = appOps.unsafeCheckOpNoThrow(
                OPSTR_ACCESS_RESTRICTED_SETTINGS,
                context.applicationInfo.uid,
                context.packageName
            )
            mode != AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) {
            false
        }
    }

    /** Alias for backward compatibility */
    fun isRestricted(context: Context): Boolean = isRestrictedSettingsEnforced(context)
}
