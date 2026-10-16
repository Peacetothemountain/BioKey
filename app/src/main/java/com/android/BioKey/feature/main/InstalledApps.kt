package com.android.BioKey.feature.main

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.os.Process
import android.os.UserHandle
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.createBitmap
import com.android.BioKey.core.data.AppCategory
import com.android.BioKey.core.data.AppClassifier
import com.android.BioKey.feature.lock.BioKeyAccessibilityService
import com.android.BioKey.ui.AppIcons

/** Launcher apps the user can lock, kept across re-authentication until packages change. */
internal object InstalledAppsCache {
    @Volatile var apps: List<AppItem> = emptyList()
    @Volatile var version: Int = -1
    @Volatile var iconPx: Int = -1

    fun get(version: Int, iconPx: Int): List<AppItem>? =
        apps.takeIf { this.version == version && this.iconPx == iconPx && it.isNotEmpty() }
}

/** Google Password Manager is reachable through two packages; the UI shows it as one app. */
internal val PASSWORD_MANAGER_PACKAGES = setOf(
    BioKeyAccessibilityService.VIRTUAL_PKG_GOOGLE_PASSWORDS,
    BioKeyAccessibilityService.PKG_CREDENTIAL_MANAGER
)

internal fun packageAliases(pkg: String): Set<String> =
    if (pkg in PASSWORD_MANAGER_PACKAGES) PASSWORD_MANAGER_PACKAGES else setOf(pkg)

/** Blocking: call off the main thread. */
internal fun loadInstalledApps(context: Context, iconPx: Int): List<AppItem> {
    val pm = context.packageManager
    val items = mutableListOf<AppItem>()

    context.getSystemService(LauncherApps::class.java)?.let { la ->
        val myUser = Process.myUserHandle()
        for (user in la.profiles.sortedBy { it != myUser }) {
            val isWork = user != myUser
            for (activity in la.getActivityList(null, user)) {
                val pkg = activity.applicationInfo.packageName
                if (pkg == context.packageName || pkg == BioKeyAccessibilityService.PKG_CREDENTIAL_MANAGER) continue
                val label = activity.label.toString()
                // Render first, then badge, so the briefcase sits on top of the final icon.
                val rendered = AppIcons.render(activity.getIcon(0), iconPx)
                val icon = if (isWork) badged(context, rendered, user, iconPx) else rendered
                items += AppItem(
                    packageName = pkg,
                    label = label,
                    iconBitmap = icon.asImageBitmap(),
                    category = AppClassifier.categorize(pkg, label, activity.applicationInfo.category),
                    isWorkProfile = isWork
                )
            }
        }
    }

    if (items.isEmpty()) {
        val mainIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        for (info in pm.queryIntentActivities(mainIntent, 0)) {
            val pkg = info.activityInfo.packageName
            if (pkg == context.packageName || pkg == BioKeyAccessibilityService.PKG_CREDENTIAL_MANAGER) continue
            val label = info.loadLabel(pm).toString()
            items += AppItem(
                packageName = pkg,
                label = label,
                iconBitmap = AppIcons.render(info.loadIcon(pm), iconPx).asImageBitmap(),
                category = AppClassifier.categorize(pkg, label, info.activityInfo.applicationInfo?.category ?: -1)
            )
        }
    }

    passwordManagerItem(pm, iconPx)?.let(items::add)
    return items.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
}

/** Google Password Manager has no launcher icon of its own on most devices: offer it explicitly. */
private fun passwordManagerItem(pm: PackageManager, iconPx: Int): AppItem? {
    val hasCredMgr = runCatching { pm.getPackageInfo(BioKeyAccessibilityService.PKG_CREDENTIAL_MANAGER, 0) }.isSuccess
    val hasGmsPasswords = hasCredMgr || runCatching {
        pm.getPackageInfo("com.google.android.gms", PackageManager.GET_ACTIVITIES).activities?.any {
            it.name.contains("PasswordManager", ignoreCase = true) || it.name.contains("ManagePasswords", ignoreCase = true)
        } == true
    }.getOrDefault(false)
    if (!hasGmsPasswords) return null
    val icon = runCatching {
        val source = if (hasCredMgr) BioKeyAccessibilityService.PKG_CREDENTIAL_MANAGER else "com.google.android.gms"
        AppIcons.render(pm.getApplicationIcon(source), iconPx).asImageBitmap()
    }.getOrNull()
    return AppItem(
        packageName = BioKeyAccessibilityService.VIRTUAL_PKG_GOOGLE_PASSWORDS,
        label = "Google Password Manager",
        iconBitmap = icon,
        category = AppCategory.AUTH
    )
}

private fun badged(context: Context, icon: Bitmap, user: UserHandle, sizePx: Int): Bitmap {
    val drawable = context.packageManager.getUserBadgedIcon(BitmapDrawable(context.resources, icon), user)
    if (drawable is BitmapDrawable && drawable.bitmap != null) return drawable.bitmap
    return createBitmap(sizePx, sizePx).also { out ->
        drawable.setBounds(0, 0, sizePx, sizePx)
        drawable.draw(Canvas(out))
    }
}
