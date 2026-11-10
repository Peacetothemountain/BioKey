package com.android.BioKey.feature.main

import android.app.usage.UsageStatsManager
import android.content.Context
import androidx.annotation.VisibleForTesting
import com.android.BioKey.core.security.ProtectionEngine

/**
 * The order of home's app list, as Android's own App lock lists apps: locked first, then the apps
 * used in the last 24 hours (most recent first), then A–Z. The order is worked out when the list
 * is read or BioKey comes back, and not again while switches are flipped: a row stays where it is
 * under the finger, as Google's does.
 *
 * When each app was last used comes from Android's usage stats, only where the owner has already
 * given BioKey usage access (compatibility mode, [ProtectionEngine.hasUsageAccess]); read as the
 * list is shown, held only in memory, never kept. Without it there's no "recent", only A–Z:
 * BioKey doesn't keep a usage history of its own for this.
 */
internal object AppOrder {

    /** "Recently used": the last 24 hours. */
    const val RECENT_MS = 24 * 60 * 60_000L

    /** When each app was last in use over [RECENT_MS], as Android says; empty without usage access. */
    @VisibleForTesting
    internal var lastUsed: (Context) -> Map<String, Long> = ::readUsage

    /** The last [lastUsed] read in this process: home's first frame, before it reads again. */
    @Volatile var lastRead: Map<String, Long> = emptyMap()
        private set

    /** Reads [lastUsed] (blocking: off the main thread), and keeps it for the next first frame. */
    fun recentUse(context: Context): Map<String, Long> =
        runCatching { lastUsed(context) }.getOrDefault(emptyMap()).also { lastRead = it }

    private fun readUsage(context: Context): Map<String, Long> {
        if (!ProtectionEngine.hasUsageAccess(context)) return emptyMap()
        val usage = context.getSystemService(UsageStatsManager::class.java) ?: return emptyMap()
        val now = System.currentTimeMillis()
        val since = now - RECENT_MS
        return usage.queryAndAggregateUsageStats(since, now).mapNotNull { (pkg, stats) ->
            val last = maxOf(stats.lastTimeUsed, stats.lastTimeVisible)
            if (last >= since && pkg != context.packageName) pkg to last else null
        }.toMap()
    }

    /**
     * [apps] in list order: those [lockedFirst] says first, then each group's [lastUsed] apps,
     * most recent first, then the rest A–Z (as the list was read).
     */
    fun order(apps: List<AppItem>, lockedFirst: (String) -> Boolean, lastUsed: Map<String, Long>): List<AppItem> =
        apps.sortedWith(
            compareByDescending<AppItem> { lockedFirst(it.packageName) }
                .thenByDescending { lastUsed[it.packageName] ?: 0L }
                .thenBy { it.label.lowercase() }
        )
}
