package com.android.BioKey.feature.lock

import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.UserManager
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

data class CachedAppInfo(
    val packageName: String,
    val label: String,
    val iconBitmap: ImageBitmap,
    val iconDrawable: Drawable
)

/**
 * Shared in-memory cache for app labels and icons.
 * Pre-warmed on background IO so both [InstantShieldOverlay] and [LockScreenActivity]
 * have immediate, synchronous (< 1ms) access to app assets from frame 1,
 * eliminating all visual pop-in, label flashing, and layout jitter.
 */
object AppInfoCache {
    private const val TAG = "AppInfoCache"

    private val cache = ConcurrentHashMap<String, CachedAppInfo>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun get(packageName: String): CachedAppInfo? = cache[packageName]

    fun put(packageName: String, info: CachedAppInfo) {
        cache[packageName] = info
    }

    fun invalidate(packageName: String) {
        cache.remove(packageName)
    }

    /**
     * Proactively pre-warms all locked apps into memory on background thread
     * so app launch interception never experiences a cold cache miss.
     */
    fun preWarm(context: Context, packages: Collection<String>) {
        val appContext = context.applicationContext
        scope.launch {
            val pm = appContext.packageManager
            for (pkg in packages) {
                if (cache.containsKey(pkg)) continue
                loadAndCache(appContext, pm, pkg)
            }
        }
    }

    /** Label only (no icon decode), for the first frame of a cover on a cache miss. */
    fun quickLabel(context: Context, pkg: String): String {
        cache[pkg]?.let { return it.label }
        if (pkg == BioKeyAccessibilityService.VIRTUAL_PKG_GOOGLE_PASSWORDS || pkg == BioKeyAccessibilityService.PKG_CREDENTIAL_MANAGER) {
            return "Google Password Manager"
        }
        return runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault("This app")
    }

    fun loadAndCache(context: Context, pm: PackageManager, pkg: String): CachedAppInfo? {
        return try {
            val isPasswordManager = pkg == BioKeyAccessibilityService.VIRTUAL_PKG_GOOGLE_PASSWORDS ||
                    pkg == BioKeyAccessibilityService.PKG_CREDENTIAL_MANAGER
            val realPkg = if (isPasswordManager) {
                runCatching { pm.getApplicationInfo(BioKeyAccessibilityService.PKG_CREDENTIAL_MANAGER, 0) }
                    .map { BioKeyAccessibilityService.PKG_CREDENTIAL_MANAGER }
                    .getOrDefault("com.google.android.gms")
            } else {
                pkg
            }

            val resolved: Pair<String, Drawable>? = runCatching {
                val appInfo = pm.getApplicationInfo(realPkg, 0)
                val label = if (isPasswordManager) "Google Password Manager" else pm.getApplicationLabel(appInfo).toString()
                val drawable = pm.getApplicationIcon(appInfo)
                label to drawable
            }.getOrElse {
                // Work Profile fallback via LauncherApps
                resolveFromLauncherApps(context, realPkg, isPasswordManager)
            }

            if (resolved == null) return null
            val (label, drawable) = resolved
            val bitmap = drawableToBitmap(drawable)

            val cached = CachedAppInfo(
                packageName = pkg,
                label = label,
                iconBitmap = bitmap.asImageBitmap(),
                iconDrawable = drawable
            )
            cache[pkg] = cached
            cached
        } catch (e: Exception) {
            Log.w(TAG, "Failed caching app info for $pkg: ${e.message}")
            null
        }
    }

    private fun resolveFromLauncherApps(
        context: Context,
        pkg: String,
        isPasswordManager: Boolean
    ): Pair<String, Drawable>? {
        return runCatching {
            val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return null
            val userManager = context.getSystemService(UserManager::class.java) ?: return null
            for (profile in userManager.userProfiles) {
                val activities = launcherApps.getActivityList(pkg, profile)
                val first = activities.firstOrNull()
                if (first != null) {
                    val label = if (isPasswordManager) "Google Password Manager" else first.label.toString()
                    val icon = first.getBadgedIcon(0)
                    return label to icon
                }
            }
            null
        }.getOrNull()
    }

    fun drawableToBitmap(drawable: Drawable): Bitmap {
        val targetMax = 192
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            val bmp = drawable.bitmap
            if (bmp.width <= targetMax && bmp.height <= targetMax) return bmp
            return Bitmap.createScaledBitmap(bmp, targetMax, targetMax, true)
        }
        val width = drawable.intrinsicWidth.takeIf { it in 1..targetMax } ?: targetMax
        val height = drawable.intrinsicHeight.takeIf { it in 1..targetMax } ?: targetMax
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
        }
    }
}
