package com.android.BioKey.feature.main

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.createBitmap
import com.android.BioKey.core.data.AppCategory
import com.android.BioKey.core.data.AppClassifier
import com.android.BioKey.core.data.PasswordManager
import com.android.BioKey.ui.AppIcons
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Launcher apps the user can lock, kept across re-authentication until packages change. Names
 * only: icons live in [AppIconCache].
 */
internal object InstalledAppsCache {
    @Volatile var apps: List<AppItem> = emptyList()
    @Volatile var version: Int = -1
    /** The [version] auto-sort last went through, so it runs once per package change, not per unlock. */
    @Volatile var autoSortedVersion: Int = -1

    fun isCurrent(version: Int): Boolean = this.version == version && apps.isNotEmpty()
}

/**
 * App icons, drawn when a row shows them and kept within a few megabytes. BioKey's process lives
 * as long as its accessibility service, so nothing here may grow with the number of apps; the
 * icons are dropped a few minutes after BioKey's screens were last on view.
 */
internal object AppIconCache {
    private const val MAX_BYTES = 4 * 1024 * 1024
    private const val RELEASE_AFTER_MS = 5 * 60_000L

    private val icons = object : LruCache<String, ImageBitmap>(MAX_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }
    private val main = Handler(Looper.getMainLooper())
    private val release = Runnable { icons.evictAll() }

    fun key(app: AppItem, sizePx: Int) = "${app.packageName}/${app.user?.hashCode() ?: 0}/$sizePx"

    fun peek(key: String): ImageBitmap? = icons.get(key)

    fun put(key: String, icon: ImageBitmap) {
        icons.put(key, icon)
    }

    /** Blocking: call off the main thread. Null when the app has no icon BioKey can draw. */
    fun load(context: Context, app: AppItem, sizePx: Int): ImageBitmap? {
        val key = key(app, sizePx)
        peek(key)?.let { return it }
        // A third-party drawable can throw while drawing: that app gets the letter placeholder.
        val icon = runCatching { render(context, app, sizePx) }.getOrNull()?.asImageBitmap() ?: return null
        put(key, icon)
        return icon
    }

    /** BioKey's screens are on view again: keep the icons. */
    fun keep() = main.removeCallbacks(release)

    /** BioKey's screens went away: drop the icons unless they're back within a few minutes. */
    fun releaseLater() {
        main.removeCallbacks(release)
        main.postDelayed(release, RELEASE_AFTER_MS)
    }

    private fun render(context: Context, app: AppItem, sizePx: Int): Bitmap {
        val pm = context.packageManager
        if (app.packageName == PasswordManager.VIRTUAL_PACKAGE) {
            val source = if (isInstalled(pm, PasswordManager.CREDENTIAL_MANAGER)) PasswordManager.CREDENTIAL_MANAGER else PasswordManager.GMS
            return AppIcons.render(pm.getApplicationIcon(source), sizePx)
        }
        val user = app.user ?: Process.myUserHandle()
        val drawable = context.getSystemService(LauncherApps::class.java)
            ?.getActivityList(app.packageName, user)
            ?.firstOrNull()
            ?.getIcon(0)
            ?: pm.getApplicationIcon(app.packageName)
        // Render first, then badge, so the briefcase sits on top of the final icon.
        val rendered = AppIcons.render(drawable, sizePx)
        return if (app.user != null) badged(context, rendered, user, sizePx) else rendered
    }
}

/** Off the main thread. Stops early if the caller is cancelled (packages changed again). */
internal suspend fun loadInstalledApps(context: Context): List<AppItem> {
    val pm = context.packageManager
    val items = mutableListOf<AppItem>()

    context.getSystemService(LauncherApps::class.java)?.let { la ->
        val myUser = Process.myUserHandle()
        // Apps that have a copy in another profile, which compatibility mode can't see.
        val otherProfilePkgs = HashSet<String>()
        for (user in la.profiles.sortedBy { it != myUser }) {
            // A work profile being removed or paused can throw: list the others.
            val activities = runCatching { la.getActivityList(null, user) }.getOrNull() ?: continue
            for (activity in activities) {
                currentCoroutineContext().ensureActive()
                val pkg = activity.applicationInfo.packageName
                if (pkg == context.packageName || pkg == PasswordManager.CREDENTIAL_MANAGER) continue
                if (user != myUser) otherProfilePkgs += pkg
                val label = activity.label.toString()
                items += AppItem(
                    packageName = pkg,
                    label = label,
                    category = AppClassifier.categorize(pkg, label, activity.applicationInfo.category),
                    user = user.takeIf { it != myUser }
                )
            }
        }
        // Before de-duplicating, so the personal row of an app in both profiles carries it too.
        items.replaceAll { if (it.packageName in otherProfilePkgs) it.copy(inOtherProfile = true) else it }
    }

    if (items.isEmpty()) {
        val mainIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        for (info in pm.queryIntentActivities(mainIntent, 0)) {
            currentCoroutineContext().ensureActive()
            val pkg = info.activityInfo.packageName
            if (pkg == context.packageName || pkg == PasswordManager.CREDENTIAL_MANAGER) continue
            val label = info.loadLabel(pm).toString()
            items += AppItem(
                packageName = pkg,
                label = label,
                category = AppClassifier.categorize(pkg, label, info.activityInfo.applicationInfo?.category ?: -1)
            )
        }
    }

    passwordManagerItem(pm)?.let(items::add)
    return items.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
}

/**
 * Google Password Manager has no launcher icon of its own on most devices: offer it explicitly.
 * It ships inside Play services, so Play services being installed is enough.
 */
private fun passwordManagerItem(pm: PackageManager): AppItem? {
    val present = isInstalled(pm, PasswordManager.CREDENTIAL_MANAGER) || isInstalled(pm, PasswordManager.GMS)
    if (!present) return null
    return AppItem(
        packageName = PasswordManager.VIRTUAL_PACKAGE,
        label = "Google Password Manager",
        category = AppCategory.AUTH
    )
}

private fun isInstalled(pm: PackageManager, pkg: String) = runCatching { pm.getPackageInfo(pkg, 0) }.isSuccess

private fun badged(context: Context, icon: Bitmap, user: UserHandle, sizePx: Int): Bitmap {
    val drawable = context.packageManager.getUserBadgedIcon(BitmapDrawable(context.resources, icon), user)
    if (drawable is BitmapDrawable && drawable.bitmap != null) return drawable.bitmap
    return createBitmap(sizePx, sizePx).also { out ->
        drawable.setBounds(0, 0, sizePx, sizePx)
        drawable.draw(Canvas(out))
    }
}
