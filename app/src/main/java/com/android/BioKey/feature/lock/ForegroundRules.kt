package com.android.BioKey.feature.lock

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.MediaStore
import android.view.inputmethod.InputMethodManager
import androidx.annotation.VisibleForTesting
import com.android.BioKey.BioKeyApplication
import com.android.BioKey.core.data.PasswordManager
import com.android.BioKey.core.security.SecurityVault
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What the app in front means for sessions, shared by both detection engines (accessibility and
 * compatibility mode) so they behave identically.
 */
object ForegroundRules {

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
        PasswordManager.GMS,
        "com.google.android.gsf",
        PasswordManager.CREDENTIAL_MANAGER,
        // Google Password Manager's own screens, to copy a password across.
        PasswordManager.VIRTUAL_PACKAGE,
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
        "com.sec.android.app.camera",
        // Interruptions the user returns from: calls, alarms and timers.
        "com.google.android.dialer",
        "com.android.dialer",
        "com.android.incallui",
        "com.samsung.android.incallui",
        "com.android.server.telecom",
        "com.google.android.deskclock",
        "com.android.deskclock",
        "com.sec.android.app.clockpackage",
        // Pages apps send the user to and expect back from: Settings (notification or permission
        // switches), the assistant and Circle to Search, Google Wallet.
        "com.android.settings",
        "com.google.android.googlequicksearchbox",
        "com.google.android.apps.walletnfcrel",
        // Play's purchase, review and update sheets, which open over the app that asked for them
        // (in its task), and a Play listing the app sends the user to ("Rate us", "Update").
        "com.android.vending"
    )

    /** The system file picker: the one trip BioKey's own session survives ([com.android.BioKey.core.security.LockStateTracker.beginOwnPicker]). */
    val DOCUMENTS_UI = setOf("com.android.documentsui", "com.google.android.documentsui")

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

    /** Lists read from the system are refreshed here, never on an engine's event path. */
    private val refresher = Executors.newSingleThreadExecutor { r -> Thread(r, "biokey-system-lists").apply { isDaemon = true } }

    /**
     * A list read from the system. Read on first use, so it is never empty; after that it is read
     * again in the background once older than [maxAgeMs] or after apps changed, and callers keep
     * the last one meanwhile.
     */
    private class SystemList<T : Any>(private val maxAgeMs: Long, private val fallback: T, private val read: (Context) -> T) {
        @Volatile private var value: T? = null
        @Volatile private var readAt = 0L
        @Volatile private var readForPackages = 0
        private val reading = AtomicBoolean(false)

        fun get(context: Context, maxAgeMs: Long = this.maxAgeMs): T {
            val current = value ?: return readNow(context.applicationContext)
            if (SystemClock.elapsedRealtime() - readAt > maxAgeMs || readForPackages != BioKeyApplication.packageChangeVersion.value) {
                refresh(context)
            }
            return current
        }

        fun refresh(context: Context) {
            if (!reading.compareAndSet(false, true)) return
            val app = context.applicationContext
            runCatching {
                refresher.execute {
                    try {
                        readNow(app)
                    } finally {
                        reading.set(false)
                    }
                }
            }.onFailure { reading.set(false) }
        }

        private fun readNow(context: Context): T {
            readForPackages = BioKeyApplication.packageChangeVersion.value
            readAt = SystemClock.elapsedRealtime()
            val fresh = runCatching { read(context) }.getOrNull() ?: value ?: fallback
            value = fresh
            return fresh
        }

        fun reset() {
            value = null
            readAt = 0L
        }
    }

    /** Apps with a home screen: whether each is a system app, and which one is the home screen in use. */
    private class Homes(val apps: Map<String, Boolean>, val default: String?)

    private val HOME = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)

    /** How old the home-screen list may be when it decides whether a locked home-screen app is covered. */
    private const val LOCKED_HOME_MAX_AGE_MS = 2_000L

    /** Home-screen apps (read again at most every 30 s: the user can switch launchers without any broadcast). */
    private val homes = SystemList(30_000L, Homes(emptyMap(), null)) { context ->
        val pm = context.packageManager
        val apps = pm.queryIntentActivities(HOME, PackageManager.MATCH_ALL)
            .mapNotNull { it.activityInfo }
            .filter { it.packageName != "com.android.settings" } // Settings' fallback home is not a launcher.
            .associate { it.packageName to (it.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0) }
        val default = pm.resolveActivity(HOME, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
            ?.takeIf { it != "android" } // No default chosen yet: the chooser.
        Homes(apps, default)
    }

    /**
     * Home-screen apps. Any app can declare a home screen (other launchers, kids modes): one the
     * user locked counts as an app like any other, unless it is the home screen in use or the
     * system's own (which also hosts Recents), so locking one can never lock the user out of Home.
     */
    fun isLauncher(
        context: Context,
        pkg: String,
        isLocked: (String) -> Boolean = { SecurityVault.getInstance(context).isAppLocked(it) }
    ): Boolean {
        if (pkg in KNOWN_LAUNCHERS) return true
        val isSystem = homes.get(context).apps[pkg] ?: return false
        if (isSystem || !isLocked(pkg)) return true
        // A locked one stays uncovered only while it is the home screen in use, and switching home
        // screens sends no broadcast: for these, the list is read again within seconds (else a
        // home screen just chosen would be covered every time Home is pressed, for up to 30 s).
        return pkg == homes.get(context, LOCKED_HOME_MAX_AGE_MS).default
    }

    /**
     * Intents for picking or capturing photos, videos and files. Whatever handles them on this
     * device (the system photo picker, Google Photos, a brand's gallery, file managers, cameras)
     * is a helper: an app opens it mid-task and gets the result back. Each needs a `<queries>`
     * entry in the manifest, or Android hides its handlers.
     */
    internal val PICKER_INTENTS = listOf(
        Intent("android.provider.action.PICK_IMAGES"),
        Intent(Intent.ACTION_GET_CONTENT).setType("image/*"),
        Intent(Intent.ACTION_GET_CONTENT).setType("*/*"),
        Intent(Intent.ACTION_PICK).setType("image/*"),
        Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),
        Intent(MediaStore.ACTION_IMAGE_CAPTURE),
        Intent(MediaStore.ACTION_VIDEO_CAPTURE)
    )

    /** Apps that pick or capture media and files on this device (read again at most every 5 min). */
    private val pickers = SystemList(5 * 60_000L, emptySet<String>()) { context ->
        val pm = context.packageManager
        PICKER_INTENTS.flatMapTo(HashSet()) { intent ->
            pm.queryIntentActivities(intent, PackageManager.MATCH_ALL).mapNotNull { it.activityInfo?.packageName }
        } - context.packageName
    }

    /** Enabled keyboards (read again at most every 30 s, and at once after [onInputMethodsChanged]). */
    private val keyboards = SystemList(30_000L, emptySet<String>()) { context ->
        context.getSystemService(InputMethodManager::class.java).enabledInputMethodList.mapTo(HashSet()) { it.packageName }
    }

    /** Packages of the enabled keyboards: a keyboard's window never means the user switched apps. */
    fun inputMethods(context: Context): Set<String> = keyboards.get(context)

    /** The user enabled or disabled a keyboard. */
    fun onInputMethodsChanged(context: Context) = keyboards.refresh(context)

    /** Forgets every list read so far (tests register apps between cases). */
    @VisibleForTesting
    internal fun resetSystemLists() {
        homes.reset()
        pickers.reset()
        keyboards.reset()
    }

    /**
     * Anything that can come between leaving an unlocked app and coming straight back to it
     * without ending its session: a helper, a system screen it opened (permission page, share
     * sheet), or BioKey itself (the lock screen of a helper that is locked too).
     */
    fun isHopTarget(context: Context, pkg: String, activityClass: String?): Boolean =
        pkg == context.packageName || pkg in SYSTEM_OVERLAYS || isHopHelper(context, pkg, activityClass)

    /** A helper app worth keeping the previous app's session for; [activityClass] spots custom tabs. */
    fun isHopHelper(context: Context, pkg: String, activityClass: String?): Boolean {
        if (pkg in HOP_HELPER_PACKAGES || pkg in pickers.get(context)) return true
        val cls = activityClass.orEmpty()
        return pkg in KNOWN_BROWSERS && (cls.contains("CustomTab", ignoreCase = true) || cls.contains("AuthTab", ignoreCase = true))
    }
}
