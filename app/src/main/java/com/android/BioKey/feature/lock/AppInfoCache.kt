package com.android.BioKey.feature.lock

import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.content.res.TypedArray
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.LocaleList
import android.os.Looper
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import android.util.TypedValue
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import com.android.BioKey.R
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
    val accent: Int?,
    /**
     * Whether the app's own launch screen is dark ([AppInfoCache.launchBackgroundIsDark]), which
     * the cover follows; null when that can't be told, and the cover follows the phone's mode.
     */
    val dark: Boolean? = null
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
    /** Names looked up by [quickLabel], which has no icon to cache them with. */
    private val labels = ConcurrentHashMap<String, String>()
    private val loader = Executors.newSingleThreadExecutor { r -> Thread(r, "biokey-app-info").apply { isDaemon = true } }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val following = AtomicBoolean(false)

    fun get(packageName: String): CachedAppInfo? = cache[packageName]

    /** [packageName]'s name if it is known already, without asking the package manager. */
    fun cachedLabel(packageName: String): String? =
        cache[packageName]?.label ?: labels[packageName] ?: PasswordManager.LABEL.takeIf { packageName in PasswordManager.ALIASES }

    fun invalidate(packageName: String) {
        cache.remove(packageName)
        labels.remove(packageName)
    }

    /**
     * Forgets everything and loads [packages] again: names are in the old language after a
     * language change, and icons at the old size after a display-size change.
     */
    fun reload(context: Context, packages: Collection<String>) {
        cache.clear()
        labels.clear()
        preWarm(context, packages)
    }

    /** The language, display density and light or dark mode names, icons and schemes were last loaded for ([onConfiguration]). */
    @Volatile private var loadedFor: Triple<LocaleList, Int, Int>? = null

    /**
     * The engine's configuration is now [config]. True if its language, display density or light
     * or dark mode changed since the last one seen (an app's launch screen can follow the mode), so
     * what is cached is out of date ([reload]); false the first time.
     */
    fun onConfiguration(config: Configuration): Boolean {
        val now = Triple(config.locales, config.densityDpi, config.uiMode and Configuration.UI_MODE_NIGHT_MASK)
        val before = loadedFor
        loadedFor = now
        return before != null && before != now
    }

    /**
     * From now on keeps only [vault]'s locked apps: an app that stops being locked lets its icon go.
     * Once per process; later calls do nothing.
     */
    fun follow(vault: SecurityVault) {
        if (!following.compareAndSet(false, true)) return
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            vault.lockedAppsFlow.collect { locked ->
                cache.keys.retainAll(locked)
                labels.keys.retainAll(locked)
            }
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

    /**
     * Label only (no icon), where a name is needed at once (the prompt's title) and the icon may
     * not be loaded yet. Blocking the first time for each app (it asks the package manager), so
     * never on the way to a cover's first frame: see [cachedLabel].
     */
    fun quickLabel(context: Context, pkg: String): String {
        cachedLabel(pkg)?.let { return it }
        return runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrNull()?.also { labels[pkg] = it } ?: context.getString(R.string.app_label_unknown)
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
        // Not for an app in another profile only, whose resources this user can't open.
        val dark = if (user == null) launchBackgroundIsDark(pm, PasswordManager.launchPackage(pkg)) else null
        CachedAppInfo(pkg, name, icon, accentOf(icon, context.resources), dark).also { cache[pkg] = it }
    } catch (e: Exception) {
        Log.w(TAG, "Couldn't load app info: ${e.message}")
        null
    }

    /**
     * Whether [pkg]'s own launch screen is dark: the background its launch activity's theme gives
     * its window (`windowBackground`, else `colorBackground`), read from the app's own resources in
     * the phone's current light or dark mode, dark below half luminance. The cover then takes the
     * same scheme, so going from it to the app's own first screen doesn't flash light to dark.
     * Null when it can't be told (no launch screen, a background that's a picture rather than a
     * colour): the cover follows the phone's mode, as it always has. Blocking: worked out with the
     * icon, off the main thread, never on the way to a cover.
     */
    internal fun launchBackgroundIsDark(pm: PackageManager, pkg: String): Boolean? = runCatching {
        val launch = pm.getLaunchIntentForPackage(pkg) ?: return null
        val activity = pm.resolveActivity(launch, 0)?.activityInfo ?: return null
        val theme = activity.themeResource.takeIf { it != 0 } ?: return null
        val res = pm.getResourcesForApplication(activity.applicationInfo)
        val styled = res.newTheme().apply { applyStyle(theme, true) }
            .obtainStyledAttributes(intArrayOf(android.R.attr.windowBackground, android.R.attr.colorBackground))
        val background = try {
            colorOf(styled, 0, res) ?: colorOf(styled, 1, res)
        } finally {
            styled.recycle()
        }
        background?.takeIf { Color.alpha(it) > 0 }?.let { Color.luminance(it) < 0.5f }
    }.getOrNull()

    /** The colour at [index] of [styled]: a colour, or a drawable that is one plain colour; else null. */
    private fun colorOf(styled: TypedArray, index: Int, res: Resources): Int? {
        val value = styled.peekValue(index) ?: return null
        if (value.type >= TypedValue.TYPE_FIRST_COLOR_INT && value.type <= TypedValue.TYPE_LAST_COLOR_INT) return value.data
        if (index == 1) return runCatching { styled.getColor(index, 0) }.getOrNull()
        val drawable = runCatching { styled.getDrawable(index) }.getOrNull() ?: return null
        return (drawable as? ColorDrawable)?.color
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
