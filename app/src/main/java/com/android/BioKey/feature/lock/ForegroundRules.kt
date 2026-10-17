package com.android.BioKey.feature.lock

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.MediaStore

/**
 * What the app in front means for sessions, shared by both detection engines (accessibility and
 * compatibility mode) so they behave identically.
 */
object ForegroundRules {

    private const val PKG_GMS = "com.google.android.gms"

    private val KNOWN_LAUNCHERS = setOf(
        "com.google.android.apps.nexuslauncher",
        "com.android.launcher",
        "com.android.launcher3",
        "com.sec.android.app.launcher",
        "com.miui.home",
        "com.oppo.launcher",
        "com.oneplus.launcher",
        "com.huawei.android.launcher"
    )

    /**
     * Apps people hop to mid-task (authenticators, SMS/email for codes, account and file pickers,
     * cameras for scanning). Going to one straight from an unlocked app and coming straight back
     * keeps that app's session, even in "Immediately" mode.
     */
    private val HOP_HELPER_PACKAGES = setOf(
        PKG_GMS,
        "com.google.android.gsf",
        "com.google.android.apps.credentialmanager",
        "com.android.credentialmanager",
        "com.google.android.apps.authenticator2",
        "com.azure.authenticator",
        "com.authy.authy",
        "com.yubico.yubioath",
        "com.duosecurity.duomobile",
        "org.fedorahosted.freeotp",
        "org.liberty.android.freeotpplus",
        "com.beemdevelopment.aegis",
        "com.twofasapp",
        "io.ente.auth",
        "com.bitwarden.authenticator",
        "com.x8bit.bitwarden",
        "com.onepassword.android",
        "com.lastpass.authenticator",
        "com.callpod.android_apps.keeper",
        "com.dashlane",
        "com.zoho.accounts.oneauth",
        "com.google.android.apps.messaging",
        "com.samsung.android.messaging",
        "com.google.android.gm",
        "com.microsoft.office.outlook",
        "ch.protonmail.android",
        "com.google.android.documentsui",
        "com.android.documentsui",
        "com.google.android.providers.media.module",
        "com.android.providers.media.module",
        // The photo picker's own module (Android 15+), and Google Photos, which apps open to pick.
        "com.android.photopicker",
        "com.google.android.photopicker",
        "com.google.android.apps.photos",
        "com.google.android.GoogleCamera",
        "com.android.camera",
        "com.android.camera2",
        "com.sec.android.app.camera"
    )

    /** Windows that float over apps without meaning the user switched apps. Exact names only. */
    val SYSTEM_OVERLAYS = setOf(
        "android",
        "com.android.systemui",
        "com.google.android.permissioncontroller",
        "com.android.permissioncontroller",
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
        "com.android.credentialmanager",
        "com.google.android.webview",
        "com.android.webview",
        "com.google.android.as",
        "com.google.android.apps.wellbeing",
        "com.google.android.intentresolver",
        "com.android.intentresolver",
        "com.google.android.markup",
        "com.android.companiondevicemanager"
    )

    private val KNOWN_BROWSERS = setOf(
        "com.android.chrome", "com.chrome.beta", "com.chrome.canary", "com.chrome.dev",
        "org.mozilla.firefox", "org.mozilla.firefox_beta", "org.mozilla.fenix", "org.mozilla.focus",
        "com.microsoft.emmx", "com.duckduckgo.mobile.android", "com.opera.browser", "com.opera.gx",
        "com.opera.mini.native", "com.brave.browser", "com.sec.android.app.sbrowser",
        "com.sec.android.app.sbrowser.beta", "com.vivaldi.browser"
    )

    @Volatile private var launchers: Set<String> = KNOWN_LAUNCHERS
    @Volatile private var launchersCheckedAt = 0L

    /** Home-screen apps (refreshed at most every 30 s; the user can switch launchers). */
    fun isLauncher(context: Context, pkg: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (launchersCheckedAt == 0L || now - launchersCheckedAt > 30_000L) {
            launchersCheckedAt = now
            runCatching {
                launchers = KNOWN_LAUNCHERS + context.packageManager
                    .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_ALL)
                    .mapNotNull { it.activityInfo?.packageName }
                    .filter { it != "com.android.settings" } // Settings' fallback home is not a launcher.
            }
        }
        return pkg in launchers
    }

    /**
     * Intents for picking or capturing photos, videos and files. Whatever handles them on this
     * device (the system photo picker, Google Photos, a brand's gallery, file managers, cameras)
     * is a helper: an app opens it mid-task and gets the result back.
     */
    private val PICKER_INTENTS = listOf(
        Intent("android.provider.action.PICK_IMAGES"),
        Intent(Intent.ACTION_GET_CONTENT).setType("image/*"),
        Intent(Intent.ACTION_GET_CONTENT).setType("*/*"),
        Intent(Intent.ACTION_PICK).setType("image/*"),
        Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),
        Intent(MediaStore.ACTION_IMAGE_CAPTURE),
        Intent(MediaStore.ACTION_VIDEO_CAPTURE)
    )

     private var pickers: Set<String> = emptySet()
     private var pickersCheckedAt = 0L

    /** Apps that pick or capture media and files on this device (refreshed at most every 5 min). */
    private fun pickerApps(context: Context): Set<String> {
        val now = SystemClock.elapsedRealtime()
        if (pickersCheckedAt == 0L || now - pickersCheckedAt > 5 * 60_000L) {
            pickersCheckedAt = now
            runCatching {
                val pm = context.packageManager
                pickers = PICKER_INTENTS.flatMapTo(HashSet()) { intent ->
                    pm.queryIntentActivities(intent, PackageManager.MATCH_ALL).mapNotNull { it.activityInfo?.packageName }
                } - context.packageName
            }
        }
        return pickers
    }

    /** A helper app worth keeping the previous app's session for; [activityClass] spots custom tabs. */
    fun isHopHelper(context: Context, pkg: String, activityClass: String?): Boolean {
        if (pkg in HOP_HELPER_PACKAGES || pkg in pickerApps(context)) return true
        val cls = activityClass.orEmpty()
        return pkg in KNOWN_BROWSERS && (cls.contains("CustomTab", ignoreCase = true) || cls.contains("AuthTab", ignoreCase = true))
    }
}
