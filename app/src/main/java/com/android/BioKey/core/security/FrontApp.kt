package com.android.BioKey.core.security

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.android.BioKey.core.data.AppClassifier
import com.android.BioKey.core.data.PasswordManager
import com.android.BioKey.core.diagnostics.DiagnosticLog

/**
 * The app last in front, as whichever engine runs saw it ([LockStateTracker.onForegroundChanged]:
 * the accessibility engine's look at the screen, compatibility mode's last app opened), for the
 * "Lock this app" Quick Settings tile. Pulling down the shade isn't an app coming to the front
 * (SystemUI is never noted), so it's the app the shade came down over. In memory only, and only
 * the one package: nothing builds up.
 */
object FrontApp {

    /** Windows that are never "the app": the shade and status bar, and Android's own covers and dialogs. */
    private val NEVER_THE_APP = setOf("com.android.systemui", "android")

    @Volatile
    var last: String? = null
        private set

    /** Something came to the front ([LockStateTracker.onForegroundChanged]). Cheap: called on every look at the screen. */
    fun onFront(pkg: String) {
        if (pkg.isEmpty() || pkg in NEVER_THE_APP) return
        // An app whose windows hide from accessibility services can't be named: rather than the
        // app before it, nothing.
        last = pkg.takeIf { ':' !in it }
    }

    @VisibleForTesting
    internal fun setForTests(pkg: String?) {
        last = pkg
    }

    /** What "Lock this app" did. */
    sealed interface Outcome {
        /** Nothing BioKey can lock is in front: no app seen yet, a home screen, BioKey itself, or something with no launcher icon. */
        data object NothingToLock : Outcome
        /** BioKey isn't set up. */
        data object NotSetUp : Outcome
        /** Locked (or was already, and its session is over now): it asks again the next time it opens, and at once if it's on screen. */
        data class Locked(val pkg: String, val paused: Boolean, val protecting: Boolean) : Outcome
        /** Its switch is on, but [profileName] is off, so it isn't locked: turning the profile on is the owner's call. */
        data class ProfileOff(val pkg: String, val profileName: String) : Outcome
    }

    /**
     * Locks [pkg] (the app in front), as the tile asks. Only adds protection, so it needs no
     * authentication, like Lock all now. An app with no profile goes where auto-sort would put it
     * (if auto-sort is on and that profile is on), else with no profile, so it follows the
     * default re-lock delay; one already in a profile stays there. Already locked, its session
     * ends, so it's covered at once. Not for home screens, BioKey, or anything without a launcher
     * icon (the apps BioKey's list offers). [isHomeScreen] is the engines' own test.
     */
    fun lock(context: Context, pkg: String?, isHomeScreen: (String) -> Boolean): Outcome {
        val app = context.applicationContext
        val vault = SecurityVault.getInstance(app)
        if (!vault.isSetupCompleted()) return Outcome.NotSetUp
        if (pkg == null || pkg == app.packageName || pkg in PasswordManager.ALIASES) return Outcome.NothingToLock
        val pm = app.packageManager
        if (pm.getLaunchIntentForPackage(pkg) == null || runCatching { isHomeScreen(pkg) }.getOrDefault(true)) return Outcome.NothingToLock

        if (pkg !in vault.selectedAppsFlow.value) {
            if (vault.isAutoSortProfilesEnabled() && vault.profileOf(pkg) == null) {
                val info = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull()
                val label = info?.let { runCatching { pm.getApplicationLabel(it).toString() }.getOrNull() } ?: pkg
                val category = AppClassifier.categorize(pkg, label, info?.category ?: -1)
                val target = AppClassifier.presetProfileFor(category)
                // Only into a profile that's on: filed into one that's off, it wouldn't be locked.
                if (vault.profilesFlow.value.any { it.id == target && it.enabled }) vault.autoSortAppsIntoProfiles(listOf(pkg to category))
            }
            if (pkg !in vault.selectedAppsFlow.value) vault.setAppLock(pkg, true)
        }
        vault.profileOf(pkg)?.takeIf { !it.enabled }?.let { return Outcome.ProfileOff(pkg, it.name) }
        // Already locked and open now: its session ends, as Lock on home's unlocked card does.
        LockStateTracker.lockPackage(pkg, "locked from the Lock this app tile")
        DiagnosticLog.log("Locked $pkg from the Lock this app tile")
        return Outcome.Locked(pkg, paused = !vault.isProtectionActive(), protecting = ProtectionEngine.isProtecting(app))
    }

    /** [pkg]'s name, as the launcher shows it. */
    fun label(context: Context, pkg: String): String {
        val pm = context.packageManager
        return runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
    }
}
