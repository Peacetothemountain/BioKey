package com.android.BioKey.feature.lock

import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.UserManager
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

data class CachedAppInfo(
    val packageName: String,
    val label: String,
    val icon: Drawable
)

/**
 * Labels and icons of locked apps, loaded ahead of time off the main thread so the lock cover
 * can draw an app's identity in its very first frame.
 */
object AppInfoCache {
    private const val TAG = "AppInfoCache"

    private val cache = ConcurrentHashMap<String, CachedAppInfo>()
    private val loader = Executors.newSingleThreadExecutor { r -> Thread(r, "biokey-app-info").apply { isDaemon = true } }

    fun get(packageName: String): CachedAppInfo? = cache[packageName]

    fun invalidate(packageName: String) {
        cache.remove(packageName)
    }

    /** Loads any of [packages] not yet cached, in the background. */
    fun preWarm(context: Context, packages: Collection<String>) {
        val appContext = context.applicationContext
        val missing = packages.filterNot(cache::containsKey)
        if (missing.isEmpty()) return
        loader.execute {
            for (pkg in missing) if (!cache.containsKey(pkg)) loadAndCache(appContext, appContext.packageManager, pkg)
        }
    }

    /** Label only (no icon), for the first frame of a cover on a cache miss. */
    fun quickLabel(context: Context, pkg: String): String {
        cache[pkg]?.let { return it.label }
        if (isPasswordManager(pkg)) return "Google Password Manager"
        return runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault("This app")
    }

    /** Blocking: loads and caches [pkg]'s label and icon. */
    fun loadAndCache(context: Context, pm: PackageManager, pkg: String): CachedAppInfo? = try {
        val passwordManager = isPasswordManager(pkg)
        // Google Password Manager is a virtual app: show Credential Manager's (or Play services') icon.
        val realPkg = if (!passwordManager) pkg else {
            runCatching { pm.getApplicationInfo(BioKeyAccessibilityService.PKG_CREDENTIAL_MANAGER, 0) }
                .map { BioKeyAccessibilityService.PKG_CREDENTIAL_MANAGER }
                .getOrDefault("com.google.android.gms")
        }
        val (label, icon) = runCatching {
            val info = pm.getApplicationInfo(realPkg, 0)
            pm.getApplicationLabel(info).toString() to pm.getApplicationIcon(info)
        }.getOrNull() ?: fromOtherProfiles(context, realPkg) ?: return null
        CachedAppInfo(pkg, if (passwordManager) "Google Password Manager" else label, icon).also { cache[pkg] = it }
    } catch (e: Exception) {
        Log.w(TAG, "Couldn't load app info for $pkg: ${e.message}")
        null
    }

    /** Apps only installed in the work profile aren't visible to [PackageManager] in the main user. */
    private fun fromOtherProfiles(context: Context, pkg: String): Pair<String, Drawable>? = runCatching {
        val launcherApps = context.getSystemService(LauncherApps::class.java)
        val users = context.getSystemService(UserManager::class.java).userProfiles
        users.firstNotNullOfOrNull { user ->
            launcherApps.getActivityList(pkg, user).firstOrNull()?.let { it.label.toString() to it.getBadgedIcon(0) }
        }
    }.getOrNull()

    private fun isPasswordManager(pkg: String) =
        pkg == BioKeyAccessibilityService.VIRTUAL_PKG_GOOGLE_PASSWORDS || pkg == BioKeyAccessibilityService.PKG_CREDENTIAL_MANAGER
}
