package com.android.BioKey.feature.lock

import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import com.android.BioKey.core.data.PasswordManager
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.ui.AppIcons
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

data class CachedAppInfo(
    val packageName: String,
    val label: String,
    val icon: Drawable,
    /** The glow colour of the cover behind [icon] ([AppInfoCache.accentOf]); null for BioKey's own. */
    val accent: Int?
)

/**
 * Labels and icons of locked apps, loaded ahead of time off the main thread so the lock cover
 * can draw an app's identity in its very first frame.
 */
object AppInfoCache {
    private const val TAG = "AppInfoCache"

    /** Size of the app icon on the lock cover. */
    const val ICON_DP = 96

    private const val HUE_BUCKETS = 18

    private val cache = ConcurrentHashMap<String, CachedAppInfo>()
    private val loader = Executors.newSingleThreadExecutor { r -> Thread(r, "biokey-app-info").apply { isDaemon = true } }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val following = AtomicBoolean(false)

    fun get(packageName: String): CachedAppInfo? = cache[packageName]

    fun invalidate(packageName: String) {
        cache.remove(packageName)
    }

    /**
     * From now on keeps only [vault]'s locked apps: an app that stops being locked lets its icon go.
     * Once per process; later calls do nothing.
     */
    fun follow(vault: SecurityVault) {
        if (!following.compareAndSet(false, true)) return
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            vault.lockedAppsFlow.collect { locked -> cache.keys.retainAll(locked) }
        }
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
        if (pkg in PasswordManager.ALIASES) return PasswordManager.LABEL
        return runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault("This app")
    }

    /** Blocking: loads and caches [pkg]'s label and icon. */
    private fun loadAndCache(context: Context, pm: PackageManager, pkg: String): CachedAppInfo? = try {
        val passwordManager = pkg in PasswordManager.ALIASES
        // Google Password Manager is a virtual app: show Credential Manager's (or Play services') icon.
        val realPkg = if (passwordManager) PasswordManager.iconSource(pm) else pkg
        val (label, raw, user) = runCatching {
            val info = pm.getApplicationInfo(realPkg, 0)
            Triple(pm.getApplicationLabel(info).toString(), pm.getApplicationIcon(info), null as UserHandle?)
        }.getOrNull() ?: fromOtherProfiles(context, realPkg) ?: return null
        // Drawn once at cover size (see [AppIcons]); a work app gets its badge after.
        val px = (ICON_DP * context.resources.displayMetrics.density).roundToInt()
        val rendered: Drawable = AppIcons.render(raw, px).toDrawable(context.resources)
        val icon = if (user != null) pm.getUserBadgedIcon(rendered, user) else rendered
        val name = if (passwordManager) PasswordManager.LABEL else label
        CachedAppInfo(pkg, name, icon, accentOf(icon, context.resources)).also { cache[pkg] = it }
    } catch (e: Exception) {
        Log.w(TAG, "Couldn't load app info: ${e.message}")
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

    /**
     * The icon's own colour for the cover's glow: its most prominent vivid hue (white, black and
     * grey backgrounds don't count), so a red logo on a white tile glows red rather than a muddy
     * average. Null if the icon has no colour (monochrome), and BioKey's own colour is used.
     *
     * Worked out once, with the icon, off the main thread: covers get it ready-made.
     */
    internal fun accentOf(drawable: Drawable, resources: Resources): Int? = runCatching {
        val sample = drawable.constantState?.newDrawable(resources)?.mutate() ?: return null
        val n = 24
        val bmp = createBitmap(n, n)
        sample.setBounds(0, 0, n, n)
        sample.draw(Canvas(bmp))
        // Hue buckets weighted by how vivid each pixel is; then the average colour of the winner.
        val weight = FloatArray(HUE_BUCKETS)
        val sums = Array(HUE_BUCKETS) { FloatArray(3) }
        val hsv = FloatArray(3)
        for (x in 0 until n) for (y in 0 until n) {
            val c = bmp.getPixel(x, y)
            if (Color.alpha(c) < 160) continue
            Color.colorToHSV(c, hsv)
            if (hsv[1] < 0.25f || hsv[2] < 0.25f) continue
            val bucket = (hsv[0] / 360f * HUE_BUCKETS).toInt().coerceIn(0, HUE_BUCKETS - 1)
            val w = hsv[1] * hsv[2]
            weight[bucket] += w
            sums[bucket][0] += Color.red(c) * w
            sums[bucket][1] += Color.green(c) * w
            sums[bucket][2] += Color.blue(c) * w
        }
        bmp.recycle()
        val best = weight.indices.maxBy { weight[it] }
        if (weight[best] < n * n * 0.03f) return null
        val s = sums[best]
        Color.colorToHSV(Color.rgb((s[0] / weight[best]).toInt(), (s[1] / weight[best]).toInt(), (s[2] / weight[best]).toInt()), hsv)
        hsv[1] = hsv[1].coerceIn(0.45f, 0.85f)
        hsv[2] = hsv[2].coerceIn(0.65f, 0.95f)
        Color.HSVToColor(hsv)
    }.getOrNull()
}
