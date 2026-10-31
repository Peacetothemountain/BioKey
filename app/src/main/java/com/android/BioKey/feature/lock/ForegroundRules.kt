package com.android.BioKey.feature.lock

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.view.inputmethod.InputMethodManager
import androidx.annotation.VisibleForTesting
import com.android.BioKey.BioKeyApplication
import com.android.BioKey.core.data.PasswordManager
import com.android.BioKey.core.diagnostics.EngineTrace
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.feature.main.MainActivity
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
        "net.oneplus.launcher",
        "com.huawei.android.launcher",
        "com.bbk.launcher2", // vivo (Funtouch OS, OriginOS)
        "com.hihonor.android.launcher",
        "com.mi.android.globallauncher", // POCO
        "com.motorola.launcher3",
        "com.nothing.launcher"
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

    /** The activity every screensaver runs in, whichever app's it is. */
    const val DREAM_ACTIVITY = "android.service.dreams.DreamActivity"

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
        /** Set by a replay ([pinSystemLists]): this, never read from the system. */
        @Volatile private var pinned: T? = null

        fun get(context: Context, maxAgeMs: Long = this.maxAgeMs): T {
            pinned?.let { return it }
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
            pinned = null
        }

        fun pin(list: T) {
            pinned = list
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

    /**
     * What may open over the phone's lock screen without its app lock, as Android's own App lock
     * lets it (AppLockOverlayController's permitted secure actions): the lock-screen camera
     * (still, capture and motion-photo, and reviewing what it just took) and the emergency dialer
     * and calls. Each needs a `<queries>` entry in the manifest, or Android hides its handlers.
     */
    internal val KEYGUARD_INTENTS = listOf(
        Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE),
        Intent(MediaStore.ACTION_IMAGE_CAPTURE_SECURE),
        Intent("android.media.action.MOTION_PHOTO_CAPTURE_SECURE"),
        Intent("android.provider.action.REVIEW_SECURE").setType("image/*"),
        Intent("android.intent.action.DIAL_EMERGENCY"),
        Intent("android.intent.action.DIAL_EMERGENCY", Uri.parse("tel:")),
        Intent("android.intent.action.CALL_EMERGENCY", Uri.parse("tel:"))
    )

    /** The apps handling [KEYGUARD_INTENTS] on this phone, each with those of its activities (read again at most every 5 min). */
    private val keyguardApps = SystemList(5 * 60_000L, emptyMap<String, Set<String>>()) { context ->
        val pm = context.packageManager
        val found = HashMap<String, MutableSet<String>>()
        for (intent in KEYGUARD_INTENTS) {
            for (info in pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)) {
                val activity = info.activityInfo ?: continue
                found.getOrPut(activity.packageName) { HashSet() } += activity.name
            }
        }
        found - context.packageName
    }

    /** Whether [pkg] is the phone's lock-screen camera or emergency dialer ([KEYGUARD_INTENTS]). */
    fun isKeyguardApp(context: Context, pkg: String): Boolean = pkg in keyguardApps.get(context)

    /** [pkg]'s activities that handle [KEYGUARD_INTENTS] (none for any other app). */
    fun keyguardActivities(context: Context, pkg: String): Set<String> = keyguardApps.get(context)[pkg].orEmpty()

    /** Enabled keyboards (read again at most every 30 s, and at once after [onInputMethodsChanged]). */
    private val keyboards = SystemList(30_000L, emptySet<String>()) { context ->
        context.getSystemService(InputMethodManager::class.java).enabledInputMethodList.mapTo(HashSet()) { it.packageName }
    }

    /** Packages of the enabled keyboards: a keyboard's window never means the user switched apps. */
    fun inputMethods(context: Context): Set<String> = keyboards.get(context)

    /** The user enabled or disabled a keyboard. */
    fun onInputMethodsChanged(context: Context) = keyboards.refresh(context)

    /**
     * Reads every list in the background now, as an engine starts: read on first use instead, they
     * would be read on the main thread at the first app switch (about ten package-manager queries
     * on the way to a cover).
     */
    fun warmUp(context: Context) {
        homes.refresh(context)
        pickers.refresh(context)
        keyboards.refresh(context)
        keyguardApps.refresh(context)
    }

    /** Forgets every list read so far (tests register apps between cases). */
    @VisibleForTesting
    internal fun resetSystemLists() {
        homes.reset()
        pickers.reset()
        keyboards.reset()
        keyguardApps.reset()
    }

    /**
     * The lists as a trace records them (EngineTrace's `env` records): home screens (whether each
     * is a system app, and the one in use), helper apps, keyboards, and what opens over the lock
     * screen (each app with an activity), as last read.
     */
    internal fun traceEnvironment(context: Context): List<String> = buildList {
        val h = homes.get(context)
        for ((pkg, system) in h.apps) add(EngineTrace.line("env", "home", pkg, if (system) 1 else 0, if (pkg == h.default) 1 else 0))
        for (pkg in pickers.get(context)) add(EngineTrace.line("env", "picker", pkg))
        for (pkg in keyboards.get(context)) add(EngineTrace.line("env", "keyboard", pkg))
        for ((pkg, activities) in keyguardApps.get(context)) for (cls in activities) add(EngineTrace.line("env", "keyguardApp", pkg, cls))
    }

    /** A replay's lists, from its trace's `env` records, in place of the system's until [resetSystemLists]. */
    @VisibleForTesting
    internal fun pinSystemLists(
        homeApps: Map<String, Boolean>,
        defaultHome: String?,
        helpers: Set<String>,
        inputMethods: Set<String>,
        overKeyguard: Map<String, Set<String>> = emptyMap()
    ) {
        homes.pin(Homes(homeApps, defaultHome))
        pickers.pin(helpers)
        keyboards.pin(inputMethods)
        keyguardApps.pin(overKeyguard)
    }

    /**
     * Anything that can come between leaving an unlocked app and coming straight back to it
     * without ending its session: a helper, a system screen it opened (permission page, share
     * sheet), or BioKey's lock screen (for a helper that is locked too). Not BioKey's own screens:
     * going there is leaving the app.
     */
    fun isHopTarget(context: Context, pkg: String, activityClass: String?): Boolean =
        (pkg == context.packageName && activityClass != MainActivity::class.java.name) || pkg in SYSTEM_OVERLAYS ||
            isHopHelper(context, pkg, activityClass)

    /** A helper app worth keeping the previous app's session for; [activityClass] spots custom tabs. */
    fun isHopHelper(context: Context, pkg: String, activityClass: String?): Boolean {
        if (pkg in HOP_HELPER_PACKAGES || pkg in pickers.get(context)) return true
        val cls = activityClass.orEmpty()
        return pkg in KNOWN_BROWSERS && (cls.contains("CustomTab", ignoreCase = true) || cls.contains("AuthTab", ignoreCase = true))
    }
}
