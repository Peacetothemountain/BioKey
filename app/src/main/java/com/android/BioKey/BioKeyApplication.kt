package com.android.BioKey

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.UserManager
import android.security.advancedprotection.AdvancedProtectionManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.android.BioKey.core.security.ProtectionEngine
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.feature.lock.AppInfoCache
import com.android.BioKey.feature.lock.ScreenReceiver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.Executors

class BioKeyApplication : Application() {

    companion object {
        private const val TAG = "BioKeyApplication"
        private val initExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "biokey-async-init").apply { isDaemon = true }
        }
        private val _packageChangeVersion = MutableStateFlow(0)
        val packageChangeVersion: StateFlow<Int> = _packageChangeVersion.asStateFlow()
    }

    override fun onCreate() {
        super.onCreate()
        ScreenReceiver.register(this)

        val userManager = getSystemService(UserManager::class.java)
        val isUnlocked = userManager?.isUserUnlocked ?: true

        if (isUnlocked) {
            initCredentialProtectedComponents()
        } else {
            val filter = IntentFilter(Intent.ACTION_USER_UNLOCKED)
            ContextCompat.registerReceiver(
                this,
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        if (intent?.action == Intent.ACTION_USER_UNLOCKED) {
                            runCatching { this@BioKeyApplication.unregisterReceiver(this) }
                            initCredentialProtectedComponents()
                        }
                    }
                },
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }
    }

    private fun initCredentialProtectedComponents() {
        val app = this
        initExecutor.execute {
            try {
                // Pre-warm SecurityVault so UI threads find it ready in memory
                SecurityVault.getInstance(app)
                ProtectionEngine.refresh(app)
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
                                        val label = runCatching {
                                            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                                        }.getOrDefault(pkg)
                                        vault.autoSortSinglePackage(pkg, label, pm)
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
                                        vault.onPackageUninstalled(pkg)
                                    }
                                }
                            }
                            _packageChangeVersion.update { it + 1 }
                        } catch (e: Exception) {
                            Log.w(TAG, "Error handling package change for $pkg: ${e.message}")
                        }
                    }
                }
            },
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }
}
