package com.android.BioKey.feature.main

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import androidx.annotation.VisibleForTesting
import androidx.core.content.edit
import com.android.BioKey.R
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.core.security.UnlockMethods
import com.android.BioKey.feature.lock.ForegroundRules
import com.android.BioKey.feature.notification.BioKeyNotificationShieldService

/**
 * What locking an app does and doesn't do, said honestly the first time the owner locks it by hand
 * (and again from its options): a lock covers the app, not its data. Like Android's own App lock
 * sheet, it says what still shows (notifications without the shield, its home-screen widgets),
 * that assistants and apps given access keep it, and, for a gallery, a files app or the messaging
 * app, which other apps can read the same photos, files or messages.
 *
 * Shown once per app, only when the owner switches a lock on in BioKey's list or an app's options
 * (not for apps auto-sort files, a new-app offer locks, apps added to a profile together, or the
 * tile: those would each bring it up for many apps at once), and never again after "Don't show
 * again". What's kept for that is in home's own small memory ([homePrefs]): the apps already
 * explained, and the choice; nothing about the other apps is kept.
 */
internal object LockExplainer {

    /** The kind of data an app is the home of, whose other readers are listed. */
    enum class DataKind(
        /** The permissions that let another app read it: Android's own App lock lists the same. */
        val permissions: List<String>
    ) {
        PHOTOS(
            listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
                Manifest.permission.MANAGE_EXTERNAL_STORAGE
            )
        ),
        FILES(listOf(Manifest.permission.MANAGE_EXTERNAL_STORAGE)),
        MESSAGES(
            listOf(
                Manifest.permission.READ_SMS,
                Manifest.permission.RECEIVE_SMS,
                Manifest.permission.SEND_SMS,
                Manifest.permission.RECEIVE_MMS,
                Manifest.permission.RECEIVE_WAP_PUSH
            )
        )
    }

    /** Whether "Hide notification content" hides this app's notifications. */
    enum class Notifications { HIDDEN, SHIELD_OFF, NO_ACCESS }

    /** Everything the sheet says about one app. */
    data class Explanation(
        val packageName: String,
        /** Android's words for how it opens now, or BioKey's own. */
        val howItOpens: String,
        val notifications: Notifications,
        val hasWidgets: Boolean,
        val dataKind: DataKind?,
        /** Other apps on the list that hold [dataKind]'s permissions, A–Z. */
        val otherApps: List<AppItem>
    )

    private const val KEY_EXPLAINED = "lock_explained"
    private const val KEY_OFF = "lock_explain_off"
    /** Apps remembered as explained, at most: the oldest are forgotten (and may be explained once more). */
    private const val MAX_EXPLAINED = 300

    /** Whether to explain [pkg] as it's locked by hand: not explained before, and not switched off. */
    fun shouldExplain(context: Context, pkg: String): Boolean {
        val p = homePrefs(context)
        return !p.getBoolean(KEY_OFF, false) && pkg !in p.getStringSet(KEY_EXPLAINED, emptySet()).orEmpty()
    }

    /** [pkg] has been explained: not again on its own. */
    fun markExplained(context: Context, pkg: String) {
        val p = homePrefs(context)
        val explained = p.getStringSet(KEY_EXPLAINED, emptySet()).orEmpty()
        if (pkg in explained) return
        p.edit { putStringSet(KEY_EXPLAINED, (explained.toList().takeLast(MAX_EXPLAINED - 1) + pkg).toSet()) }
    }

    /** "Don't show again": it no longer comes up by itself; an app's options still open it. */
    fun switchOff(context: Context) {
        homePrefs(context).edit { putBoolean(KEY_OFF, true) }
    }

    /** Apps holding any of [permissions] (that BioKey can see: launchable ones are). */
    @VisibleForTesting
    internal var holdersOf: (Context, List<String>) -> Set<String> = { context, permissions ->
        runCatching {
            context.packageManager.getPackagesHoldingPermissions(permissions.toTypedArray(), 0).mapTo(HashSet()) { it.packageName }
        }.getOrDefault(emptySet())
    }

    /** Whether [app] has home-screen widgets. */
    @VisibleForTesting
    internal var hasWidgets: (Context, AppItem) -> Boolean = { context, app ->
        runCatching {
            AppWidgetManager.getInstance(context)?.getInstalledProvidersForPackage(app.packageName, app.user)?.isNotEmpty() == true
        }.getOrDefault(false)
    }

    /** The phone's default SMS app, if Android says. */
    @VisibleForTesting
    internal var defaultSmsApp: (Context) -> String? = { context -> runCatching { Telephony.Sms.getDefaultSmsPackage(context) }.getOrNull() }

    /**
     * What locking [app] does, worked out now: blocking (the package manager, Android's
     * services), so off the main thread. [installedApps]: BioKey's list, where the other apps
     * that can read its data are looked for (they're launchable, so visible to BioKey).
     */
    fun explain(context: Context, app: AppItem, installedApps: List<AppItem>): Explanation {
        val vault = SecurityVault.getInstance(context)
        val notifications = when {
            !vault.isNotificationShieldEnabled() -> Notifications.SHIELD_OFF
            !BioKeyNotificationShieldService.isNotificationAccessGranted(context) -> Notifications.NO_ACCESS
            else -> Notifications.HIDDEN
        }
        val kind = dataKindOf(context, app.packageName)
        val others = if (kind == null) emptyList() else {
            val holders = holdersOf(context, kind.permissions)
            installedApps.filter { it.packageName in holders && it.packageName != app.packageName && it.packageName != context.packageName }
                .distinctBy { it.packageName }
                .sortedBy { it.label.lowercase() }
        }
        return Explanation(
            packageName = app.packageName,
            howItOpens = UnlockMethods.howLockedAppsOpen(context, context.getString(R.string.explain_opens_fallback)),
            notifications = notifications,
            hasWidgets = hasWidgets(context, app),
            dataKind = kind,
            otherApps = others
        )
    }

    /**
     * Whose data [pkg] is the home of, as Android's own App lock decides it: the phone's SMS app
     * (or one that says it's a messaging app), a files app or a gallery, by what it says it is;
     * failing that, by what it picks for other apps (photos: what BioKey already counts as a photo
     * picker, [ForegroundRules.PICKER_INTENTS]; documents). Null for any other app.
     */
    @VisibleForTesting
    internal fun dataKindOf(context: Context, pkg: String): DataKind? {
        val pm = context.packageManager
        fun answers(intent: Intent) = runCatching { pm.queryIntentActivities(Intent(intent).setPackage(pkg), 0).isNotEmpty() }.getOrDefault(false)
        fun hasCategory(category: String) = answers(Intent(Intent.ACTION_MAIN).addCategory(category))
        val pickers = ForegroundRules.PICKER_INTENTS
        // What the app says it is wins: a files app picks images for other apps too, and isn't a
        // gallery for it. Only an app that says nothing is judged by what it picks.
        return when {
            defaultSmsApp(context) == pkg || hasCategory(Intent.CATEGORY_APP_MESSAGING) -> DataKind.MESSAGES
            hasCategory(Intent.CATEGORY_APP_FILES) -> DataKind.FILES
            hasCategory(Intent.CATEGORY_APP_GALLERY) -> DataKind.PHOTOS
            pickers.any { (it.action == ACTION_PICK_IMAGES || it.type == "image/*") && answers(it) } -> DataKind.PHOTOS
            pickers.any { it.action == Intent.ACTION_OPEN_DOCUMENT && answers(it) } -> DataKind.FILES
            else -> null
        }
    }

    private const val ACTION_PICK_IMAGES = "android.provider.action.PICK_IMAGES"

    /** How many of the other apps show before "View all". */
    const val SHOWN_APPS = 3
}
