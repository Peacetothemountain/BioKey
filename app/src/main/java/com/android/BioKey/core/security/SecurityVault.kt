package com.android.BioKey.core.security

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.SystemClock
import com.android.BioKey.core.data.LockTimeoutMode
import com.android.BioKey.core.data.SecurityProfile
import com.android.BioKey.feature.lock.InstantShieldOverlay
import com.android.BioKey.feature.notification.BioKeyNotificationShieldService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Lean, high-performance SecurityVault for BioKey.
 * Stores locked packages, profiles, timeouts, and protection states directly in SharedPreferences.
 */
class SecurityVault private constructor(private val context: Context) {

    companion object {
        private const val PREF_NAME = "biokey_vault"
        private const val KEY_LOCKED = "locked_apps_set"
        private const val KEY_GLOBAL = "global_lock_enabled"
        private const val KEY_TIMEOUT = "timeout_mode"
        private const val KEY_NOTIFICATION_SHIELD = "notification_shield_enabled"
        private const val KEY_SETUP_COMPLETED = "setup_completed"
        private const val KEY_PAUSED_UNTIL = "paused_until_wall"
        private const val KEY_PAUSED_ELAPSED = "paused_until_elapsed"
        const val PROFILE_ALL = "all"
        private const val KEY_PROFILES_JSON = "security_profiles_json"
        private const val KEY_AUTO_SORT = "auto_sort_profiles_enabled"
        private const val KEY_AUTO_SORT_EXCLUDED = "auto_sort_excluded_packages"
        private const val KEY_SELF_PROTECTION = "accessibility_self_protection_enabled"
        private const val MAX_RESTORED_PAUSE_MS = 60 * 60_000L

        private const val PKG_GMS_PASSWORDS = "com.google.android.gms.passwordmanager"
        private const val PKG_CREDENTIAL_MANAGER = "com.google.android.apps.credentialmanager"
        private val PASSWORD_MANAGER_ALIASES = setOf(PKG_GMS_PASSWORDS, PKG_CREDENTIAL_MANAGER)

        @Volatile
        private var INSTANCE: SecurityVault? = null

        fun getInstance(context: Context): SecurityVault {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SecurityVault(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val sharedPrefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "BioKeyVault") }
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pauseExpiryJob: Job? = null

    private val lockedAppsSet = ConcurrentHashMap.newKeySet<String>()
    private val autoSortExcludedSet = ConcurrentHashMap.newKeySet<String>()

    private val _lockedAppsFlow = MutableStateFlow<Set<String>>(emptySet())
    val lockedAppsFlow: StateFlow<Set<String>> = _lockedAppsFlow.asStateFlow()

    private val _globalLockEnabledFlow = MutableStateFlow(true)
    val globalLockEnabledFlow: StateFlow<Boolean> = _globalLockEnabledFlow.asStateFlow()

    private val _pausedUntilFlow = MutableStateFlow(0L)
    val pausedUntilFlow: StateFlow<Long> = _pausedUntilFlow.asStateFlow()

    private val _timeoutModeFlow = MutableStateFlow(LockTimeoutMode.IMMEDIATE)
    val timeoutModeFlow: StateFlow<LockTimeoutMode> = _timeoutModeFlow.asStateFlow()

    private val _notificationShieldFlow = MutableStateFlow(true)
    val notificationShieldFlow: StateFlow<Boolean> = _notificationShieldFlow.asStateFlow()

    private val _setupCompletedFlow = MutableStateFlow(false)
    val setupCompletedFlow: StateFlow<Boolean> = _setupCompletedFlow.asStateFlow()

    private val _profilesFlow = MutableStateFlow<List<SecurityProfile>>(emptyList())
    val profilesFlow: StateFlow<List<SecurityProfile>> = _profilesFlow.asStateFlow()

    private val _autoSortProfilesFlow = MutableStateFlow(true)
    val autoSortProfilesFlow: StateFlow<Boolean> = _autoSortProfilesFlow.asStateFlow()

    private val _accessibilitySelfProtectionFlow = MutableStateFlow(true)
    val accessibilitySelfProtectionFlow: StateFlow<Boolean> = _accessibilitySelfProtectionFlow.asStateFlow()

    @Volatile private var pausedUntilElapsed: Long = 0L
    private val stateLock = Any()

    private data class LookupSnapshot(
        val effectiveLockedPackages: Set<String> = emptySet(),
        val effectiveTimeoutByPackage: Map<String, LockTimeoutMode> = emptyMap(),
        val defaultTimeoutMode: LockTimeoutMode = LockTimeoutMode.IMMEDIATE
    )

    @Volatile private var snapshot = LookupSnapshot()

    init {
        val savedLocked = sharedPrefs.getStringSet(KEY_LOCKED, emptySet()).orEmpty()
        lockedAppsSet.addAll(savedLocked)

        val savedExcluded = sharedPrefs.getStringSet(KEY_AUTO_SORT_EXCLUDED, emptySet()).orEmpty()
        autoSortExcludedSet.addAll(savedExcluded)

        val global = sharedPrefs.getBoolean(KEY_GLOBAL, true)
        val pausedWall = sharedPrefs.getLong(KEY_PAUSED_UNTIL, 0L)
        val nowElapsed = SystemClock.elapsedRealtime()
        val nowWall = System.currentTimeMillis()
        val rawRemainingWall = pausedWall - nowWall

        if (!global && pausedWall > 0L && rawRemainingWall <= 0L) {
            // Expired while phone was asleep or rebooted
            _globalLockEnabledFlow.value = true
            _pausedUntilFlow.value = 0L
            pausedUntilElapsed = 0L
            sharedPrefs.edit().putBoolean(KEY_GLOBAL, true).putLong(KEY_PAUSED_UNTIL, 0L).apply()
        } else if (!global && pausedWall > 0L && rawRemainingWall > 0L) {
            // Still within timed pause after reboot (capped at MAX_RESTORED_PAUSE_MS against clock tampering)
            val remainingWall = minOf(rawRemainingWall, MAX_RESTORED_PAUSE_MS)
            _globalLockEnabledFlow.value = false
            _pausedUntilFlow.value = nowWall + remainingWall
            pausedUntilElapsed = nowElapsed + remainingWall
            schedulePauseExpiry(remainingWall)
        } else {
            _globalLockEnabledFlow.value = global
            _pausedUntilFlow.value = pausedWall
            pausedUntilElapsed = 0L
        }

        _timeoutModeFlow.value = LockTimeoutMode.fromName(sharedPrefs.getString(KEY_TIMEOUT, null))
        _notificationShieldFlow.value = sharedPrefs.getBoolean(KEY_NOTIFICATION_SHIELD, true)
        _setupCompletedFlow.value = sharedPrefs.getBoolean(KEY_SETUP_COMPLETED, false)
        _accessibilitySelfProtectionFlow.value = sharedPrefs.getBoolean(KEY_SELF_PROTECTION, true)

        val profilesJson = sharedPrefs.getString(KEY_PROFILES_JSON, null)

        val loadedProfiles = if (!profilesJson.isNullOrBlank()) {
            runCatching {
                val array = JSONArray(profilesJson)
                (0 until array.length()).map { SecurityProfile.fromJson(array.getJSONObject(it)) }
            }.getOrDefault(defaultProfiles())
        } else {
            defaultProfiles()
        }
        // Ensure each package belongs to at most one profile and sync with lockedAppsSet
        val seenInProfile = HashSet<String>()
        val dedupedProfiles = loadedProfiles.map { profile ->
            val uniquePkgs = profile.packages.filter { pkg -> seenInProfile.add(pkg) }.toSet()
            profile.copy(packages = uniquePkgs)
        }
        _profilesFlow.value = dedupedProfiles
        for (profile in dedupedProfiles) {
            for (pkg in profile.packages) {
                lockedAppsSet.addAll(aliasesOf(pkg))
            }
        }
        _lockedAppsFlow.value = lockedAppsSet.toSet()

        _autoSortProfilesFlow.value = sharedPrefs.getBoolean(KEY_AUTO_SORT, true)
        rebuildSnapshot()
        com.android.BioKey.feature.lock.AppInfoCache.preWarm(context, getLockedPackages())
    }

    private fun schedulePauseExpiry(durationMillis: Long) {
        pauseExpiryJob?.cancel()
        if (durationMillis <= 0L) {
            pauseExpiryJob = null
            return
        }
        pauseExpiryJob = mainScope.launch {
            delay(durationMillis + 50L)
            if (!_globalLockEnabledFlow.value && pausedUntilElapsed > 0L && SystemClock.elapsedRealtime() >= pausedUntilElapsed) {
                resumeProtection("Pause expired")
            }
        }
    }

    private fun defaultProfiles(): List<SecurityProfile> = listOf(
        SecurityProfile(id = "profile_personal", name = "Personal", iconKey = "shield", isPreset = true),
        SecurityProfile(id = "profile_finance", name = "Finance", iconKey = "finance", isPreset = true, timeoutMode = LockTimeoutMode.IMMEDIATE),
        SecurityProfile(id = "profile_social", name = "Social", iconKey = "social", isPreset = true),
        SecurityProfile(id = "profile_work", name = "Work", iconKey = "work", isPreset = true, timeoutMode = LockTimeoutMode.GRACE_5_MINUTES)
    )

    private fun aliasesOf(pkg: String): Set<String> =
        if (pkg in PASSWORD_MANAGER_ALIASES) PASSWORD_MANAGER_ALIASES else setOf(pkg)

    private fun rebuildSnapshot() {
        val profiles = _profilesFlow.value
        val globalDefaultTimeout = _timeoutModeFlow.value

        val lockedSet = HashSet<String>()
        val timeoutMap = HashMap<String, LockTimeoutMode>()

        for (pkg in lockedAppsSet) {
            lockedSet.addAll(aliasesOf(pkg))
        }
        for (profile in profiles) {
            for (pkg in profile.packages) {
                for (alias in aliasesOf(pkg)) {
                    lockedSet.add(alias)
                    timeoutMap[alias] = profile.timeoutMode
                }
            }
        }

        snapshot = LookupSnapshot(
            effectiveLockedPackages = lockedSet,
            effectiveTimeoutByPackage = timeoutMap,
            defaultTimeoutMode = globalDefaultTimeout
        )
    }

    /**
     * Checks whether [packageName] (or any of its credential aliases) is assigned to ANY profile.
     */
    fun isAppInAnyProfile(packageName: String): Boolean {
        val targets = aliasesOf(packageName)
        return _profilesFlow.value.any { profile ->
            profile.packages.any { it in targets }
        }
    }

    /**
     * Returns all [SecurityProfile] instances that contain [packageName] or its aliases.
     */
    fun getProfilesForApp(packageName: String): List<SecurityProfile> {
        val targets = aliasesOf(packageName)
        return _profilesFlow.value.filter { profile ->
            profile.packages.any { it in targets }
        }
    }

    /**
     * Returns all locked packages across the master locked set and all profiles.
     */
    fun getLockedPackages(): Set<String> {
        val result = HashSet<String>(lockedAppsSet)
        for (profile in _profilesFlow.value) {
            result.addAll(profile.packages)
        }
        return result
    }

    /**
     * Atomically purges [packageName] and its aliases from all profiles.
     * Returns true if any profile was mutated.
     */
    fun removeAppFromAllProfiles(packageName: String): Boolean = synchronized(stateLock) {
        val targets = aliasesOf(packageName)
        val prevEffective = snapshot.effectiveLockedPackages
        var modified = false
        val current = _profilesFlow.value
        val updated = current.map { profile ->
            val remaining = profile.packages.filterNot { it in targets }.toSet()
            if (remaining.size != profile.packages.size) {
                modified = true
                profile.copy(packages = remaining)
            } else {
                profile
            }
        }
        if (modified) {
            _profilesFlow.value = updated
            autoSortExcludedSet.addAll(targets)
            rebuildSnapshot()
            val newlyUnlocked = prevEffective - snapshot.effectiveLockedPackages
            if (newlyUnlocked.isNotEmpty()) {
                BioKeyNotificationShieldService.restoreNotificationsForPackages(newlyUnlocked)
            }
            persist()
        }
        modified
    }

    /**
     * Removes an uninstalled package from the master lock set and all profiles.
     */
    fun onPackageUninstalled(packageName: String) = synchronized(stateLock) {
        val targets = aliasesOf(packageName)
        val removedFromMaster = lockedAppsSet.removeAll(targets)
        autoSortExcludedSet.removeAll(targets)
        var profilesModified = false
        val updatedProfiles = _profilesFlow.value.map { profile ->
            val remaining = profile.packages.filterNot { it in targets }.toSet()
            if (remaining.size != profile.packages.size) {
                profilesModified = true
                profile.copy(packages = remaining)
            } else {
                profile
            }
        }
        if (removedFromMaster || profilesModified) {
            if (removedFromMaster) _lockedAppsFlow.value = lockedAppsSet.toSet()
            if (profilesModified) _profilesFlow.value = updatedProfiles
            rebuildSnapshot()
            targets.forEach(LockStateTracker::lockPackage)
            BioKeyNotificationShieldService.restoreNotificationsForPackages(targets)
            persist()
        }
    }

    fun isAppLocked(packageName: String): Boolean {
        if (!isProtectionActive()) return false
        return snapshot.effectiveLockedPackages.contains(packageName)
    }

    fun isGlobalLockEnabled(): Boolean = isProtectionActive()

    fun isProtectionActive(): Boolean {
        if (_globalLockEnabledFlow.value) return true
        // Check if pause expired
        if (pausedUntilElapsed > 0L && SystemClock.elapsedRealtime() >= pausedUntilElapsed) {
            resumeProtection("Pause expired")
            return true
        }
        return false
    }

    fun getTimeoutMode(packageName: String? = null): LockTimeoutMode {
        val s = snapshot
        if (packageName != null) {
            return s.effectiveTimeoutByPackage[packageName] ?: s.defaultTimeoutMode
        }
        return s.defaultTimeoutMode
    }

    fun isNotificationShieldEnabled(): Boolean = _notificationShieldFlow.value

    fun isAccessibilitySelfProtectionEnabled(): Boolean = _accessibilitySelfProtectionFlow.value

    fun setAccessibilitySelfProtectionEnabled(enabled: Boolean): Boolean = synchronized(stateLock) {
        _accessibilitySelfProtectionFlow.value = enabled
        if (!enabled) {
            LockStateTracker.clearSelfProtection()
        }
        persist()
        true
    }

    fun isSetupCompleted(): Boolean = _setupCompletedFlow.value

    fun getPausedUntil(): Long = _pausedUntilFlow.value

    fun pauseProtection(durationMillis: Long?): Boolean = synchronized(stateLock) {
        _globalLockEnabledFlow.value = false
        val nowWall = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtime()

        val pausedWall = durationMillis?.let { nowWall + it } ?: 0L
        pausedUntilElapsed = durationMillis?.let { nowElapsed + it } ?: 0L
        _pausedUntilFlow.value = pausedWall
        schedulePauseExpiry(durationMillis ?: 0L)

        InstantShieldOverlay.dismiss()
        BioKeyNotificationShieldService.restoreAllSnoozedNotifications()
        persist()
        com.android.BioKey.feature.tile.BioKeyTileService.requestTileUpdate(context)
        true
    }

    fun resumeProtection(reason: String = "Resumed by owner") = synchronized(stateLock) {
        pauseExpiryJob?.cancel()
        pauseExpiryJob = null
        _globalLockEnabledFlow.value = true
        _pausedUntilFlow.value = 0L
        pausedUntilElapsed = 0L
        LockStateTracker.lockAll(includeOwnApp = false)
        persist()
        com.android.BioKey.feature.tile.BioKeyTileService.requestTileUpdate(context)
    }

    fun setAppLock(packageName: String, locked: Boolean): Boolean = synchronized(stateLock) {
        val targets = aliasesOf(packageName)
        if (locked) {
            lockedAppsSet.addAll(targets)
            autoSortExcludedSet.removeAll(targets)
            com.android.BioKey.feature.lock.AppInfoCache.preWarm(context, targets)
        } else {
            lockedAppsSet.removeAll(targets)
            autoSortExcludedSet.addAll(targets)
            // Master revocation: toggling OFF purges app from all profiles
            val currentProfiles = _profilesFlow.value
            var profilesModified = false
            val updatedProfiles = currentProfiles.map { profile ->
                val remaining = profile.packages.filterNot { it in targets }.toSet()
                if (remaining.size != profile.packages.size) {
                    profilesModified = true
                    profile.copy(packages = remaining)
                } else {
                    profile
                }
            }
            if (profilesModified) {
                _profilesFlow.value = updatedProfiles
            }
        }
        _lockedAppsFlow.value = lockedAppsSet.toSet()
        rebuildSnapshot()
        targets.forEach(LockStateTracker::lockPackage)
        if (!locked) {
            BioKeyNotificationShieldService.restoreNotificationsForPackages(targets)
        }
        persist()
        true
    }

    fun setMultipleAppLocks(packages: Collection<String>, locked: Boolean): Boolean = synchronized(stateLock) {
        val targets = packages.flatMap(::aliasesOf).toSet()
        if (locked) {
            lockedAppsSet.addAll(targets)
            autoSortExcludedSet.removeAll(targets)
            com.android.BioKey.feature.lock.AppInfoCache.preWarm(context, targets)
        } else {
            lockedAppsSet.removeAll(targets)
            autoSortExcludedSet.addAll(targets)
            val currentProfiles = _profilesFlow.value
            var profilesModified = false
            val updatedProfiles = currentProfiles.map { profile ->
                val remaining = profile.packages.filterNot { it in targets }.toSet()
                if (remaining.size != profile.packages.size) {
                    profilesModified = true
                    profile.copy(packages = remaining)
                } else {
                    profile
                }
            }
            if (profilesModified) {
                _profilesFlow.value = updatedProfiles
            }
        }
        _lockedAppsFlow.value = lockedAppsSet.toSet()
        rebuildSnapshot()
        targets.forEach(LockStateTracker::lockPackage)
        if (!locked) {
            BioKeyNotificationShieldService.restoreNotificationsForPackages(targets)
        }
        persist()
        true
    }

    fun setGlobalLockEnabled(enabled: Boolean): Boolean {
        if (enabled) resumeProtection() else pauseProtection(null)
        return true
    }

    fun setTimeoutMode(mode: LockTimeoutMode): Boolean = synchronized(stateLock) {
        _timeoutModeFlow.value = mode
        rebuildSnapshot()
        persist()
        true
    }

    fun setNotificationShield(enabled: Boolean): Boolean = synchronized(stateLock) {
        _notificationShieldFlow.value = enabled
        if (!enabled) {
            BioKeyNotificationShieldService.restoreAllSnoozedNotifications()
        }
        persist()
        true
    }

    fun setSetupCompleted(completed: Boolean) = synchronized(stateLock) {
        _setupCompletedFlow.value = completed
        persist()
    }

    fun setSetupCompletedSync(completed: Boolean) = synchronized(stateLock) {
        _setupCompletedFlow.value = completed
        sharedPrefs.edit().putBoolean(KEY_SETUP_COMPLETED, completed).commit()
    }

    /** Creates an empty profile and returns it. */
    fun createProfile(name: String, iconKey: String, timeoutMode: LockTimeoutMode = LockTimeoutMode.IMMEDIATE): SecurityProfile {
        val profile = SecurityProfile(
            id = "profile_${java.util.UUID.randomUUID()}",
            name = name.trim().ifBlank { "New profile" },
            iconKey = iconKey,
            timeoutMode = timeoutMode
        )
        saveProfile(profile)
        return profile
    }

    /** Renames / re-icons / re-times a profile without touching its apps. */
    fun updateProfileDetails(profileId: String, name: String? = null, iconKey: String? = null, timeoutMode: LockTimeoutMode? = null) =
        synchronized(stateLock) {
            val profile = _profilesFlow.value.firstOrNull { it.id == profileId } ?: return@synchronized
            saveProfile(
                profile.copy(
                    name = name?.trim()?.ifBlank { profile.name } ?: profile.name,
                    iconKey = iconKey ?: profile.iconKey,
                    timeoutMode = timeoutMode ?: profile.timeoutMode
                )
            )
        }

    fun saveProfile(profile: SecurityProfile): Boolean = synchronized(stateLock) {
        val prevEffective = snapshot.effectiveLockedPackages
        val current = _profilesFlow.value
        val existingProfile = current.firstOrNull { it.id == profile.id }
        val expandedPackages = profile.packages.flatMap(::aliasesOf).toSet()
        val normalizedProfile = profile.copy(packages = expandedPackages)

        val removedFromThisProfile = existingProfile?.packages.orEmpty() - expandedPackages
        // Ensure packages added to this profile are removed from any other profile
        val updated = if (existingProfile != null) {
            current.map { p ->
                if (p.id == normalizedProfile.id) {
                    normalizedProfile
                } else {
                    val cleaned = p.packages - expandedPackages
                    if (cleaned.size != p.packages.size) p.copy(packages = cleaned) else p
                }
            }
        } else {
            current.map { p ->
                val cleaned = p.packages - expandedPackages
                if (cleaned.size != p.packages.size) p.copy(packages = cleaned) else p
            } + normalizedProfile
        }

        _profilesFlow.value = updated
        if (expandedPackages.isNotEmpty()) {
            lockedAppsSet.addAll(expandedPackages)
            autoSortExcludedSet.removeAll(expandedPackages)
        }
        if (removedFromThisProfile.isNotEmpty()) {
            val stillInOtherProfiles = updated.any { p -> p.packages.any { it in removedFromThisProfile } }
            if (!stillInOtherProfiles) {
                lockedAppsSet.removeAll(removedFromThisProfile)
            }
            autoSortExcludedSet.addAll(removedFromThisProfile)
        }
        _lockedAppsFlow.value = lockedAppsSet.toSet()

        rebuildSnapshot()
        val nowEffective = snapshot.effectiveLockedPackages
        val newlyUnlocked = prevEffective - nowEffective
        val newlyLocked = nowEffective - prevEffective
        if (newlyLocked.isNotEmpty()) {
            newlyLocked.forEach(LockStateTracker::lockPackage)
        }
        if (newlyUnlocked.isNotEmpty()) {
            BioKeyNotificationShieldService.restoreNotificationsForPackages(newlyUnlocked)
        }
        if (expandedPackages.isNotEmpty()) {
            com.android.BioKey.feature.lock.AppInfoCache.preWarm(context, expandedPackages)
        }
        persist()
        true
    }


    /** Deletes a profile. Its apps stay locked and fall back to the default re-lock delay. */
    fun deleteProfile(profileId: String) = synchronized(stateLock) {
        val deletedProfile = _profilesFlow.value.firstOrNull { it.id == profileId } ?: return@synchronized
        _profilesFlow.value = _profilesFlow.value.filterNot { it.id == profileId }
        lockedAppsSet.addAll(deletedProfile.packages)
        _lockedAppsFlow.value = lockedAppsSet.toSet()
        rebuildSnapshot()
        persist()
    }

    /** Adds apps to a profile (moving them out of any other one). They become locked. */
    fun addAppsToProfile(profileId: String, packages: Collection<String>) = synchronized(stateLock) {
        val profile = _profilesFlow.value.firstOrNull { it.id == profileId } ?: return@synchronized
        saveProfile(profile.copy(packages = profile.packages + packages.flatMap(::aliasesOf)))
    }

    /**
     * Puts a locked app in [profileId], or in no profile when null. The app stays locked either way.
     */
    fun moveAppToProfile(packageName: String, profileId: String?) = synchronized(stateLock) {
        val targets = aliasesOf(packageName)
        if (profileId != null) {
            addAppsToProfile(profileId, targets)
            return@synchronized
        }
        lockedAppsSet.addAll(targets)
        _lockedAppsFlow.value = lockedAppsSet.toSet()
        _profilesFlow.value = _profilesFlow.value.map { p ->
            if (p.packages.any { it in targets }) p.copy(packages = p.packages - targets) else p
        }
        // Taken out of a profile by hand: auto-sort must not put it straight back.
        autoSortExcludedSet.addAll(targets)
        rebuildSnapshot()
        persist()
    }

    fun profileOf(packageName: String): SecurityProfile? {
        val targets = aliasesOf(packageName)
        return _profilesFlow.value.firstOrNull { p -> p.packages.any { it in targets } }
    }


    fun isAutoSortProfilesEnabled(): Boolean = _autoSortProfilesFlow.value

    fun setAutoSortProfilesEnabled(enabled: Boolean) = synchronized(stateLock) {
        _autoSortProfilesFlow.value = enabled
        if (enabled) {
            autoSortExcludedSet.clear()
        }
        persist()
    }

    fun autoSortSinglePackage(pkg: String, label: String, pm: PackageManager? = null) {
        if (!_autoSortProfilesFlow.value) return
        autoSortAppsIntoProfiles(listOf(pkg to label), pm, force = false)
    }

    fun autoSortAppsIntoProfiles(
        appList: List<Pair<String, String>>,
        pm: PackageManager? = null,
        force: Boolean = false
    ) = synchronized(stateLock) {
        if (!_autoSortProfilesFlow.value || appList.isEmpty() || _profilesFlow.value.isEmpty()) return@synchronized

        val financeWords = listOf(
            "bank", "crypto", "wallet", "pay", "cash", "venmo", "paypal", "zelle", "chime", "wise",
            "revolut", "robinhood", "coinbase", "binance", "kraken", "metamask", "trustwallet",
            "phantom", "ledger", "fidelity", "vanguard", "schwab", "etrade", "webull", "acorns",
            "stash", "mint", "ynab", "credit", "karma", "experian", "equifax", "transunion",
            "amex", "chase", "wellsfargo", "citi", "capitalone", "discover", "barclays", "hsbc",
            "santander", "ally", "sofi", "affirm", "klarna", "afterpay", "stripe", "square",
            "turbotax", "irs", "finance", "invest", "trading", "stock", "money"
        )
        val socialWords = listOf(
            "whatsapp", "telegram", "signal", "messenger", "instagram", "facebook", "threads",
            "twitter", "x.android", "snapchat", "tiktok", "musically", "discord", "reddit",
            "pinterest", "tumblr", "linkedin", "mastodon", "bluesky", "wechat", "line", "viber",
            "kakaotalk", "hinge", "tinder", "bumble", "grindr", "badoo", "okcupid", "match",
            "messages", "messaging", "chat"
        )
        val workWords = listOf(
            "authenticator", "authy", "2fas", "totp", "bitwarden", "1password", "dashlane",
            "lastpass", "keepass", "enpass", "nordpass", "protonpass", "roboform", "keeper",
            "yubico", "okta", "duo", "passwordmanager", "credentialmanager",
            "slack", "teams", "zoom", "webex", "meet", "gmail", "outlook", "protonmail",
            "fastmail", "spark", "superhuman", "thunderbird", "mail", "email", "calendar",
            "drive", "docs", "sheets", "slides", "onedrive", "dropbox", "notion", "obsidian",
            "evernote", "onenote", "trello", "asana", "jira", "confluence", "linear", "monday",
            "clickup", "basecamp", "github", "gitlab", "bitbucket", "salesforce", "hubspot",
            "zendesk", "workday", "adp", "gusto", "quickbooks", "xero", "freshbooks",
            "docusign", "acrobat", "camscanner", "office", "word", "excel", "powerpoint"
        )
        val personalWords = listOf(
            "photo", "gallery", "camera", "album", "files", "filemanager", "documents",
            "downloads", "explorer", "solidexplorer", "mixplorer", "vault", "private", "secret",
            "locker", "notes", "keep", "diary", "journal", "dayone", "health", "garmin", "strava",
            "flo", "clue", "myfitnesspal", "medical", "patient", "mychart", "pharmacy",
            "walgreens", "cvs", "chrome", "firefox", "brave", "duckduckgo", "opera", "vivaldi",
            "edge", "tor", "vpn", "protonvpn", "nordvpn", "expressvpn", "mullvad", "tailscale", "wireguard"
        )

        val currentProfiles = _profilesFlow.value
        val alreadyAssigned = HashSet<String>()
        for (p in currentProfiles) {
            alreadyAssigned.addAll(p.packages)
        }

        val buckets = mapOf(
            "finance" to mutableSetOf<String>(),
            "social" to mutableSetOf<String>(),
            "work" to mutableSetOf<String>(),
            "shield" to mutableSetOf<String>()
        )

        val uniqueApps = appList.distinctBy { it.first }
        for ((pkg, label) in uniqueApps) {
            if (pkg == context.packageName) continue
            val aliases = aliasesOf(pkg)
            if (!force && aliases.any { it in autoSortExcludedSet }) continue
            if (aliases.any { it in alreadyAssigned }) continue

            val category = runCatching { pm?.getApplicationInfo(pkg, 0)?.category }.getOrNull() ?: -1
            val haystack = "${pkg.lowercase()} ${label.lowercase()}"
            val targetBucket = when {
                financeWords.any(haystack::contains) -> "finance"
                category == ApplicationInfo.CATEGORY_SOCIAL || socialWords.any(haystack::contains) -> "social"
                category == ApplicationInfo.CATEGORY_PRODUCTIVITY || workWords.any(haystack::contains) -> "work"
                category == ApplicationInfo.CATEGORY_IMAGE || personalWords.any(haystack::contains) -> "shield"
                else -> null
            }
            if (targetBucket != null) {
                buckets.getValue(targetBucket).addAll(aliases)
                alreadyAssigned.addAll(aliases)
            }
        }

        var modified = false
        val seenAcrossProfiles = HashSet<String>()
        val updated = currentProfiles.map { profile ->
            val key = if (profile.isPreset) {
                when (profile.id) {
                    "profile_finance" -> "finance"
                    "profile_social" -> "social"
                    "profile_work" -> "work"
                    "profile_personal" -> "shield"
                    else -> null
                }
            } else null
            val additions = key?.let { buckets[it] }.orEmpty()
            val combined = (profile.packages + additions)
                .flatMap(::aliasesOf)
                .filter { seenAcrossProfiles.add(it) }
                .toSet()
            if (combined != profile.packages) {
                modified = true
                profile.copy(packages = combined)
            } else {
                profile
            }
        }

        var lockedChanged = false
        for (profile in updated) {
            for (pkg in profile.packages) {
                if (lockedAppsSet.addAll(aliasesOf(pkg))) {
                    lockedChanged = true
                }
            }
        }

        if (modified || lockedChanged) {
            if (modified) _profilesFlow.value = updated
            if (lockedChanged) _lockedAppsFlow.value = lockedAppsSet.toSet()
            rebuildSnapshot()
            com.android.BioKey.feature.lock.AppInfoCache.preWarm(context, getLockedPackages())
            persist()
        }
    }

    private fun persist() {
        io.execute {
            synchronized(stateLock) {
                val currentLocked = lockedAppsSet.toSet()
                val currentExcluded = autoSortExcludedSet.toSet()
                val global = _globalLockEnabledFlow.value
                val pausedWall = _pausedUntilFlow.value
                val timeout = _timeoutModeFlow.value.name
                val notif = _notificationShieldFlow.value
                val selfProt = _accessibilitySelfProtectionFlow.value
                val setup = _setupCompletedFlow.value
                val profilesJson = JSONArray().apply {
                    _profilesFlow.value.forEach { put(it.toJson()) }
                }.toString()
                val autoSort = _autoSortProfilesFlow.value

                sharedPrefs.edit()
                    .putStringSet(KEY_LOCKED, currentLocked)
                    .putStringSet(KEY_AUTO_SORT_EXCLUDED, currentExcluded)
                    .putBoolean(KEY_GLOBAL, global)
                    .putLong(KEY_PAUSED_UNTIL, pausedWall)
                    .putString(KEY_TIMEOUT, timeout)
                    .putBoolean(KEY_NOTIFICATION_SHIELD, notif)
                    .putBoolean(KEY_SELF_PROTECTION, selfProt)
                    .putBoolean(KEY_SETUP_COMPLETED, setup)
                    .putString(KEY_PROFILES_JSON, profilesJson)
                    .putBoolean(KEY_AUTO_SORT, autoSort)
                    .apply()
            }
        }
    }
}
