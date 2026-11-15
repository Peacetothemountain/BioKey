package com.android.BioKey.feature.setup

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.annotation.VisibleForTesting

/**
 * Whether Android's "restricted settings" stop the accessibility switch for BioKey (Android 13
 * and later), as far as an app can tell about itself.
 *
 * What can be known, by version (AOSP; an app can't ask Android's own answer, which needs a
 * system permission):
 * - Android 12 and earlier: no such restriction.
 * - Android 13 and 14: the app op `ACCESS_RESTRICTED_SETTINGS` is set to "errored" when an app is
 *   installed from a local or downloaded file (not by an app store, not by adb). "Errored" is
 *   restricted; "ignored" is restricted with Android's dialog already seen (which is what unlocks
 *   "Allow restricted settings" in App info); "allowed" (Android's starting value) is not.
 * - Android 15 to 17 (Enhanced Confirmation Mode): the same op, but left at "default", and Android
 *   decides from how the app was installed: a local or downloaded file is restricted unless the
 *   installer is one Android trusts, which an app can't see. "Errored" and "ignored" still mean
 *   restricted, "allowed" never.
 *
 * So [Verdict.BLOCKED] is sure; [Verdict.UNSURE] is where only Android knows (or the check failed),
 * and setup offers help only once the owner comes back without the service on.
 */
object RestrictedSettingsHelper {
    private const val OPSTR_ACCESS_RESTRICTED_SETTINGS = "android:access_restricted_settings"

    enum class Verdict {
        /** Android doesn't restrict the switch for BioKey. */
        NOT_BLOCKED,

        /** It does, for certain: the step is shown from the start. */
        BLOCKED,

        /** It might: help is offered only if the owner comes back from Android's settings without the service on. */
        UNSURE
    }

    fun verdict(context: Context): Verdict {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return Verdict.NOT_BLOCKED
        return try {
            val pm = context.packageManager
            val installSource = pm.getInstallSourceInfo(context.packageName)
            val installer = installSource.installingPackageName
            val installerIsSystem = installer != null && runCatching {
                pm.getApplicationInfo(installer, 0).flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            }.getOrDefault(false)
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return Verdict.UNSURE
            val mode = if (Build.VERSION.SDK_INT >= 36) {
                appOps.checkOpNoThrow(OPSTR_ACCESS_RESTRICTED_SETTINGS, context.applicationInfo.uid, context.packageName)
            } else {
                @Suppress("DEPRECATION")
                appOps.unsafeCheckOpNoThrow(OPSTR_ACCESS_RESTRICTED_SETTINGS, context.applicationInfo.uid, context.packageName)
            }
            verdictFor(Build.VERSION.SDK_INT, mode, installSource.packageSource, installer, installerIsSystem)
        } catch (_: Throwable) {
            Verdict.UNSURE
        }
    }

    /** The rules above, from what [verdict] reads. */
    @VisibleForTesting
    internal fun verdictFor(sdk: Int, mode: Int, packageSource: Int, installer: String?, installerIsSystem: Boolean): Verdict = when {
        sdk < Build.VERSION_CODES.TIRAMISU -> Verdict.NOT_BLOCKED
        mode == AppOpsManager.MODE_ALLOWED -> Verdict.NOT_BLOCKED
        mode == AppOpsManager.MODE_ERRORED || mode == AppOpsManager.MODE_IGNORED -> Verdict.BLOCKED
        mode != AppOpsManager.MODE_DEFAULT -> Verdict.UNSURE
        // "Default" on 13 and 14 isn't Android's own starting value ("allowed"): only the phone's maker knows.
        sdk < Build.VERSION_CODES.VANILLA_ICE_CREAM -> Verdict.UNSURE
        // From 15, Android decides "default" from the install source.
        packageSource != PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE &&
            packageSource != PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE -> Verdict.NOT_BLOCKED
        // A file, but put in by Play or the phone's own store: Android trusts those.
        installer == PLAY_STORE || installerIsSystem -> Verdict.NOT_BLOCKED
        else -> Verdict.UNSURE
    }

    /**
     * Whether setup shows the restricted-settings step: from the start where it's sure to be
     * needed; where it might be, only after [cameBackWithoutService] (the owner went to Android's
     * settings and BioKey still isn't on).
     */
    fun showStep(verdict: Verdict, cameBackWithoutService: Boolean): Boolean = when (verdict) {
        Verdict.NOT_BLOCKED -> false
        Verdict.BLOCKED -> true
        Verdict.UNSURE -> cameBackWithoutService
    }

    private const val PLAY_STORE = "com.android.vending"
}
