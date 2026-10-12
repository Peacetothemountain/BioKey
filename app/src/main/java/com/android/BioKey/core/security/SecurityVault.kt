package com.android.BioKey.core.security

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.util.Log
import com.android.BioKey.core.data.BioKeyPreferences
import com.android.BioKey.core.data.LockTimeoutMode
import com.android.BioKey.core.data.SecurityProfile
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import com.android.BioKey.feature.lock.BioKeyAccessibilityService
import com.android.BioKey.feature.lock.InstantShieldOverlay

/**
 * High-performance, zero-latency in-memory security repository.
 * Eliminates async DataStore race conditions in BioKeyAccessibilityService.
 * Provides instant thread-safe synchronous reads & writes backed by SharedPreferences and DataStore.
 */
class SecurityVault private constructor(private val context: Context) {

    companion object {
        private const val TAG = "SecurityVault"
        private const val PREF_NAME = "biokey_vault_sync"
        private const val KEY_LOCKED = "locked_apps_set"
        private const val KEY_GLOBAL = "global_lock_enabled"
        private const val KEY_TIMEOUT = "timeout_mode"
        private const val KEY_STEALTH_GLOBAL = "stealth_global"
        private const val KEY_STEALTH_APPS = "stealth_apps_set"
        private const val KEY_INTRUDER = "intruder_detection"
        private const val KEY_OVERLAY = "overlay_shield"
        private const val KEY_SELF_PROTECT = "self_protection"
        private const val KEY_PROFILES_JSON = "security_profiles_json"
        private const val KEY_ACTIVE_PROFILE_ID = "active_profile_id"
        private const val KEY_AUTO_SORT = "auto_sort_profiles_enabled"
        private const val KEY_SETUP_COMPLETED = "setup_completed"
        private const val KEY_CLEAN_DEFAULTS = "clean_defaults_v4"
        private const val KEY_NOTIFICATION_SHIELD = "notification_shield_enabled"
        private const val KEY_RECENTS_SHIELD = "recents_shield_enabled"
        private const val KEY_IDENTITY_CHECK = "identity_check_enabled"

        @Volatile
        private var INSTANCE: SecurityVault? = null

        fun getInstance(context: Context): SecurityVault {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SecurityVault(context.applicationContext).also { INSTANCE = it }
            }
        }

        fun isExcludedFromSecurity(pkg: String, label: String = "", category: Int = -1): Boolean {
            val p = pkg.lowercase()
            val l = label.lowercase()

            // 1. Android ApplicationInfo Category Exclusions
            if (category == ApplicationInfo.CATEGORY_GAME ||
                category == ApplicationInfo.CATEGORY_AUDIO ||
                category == ApplicationInfo.CATEGORY_MAPS ||
                category == ApplicationInfo.CATEGORY_NEWS
            ) {
                return true
            }

            // 2. Games (2048, puzzles, arcades, engines, game studios, popular mobile games)
            val gameKeywords = listOf(
                "2048", "game", "puzzle", "arcade", "unity3d", "roblox", "supercell",
                "sudoku", "chess", "solitaire", "candycrush", "minecraft", "pokemon",
                "epicgames", "ea.game", "playrix", "king.com", "taptap", "steam",
                "riotgames", "gameloft", "blizzard", "mojang", "clash", "brawlstars",
                "genshin", "subway", "angrybirds", "crossy", "templerun", "trivia",
                "tetris", "mahjong", "boardgame", "crossword", "wordle", "wordsearch"
            )
            if (gameKeywords.any { p.contains(it) || l.contains(it) }) {
                return true
            }

            // 3. Audio / Music / Streaming / Entertainment / Benign Utilities
            val mediaAndToolKeywords = listOf(
                "spotify", "podcast", "music", "audio", "radio", "pandora", "deezer",
                "soundcloud", "shazam", "bandcamp", "tidal", "tunein", "audible",
                "netflix", "hulu", "disney", "primevideo", "twitch", "crunchyroll",
                "youtube", "ytkids", "vimeo", "plex", "hbomax", "max.com", "peacock",
                "wallpaper", "weather", "calculator", "clock", "timer", "flashlight",
                "compass", "benchmark", "antutu", "geekbench", "speedtest", "unitconverter"
            )
            if (mediaAndToolKeywords.any { p.contains(it) || l.contains(it) }) {
                return true
            }

            // 4. System Overlays, Package Installers & Input Methods
            if (p == "com.android.systemui" || p.contains("inputmethod") ||
                p.contains("keyboard") || p.contains("launcher") ||
                p.contains("packageinstaller") || p.contains("uninstaller") ||
                p.contains("permissioncontroller") || p.contains("captiveportallogin") ||
                p.contains("companiondevicemanager") ||
                p == "com.google.android.packageinstaller" || p == "com.android.packageinstaller"
            ) {
                return true
            }

            return false
        }
    }

    private val sharedPrefs: SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val dataStorePrefs = BioKeyPreferences.getInstance(context)
    private val vaultScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Synchronous In-Memory Cache
    private val lockedAppsSet = ConcurrentHashMap.newKeySet<String>()
    private val stealthAppsSet = ConcurrentHashMap.newKeySet<String>()

    private val _lockedAppsFlow = MutableStateFlow<Set<String>>(emptySet())
    val lockedAppsFlow: StateFlow<Set<String>> = _lockedAppsFlow.asStateFlow()

    private val _stealthAppsFlow = MutableStateFlow<Set<String>>(emptySet())
    val stealthAppsFlow: StateFlow<Set<String>> = _stealthAppsFlow.asStateFlow()

    private val _globalLockEnabledFlow = MutableStateFlow(true)
    val globalLockEnabledFlow: StateFlow<Boolean> = _globalLockEnabledFlow.asStateFlow()

    private val _timeoutModeFlow = MutableStateFlow(LockTimeoutMode.IMMEDIATE)
    val timeoutModeFlow: StateFlow<LockTimeoutMode> = _timeoutModeFlow.asStateFlow()

    private val _stealthModeGlobalFlow = MutableStateFlow(false)
    val stealthModeGlobalFlow: StateFlow<Boolean> = _stealthModeGlobalFlow.asStateFlow()

    private val _intruderDetectionFlow = MutableStateFlow(true)
    val intruderDetectionFlow: StateFlow<Boolean> = _intruderDetectionFlow.asStateFlow()

    private val _overlayShieldFlow = MutableStateFlow(true)
    val overlayShieldFlow: StateFlow<Boolean> = _overlayShieldFlow.asStateFlow()

    private val _selfProtectionFlow = MutableStateFlow(true)
    val selfProtectionFlow: StateFlow<Boolean> = _selfProtectionFlow.asStateFlow()

    private val _profilesFlow = MutableStateFlow<List<SecurityProfile>>(emptyList())
    val profilesFlow: StateFlow<List<SecurityProfile>> = _profilesFlow.asStateFlow()

    private val _activeProfileIdFlow = MutableStateFlow<String?>("all")
    val activeProfileIdFlow: StateFlow<String?> = _activeProfileIdFlow.asStateFlow()

    private val _autoSortProfilesFlow = MutableStateFlow(false)
    val autoSortProfilesFlow: StateFlow<Boolean> = _autoSortProfilesFlow.asStateFlow()

    private val _setupCompletedFlow = MutableStateFlow(false)
    val setupCompletedFlow: StateFlow<Boolean> = _setupCompletedFlow.asStateFlow()

    private val _notificationShieldFlow = MutableStateFlow(true)
    val notificationShieldFlow: StateFlow<Boolean> = _notificationShieldFlow.asStateFlow()

    private val _recentsShieldFlow = MutableStateFlow(true)
    val recentsShieldFlow: StateFlow<Boolean> = _recentsShieldFlow.asStateFlow()

    private val _identityCheckFlow = MutableStateFlow(false)
    val identityCheckFlow: StateFlow<Boolean> = _identityCheckFlow.asStateFlow()

    init {
        val cleanDefaultsDone = sharedPrefs.getBoolean(KEY_CLEAN_DEFAULTS, false)

        // Synchronous In-Memory Cache Initialization:
        // On first run or reset migration, ALL apps are disabled by default.
        val savedLocked = if (!cleanDefaultsDone) {
            emptySet()
        } else {
            sharedPrefs.getStringSet(KEY_LOCKED, emptySet()) ?: emptySet()
        }
        lockedAppsSet.addAll(savedLocked)
        _lockedAppsFlow.value = savedLocked.toSet()

        if (!cleanDefaultsDone) {
            sharedPrefs.edit()
                .putStringSet(KEY_LOCKED, emptySet())
                .putBoolean(KEY_CLEAN_DEFAULTS, true)
                .apply()
        }

        val savedStealth = sharedPrefs.getStringSet(KEY_STEALTH_APPS, emptySet()) ?: emptySet()
        stealthAppsSet.addAll(savedStealth)
        _stealthAppsFlow.value = savedStealth.toSet()

        val global = sharedPrefs.getBoolean(KEY_GLOBAL, true)
        _globalLockEnabledFlow.value = global

        val timeoutStr = sharedPrefs.getString(KEY_TIMEOUT, LockTimeoutMode.IMMEDIATE.name)
        _timeoutModeFlow.value = LockTimeoutMode.fromName(timeoutStr)

        val stealthGlobal = sharedPrefs.getBoolean(KEY_STEALTH_GLOBAL, false)
        _stealthModeGlobalFlow.value = stealthGlobal

        val intruder = sharedPrefs.getBoolean(KEY_INTRUDER, true)
        _intruderDetectionFlow.value = intruder

        val overlay = sharedPrefs.getBoolean(KEY_OVERLAY, true)
        _overlayShieldFlow.value = overlay

        val selfProtect = sharedPrefs.getBoolean(KEY_SELF_PROTECT, true)
        _selfProtectionFlow.value = selfProtect

        val autoSort = sharedPrefs.getBoolean(KEY_AUTO_SORT, false)
        _autoSortProfilesFlow.value = autoSort

        val legacyBioKeyPrefs = context.getSharedPreferences("biokey_prefs", Context.MODE_PRIVATE)
        val setupCompleted = sharedPrefs.getBoolean(KEY_SETUP_COMPLETED, false) ||
                sharedPrefs.getBoolean("setup_complete", false) ||
                legacyBioKeyPrefs.getBoolean("setup_complete", false)
        _setupCompletedFlow.value = setupCompleted

        val notifShield = sharedPrefs.getBoolean(KEY_NOTIFICATION_SHIELD, true)
        _notificationShieldFlow.value = notifShield

        val recentsShield = sharedPrefs.getBoolean(KEY_RECENTS_SHIELD, true)
        _recentsShieldFlow.value = recentsShield

        val migrationV5Done = sharedPrefs.getBoolean("migration_v5_identity_check_default", false)
        if (!migrationV5Done) {
            sharedPrefs.edit()
                .putBoolean(KEY_IDENTITY_CHECK, false)
                .putBoolean("migration_v5_identity_check_default", true)
                .apply()
        }

        val idCheck = sharedPrefs.getBoolean(KEY_IDENTITY_CHECK, false)
        _identityCheckFlow.value = idCheck

        val savedProfilesJson = if (!cleanDefaultsDone) null else sharedPrefs.getString(KEY_PROFILES_JSON, null)
        val loadedProfiles = if (!savedProfilesJson.isNullOrBlank()) {
            try {
                val array = JSONArray(savedProfilesJson)
                (0 until array.length()).map { i ->
                    SecurityProfile.fromJson(array.getJSONObject(i))
                }
            } catch (e: Exception) {
                getDefaultPresets()
            }
        } else {
            getDefaultPresets()
        }

        val finalProfiles = if (!cleanDefaultsDone) {
            loadedProfiles.map { prof ->
                if (prof.id in listOf("profile_personal", "profile_finance", "profile_social", "profile_work")) {
                    prof.copy(packages = emptySet())
                } else {
                    prof
                }
            }
        } else {
            loadedProfiles
        }
        _profilesFlow.value = finalProfiles
        if (!cleanDefaultsDone) {
            persistProfiles(finalProfiles)
        }

        val activeProfile = sharedPrefs.getString(KEY_ACTIVE_PROFILE_ID, "all") ?: "all"
        _activeProfileIdFlow.value = activeProfile

        // Purge any excluded apps (like 2048 or mobile games) immediately on startup
        purgeExcludedApps(context.packageManager)

        // Clear stale DataStore locks if cleaning defaults, or reconcile DataStore on restart
        vaultScope.launch {
            try {
                if (!cleanDefaultsDone) {
                    val dsCurrent = dataStorePrefs.lockedAppsFlow.first()
                    if (dsCurrent.isNotEmpty()) {
                        dataStorePrefs.setMultipleAppLocks(dsCurrent, false)
                    }
                } else {
                    val dsCurrent = dataStorePrefs.lockedAppsFlow.first()
                    if (dsCurrent.isNotEmpty() && lockedAppsSet.isEmpty()) {
                        lockedAppsSet.addAll(dsCurrent)
                        _lockedAppsFlow.value = dsCurrent.toSet()
                        sharedPrefs.edit().putStringSet(KEY_LOCKED, dsCurrent).apply()
                    } else if (lockedAppsSet.isNotEmpty() && dsCurrent.isEmpty()) {
                        dataStorePrefs.setMultipleAppLocks(lockedAppsSet, true)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "DataStore sync warning: ${e.message}")
            }
        }
    }

    // Synchronous read checks - 0ms delay
    fun isAppLocked(packageName: String): Boolean {
        if (!_globalLockEnabledFlow.value) return false
        if (isExcludedFromSecurity(packageName)) return false
        val isPwMgr = packageName == BioKeyAccessibilityService.VIRTUAL_PKG_GOOGLE_PASSWORDS ||
                packageName == BioKeyAccessibilityService.PKG_CREDENTIAL_MANAGER
        if (isPwMgr) {
            if (lockedAppsSet.contains(BioKeyAccessibilityService.VIRTUAL_PKG_GOOGLE_PASSWORDS) ||
                lockedAppsSet.contains(BioKeyAccessibilityService.PKG_CREDENTIAL_MANAGER)
            ) {
                return true
            }
        } else if (lockedAppsSet.contains(packageName)) {
            return true
        }

        val activeId = _activeProfileIdFlow.value
        if (activeId != null && activeId != "all") {
            val prof = _profilesFlow.value.firstOrNull { it.id == activeId }
            if (prof != null) {
                if (isPwMgr) {
                    if (prof.packages.contains(BioKeyAccessibilityService.VIRTUAL_PKG_GOOGLE_PASSWORDS) ||
                        prof.packages.contains(BioKeyAccessibilityService.PKG_CREDENTIAL_MANAGER)
                    ) {
                        return true
                    }
                } else if (prof.packages.contains(packageName)) {
                    return true
                }
            }
        }
        return false
    }

    fun isStealthCrash(packageName: String): Boolean {
        if (_stealthModeGlobalFlow.value) return true
        val isPwMgr = packageName == BioKeyAccessibilityService.VIRTUAL_PKG_GOOGLE_PASSWORDS ||
                packageName == BioKeyAccessibilityService.PKG_CREDENTIAL_MANAGER
        if (isPwMgr) {
            if (stealthAppsSet.contains(BioKeyAccessibilityService.VIRTUAL_PKG_GOOGLE_PASSWORDS) ||
                stealthAppsSet.contains(BioKeyAccessibilityService.PKG_CREDENTIAL_MANAGER)
            ) {
                return true
            }
        } else if (stealthAppsSet.contains(packageName)) {
            return true
        }

        val activeId = _activeProfileIdFlow.value
        if (activeId != null && activeId != "all") {
            val prof = _profilesFlow.value.firstOrNull { it.id == activeId }
            if (prof?.isStealth == true) {
                if (isPwMgr) {
                    if (lockedAppsSet.contains(BioKeyAccessibilityService.VIRTUAL_PKG_GOOGLE_PASSWORDS) ||
                        lockedAppsSet.contains(BioKeyAccessibilityService.PKG_CREDENTIAL_MANAGER) ||
                        prof.packages.contains(BioKeyAccessibilityService.VIRTUAL_PKG_GOOGLE_PASSWORDS) ||
                        prof.packages.contains(BioKeyAccessibilityService.PKG_CREDENTIAL_MANAGER)
                    ) {
                        return true
                    }
                } else if (lockedAppsSet.contains(packageName) || prof.packages.contains(packageName)) {
                    return true
                }
            }
        }
        return false
    }

    fun isStealthModeGlobal(): Boolean = _stealthModeGlobalFlow.value
    fun setStealthModeGlobal(enabled: Boolean) = setStealthCrashMode(enabled)

    fun isGlobalLockEnabled(): Boolean = _globalLockEnabledFlow.value
    fun getTimeoutMode(): LockTimeoutMode = _timeoutModeFlow.value
    fun isOverlayShieldEnabled(): Boolean = _overlayShieldFlow.value
    fun isSelfProtectionEnabled(): Boolean = _selfProtectionFlow.value
    fun isIntruderDetectionEnabled(): Boolean = _intruderDetectionFlow.value

    fun setAppLock(packageName: String, locked: Boolean) {
        val isPwMgr = packageName == BioKeyAccessibilityService.VIRTUAL_PKG_GOOGLE_PASSWORDS ||
                packageName == BioKeyAccessibilityService.PKG_CREDENTIAL_MANAGER
        val targetPkgs = if (isPwMgr) {
            setOf(BioKeyAccessibilityService.VIRTUAL_PKG_GOOGLE_PASSWORDS, BioKeyAccessibilityService.PKG_CREDENTIAL_MANAGER)
        } else {
            setOf(packageName)
        }

        if (locked) {
            lockedAppsSet.addAll(targetPkgs)
        } else {
            lockedAppsSet.removeAll(targetPkgs)
            targetPkgs.forEach { LockStateTracker.onAppUnlocked(it) }
        }
        val newSet = lockedAppsSet.toSet()
        _lockedAppsFlow.value = newSet
        sharedPrefs.edit().putStringSet(KEY_LOCKED, newSet).apply()

        // Sync with active profile if not "all"
        val activeId = _activeProfileIdFlow.value
        if (activeId != null && activeId != "all") {
            val current = _profilesFlow.value.toMutableList()
            val index = current.indexOfFirst { it.id == activeId }
            if (index >= 0) {
                val prof = current[index]
                val updatedPkgs = if (locked) prof.packages + targetPkgs else prof.packages - targetPkgs
                current[index] = prof.copy(packages = updatedPkgs)
                _profilesFlow.value = current
                persistProfiles(current)
            }
        }

        vaultScope.launch {
            targetPkgs.forEach { dataStorePrefs.setAppLock(it, locked) }
        }
    }

    fun setMultipleAppLocks(packages: Collection<String>, locked: Boolean) {
        val fullPackages = packages.toMutableSet()
        if (fullPackages.contains(BioKeyAccessibilityService.VIRTUAL_PKG_GOOGLE_PASSWORDS) ||
            fullPackages.contains(BioKeyAccessibilityService.PKG_CREDENTIAL_MANAGER)
        ) {
            fullPackages.add(BioKeyAccessibilityService.VIRTUAL_PKG_GOOGLE_PASSWORDS)
            fullPackages.add(BioKeyAccessibilityService.PKG_CREDENTIAL_MANAGER)
        }

        if (locked) {
            lockedAppsSet.addAll(fullPackages)
        } else {
            lockedAppsSet.removeAll(fullPackages)
            fullPackages.forEach { LockStateTracker.onAppUnlocked(it) }
        }
        val newSet = lockedAppsSet.toSet()
        _lockedAppsFlow.value = newSet
        sharedPrefs.edit().putStringSet(KEY_LOCKED, newSet).apply()

        vaultScope.launch {
            dataStorePrefs.setMultipleAppLocks(fullPackages, locked)
        }
    }

    fun setAppStealthCrash(packageName: String, enabled: Boolean) {
        val isPwMgr = packageName == BioKeyAccessibilityService.VIRTUAL_PKG_GOOGLE_PASSWORDS ||
                packageName == BioKeyAccessibilityService.PKG_CREDENTIAL_MANAGER
        val targetPkgs = if (isPwMgr) {
            setOf(BioKeyAccessibilityService.VIRTUAL_PKG_GOOGLE_PASSWORDS, BioKeyAccessibilityService.PKG_CREDENTIAL_MANAGER)
        } else {
            setOf(packageName)
        }

        if (enabled) {
            stealthAppsSet.addAll(targetPkgs)
        } else {
            stealthAppsSet.removeAll(targetPkgs)
        }
        val newSet = stealthAppsSet.toSet()
        _stealthAppsFlow.value = newSet
        sharedPrefs.edit().putStringSet(KEY_STEALTH_APPS, newSet).apply()

        vaultScope.launch {
            targetPkgs.forEach { dataStorePrefs.setAppStealthCrash(it, enabled) }
        }
    }

    fun setGlobalLockEnabled(enabled: Boolean) {
        _globalLockEnabledFlow.value = enabled
        sharedPrefs.edit().putBoolean(KEY_GLOBAL, enabled).apply()
        if (!enabled) {
            InstantShieldOverlay.dismiss()
        }
        vaultScope.launch { dataStorePrefs.setGlobalLockEnabled(enabled) }
    }

    fun setTimeoutMode(mode: LockTimeoutMode) {
        _timeoutModeFlow.value = mode
        sharedPrefs.edit().putString(KEY_TIMEOUT, mode.name).apply()
        vaultScope.launch { dataStorePrefs.setTimeoutMode(mode) }
    }

    fun setStealthCrashMode(enabled: Boolean) {
        _stealthModeGlobalFlow.value = enabled
        sharedPrefs.edit().putBoolean(KEY_STEALTH_GLOBAL, enabled).apply()
        vaultScope.launch { dataStorePrefs.setStealthCrashMode(enabled) }
    }

    fun setIntruderDetection(enabled: Boolean) {
        _intruderDetectionFlow.value = enabled
        sharedPrefs.edit().putBoolean(KEY_INTRUDER, enabled).apply()
        vaultScope.launch { dataStorePrefs.setIntruderDetection(enabled) }
    }

    fun setSelfProtection(enabled: Boolean) {
        _selfProtectionFlow.value = enabled
        sharedPrefs.edit().putBoolean(KEY_SELF_PROTECT, enabled).apply()
        vaultScope.launch { dataStorePrefs.setSelfProtection(enabled) }
    }

    fun setOverlayShield(enabled: Boolean) {
        _overlayShieldFlow.value = enabled
        sharedPrefs.edit().putBoolean(KEY_OVERLAY, enabled).apply()
        vaultScope.launch { dataStorePrefs.setOverlayShield(enabled) }
    }

    fun isAutoSortProfilesEnabled(): Boolean = _autoSortProfilesFlow.value

    fun setAutoSortProfilesEnabled(enabled: Boolean) {
        _autoSortProfilesFlow.value = enabled
        sharedPrefs.edit().putBoolean(KEY_AUTO_SORT, enabled).apply()
        vaultScope.launch { dataStorePrefs.setAutoSortProfiles(enabled) }
    }

    fun isSetupCompleted(): Boolean = _setupCompletedFlow.value

    fun setSetupCompleted(completed: Boolean) {
        _setupCompletedFlow.value = completed
        sharedPrefs.edit()
            .putBoolean(KEY_SETUP_COMPLETED, completed)
            .putBoolean("setup_complete", completed)
            .apply()
        val legacyBioKeyPrefs = context.getSharedPreferences("biokey_prefs", Context.MODE_PRIVATE)
        legacyBioKeyPrefs.edit().putBoolean("setup_complete", completed).apply()
        vaultScope.launch { dataStorePrefs.setSetupCompleted(completed) }
    }

    fun resetSetup() {
        setSetupCompleted(false)
    }

    fun isNotificationShieldEnabled(): Boolean = _notificationShieldFlow.value

    fun setNotificationShield(enabled: Boolean) {
        _notificationShieldFlow.value = enabled
        sharedPrefs.edit().putBoolean(KEY_NOTIFICATION_SHIELD, enabled).apply()
        vaultScope.launch { dataStorePrefs.setNotificationShield(enabled) }
    }

    fun isRecentsShieldEnabled(): Boolean = _recentsShieldFlow.value

    fun setRecentsShield(enabled: Boolean) {
        _recentsShieldFlow.value = enabled
        sharedPrefs.edit().putBoolean(KEY_RECENTS_SHIELD, enabled).apply()
        vaultScope.launch { dataStorePrefs.setRecentsShield(enabled) }
    }

    /**
     * Identity Check (Biometric PIN-hijack protection).
     * Enforces Strong biometrics (Fingerprint / Face Unlock) across locked applications.
     */
    fun isIdentityCheckEnabled(): Boolean = _identityCheckFlow.value

    fun isStrictBiometricEnforced(): Boolean = _identityCheckFlow.value

    fun setIdentityCheckEnabled(enabled: Boolean) {
        _identityCheckFlow.value = enabled
        sharedPrefs.edit().putBoolean(KEY_IDENTITY_CHECK, enabled).apply()
        vaultScope.launch {
            dataStorePrefs.setIdentityCheck(enabled)
            TitanM3SecurityManager.INSTANCE.generateTitanM3BiometricKey(context)
        }
    }

    fun autoSortAppsIntoProfiles(
        appList: List<Pair<String, String>>,
        pm: android.content.pm.PackageManager? = null
    ) {
        if (!_autoSortProfilesFlow.value) return
        if (appList.isEmpty()) return

        val currentProfiles = _profilesFlow.value.toMutableList()
        if (currentProfiles.isEmpty()) return

        var modified = false

        val financePkgs = mutableSetOf<String>()
        val socialPkgs = mutableSetOf<String>()
        val workPkgs = mutableSetOf<String>()
        val personalPkgs = mutableSetOf<String>()

        for ((pkg, label) in appList) {
            val p = pkg.lowercase()
            val l = label.lowercase()

            var category = -1
            if (pm != null) {
                try {
                    val appInfo = pm.getApplicationInfo(pkg, 0)
                    category = appInfo.category
                } catch (_: Exception) {}
            }

            // EXCLUSION CHECK FIRST! Absolutely do not sort games, music, streaming, weather, etc.
            if (isExcludedFromSecurity(pkg, label, category)) {
                continue
            }

            // 1. Finance & Banking (High Risk)
            if (p.contains("bank") || p.contains("crypto") || p.contains("wallet") ||
                p.contains("pay") || p.contains("cash") || p.contains("binance") ||
                p.contains("coinbase") || p.contains("robinhood") || p.contains("revolut") ||
                p.contains("chase") || p.contains("wells") || p.contains("citi") ||
                p.contains("fidelity") || p.contains("schwab") || p.contains("vanguard") ||
                p.contains("sofi") || p.contains("metamask") || p.contains("trustwallet") ||
                p.contains("kraken") || p.contains("gemini") || p.contains("affirm") ||
                p.contains("klarna") || p.contains("venmo") || p.contains("paypal") ||
                l.contains("bank") || l.contains("crypto") || l.contains("wallet") ||
                l.contains("pay") || l.contains("finance") || l.contains("invest") ||
                l.contains("cash") || l.contains("money") || l.contains("credit")
            ) {
                financePkgs.add(pkg)
            }
            // 2. Social & Private Communication
            else if (category == ApplicationInfo.CATEGORY_SOCIAL ||
                p.contains("whatsapp") || p.contains("telegram") || p.contains("signal") ||
                p.contains("instagram") || p.contains("facebook") || p.contains("messenger") ||
                p.contains("twitter") || p.contains("tiktok") || p.contains("snapchat") ||
                p.contains("discord") || p.contains("reddit") || p.contains("threads") ||
                p.contains("bluesky") || p.contains("wechat") || p.contains("viber") ||
                p.contains("line") || p.contains("tinder") || p.contains("bumble") ||
                p.contains("hinge") || p.contains("messaging") ||
                (p.contains("messages") && !p.contains("google.android.apps.messaging")) ||
                l.contains("messages") || l.contains("chat") || l.contains("social")
            ) {
                socialPkgs.add(pkg)
            }
            // 3. Work, Productivity & 2FA Authenticator
            else if (category == ApplicationInfo.CATEGORY_PRODUCTIVITY ||
                p.contains("gmail") || p.contains("outlook") || p.contains("mail") ||
                p.contains("slack") || p.contains("teams") || p.contains("zoom") ||
                p.contains("meet") || p.contains("drive") || p.contains("docs") ||
                p.contains("sheets") || p.contains("slides") || p.contains("notion") ||
                p.contains("trello") || p.contains("asana") || p.contains("jira") ||
                p.contains("office") || p.contains("word") || p.contains("excel") ||
                p.contains("powerpoint") || p.contains("dropbox") || p.contains("onedrive") ||
                p.contains("authenticator") || p.contains("duo") || p.contains("bitwarden") ||
                p.contains("1password") || p.contains("lastpass") || p.contains("dashlane") ||
                p.contains("password") || l.contains("password") ||
                l.contains("office") || l.contains("authenticator")
            ) {
                workPkgs.add(pkg)
            }
            // 4. Personal Vault (Photos, Gallery, Files, Camera) - STRICTLY NO GAMES OR MEDIA PLAYERS
            else if (category == ApplicationInfo.CATEGORY_IMAGE ||
                p.contains("photo") || p.contains("gallery") || p.contains("camera") ||
                p.contains("files") || p.contains("filemanager") ||
                l.contains("photos") || l.contains("gallery") || l.contains("camera") ||
                l.contains("files")
            ) {
                personalPkgs.add(pkg)
            }
        }

        for (i in currentProfiles.indices) {
            val prof = currentProfiles[i]
            val matchedPkgs = when {
                prof.id == "profile_finance" || prof.iconKey == "finance" ||
                        prof.name.contains("finance", ignoreCase = true) ||
                        prof.name.contains("bank", ignoreCase = true) ||
                        prof.name.contains("crypto", ignoreCase = true) -> financePkgs

                prof.id == "profile_social" || prof.iconKey == "social" ||
                        prof.name.contains("social", ignoreCase = true) ||
                        prof.name.contains("message", ignoreCase = true) ||
                        prof.name.contains("chat", ignoreCase = true) -> socialPkgs

                prof.id == "profile_work" || prof.iconKey == "work" ||
                        prof.name.contains("work", ignoreCase = true) ||
                        prof.name.contains("office", ignoreCase = true) ||
                        prof.name.contains("prod", ignoreCase = true) -> workPkgs

                prof.id == "profile_personal" || prof.iconKey == "shield" ||
                        prof.name.contains("personal", ignoreCase = true) ||
                        prof.name.contains("private", ignoreCase = true) -> personalPkgs

                else -> emptySet()
            }

            if (matchedPkgs.isNotEmpty()) {
                val union = prof.packages + matchedPkgs
                if (union != prof.packages) {
                    currentProfiles[i] = prof.copy(packages = union)
                    modified = true
                }
            }
        }

        if (modified) {
            _profilesFlow.value = currentProfiles
            persistProfiles(currentProfiles)
            Log.i(TAG, "Auto-sorted installed apps into security profiles.")

            val activeId = _activeProfileIdFlow.value
            if (activeId != null && activeId != "all") {
                val activeProf = currentProfiles.firstOrNull { it.id == activeId }
                if (activeProf != null && activeProf.packages.isNotEmpty()) {
                    setMultipleAppLocks(activeProf.packages, true)
                }
            }
        }
    }

    /**
     * Purges any games or excluded apps (like 2048) that were previously auto-sorted
     * into profiles or the lockedAppsSet.
     */
    fun purgeExcludedApps(pm: android.content.pm.PackageManager? = null) {
        var modified = false
        val currentProfiles = _profilesFlow.value.toMutableList()

        for (i in currentProfiles.indices) {
            val prof = currentProfiles[i]
            val cleaned = prof.packages.filterNot { pkg ->
                var cat = -1
                var label = ""
                if (pm != null) {
                    try {
                        val info = pm.getApplicationInfo(pkg, 0)
                        cat = info.category
                        label = pm.getApplicationLabel(info).toString()
                    } catch (_: Exception) {}
                }
                isExcludedFromSecurity(pkg, label, cat)
            }.toSet()

            if (cleaned != prof.packages) {
                currentProfiles[i] = prof.copy(packages = cleaned)
                modified = true
            }
        }

        val toRemoveFromLocked = lockedAppsSet.filter { pkg ->
            var cat = -1
            var label = ""
            if (pm != null) {
                try {
                    val info = pm.getApplicationInfo(pkg, 0)
                    cat = info.category
                    label = pm.getApplicationLabel(info).toString()
                } catch (_: Exception) {}
            }
            isExcludedFromSecurity(pkg, label, cat)
        }

        if (toRemoveFromLocked.isNotEmpty()) {
            lockedAppsSet.removeAll(toRemoveFromLocked.toSet())
            val newSet = lockedAppsSet.toSet()
            _lockedAppsFlow.value = newSet
            sharedPrefs.edit().putStringSet(KEY_LOCKED, newSet).apply()
            vaultScope.launch {
                dataStorePrefs.setMultipleAppLocks(toRemoveFromLocked, false)
            }
            toRemoveFromLocked.forEach { LockStateTracker.onAppUnlocked(it) }
            Log.i(TAG, "Purged ${toRemoveFromLocked.size} excluded apps from lockedAppsSet.")
        }

        if (modified) {
            _profilesFlow.value = currentProfiles
            persistProfiles(currentProfiles)
            Log.i(TAG, "Purged excluded apps from all security profiles.")
        }
    }

    private fun getDefaultPresets(): List<SecurityProfile> {
        return listOf(
            SecurityProfile(
                id = "profile_personal",
                name = "Personal",
                iconKey = "shield",
                packages = emptySet()
            ),
            SecurityProfile(
                id = "profile_finance",
                name = "Finance",
                iconKey = "finance",
                packages = emptySet()
            ),
            SecurityProfile(
                id = "profile_social",
                name = "Social",
                iconKey = "social",
                packages = emptySet()
            ),
            SecurityProfile(
                id = "profile_work",
                name = "Work",
                iconKey = "work",
                packages = emptySet()
            )
        )
    }

    fun saveProfile(profile: SecurityProfile) {
        val current = _profilesFlow.value.toMutableList()
        val index = current.indexOfFirst { it.id == profile.id }
        val removedPackages = if (index >= 0) {
            current[index].packages - profile.packages
        } else {
            emptySet()
        }

        if (index >= 0) {
            current[index] = profile
        } else {
            current.add(profile)
        }
        _profilesFlow.value = current
        persistProfiles(current)

        for (pkg in removedPackages) {
            val inOtherProfile = current.any { it.packages.contains(pkg) }
            if (!inOtherProfile) {
                setAppLock(pkg, false)
            }
        }

        if (_activeProfileIdFlow.value == profile.id) {
            activateProfile(profile.id)
        }
    }

    fun deleteProfile(profileId: String) {
        val targetProfile = _profilesFlow.value.firstOrNull { it.id == profileId }
        val current = _profilesFlow.value.filterNot { it.id == profileId }
        _profilesFlow.value = current
        persistProfiles(current)

        if (targetProfile != null) {
            for (pkg in targetProfile.packages) {
                val inOtherProfile = current.any { it.packages.contains(pkg) }
                if (!inOtherProfile) {
                    setAppLock(pkg, false)
                }
            }
        }

        if (_activeProfileIdFlow.value == profileId) {
            _activeProfileIdFlow.value = "all"
            sharedPrefs.edit().putString(KEY_ACTIVE_PROFILE_ID, "all").apply()
        }
    }

    fun addAppToProfile(profileId: String, packageName: String) {
        val current = _profilesFlow.value.toMutableList()
        val index = current.indexOfFirst { it.id == profileId }
        if (index >= 0) {
            val prof = current[index]
            val updated = prof.copy(packages = prof.packages + packageName)
            current[index] = updated
            _profilesFlow.value = current
            persistProfiles(current)
            setAppLock(packageName, true)
        }
    }

    fun removeAppFromProfile(profileId: String, packageName: String) {
        val current = _profilesFlow.value.toMutableList()
        val index = current.indexOfFirst { it.id == profileId }
        if (index >= 0) {
            val prof = current[index]
            val updated = prof.copy(packages = prof.packages - packageName)
            current[index] = updated
            _profilesFlow.value = current
            persistProfiles(current)

            val inOtherProfile = current.any { it.packages.contains(packageName) }
            if (!inOtherProfile) {
                setAppLock(packageName, false)
            }
        }
    }

    fun activateProfile(profileId: String?) {
        val targetId = profileId ?: "all"
        _activeProfileIdFlow.value = targetId
        sharedPrefs.edit().putString(KEY_ACTIVE_PROFILE_ID, targetId).apply()

        if (targetId == "all") {
            return
        }

        val profile = _profilesFlow.value.firstOrNull { it.id == targetId } ?: return
        if (profile.packages.isNotEmpty()) {
            setMultipleAppLocks(profile.packages, true)
        }
        if (profile.isStealth) {
            setStealthCrashMode(true)
        }
        setTimeoutMode(profile.timeoutMode)
    }

    private fun persistProfiles(profiles: List<SecurityProfile>) {
        try {
            val array = JSONArray()
            profiles.forEach { array.put(it.toJson()) }
            sharedPrefs.edit().putString(KEY_PROFILES_JSON, array.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed persisting profiles: ${e.message}", e)
        }
    }
}
