package com.android.BioKey.core.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import com.android.BioKey.core.data.AppClassifier
import com.android.BioKey.core.data.LockTimeoutMode
import com.android.BioKey.core.data.SecurityProfile
import com.android.BioKey.feature.lock.AppInfoCache
import com.android.BioKey.feature.lock.InstantShieldOverlay
import com.android.BioKey.feature.notification.BioKeyNotificationShieldService
import com.android.BioKey.feature.tile.BioKeyTileService
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
import java.util.UUID
import java.util.concurrent.Executors

/**
 * BioKey's persistent configuration: which apps are locked, profiles, re-lock delays, pause
 * state and feature switches.
 *
 * Invariants, kept by [mutate]:
 * - every app in a profile is also in [lockedAppsFlow];
 * - an app is in at most one profile;
 * - Google Password Manager's two package names always travel together.
 *
 * Reads are lock-free (immutable snapshots); writes are serialised and persisted off the caller's
 * thread.
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
        private const val KEY_PROFILES_JSON = "security_profiles_json"
        private const val KEY_AUTO_SORT = "auto_sort_profiles_enabled"
        private const val KEY_AUTO_SORT_EXCLUDED = "auto_sort_excluded_packages"
        private const val KEY_SELF_PROTECTION = "accessibility_self_protection_enabled"

        /** A timed pause restored after a reboot never runs longer than this (wall clock can lie). */
        private const val MAX_RESTORED_PAUSE_MS = 60 * 60_000L

        private const val PKG_GMS_PASSWORDS = "com.google.android.gms.passwordmanager"
        private const val PKG_CREDENTIAL_MANAGER = "com.google.android.apps.credentialmanager"
        private val PASSWORD_MANAGER_ALIASES = setOf(PKG_GMS_PASSWORDS, PKG_CREDENTIAL_MANAGER)

        @Volatile
        private var INSTANCE: SecurityVault? = null

        fun getInstance(context: Context): SecurityVault =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: SecurityVault(context.applicationContext).also { INSTANCE = it }
            }

        private fun aliasesOf(pkg: String): Set<String> =
            if (pkg in PASSWORD_MANAGER_ALIASES) PASSWORD_MANAGER_ALIASES else setOf(pkg)

        private fun expand(packages: Collection<String>): Set<String> = packages.flatMapTo(HashSet(), ::aliasesOf)
    }

    private val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "BioKeyVault") }
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lock = Any()

    private val _lockedAppsFlow = MutableStateFlow<Set<String>>(emptySet())
    /** Every locked package, including those in profiles. */
    val lockedAppsFlow: StateFlow<Set<String>> = _lockedAppsFlow.asStateFlow()

    private val _profilesFlow = MutableStateFlow<List<SecurityProfile>>(emptyList())
    val profilesFlow: StateFlow<List<SecurityProfile>> = _profilesFlow.asStateFlow()

    private val _globalLockEnabledFlow = MutableStateFlow(true)
    val globalLockEnabledFlow: StateFlow<Boolean> = _globalLockEnabledFlow.asStateFlow()

    /** Wall-clock end of a timed pause (for display), or 0. */
    private val _pausedUntilFlow = MutableStateFlow(0L)
    val pausedUntilFlow: StateFlow<Long> = _pausedUntilFlow.asStateFlow()

    /** Re-lock delay for apps that aren't in a profile. */
    private val _timeoutModeFlow = MutableStateFlow(LockTimeoutMode.IMMEDIATE)
    val timeoutModeFlow: StateFlow<LockTimeoutMode> = _timeoutModeFlow.asStateFlow()

    private val _notificationShieldFlow = MutableStateFlow(true)
    val notificationShieldFlow: StateFlow<Boolean> = _notificationShieldFlow.asStateFlow()

    private val _setupCompletedFlow = MutableStateFlow(false)
    val setupCompletedFlow: StateFlow<Boolean> = _setupCompletedFlow.asStateFlow()

    private val _autoSortProfilesFlow = MutableStateFlow(true)
    val autoSortProfilesFlow: StateFlow<Boolean> = _autoSortProfilesFlow.asStateFlow()

    private val _accessibilitySelfProtectionFlow = MutableStateFlow(true)
    val accessibilitySelfProtectionFlow: StateFlow<Boolean> = _accessibilitySelfProtectionFlow.asStateFlow()

    /** Apps the owner took out of a profile by hand, which auto-sort must leave alone. */
    private val autoSortExcluded = HashSet<String>()

    /** The timed pause's end on the monotonic clock, or 0. Survives wall-clock changes. */
    @Volatile private var pausedUntilElapsed = 0L
    private var pauseExpiryJob: Job? = null

    /** Per-package re-lock delay for apps in a profile. */
    @Volatile private var profileTimeouts: Map<String, LockTimeoutMode> = emptyMap()

    init {
        load()
        AppInfoCache.preWarm(context, getLockedPackages())
    }

    // ---------------------------------------------------------------------------------------
    // Queries
    // ---------------------------------------------------------------------------------------

    fun isProtectionActive(): Boolean {
        if (_globalLockEnabledFlow.value) return true
        val until = pausedUntilElapsed
        if (until > 0L && SystemClock.elapsedRealtime() >= until) {
            resumeProtection()
            return true
        }
        return false
    }

    fun isGlobalLockEnabled(): Boolean = isProtectionActive()

    fun isAppLocked(packageName: String): Boolean = isProtectionActive() && packageName in _lockedAppsFlow.value

    fun getLockedPackages(): Set<String> = _lockedAppsFlow.value

    fun getTimeoutMode(packageName: String): LockTimeoutMode = profileTimeouts[packageName] ?: _timeoutModeFlow.value

    fun profileOf(packageName: String): SecurityProfile? =
        _profilesFlow.value.firstOrNull { packageName in it.packages }

    fun isNotificationShieldEnabled(): Boolean = _notificationShieldFlow.value
    fun isAccessibilitySelfProtectionEnabled(): Boolean = _accessibilitySelfProtectionFlow.value
    fun isSetupCompleted(): Boolean = _setupCompletedFlow.value
    fun isAutoSortProfilesEnabled(): Boolean = _autoSortProfilesFlow.value

    // ---------------------------------------------------------------------------------------
    // Locking apps and profiles
    // ---------------------------------------------------------------------------------------

    /**
     * Applies a change to the locked set and profiles, restores the invariants, then reacts to
     * what actually changed: apps that just became locked lose any session, apps that just
     * stopped being locked get their notifications back.
     */
    private fun mutate(change: (locked: MutableSet<String>, profiles: MutableList<SecurityProfile>) -> Unit) {
        synchronized(lock) {
            val before = _lockedAppsFlow.value
            val locked = before.toMutableSet()
            val profiles = _profilesFlow.value.toMutableList()
            change(locked, profiles)

            val seen = HashSet<String>()
            val cleanProfiles = profiles.map { p ->
                p.copy(packages = expand(p.packages).filterTo(HashSet()) { seen.add(it) })
            }
            locked += seen

            _profilesFlow.value = cleanProfiles
            _lockedAppsFlow.value = locked
            rebuildProfileTimeouts()

            val newlyLocked = locked - before
            val newlyUnlocked = before - locked
            newlyLocked.forEach(LockStateTracker::lockPackage)
            if (newlyLocked.isNotEmpty()) AppInfoCache.preWarm(context, newlyLocked)
            if (newlyUnlocked.isNotEmpty()) BioKeyNotificationShieldService.restoreNotificationsForPackages(newlyUnlocked)
            persist()
        }
    }

    private fun MutableList<SecurityProfile>.removePackages(targets: Set<String>) {
        replaceAll { p -> if (p.packages.any { it in targets }) p.copy(packages = p.packages - targets) else p }
    }

    private fun MutableList<SecurityProfile>.update(profileId: String, transform: (SecurityProfile) -> SecurityProfile) {
        val i = indexOfFirst { it.id == profileId }
        if (i >= 0) this[i] = transform(this[i])
    }

    /** Locks or unlocks an app. Unlocking also takes it out of its profile. */
    fun setAppLock(packageName: String, locked: Boolean) {
        val targets = aliasesOf(packageName)
        mutate { lockedSet, profiles ->
            if (locked) {
                lockedSet += targets
            } else {
                lockedSet -= targets
                profiles.removePackages(targets)
            }
        }
        synchronized(lock) { if (locked) autoSortExcluded -= targets else autoSortExcluded += targets }
    }

    /** Puts a locked app in [profileId], or in no profile when null. The app stays locked either way. */
    fun moveAppToProfile(packageName: String, profileId: String?) {
        val targets = aliasesOf(packageName)
        mutate { lockedSet, profiles ->
            lockedSet += targets
            profiles.removePackages(targets)
            if (profileId != null) profiles.update(profileId) { it.copy(packages = it.packages + targets) }
        }
        // Taken out of a profile by hand: auto-sort must not put it straight back.
        synchronized(lock) { if (profileId == null) autoSortExcluded += targets else autoSortExcluded -= targets }
    }

    /** Adds apps to a profile, moving them out of any other one. They become locked. */
    fun addAppsToProfile(profileId: String, packages: Collection<String>) {
        val targets = expand(packages)
        mutate { lockedSet, profiles ->
            lockedSet += targets
            profiles.removePackages(targets)
            profiles.update(profileId) { it.copy(packages = it.packages + targets) }
        }
        synchronized(lock) { autoSortExcluded -= targets }
    }

    fun createProfile(name: String, iconKey: String, timeoutMode: LockTimeoutMode): SecurityProfile {
        val profile = SecurityProfile(
            id = "profile_${UUID.randomUUID()}",
            name = name.trim().ifBlank { "New profile" },
            iconKey = iconKey,
            timeoutMode = timeoutMode
        )
        mutate { _, profiles -> profiles += profile }
        return profile
    }

    fun updateProfileDetails(profileId: String, name: String? = null, iconKey: String? = null, timeoutMode: LockTimeoutMode? = null) {
        mutate { _, profiles ->
            profiles.update(profileId) { p ->
                p.copy(
                    name = name?.trim()?.ifBlank { null } ?: p.name,
                    iconKey = iconKey ?: p.iconKey,
                    timeoutMode = timeoutMode ?: p.timeoutMode
                )
            }
        }
    }

    /** Deletes a profile. Its apps stay locked and use the default re-lock delay. */
    fun deleteProfile(profileId: String) {
        mutate { _, profiles -> profiles.removeAll { it.id == profileId } }
    }

    fun onPackageUninstalled(packageName: String) {
        val targets = aliasesOf(packageName)
        mutate { lockedSet, profiles ->
            lockedSet -= targets
            profiles.removePackages(targets)
        }
        synchronized(lock) { autoSortExcluded -= targets }
        targets.forEach(LockStateTracker::lockPackage)
    }

    // ---------------------------------------------------------------------------------------
    // Auto-sort
    // ---------------------------------------------------------------------------------------

    fun setAutoSortProfilesEnabled(enabled: Boolean) {
        synchronized(lock) {
            _autoSortProfilesFlow.value = enabled
            if (enabled) autoSortExcluded.clear()
            persist()
        }
    }

    fun autoSortSinglePackage(pkg: String, label: String, pm: PackageManager?) =
        autoSortAppsIntoProfiles(listOf(pkg to label), pm)

    /**
     * Puts recognised apps that aren't in any profile (and weren't taken out of one by hand) into
     * the matching built-in profile, which locks them.
     */
    fun autoSortAppsIntoProfiles(apps: List<Pair<String, String>>, pm: PackageManager?) {
        if (!_autoSortProfilesFlow.value || apps.isEmpty()) return
        val presets = _profilesFlow.value.mapTo(HashSet()) { it.id }
        val assignments = HashMap<String, MutableSet<String>>()
        synchronized(lock) {
            val assigned = _profilesFlow.value.flatMapTo(HashSet()) { it.packages }
            for ((pkg, label) in apps) {
                if (pkg == context.packageName) continue
                val aliases = aliasesOf(pkg)
                if (aliases.any { it in assigned || it in autoSortExcluded }) continue
                val playCategory = runCatching { pm?.getApplicationInfo(pkg, 0)?.category }.getOrNull() ?: -1
                val target = AppClassifier.presetProfileFor(AppClassifier.categorize(pkg, label, playCategory)) ?: continue
                if (target !in presets) continue
                assignments.getOrPut(target) { HashSet() } += aliases
                assigned += aliases
            }
        }
        if (assignments.isEmpty()) return
        mutate { lockedSet, profiles ->
            for ((profileId, pkgs) in assignments) {
                lockedSet += pkgs
                profiles.update(profileId) { it.copy(packages = it.packages + pkgs) }
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Protection on/off and preferences
    // ---------------------------------------------------------------------------------------

    fun setGlobalLockEnabled(enabled: Boolean) {
        if (enabled) resumeProtection() else pauseProtection(null)
    }

    /** Pauses protection, for [durationMillis] or until resumed when null. */
    fun pauseProtection(durationMillis: Long?): Boolean {
        synchronized(lock) {
            _globalLockEnabledFlow.value = false
            _pausedUntilFlow.value = durationMillis?.let { System.currentTimeMillis() + it } ?: 0L
            pausedUntilElapsed = durationMillis?.let { SystemClock.elapsedRealtime() + it } ?: 0L
            schedulePauseExpiry(durationMillis ?: 0L)
            persist()
        }
        InstantShieldOverlay.dismiss()
        BioKeyNotificationShieldService.restoreAllSnoozedNotifications()
        BioKeyTileService.requestTileUpdate(context)
        return true
    }

    fun resumeProtection() {
        synchronized(lock) {
            pauseExpiryJob?.cancel()
            pauseExpiryJob = null
            _globalLockEnabledFlow.value = true
            _pausedUntilFlow.value = 0L
            pausedUntilElapsed = 0L
            persist()
        }
        // Nothing unlocked during the pause carries over.
        LockStateTracker.lockAll(includeOwnApp = false)
        BioKeyTileService.requestTileUpdate(context)
    }

    private fun schedulePauseExpiry(durationMillis: Long) {
        pauseExpiryJob?.cancel()
        pauseExpiryJob = if (durationMillis <= 0L) null else mainScope.launch {
            delay(durationMillis)
            if (!_globalLockEnabledFlow.value) isProtectionActive()
        }
    }

    fun setTimeoutMode(mode: LockTimeoutMode) = setPreference { _timeoutModeFlow.value = mode }

    fun setNotificationShield(enabled: Boolean) {
        setPreference { _notificationShieldFlow.value = enabled }
        if (!enabled) BioKeyNotificationShieldService.restoreAllSnoozedNotifications()
    }

    fun setAccessibilitySelfProtectionEnabled(enabled: Boolean) {
        setPreference { _accessibilitySelfProtectionFlow.value = enabled }
        if (!enabled) LockStateTracker.clearSelfProtection()
    }

    /** Written synchronously: setup must never be lost to a crash right after finishing it. */
    fun setSetupCompletedSync(completed: Boolean) {
        synchronized(lock) {
            _setupCompletedFlow.value = completed
            prefs.edit().putBoolean(KEY_SETUP_COMPLETED, completed).commit()
        }
    }

    private inline fun setPreference(change: () -> Unit) {
        synchronized(lock) {
            change()
            persist()
        }
    }

    // ---------------------------------------------------------------------------------------
    // Persistence
    // ---------------------------------------------------------------------------------------

    private fun rebuildProfileTimeouts() {
        profileTimeouts = buildMap { for (p in _profilesFlow.value) for (pkg in p.packages) put(pkg, p.timeoutMode) }
    }

    private fun load() {
        val profiles = prefs.getString(KEY_PROFILES_JSON, null)?.let { json ->
            runCatching {
                val array = JSONArray(json)
                List(array.length()) { SecurityProfile.fromJson(array.getJSONObject(it)) }
            }.getOrNull()
        } ?: SecurityProfile.DEFAULTS
        // Enforce the invariants on whatever was stored.
        val seen = HashSet<String>()
        _profilesFlow.value = profiles.map { p -> p.copy(packages = expand(p.packages).filterTo(HashSet()) { seen.add(it) }) }
        _lockedAppsFlow.value = expand(prefs.getStringSet(KEY_LOCKED, emptySet()).orEmpty()) + seen
        autoSortExcluded += prefs.getStringSet(KEY_AUTO_SORT_EXCLUDED, emptySet()).orEmpty()
        rebuildProfileTimeouts()

        _timeoutModeFlow.value = LockTimeoutMode.fromName(prefs.getString(KEY_TIMEOUT, null))
        _notificationShieldFlow.value = prefs.getBoolean(KEY_NOTIFICATION_SHIELD, true)
        _setupCompletedFlow.value = prefs.getBoolean(KEY_SETUP_COMPLETED, false)
        _accessibilitySelfProtectionFlow.value = prefs.getBoolean(KEY_SELF_PROTECTION, true)
        _autoSortProfilesFlow.value = prefs.getBoolean(KEY_AUTO_SORT, true)

        // A pause survives a reboot only while its time is left (capped, as the wall clock can lie).
        val enabled = prefs.getBoolean(KEY_GLOBAL, true)
        val pausedWall = prefs.getLong(KEY_PAUSED_UNTIL, 0L)
        val remaining = pausedWall - System.currentTimeMillis()
        when {
            enabled -> _globalLockEnabledFlow.value = true
            pausedWall == 0L -> _globalLockEnabledFlow.value = false // paused until turned back on
            remaining <= 0L -> {
                _globalLockEnabledFlow.value = true
                persist()
            }
            else -> {
                val left = minOf(remaining, MAX_RESTORED_PAUSE_MS)
                _globalLockEnabledFlow.value = false
                _pausedUntilFlow.value = System.currentTimeMillis() + left
                pausedUntilElapsed = SystemClock.elapsedRealtime() + left
                schedulePauseExpiry(left)
            }
        }
    }

    /** Snapshot now, write on the vault's own thread. */
    private fun persist() {
        val locked = _lockedAppsFlow.value
        val excluded = synchronized(lock) { autoSortExcluded.toSet() }
        val profilesJson = JSONArray().apply { _profilesFlow.value.forEach { put(it.toJson()) } }.toString()
        val global = _globalLockEnabledFlow.value
        val pausedWall = _pausedUntilFlow.value
        val timeout = _timeoutModeFlow.value.name
        val shield = _notificationShieldFlow.value
        val selfProtection = _accessibilitySelfProtectionFlow.value
        val setup = _setupCompletedFlow.value
        val autoSort = _autoSortProfilesFlow.value
        io.execute {
            prefs.edit()
                .putStringSet(KEY_LOCKED, locked)
                .putStringSet(KEY_AUTO_SORT_EXCLUDED, excluded)
                .putString(KEY_PROFILES_JSON, profilesJson)
                .putBoolean(KEY_GLOBAL, global)
                .putLong(KEY_PAUSED_UNTIL, pausedWall)
                .putString(KEY_TIMEOUT, timeout)
                .putBoolean(KEY_NOTIFICATION_SHIELD, shield)
                .putBoolean(KEY_SELF_PROTECTION, selfProtection)
                .putBoolean(KEY_SETUP_COMPLETED, setup)
                .putBoolean(KEY_AUTO_SORT, autoSort)
                .apply()
        }
    }
}
