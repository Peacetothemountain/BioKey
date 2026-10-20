package com.android.BioKey.feature.lock

import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.UserManager
import com.android.BioKey.BioKeyApplication
import com.android.BioKey.core.data.PasswordManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The names apps go by on this phone, for telling which app a tap on the home screen, a Recents
 * card or an unreadable window is about: the labels the launcher shows (every profile's), and for
 * locked apps also their application label and the titles of their screens.
 *
 * Read off the main thread, and read again when apps are installed, updated or removed, the
 * language changes or the locked apps change. Until the first read is done, a locked app's name
 * comes straight from [AppInfoCache] or the package manager.
 */
internal class AppLabels(context: Context) {

    internal companion object {
        private const val GOOGLE_PASSWORD_MANAGER = "google password manager"

        /** The name part of a label, lower-cased: "Messages, 3 notifications" or a multi-line card -> "messages". */
        fun key(raw: CharSequence?): String? =
            raw?.toString()?.lineSequence()?.firstOrNull()?.substringBefore(',')?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    }

    private data class Stamp(val locked: Set<String>, val locales: String, val packages: Int)

    private class Snapshot(
        val stamp: Stamp,
        /** Package -> the names it goes by (launcher and application labels). */
        val names: Map<String, Set<String>>,
        /** Name -> the packages that go by it. */
        val byName: Map<String, Set<String>>,
        /** Locked package -> its names and the titles of all its screens. */
        val titles: Map<String, Set<String>>
    )

    private val context = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "biokey-app-labels").apply { isDaemon = true } }
    private val building = AtomicBoolean(false)
    @Volatile private var snapshot: Snapshot? = null
    @Volatile private var wanted: Stamp? = null
    /** Names read directly while no snapshot knows the app yet. */
    private val direct = ConcurrentHashMap<String, Set<String>>()

    /** The locked apps among [locked] that go by [name] (a [key]). */
    fun lockedNamed(name: String, locked: Set<String>): List<String> {
        val snap = current(locked)
        return locked.filter { pkg -> name in (snap?.names?.get(pkg) ?: directNames(pkg)) }
    }

    /** The one app called [title] (a window's title), if exactly one is; null if none or several are. */
    fun appTitled(title: String, locked: Set<String>): String? {
        val name = key(title) ?: return null
        return current(locked)?.byName?.get(name)?.singleOrNull()
    }

    /** Whether [title] is [pkg]'s name or one of its screens' titles (as far as known). */
    fun isTitleOf(pkg: String, title: String, locked: Set<String>): Boolean {
        val name = key(title) ?: return false
        val snap = current(locked) ?: return name in directNames(pkg)
        return PasswordManager.aliasesOf(pkg).any { alias ->
            name in snap.titles[alias].orEmpty() || name in snap.names[alias].orEmpty()
        }
    }

    /** Starts the first read early, so the first tap doesn't fall back to direct reads. */
    fun warmUp(locked: Set<String>) {
        current(locked)
    }

    fun shutdown() {
        executor.shutdownNow()
    }

    private fun current(locked: Set<String>): Snapshot? {
        val stamp = Stamp(locked, context.resources.configuration.locales.toLanguageTags(), BioKeyApplication.packageChangeVersion.value)
        val snap = snapshot
        if (snap?.stamp != stamp) rebuild(stamp)
        return snap
    }

    private fun rebuild(stamp: Stamp) {
        wanted = stamp
        if (!building.compareAndSet(false, true)) return
        runCatching {
            executor.execute {
                try {
                    while (true) {
                        val target = wanted ?: break
                        snapshot = build(target)
                        direct.clear()
                        if (wanted == target) break
                    }
                } finally {
                    building.set(false)
                }
            }
        }.onFailure { building.set(false) }
    }

    private fun realPackage(pkg: String) = if (pkg in PasswordManager.ALIASES) PasswordManager.CREDENTIAL_MANAGER else pkg

    private fun build(stamp: Stamp): Snapshot {
        val names = HashMap<String, MutableSet<String>>()
        fun name(pkg: String, label: CharSequence?) {
            key(label)?.let { names.getOrPut(pkg, ::HashSet) += it }
        }
        val launcherApps = context.getSystemService(LauncherApps::class.java)
        val profiles = runCatching { context.getSystemService(UserManager::class.java)?.userProfiles }.getOrNull().orEmpty()
        for (user in profiles) {
            val activities = runCatching { launcherApps?.getActivityList(null, user) }.getOrNull().orEmpty()
            for (activity in activities) runCatching { name(activity.componentName.packageName, activity.label) }
        }
        val pm = context.packageManager
        val titles = HashMap<String, Set<String>>()
        for (pkg in stamp.locked) {
            val real = realPackage(pkg)
            if (real != pkg) names[real]?.let { names.getOrPut(pkg, ::HashSet) += it }
            if (pkg in PasswordManager.ALIASES) name(pkg, GOOGLE_PASSWORD_MANAGER)
            runCatching { pm.getApplicationLabel(pm.getApplicationInfo(real, 0)) }.getOrNull()?.let { name(pkg, it) }
            // An app's screens are titled after their activities: an unreadable window of the app
            // still carries one of these.
            val own = HashSet(names[pkg].orEmpty())
            runCatching { pm.getPackageInfo(real, PackageManager.GET_ACTIVITIES).activities }.getOrNull()
                ?.forEach { activity -> runCatching { key(activity.loadLabel(pm)) }.getOrNull()?.let(own::add) }
            titles[pkg] = own
        }
        val byName = HashMap<String, MutableSet<String>>()
        for ((pkg, set) in names) for (n in set) byName.getOrPut(n, ::HashSet) += pkg
        return Snapshot(stamp, names, byName, titles)
    }

    private fun directNames(pkg: String): Set<String> = direct.getOrPut(pkg) {
        buildSet {
            if (pkg in PasswordManager.ALIASES) add(GOOGLE_PASSWORD_MANAGER)
            val label = AppInfoCache.get(pkg)?.label ?: runCatching {
                val pm = context.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(realPackage(pkg), 0))
            }.getOrNull()
            key(label)?.let(::add)
        }
    }
}
