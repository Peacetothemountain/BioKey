package com.android.BioKey.feature.main

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import com.android.BioKey.core.security.LockStateTracker
import com.android.BioKey.core.security.ProtectionEngine
import com.android.BioKey.feature.lock.BioKeyAccessibilityService
import com.android.BioKey.feature.notification.BioKeyNotificationShieldService
import com.android.BioKey.ui.theme.BioKeyMessages

/** Settings page for one accessibility service (Android 13+); not exposed as an SDK constant. */
private const val ACCESSIBILITY_DETAILS_SETTINGS = "android.settings.ACCESSIBILITY_DETAILS_SETTINGS"

/**
 * Opens system settings pages, most specific page first. Leaving BioKey this way keeps the
 * owner's session and pre-authorises BioKey's own settings pages (see [LockStateTracker]).
 */
object IntentNavigationHelper {

    /** Starts the first intent the device can handle. */
    fun launchFallbackCascade(context: Context, intents: List<Intent>): Boolean {
        LockStateTracker.beginExternalNavigation(preAuthorizeSelfProtection = true)
        for (intent in intents) {
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
                return true
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        BioKeyMessages.show("Couldn't open that settings page on this phone", Icons.Default.ErrorOutline)
        return false
    }

    private fun appDetails(context: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))

    fun openScreenLockSetup(context: Context) = launchFallbackCascade(
        context,
        listOf(
            Intent(DevicePolicyManager.ACTION_SET_NEW_PASSWORD),
            Intent(Settings.ACTION_SECURITY_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        )
    )

    /** BioKey's own accessibility toggle where the device has that page, else the accessibility list. */
    fun accessibilitySettingsIntent(context: Context): Intent {
        val details = Intent(ACCESSIBILITY_DETAILS_SETTINGS).putExtra(
            Intent.EXTRA_COMPONENT_NAME,
            ComponentName(context, BioKeyAccessibilityService::class.java).flattenToString()
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.packageManager.resolveActivity(details, 0) != null
        ) details else Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    }

    fun openAccessibilitySettings(context: Context) = launchFallbackCascade(
        context,
        listOf(accessibilitySettingsIntent(context), Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), appDetails(context))
    )

    fun openNotificationListenerSettings(context: Context) = launchFallbackCascade(
        context,
        listOf(
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                ComponentName(context, BioKeyNotificationShieldService::class.java).flattenToString()
            ),
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
            appDetails(context)
        )
    )

    fun openAppDetailsSettings(context: Context) = launchFallbackCascade(context, listOf(appDetails(context)))

    /**
     * Compatibility mode's next missing permission: BioKey's own usage-access page, then its
     * display-over-other-apps page. Returns false when both are already granted.
     */
    fun openNextCompatibilityPermission(context: Context): Boolean {
        val pkgUri = Uri.fromParts("package", context.packageName, null)
        return when {
            !ProtectionEngine.hasUsageAccess(context) -> launchFallbackCascade(
                context,
                listOf(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, pkgUri), Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            )
            !ProtectionEngine.canDrawOverlays(context) -> launchFallbackCascade(
                context,
                listOf(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri), Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            )
            else -> false
        }
    }
}
