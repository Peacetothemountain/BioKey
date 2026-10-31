package com.android.BioKey.ui.navigation

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.core.net.toUri
import com.android.BioKey.R
import com.android.BioKey.core.FeatureLinks
import com.android.BioKey.core.security.LockStateTracker
import com.android.BioKey.core.security.ProtectionEngine
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
            // A page opens only in the system's own app, never in whatever else answers: the next
            // page in the list is tried instead (every list ends with one the system always has).
            if (!pinToSystemHandler(context, intent)) continue
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
                return true
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        // Nothing opened and BioKey is still in front: take back the leave pass and the settings
        // pre-authorisation, which would otherwise stay usable by anyone for 2 minutes.
        LockStateTracker.onOwnAppResumed()
        BioKeyMessages.show(context.getString(R.string.nav_settings_page_failed), Icons.Default.ErrorOutline)
        return false
    }

    /**
     * Sends a settings intent straight to the system app that handles it, so another app that
     * claims the same action can't be offered in a chooser and pose as Settings (to fish for the
     * screen lock, say) while BioKey's leave pass is open. Every page BioKey opens is declared in
     * its manifest's queries, so the system's handler is always seen. Where several system apps
     * answer, Settings itself is preferred. Only pages the system app lets other apps open count:
     * the package manager also lists ones it keeps to itself (Pixel's private space page), which
     * would only fail with "Permission Denial". Returns whether it was pinned (false: no system app
     * answers, and it isn't opened).
     */
    internal fun pinToSystemHandler(context: Context, intent: Intent): Boolean {
        if (intent.component != null) return true
        val handlers = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_SYSTEM_ONLY)
            .mapNotNull { it.activityInfo }
            .filter { it.packageName != "android" && it.exported }
        val handler = handlers.firstOrNull { it.packageName == SETTINGS_PACKAGE } ?: handlers.firstOrNull() ?: return false
        intent.setClassName(handler.packageName, handler.name)
        return true
    }

    private const val SETTINGS_PACKAGE = "com.android.settings"

    private fun appDetails(context: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))

    /** Android's security page: faces, fingerprints and the screen lock, without choosing a new one. */
    fun openSecuritySettings(context: Context) = launchFallbackCascade(
        context,
        listOf(Intent(Settings.ACTION_SECURITY_SETTINGS), Intent(Settings.ACTION_SETTINGS))
    )

    fun openScreenLockSetup(context: Context) = launchFallbackCascade(
        context,
        listOf(
            Intent(DevicePolicyManager.ACTION_SET_NEW_PASSWORD),
            Intent(Settings.ACTION_SECURITY_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        )
    )

    /**
     * Adding a strong (Class 3) face or fingerprint, where Android has that page (it asks for the
     * screen lock first), else the security page where they're listed.
     */
    fun openBiometricEnroll(context: Context) = launchFallbackCascade(
        context,
        listOf(
            Intent(Settings.ACTION_BIOMETRIC_ENROLL)
                .putExtra(Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED, BiometricManager.Authenticators.BIOMETRIC_STRONG),
            Intent(Settings.ACTION_SECURITY_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        )
    )

    /**
     * Private space's page, as Android's App lock page opens it. Settings may not let other apps
     * open it (Pixel's doesn't export it): then it doesn't resolve, and BioKey doesn't offer it.
     */
    private const val PRIVATE_SPACE_SETTINGS = "com.android.settings.action.OPEN_PRIVATE_SPACE_SETTINGS"

    private fun privateSpaceIntent() = Intent(PRIVATE_SPACE_SETTINGS).setPackage(SETTINGS_PACKAGE)

    /** Whether a system app answers [intent] (a copy is looked up: [pinToSystemHandler] sets its target). */
    private fun hasSystemPage(context: Context, intent: Intent): Boolean =
        runCatching { pinToSystemHandler(context, Intent(intent)) }.getOrDefault(false)

    /**
     * Private space (Android 15 and later) can be opened from here: only where its page answers
     * other apps. Blocking: off the main thread.
     */
    fun hasPrivateSpacePage(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM && hasSystemPage(context, privateSpaceIntent())

    fun openPrivateSpace(context: Context) = launchFallbackCascade(context, listOf(privateSpaceIntent()))

    /** BioKey's own accessibility toggle where the device has that page, else the accessibility list. */
    fun accessibilitySettingsIntent(context: Context): Intent {
        val details = Intent(ACCESSIBILITY_DETAILS_SETTINGS).putExtra(
            Intent.EXTRA_COMPONENT_NAME,
            FeatureLinks.accessibilityServiceName(context)
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.packageManager.resolveActivity(details, PackageManager.MATCH_SYSTEM_ONLY) != null
        ) details else Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    }

    fun openAccessibilitySettings(context: Context) = launchFallbackCascade(
        context,
        listOf(accessibilitySettingsIntent(context), Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), appDetails(context))
    )

    /** [pkg]'s notification settings in Android (its switch, its channels), else its App info page. */
    fun openAppNotificationSettings(context: Context, pkg: String) = launchFallbackCascade(
        context,
        listOf(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null))
        )
    )

    /** A notification channel's own page ([channel]), else BioKey's notification settings. */
    fun openChannelSettings(context: Context, channel: String?) = launchFallbackCascade(
        context,
        listOfNotNull(
            channel?.let {
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, it)
            },
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
            appDetails(context)
        )
    )

    const val PRIVACY_POLICY_URL = "https://peacetothemountain.github.io/BioKey/privacy/"
    const val SUPPORT_URL = "https://peacetothemountain.github.io/BioKey/support/"

    /** A web page (the privacy policy, help) in the browser, which does the fetching: BioKey can't. */
    fun openWebPage(context: Context, url: String) {
        LockStateTracker.beginExternalNavigation(preAuthorizeSelfProtection = false)
        val intent = Intent(Intent.ACTION_VIEW, url.toUri()).apply { if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        runCatching { context.startActivity(intent) }.onFailure {
            LockStateTracker.onOwnAppResumed()
            BioKeyMessages.show(context.getString(R.string.nav_no_browser, url), Icons.Default.ErrorOutline)
        }
    }

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
                listOf(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, pkgUri), Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS), appDetails(context))
            )
            !ProtectionEngine.canDrawOverlays(context) -> launchFallbackCascade(
                context,
                listOf(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri), Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION), appDetails(context))
            )
            else -> false
        }
    }
}
