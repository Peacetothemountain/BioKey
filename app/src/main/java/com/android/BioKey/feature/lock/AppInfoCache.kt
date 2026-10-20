package com.android.BioKey.feature.lock

import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import com.android.BioKey.core.data.PasswordManager
import com.android.BioKey.ui.AppIcons
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.roundToInt

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

    /** Size of the app icon on the lock cover. */
    const val ICON_DP = 96

    private val cache = ConcurrentHashMap<String, CachedAppInfo>()
    private val loader = Executors.newSingleThreadExecutor { r -> Thread(r, "biokey-app-info").apply { isDaemon = true } }
    private val mainHandler = Handler(Looper.getMainLooper())

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

    /** Hands [pkg]'s info to [onLoaded] on the main thread, loading it in the background if needed. */
    fun load(context: Context, pkg: String, onLoaded: (CachedAppInfo) -> Unit) {
        val appContext = context.applicationContext
        loader.execute {
            val info = cache[pkg] ?: loadAndCache(appContext, appContext.packageManager, pkg) ?: return@execute
            mainHandler.post { onLoaded(info) }
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
            runCatching { pm.getApplicationInfo(PasswordManager.CREDENTIAL_MANAGER, 0) }
                .map { PasswordManager.CREDENTIAL_MANAGER }
                .getOrDefault(PasswordManager.GMS)
        }
        val (label, raw, user) = runCatching {
            val info = pm.getApplicationInfo(realPkg, 0)
            Triple(pm.getApplicationLabel(info).toString(), pm.getApplicationIcon(info), null as UserHandle?)
        }.getOrNull() ?: fromOtherProfiles(context, realPkg) ?: return null
        // Drawn once at cover size (see [AppIcons]); a work app gets its badge after.
        val px = (ICON_DP * context.resources.displayMetrics.density).roundToInt()
        val rendered: Drawable = BitmapDrawable(context.resources, AppIcons.render(raw, px))
        val icon = if (user != null) pm.getUserBadgedIcon(rendered, user) else rendered
        CachedAppInfo(pkg, if (passwordManager) "Google Password Manager" else label, icon).also { cache[pkg] = it }
    } catch (e: Exception) {
        Log.w(TAG, "Couldn't load app info for $pkg: ${e.message}")
        null
    }

    /** Apps only installed in the work profile aren't visible to [PackageManager] in the main user. */
    private fun fromOtherProfiles(context: Context, pkg: String): Triple<String, Drawable, UserHandle?>? = runCatching {
        val launcherApps = context.getSystemService(LauncherApps::class.java)
        val users = context.getSystemService(UserManager::class.java).userProfiles
        users.firstNotNullOfOrNull { user ->
            launcherApps.getActivityList(pkg, user).firstOrNull()?.let { Triple(it.label.toString(), it.getIcon(0), user) }
        }
    }.getOrNull()

    private fun isPasswordManager(pkg: String) =
        pkg == PasswordManager.VIRTUAL_PACKAGE || pkg == PasswordManager.CREDENTIAL_MANAGER
}
