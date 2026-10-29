package com.android.BioKey

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.os.Build
import android.os.UserManager
import android.security.advancedprotection.AdvancedProtectionManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.android.BioKey.core.data.AppClassifier
import com.android.BioKey.core.diagnostics.DiagnosticLog
import com.android.BioKey.core.security.ProtectionAlerts
import com.android.BioKey.core.security.ProtectionEngine
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.core.security.NewAppSuggestions
import com.android.BioKey.core.security.ProtectionGaps
import com.android.BioKey.core.security.UnlockAttempts
import com.android.BioKey.feature.lock.AppInfoCache
import com.android.BioKey.feature.lock.ScreenReceiver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class BioKeyApplication : Application() {

    companion object {
        private const val TAG = "BioKeyApplication"
        private val initExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "biokey-async-init").apply { isDaemon = true }
        }
        private val _packageChangeVersion = MutableStateFlow(0)
        val packageChangeVersion: StateFlow<Int> = _packageChangeVersion.asStateFlow()

        /**
         * Whether an app uninstalled (not replaced) keeps its lock. A copy in another profile (a
         * work twin), an archived app, or one uninstalled with "keep app data" still has its data,
         * which a reinstall would open: it keeps its lock. [installedInAnyProfile] is only asked
         * when the broadcast hasn't already settled it.
         */
        internal fun keepsLockAfterRemoval(
            archival: Boolean,
            dataRemoved: Boolean,
            installedInAnyProfile: () -> Boolean,
        ): Boolean = archival || !dataRemoved || installedInAnyProfile()
    }

    private val credentialInitDone = AtomicBoolean(false)
    private var userUnlockedReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        ScreenReceiver.register(this)

        if (isUserUnlocked()) {
            initCredentialProtectedComponents()
            return
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_USER_UNLOCKED) initCredentialProtectedComponents()
            }
        }
        userUnlockedReceiver = receiver
        ContextCompat.registerReceiver(this, receiver, IntentFilter(Intent.ACTION_USER_UNLOCKED), ContextCompat.RECEIVER_NOT_EXPORTED)
        // Look again: an unlock between the first look and the registration sent its one
        // broadcast to nobody, and nothing would load for the whole process.
        if (isUserUnlocked()) initCredentialProtectedComponents()
    }

    private fun isUserUnlocked(): Boolean = getSystemService(UserManager::class.java)?.isUserUnlocked ?: true

    /** Runs once, whichever of [onCreate]'s two looks or the unlock broadcast gets here first. */
    private fun initCredentialProtectedComponents() {
        if (!credentialInitDone.compareAndSet(false, true)) return
        userUnlockedReceiver?.let { runCatching { unregisterReceiver(it) } }
        userUnlockedReceiver = null
        DiagnosticLog.init(this)
        val app = this
        initExecutor.execute {
            try {
                // Pre-warm SecurityVault so UI threads find it ready in memory
                SecurityVault.getInstance(app)
                // Off the main thread, so a prompt's first miss or a screen-off doesn't wait for the disk.
                UnlockAttempts.load(app)
                NewAppSuggestions.load(app)
                ProtectionGaps.load(app)
                // An engine that connected before the first unlock couldn't say so then.
                if (ProtectionEngine.isProtecting(app)) ProtectionGaps.onEngineUp(app)
                ProtectionEngine.refresh(app)
                ProtectionAlerts.checkAccessibilityConnects(app)
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing credential-protected components", e)
            }
        }
        registerPackageChangeReceiver()
        watchAdvancedProtection()
    }

    /** Switch engines the moment Advanced Protection is turned on or off. */
    private fun watchAdvancedProtection() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return
        runCatching {
            getSystemService(AdvancedProtectionManager::class.java)
                ?.registerAdvancedProtectionCallback(mainExecutor) { ProtectionEngine.refresh(this) }
        }
    }

    private fun registerPackageChangeReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(
            this,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    val pkg = intent?.data?.schemeSpecificPart ?: return
                    val replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
                    initExecutor.execute {
                        try {
                            AppInfoCache.invalidate(pkg)
                            val vault = SecurityVault.getInstance(this@BioKeyApplication)
                            when (intent.action) {
                                Intent.ACTION_PACKAGE_ADDED -> {
                                    if (!replacing && vault.isAutoSortProfilesEnabled()) {
                                        val pm = packageManager
                                        val info = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull()
                                        val label = info?.let { runCatching { pm.getApplicationLabel(it).toString() }.getOrNull() } ?: pkg
                                        vault.autoSortAppsIntoProfiles(listOf(pkg to AppClassifier.categorize(pkg, label, info?.category ?: -1)))
                                    }
                                    // Whatever auto-sort didn't lock is offered: "Lock <app>?".
                                    if (!replacing) {
                                        val pm = packageManager
                                        val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
                                        NewAppSuggestions.onInstalled(this@BioKeyApplication, pkg, label)
                                    }
                                    if (pkg in vault.getLockedPackages()) {
                                        AppInfoCache.preWarm(this@BioKeyApplication, listOf(pkg))
                                    }
                                }
                                Intent.ACTION_PACKAGE_REPLACED -> {
                                    if (pkg in vault.getLockedPackages()) {
                                        AppInfoCache.preWarm(this@BioKeyApplication, listOf(pkg))
                                    }
                                }
                                Intent.ACTION_PACKAGE_REMOVED -> {
                                    if (!replacing) {
                                        if (keepsLockAfterRemoval(
                                                archival = isArchival(intent),
                                                dataRemoved = intent.getBooleanExtra(Intent.EXTRA_DATA_REMOVED, true),
                                                installedInAnyProfile = { isInstalledInAnyProfile(pkg) },
                                            )
                                        ) {
                                            if (pkg in vault.selectedAppsFlow.value) {
                                                DiagnosticLog.log("Kept the lock on $pkg: its data is still on the phone")
                                            }
                                        } else {
                                            vault.onPackageUninstalled(pkg)
                                        }
                                    }
                                }
                            }
                            _packageChangeVersion.update { it + 1 }
                        } catch (e: Exception) {
                            Log.w(TAG, "Error handling a package change: ${e.javaClass.simpleName}")
                        }
                    }
                }
            },
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun isArchival(intent: Intent): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM && intent.getBooleanExtra(Intent.EXTRA_ARCHIVAL, false)

    /**
     * Whether [pkg] still has a launchable copy in any profile BioKey can see (personal, work;
     * Android hides a private space from apps other than the launcher). When that can't be told
     * it counts as installed: a lock kept too long only leaves a stale entry, one dropped too
     * soon leaves an app open.
     */
    private fun isInstalledInAnyProfile(pkg: String): Boolean {
        val launcherApps = getSystemService(LauncherApps::class.java) ?: return true
        val profiles = runCatching { launcherApps.profiles }.getOrNull() ?: return true
        return profiles.any { user -> runCatching { launcherApps.getActivityList(pkg, user).isNotEmpty() }.getOrDefault(true) }
    }
}
