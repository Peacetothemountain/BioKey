package com.android.BioKey.feature.setup

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.annotation.RequiresApi

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
    fun isRestrictedSettingsEnforced(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return try {
            val installSource = context.packageManager.getInstallSourceInfo(context.packageName)
            if (installSource.installingPackageName == "com.android.vending") return false
            val packageSource = installSource.packageSource
            if (packageSource == PackageInstaller.PACKAGE_SOURCE_STORE) return false
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
                ?: return true
            val mode = runCatching {
                if (Build.VERSION.SDK_INT >= 36) {
                    appOps.checkOpNoThrow(OPSTR_ACCESS_RESTRICTED_SETTINGS, context.applicationInfo.uid, context.packageName)
                } else {
                    @Suppress("DEPRECATION")
                    appOps.unsafeCheckOpNoThrow(OPSTR_ACCESS_RESTRICTED_SETTINGS, context.applicationInfo.uid, context.packageName)
                }
            }.getOrDefault(AppOpsManager.MODE_IGNORED)
            isRestricted(Build.VERSION.SDK_INT, mode, packageSource)
        } catch (_: Throwable) {
            true
        }
    }

    /**
     * Whether the restricted-settings app op [mode] blocks the app. Android 13 and 14 set it to
     * "errored" for sideloaded apps at install. From Android 15 it stays "default" and Android
     * decides from the install source: only apps installed from a local or downloaded file are
     * restricted, not adb or other installers. "Ignored" means restricted, and the user was told.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    internal fun isRestricted(sdk: Int, mode: Int, packageSource: Int): Boolean = when {
        mode == AppOpsManager.MODE_ALLOWED -> false
        sdk >= Build.VERSION_CODES.VANILLA_ICE_CREAM && mode == AppOpsManager.MODE_DEFAULT ->
            packageSource == PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE ||
                packageSource == PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE
        else -> true
    }
}
