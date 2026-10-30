package com.android.BioKey.core.security

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import androidx.annotation.VisibleForTesting
import androidx.core.content.edit
import com.android.BioKey.BuildConfig
import com.android.BioKey.R
import com.android.BioKey.core.data.AppCategory
import com.android.BioKey.core.data.AppClassifier
import com.android.BioKey.core.data.LockTimeoutMode
import com.android.BioKey.core.data.PasswordManager
import com.android.BioKey.core.data.SecurityProfile
import com.android.BioKey.core.diagnostics.DiagnosticLog
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
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
import org.json.JSONObject

/**
 * BioKey's persistent configuration: which apps are locked, profiles, re-lock delays, pause
 * state and feature switches.
 *
 * Invariants, kept by [mutate]:
 * - an app is in [lockedAppsFlow] when it's in [selectedAppsFlow] and its profile, if any, is on;
 * - an app is in at most one profile;
 * - Google Password Manager's two package names always travel together.
 *
 * Reads are lock-free (immutable snapshots); writes are serialised and persisted off the caller's
 * thread.
 */
class SecurityVault private constructor(private val context: Context) {

    /**
     * What the rest of BioKey does as the vault changes. The features that act on it register
     * ([addListener], from BioKeyApplication), so storage never drives a window, a cache or a
     * service itself.
     */
    interface Listener {
        /** These apps are locked (all of them as the vault loads, or newly): worth having ready. */
        fun onLocked(context: Context, packages: Collection<String>) {}
        /** These apps aren't locked any more. */
        fun onUnlocked(packages: Collection<String>) {}
        /** Protection paused: nothing should stay covered. */
        fun onPaused() {}
        /** "Hide notification content" off (switched, or restored off): nothing stays hidden. */
        fun onNotificationShieldOff() {}
    }

    companion object {
        private val listeners = java.util.concurrent.CopyOnWriteArrayList<Listener>()

        /** Registers [listener] for the vault's changes; registering again does nothing. */
        fun addListener(listener: Listener) {
            listeners.addIfAbsent(listener)
        }

        @VisibleForTesting
        internal fun removeListener(listener: Listener) {
            listeners.remove(listener)
        }

        @VisibleForTesting
        internal fun isListening(listener: Listener) = listener in listeners

        /**
         * Whether this build has self-protection at all. The Play build doesn't (see
         * app/build.gradle.kts): its switch is hidden, and a saved or restored "on" reads as off.
         */
        val selfProtectionAvailable: Boolean
            // A constant in release builds, so R8 drops what only self-protection uses.
            get() = BuildConfig.SELF_PROTECTION && !selfProtectionRemovedForTests

        /** Tests of the Play build's behaviour, in a debug build. */
        @VisibleForTesting
        internal var selfProtectionRemovedForTests = false

        private const val PREF_NAME = "biokey_vault"
        private const val KEY_LOCKED = "locked_apps_set"
        private const val KEY_GLOBAL = "global_lock_enabled"
        private const val KEY_TIMEOUT = "timeout_mode"
        private const val KEY_NOTIFICATION_SHIELD = "notification_shield_enabled"
        /** Locked apps and BioKey ask for a face or fingerprint only, never the screen lock. */
        private const val KEY_BIOMETRIC_ONLY = "biometric_only"
        private const val KEY_SETUP_COMPLETED = "setup_completed"
        private const val KEY_PAUSED_UNTIL = "paused_until_wall"
        /** A timed pause's end on the monotonic clock, valid only within [KEY_PAUSED_BOOT]. */
        private const val KEY_PAUSED_UNTIL_ELAPSED = "paused_until_elapsed"
        private const val KEY_PAUSED_BOOT = "paused_boot_count"
        private const val KEY_PAUSE_DURATION = "pause_duration_ms"
        private const val KEY_PROFILES_JSON = "security_profiles_json"
        private const val KEY_AUTO_SORT = "auto_sort_profiles_enabled"
        private const val KEY_AUTO_SORT_EXCLUDED = "auto_sort_excluded_packages"
        private const val KEY_SELF_PROTECTION = "accessibility_self_protection_enabled"
        /** 2: profile membership no longer implies locked; [KEY_LOCKED] holds each app's own switch. */
        private const val KEY_MODEL_VERSION = "lock_model_version"
        private const val MODEL_VERSION = 3

        /** A timed pause restored after a reboot never runs longer than this (wall clock can lie). */
        private const val MAX_RESTORED_PAUSE_MS = 60 * 60_000L

        /**
         * Every profile id starts with this (the presets, [createProfile]). Anything else in stored
         * or restored data is replaced, so ids can't clash with the screens' own list keys.
         */
        private const val PROFILE_ID_PREFIX = "profile_"
        private const val MAX_PROFILES = 64
        private const val MAX_PROFILE_NAME = 40
        private const val MAX_PACKAGES = 2_000
        private val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")

        @SuppressLint("StaticFieldLeak") // Holds only the application context, which lives as long as the process.
        @Volatile
        private var INSTANCE: SecurityVault? = null

        fun getInstance(context: Context): SecurityVault =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: SecurityVault(context.applicationContext).also { INSTANCE = it }
            }

        /** The next [getInstance] reads storage afresh, as after a restart. */
        @VisibleForTesting
        internal fun resetForTests() {
            // Its writes still queued would otherwise land in the next test's storage.
            INSTANCE?.awaitPendingWrites()
            INSTANCE = null
        }
    }

    private val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "BioKeyVault").apply { isDaemon = true } }
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lock = Any()

    private val _selectedAppsFlow = MutableStateFlow<Set<String>>(emptySet())
    /** Apps whose own lock switch is on, whether or not their profile is on. */
    val selectedAppsFlow: StateFlow<Set<String>> = _selectedAppsFlow.asStateFlow()

    private val _lockedAppsFlow = MutableStateFlow<Set<String>>(emptySet())
    /** Apps actually locked: their own switch is on and their profile, if any, is on. */
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

    private val _notificationShieldFlow = MutableStateFlow(false)
    val notificationShieldFlow: StateFlow<Boolean> = _notificationShieldFlow.asStateFlow()
    private val _biometricOnlyFlow = MutableStateFlow(false)
    /** The owner's choice; [SecurityEngine] applies it only while a strong face or fingerprint is set up. */
    val biometricOnlyFlow: StateFlow<Boolean> = _biometricOnlyFlow.asStateFlow()

    private val _setupCompletedFlow = MutableStateFlow(false)
    val setupCompletedFlow: StateFlow<Boolean> = _setupCompletedFlow.asStateFlow()

    private val _autoSortProfilesFlow = MutableStateFlow(true)
    val autoSortProfilesFlow: StateFlow<Boolean> = _autoSortProfilesFlow.asStateFlow()

    private val _accessibilitySelfProtectionFlow = MutableStateFlow(false)
    val accessibilitySelfProtectionFlow: StateFlow<Boolean> = _accessibilitySelfProtectionFlow.asStateFlow()

    /** Apps the owner took out of a profile by hand, which auto-sort must leave alone. Written under [lock]. */
    @Volatile private var autoSortExcluded: Set<String> = emptySet()

    /** The timed pause's end on the monotonic clock, or 0. Survives wall-clock changes. */
    @Volatile private var pausedUntilElapsed = 0L
    /** How long the timed pause was chosen for, or 0: after a reboot it never runs longer. */
    @Volatile private var pauseDurationMs = 0L
    private var pauseExpiryJob: Job? = null

    /** Which boot this is: [pausedUntilElapsed] means nothing in any other. -1 if unknown. */
    private val bootCount: Int by lazy {
        runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1) }.getOrDefault(-1)
    }

    /** Per-package re-lock delay for apps in a profile. */
    @Volatile private var profileTimeouts: Map<String, LockTimeoutMode> = emptyMap()

    init {
        load()
        LockStateTracker.timeoutModeOf = ::getTimeoutMode
        listeners.forEach { it.onLocked(context, getLockedPackages()) }
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

    fun isAppLocked(packageName: String): Boolean = isProtectionActive() && packageName in _lockedAppsFlow.value

    fun getLockedPackages(): Set<String> = _lockedAppsFlow.value

    /** The re-lock delay [packageName] follows: its profile's own, or the default in Settings. */
    fun getTimeoutMode(packageName: String): LockTimeoutMode = profileTimeouts[packageName] ?: _timeoutModeFlow.value

    fun profileOf(packageName: String): SecurityProfile? =
        _profilesFlow.value.firstOrNull { packageName in it.packages }

    fun isNotificationShieldEnabled(): Boolean = _notificationShieldFlow.value

    fun isBiometricOnly(): Boolean = _biometricOnlyFlow.value

    fun setBiometricOnly(enabled: Boolean) = setPreference { _biometricOnlyFlow.value = enabled }
    fun isAccessibilitySelfProtectionEnabled(): Boolean = _accessibilitySelfProtectionFlow.value
    fun isSetupCompleted(): Boolean = _setupCompletedFlow.value
    fun isAutoSortProfilesEnabled(): Boolean = _autoSortProfilesFlow.value

    // ---------------------------------------------------------------------------------------
    // Locking apps and profiles
    // ---------------------------------------------------------------------------------------

    /**
     * Applies a change to the app switches and profiles, restores the invariants, then reacts to
     * what actually changed: apps that just became locked lose any session, apps that just
     * stopped being locked get their notifications back.
     *
     * [change] runs under [lock], before the save, so anything else it sets (auto-sort exclusions,
     * preferences) is saved with it. Must not be called holding [lock]: the reactions run after it
     * is released, as they are slow (notification rescans) and would block every other write.
     */
    private fun mutate(change: (selected: MutableSet<String>, profiles: MutableList<SecurityProfile>) -> Unit) {
        val newlyLocked: Set<String>
        val newlyUnlocked: Set<String>
        synchronized(lock) {
            val before = _lockedAppsFlow.value
            val selected = _selectedAppsFlow.value.toMutableSet()
            val profiles = _profilesFlow.value.toMutableList()
            change(selected, profiles)

            val cleanProfiles = cleanProfiles(profiles)
            val clean = PasswordManager.expand(selected)
            val locked = effectiveLocks(clean, cleanProfiles)
            newlyLocked = locked - before
            newlyUnlocked = before - locked

            // An old session must not outlive a lock switched back on: end it before anything
            // can see the app locked.
            LockStateTracker.endSessions(newlyLocked, "lock switched on")
            _profilesFlow.value = cleanProfiles
            _selectedAppsFlow.value = clean
            _lockedAppsFlow.value = locked
            rebuildProfileTimeouts()
            persist()
        }
        if (newlyLocked.isNotEmpty()) {
            LockStateTracker.notifyLocked(newlyLocked)
            listeners.forEach { it.onLocked(context, newlyLocked) }
        }
        if (newlyUnlocked.isNotEmpty()) listeners.forEach { it.onUnlocked(newlyUnlocked) }
    }

    private fun MutableList<SecurityProfile>.removePackages(targets: Set<String>) {
        replaceAll { p -> if (p.packages.any { it in targets }) p.copy(packages = p.packages - targets) else p }
    }

    private fun MutableList<SecurityProfile>.update(profileId: String, transform: (SecurityProfile) -> SecurityProfile) {
        val i = indexOfFirst { it.id == profileId }
        if (i >= 0) this[i] = transform(this[i])
    }

    /** An app's own lock switch. It stays in its profile either way. */
    fun setAppLock(packageName: String, locked: Boolean) {
        val targets = PasswordManager.aliasesOf(packageName)
        mutate { selected, _ -> if (locked) selected += targets else selected -= targets }
    }

    /** A profile's own switch: off, none of its apps are locked; on, each follows its own switch. */
    fun setProfileEnabled(profileId: String, enabled: Boolean) {
        mutate { _, profiles -> profiles.update(profileId) { it.copy(enabled = enabled) } }
    }

    /**
     * Puts an app in [profileId], switching its lock on (unless [switchOn] is false, as when undoing
     * a removal), or takes it out of its profile when null, leaving its lock switch as it is.
     */
    fun moveAppToProfile(packageName: String, profileId: String?, switchOn: Boolean = true) {
        val targets = PasswordManager.aliasesOf(packageName)
        mutate { selected, profiles ->
            if (profileId != null && switchOn) selected += targets
            profiles.removePackages(targets)
            if (profileId != null) profiles.update(profileId) { it.copy(packages = it.packages + targets) }
            // Taken out of a profile by hand: auto-sort must not put it straight back.
            autoSortExcluded = if (profileId == null) autoSortExcluded + targets else autoSortExcluded - targets
        }
    }

    /** Takes an app out of its profile. Its own lock switch stays as it is; auto-sort leaves it alone. */
    fun removeAppFromProfile(packageName: String) = moveAppToProfile(packageName, null)

    /** Adds apps to a profile, moving them out of any other one, and switches their locks on. */
    fun addAppsToProfile(profileId: String, packages: Collection<String>) {
        val targets = PasswordManager.expand(packages)
        mutate { selected, profiles ->
            selected += targets
            profiles.removePackages(targets)
            profiles.update(profileId) { it.copy(packages = it.packages + targets) }
            autoSortExcluded = autoSortExcluded - targets
        }
    }

    /** A new profile follows the default re-lock delay in Settings unless given its own [timeoutMode]. */
    fun createProfile(name: String, iconKey: String, timeoutMode: LockTimeoutMode? = null): SecurityProfile {
        val profile = SecurityProfile(
            id = "$PROFILE_ID_PREFIX${UUID.randomUUID()}",
            name = name.trim().ifBlank { context.getString(R.string.profile_default_name) },
            iconKey = iconKey,
            timeoutMode = timeoutMode
        )
        mutate { _, profiles -> profiles += profile }
        return profile
    }

    fun updateProfileDetails(profileId: String, name: String? = null, iconKey: String? = null) {
        mutate { _, profiles ->
            profiles.update(profileId) { p ->
                p.copy(name = name?.trim()?.ifBlank { null } ?: p.name, iconKey = iconKey ?: p.iconKey)
            }
        }
    }

    /** Gives a profile its own re-lock delay, or with null, has it follow the default in Settings. */
    fun setProfileTimeout(profileId: String, timeoutMode: LockTimeoutMode?) {
        mutate { _, profiles -> profiles.update(profileId) { it.copy(timeoutMode = timeoutMode) } }
    }

    /** A deleted profile, where it was in the list, and which of its apps were switched on: what an undo needs. */
    class DeletedProfile internal constructor(val profile: SecurityProfile, val index: Int, val switchedOn: Set<String>)

    /**
     * Deletes a profile and switches its apps off: they go with it. Auto-sort leaves them alone as
     * if each had been taken out by hand: it would otherwise file them straight into a built-in
     * profile and switch their locks back on. Returns what [restoreDeletedProfile] needs, or null
     * if there was no such profile.
     */
    fun deleteProfile(profileId: String): DeletedProfile? {
        var deleted: DeletedProfile? = null
        mutate { selected, profiles ->
            val index = profiles.indexOfFirst { it.id == profileId }
            if (index < 0) return@mutate
            val profile = profiles.removeAt(index)
            deleted = DeletedProfile(profile, index, profile.packages.filterTo(HashSet()) { it in selected })
            selected -= profile.packages
            autoSortExcluded = autoSortExcluded + profile.packages
        }
        return deleted
    }

    /** Undoes [deleteProfile]: the profile is back where it was, with its apps and their switches. */
    fun restoreDeletedProfile(deleted: DeletedProfile) {
        mutate { selected, profiles ->
            if (profiles.any { it.id == deleted.profile.id }) return@mutate
            profiles.removePackages(deleted.profile.packages)
            profiles.add(deleted.index.coerceAtMost(profiles.size), deleted.profile)
            selected += deleted.switchedOn
            autoSortExcluded = autoSortExcluded - deleted.profile.packages
        }
    }

    /**
     * [packageName] is gone from every profile of the phone (the caller checks: a work-profile
     * copy or an archived app keeps its data, and so its lock).
     */
    fun onPackageUninstalled(packageName: String) {
        val targets = PasswordManager.aliasesOf(packageName)
        // Google Password Manager is still there in Play services' settings without its own app.
        if (packageName !in PasswordManager.ALIASES) {
            mutate { selected, profiles ->
                selected -= targets
                profiles.removePackages(targets)
                autoSortExcluded = autoSortExcluded - targets
            }
        }
        LockStateTracker.lockPackages(targets, "uninstalled")
    }

    // ---------------------------------------------------------------------------------------
    // Backup ([VaultBackup])
    // ---------------------------------------------------------------------------------------

    /** Everything a backup holds: app switches, profiles and preferences; never sessions or a pause. */
    fun exportState(): JSONObject = synchronized(lock) {
        JSONObject()
            .put("selected", JSONArray(_selectedAppsFlow.value.sorted()))
            .put("profiles", JSONArray().apply { _profilesFlow.value.forEach { put(it.toJson()) } })
            .put("defaultTimeout", _timeoutModeFlow.value.name)
            .put("modelVersion", MODEL_VERSION)
            .put("notificationShield", _notificationShieldFlow.value)
            .put("biometricOnly", _biometricOnlyFlow.value)
            .put("selfProtection", _accessibilitySelfProtectionFlow.value)
            .put("autoSort", _autoSortProfilesFlow.value)
            .put("autoSortExcluded", JSONArray(autoSortExcluded.sorted()))
            // The two switches kept beside the vault: their choices, not what they recorded.
            .put("unlockAttemptAlerts", UnlockAttempts.enabled.value)
            // Never answered: the new phone asks too, rather than taking the backup's "off" as an answer.
            .put("unlockAttemptAlertsChosen", UnlockAttempts.chosen.value)
            .put("screenLockUnlocks", UnlockAttempts.screenLockUnlocks.value)
            .put("newAppOffers", NewAppSuggestions.enabled.value)
            .put("diagnosticLog", DiagnosticLog.isEnabled())
    }

    /**
     * Replaces app switches, profiles and preferences with a backup's ([exportState]). Throws
     * [IllegalArgumentException], changing nothing, if it isn't shaped like one; odd contents
     * (repeated profile ids, things that aren't package names) are repaired as on [load].
     */
    fun restoreState(state: JSONObject) {
        fun strings(key: String): Set<String> {
            val array = state.optJSONArray(key) ?: return emptySet()
            return buildSet { for (i in 0 until array.length()) add(array.getString(i)) }
        }
        val profiles = state.optJSONArray("profiles")?.let { array ->
            List(array.length()) { i ->
                SecurityProfile.fromJson(array.optJSONObject(i) ?: throw IllegalArgumentException("A profile in the backup isn't one"))
            }
        } ?: throw IllegalArgumentException("No profiles in backup")
        val selected = validPackages(strings("selected"))
        val excluded = PasswordManager.expand(validPackages(strings("autoSortExcluded")))
        // On only if the backup says so and it's already agreed to on this phone (the explanation
        // comes before notification access, here, not on the phone the backup came from).
        val shield = state.optBoolean("notificationShield", false) && _notificationShieldFlow.value
        val selfProtection = state.optBoolean("selfProtection", false) && selfProtectionAvailable
        mutate { sel, profs ->
            sel.clear()
            sel += selected
            profs.clear()
            // A backup from before profiles followed Settings: its untouched built-in profiles do now.
            profs += sanitizeProfiles(profiles).let { if (state.optInt("modelVersion", 2) < 3) followSettings(it) else it }
            // Set with the locks, so what reacts to them (the notification shield) follows these.
            autoSortExcluded = excluded
            _timeoutModeFlow.value = LockTimeoutMode.fromName(state.optString("defaultTimeout"))
            _notificationShieldFlow.value = shield
            _biometricOnlyFlow.value = state.optBoolean("biometricOnly", _biometricOnlyFlow.value)
            _accessibilitySelfProtectionFlow.value = selfProtection
            _autoSortProfilesFlow.value = state.optBoolean("autoSort", true)
        }
        // As their own switches would.
        if (!shield) listeners.forEach { it.onNotificationShieldOff() }
        if (!selfProtection) LockStateTracker.clearSelfProtection()
        // Older backups don't say: what's set on this phone stays. Records here are never deleted by a restore.
        if (state.has("unlockAttemptAlerts") && state.optBoolean("unlockAttemptAlertsChosen", true)) UnlockAttempts.setEnabled(context, state.optBoolean("unlockAttemptAlerts"), keepRecords = true)
        if (state.has("screenLockUnlocks")) UnlockAttempts.setScreenLockUnlocks(context, state.optBoolean("screenLockUnlocks"))
        if (state.has("newAppOffers")) NewAppSuggestions.setEnabled(context, state.optBoolean("newAppOffers"))
        DiagnosticLog.log("Settings restored from a backup")
        // Last, so a log switched off by the restore isn't left holding that line.
        if (state.has("diagnosticLog") && state.optBoolean("diagnosticLog") != DiagnosticLog.isEnabled()) {
            DiagnosticLog.setEnabled(context, state.optBoolean("diagnosticLog"))
        }
    }

    // ---------------------------------------------------------------------------------------
    // Auto-sort
    // ---------------------------------------------------------------------------------------

    fun setAutoSortProfilesEnabled(enabled: Boolean) {
        synchronized(lock) {
            _autoSortProfilesFlow.value = enabled
            if (enabled) autoSortExcluded = emptySet()
            persist()
        }
    }

    /**
     * Puts recognised apps that aren't in any profile (and weren't taken out of one by hand) into
     * the matching built-in profile, which locks them. [apps] come with their
     * [AppClassifier.categorize] category, so nothing here asks the package manager.
     */
    fun autoSortAppsIntoProfiles(apps: List<Pair<String, AppCategory>>) {
        if (!_autoSortProfilesFlow.value || apps.isEmpty()) return
        // Usually nothing is new: find that out without taking the lock or saving anything.
        if (autoSortPlacements(apps, _profilesFlow.value, _selectedAppsFlow.value).isEmpty()) return
        mutate { selected, profiles ->
            // Decided again under the lock, so a change made meanwhile (an app just taken out of
            // its profile, auto-sort just turned off) wins.
            if (!_autoSortProfilesFlow.value) return@mutate
            for ((profileId, pkgs) in autoSortPlacements(apps, profiles, selected)) {
                selected += pkgs
                profiles.update(profileId) { it.copy(packages = it.packages + pkgs) }
            }
        }
    }

    /** Which built-in profile (by id) each of [apps] that auto-sort may place goes in, given [profiles]. */
    private fun autoSortPlacements(
        apps: List<Pair<String, AppCategory>>,
        profiles: List<SecurityProfile>,
        selected: Set<String>
    ): Map<String, Set<String>> {
        val profileIds = profiles.mapTo(HashSet()) { it.id }
        val offIds = profiles.filterNot { it.enabled }.mapTo(HashSet()) { it.id }
        val assigned = profiles.flatMapTo(HashSet()) { it.packages }
        val excluded = autoSortExcluded
        val placements = HashMap<String, MutableSet<String>>()
        for ((pkg, category) in apps) {
            if (pkg == context.packageName) continue
            val aliases = PasswordManager.aliasesOf(pkg)
            if (aliases.any { it in assigned || it in excluded }) continue
            val target = AppClassifier.presetProfileFor(category)?.takeIf { it in profileIds } ?: continue
            // Locked by the owner on its own: filing it into a profile that's off would unlock it.
            if (target in offIds && aliases.any { it in selected }) continue
            placements.getOrPut(target) { HashSet() } += aliases
            assigned += aliases
        }
        return placements
    }

    // ---------------------------------------------------------------------------------------
    // Protection on/off and preferences
    // ---------------------------------------------------------------------------------------

    fun setGlobalLockEnabled(enabled: Boolean) {
        if (enabled) resumeProtection() else pauseProtection(null)
    }

    /** Pauses protection, for [durationMillis] or until resumed when null. */
    fun pauseProtection(durationMillis: Long?): Boolean {
        DiagnosticLog.log(if (durationMillis == null) "Protection paused until turned back on" else "Protection paused for ${durationMillis / 60_000} min")
        synchronized(lock) {
            _globalLockEnabledFlow.value = false
            _pausedUntilFlow.value = durationMillis?.let { System.currentTimeMillis() + it } ?: 0L
            pausedUntilElapsed = durationMillis?.let { SystemClock.elapsedRealtime() + it } ?: 0L
            pauseDurationMs = durationMillis ?: 0L
            schedulePauseExpiry(durationMillis ?: 0L)
            persist()
        }
        listeners.forEach { it.onPaused() }
        return true
    }

    fun resumeProtection() {
        synchronized(lock) {
            pauseExpiryJob?.cancel()
            pauseExpiryJob = null
            _globalLockEnabledFlow.value = true
            _pausedUntilFlow.value = 0L
            pausedUntilElapsed = 0L
            pauseDurationMs = 0L
            persist()
        }
        // Nothing unlocked during the pause carries over.
        LockStateTracker.lockAll(includeOwnApp = false, reason = "protection resumed")
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
        if (!enabled) listeners.forEach { it.onNotificationShieldOff() }
    }

    fun setAccessibilitySelfProtectionEnabled(enabled: Boolean) {
        val on = enabled && selfProtectionAvailable
        setPreference { _accessibilitySelfProtectionFlow.value = on }
        if (!on) LockStateTracker.clearSelfProtection()
    }

    /**
     * Saved before this returns: setup must never be lost to a crash right after finishing it.
     * The save goes through the vault's own thread, after any queued before it, so none of those
     * older snapshots can land on top of it.
     */
    fun setSetupCompletedSync(completed: Boolean) {
        val saved = synchronized(lock) {
            _setupCompletedFlow.value = completed
            persist()
        }
        runCatching { saved.get() }
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

    /** Profile memberships expanded to aliases, each app in at most one profile (the first). */
    private fun cleanProfiles(profiles: List<SecurityProfile>): List<SecurityProfile> {
        val seen = HashSet<String>()
        return profiles.map { p -> p.copy(packages = PasswordManager.expand(p.packages).filterTo(HashSet()) { seen.add(it) }) }
    }

    /** Switched-on apps whose profile, if they have one, is on. */
    private fun effectiveLocks(selected: Set<String>, profiles: List<SecurityProfile>): Set<String> {
        val off = profiles.filterNot { it.enabled }.flatMapTo(HashSet()) { it.packages }
        return selected.filterTo(HashSet()) { it !in off }
    }

    private fun rebuildProfileTimeouts() {
        profileTimeouts = buildMap { for (p in _profilesFlow.value) p.timeoutMode?.let { mode -> for (pkg in p.packages) put(pkg, mode) } }
    }

    /**
     * Built-in profiles still on the re-lock delays they came with (before version 3) follow the
     * default in Settings: otherwise the default reaches almost no app, as auto-sort files nearly
     * every app into one of them.
     */
    private fun followSettings(profiles: List<SecurityProfile>): List<SecurityProfile> = profiles.map { p ->
        if (p.timeoutMode != null && SecurityProfile.FORMER_PRESET_TIMEOUTS[p.id] == p.timeoutMode) p.copy(timeoutMode = null) else p
    }

    /** What looks like a package name, but not BioKey's own, at most [MAX_PACKAGES]. */
    private fun validPackages(packages: Collection<String>): Set<String> =
        packages.asSequence().filter { it != context.packageName && PACKAGE_NAME.matches(it) }.take(MAX_PACKAGES).toSet()

    /**
     * Stored or restored profiles made safe to show: each id unique and prefixed (a repeated id
     * crashes the screens' lists), names of a sane length, only package names inside.
     */
    private fun sanitizeProfiles(profiles: List<SecurityProfile>): List<SecurityProfile> {
        val ids = HashSet<String>()
        return profiles.take(MAX_PROFILES).map { p ->
            val id = p.id.takeIf { it.length > PROFILE_ID_PREFIX.length && it.startsWith(PROFILE_ID_PREFIX) && ids.add(it) }
                ?: "$PROFILE_ID_PREFIX${UUID.randomUUID()}".also { ids.add(it) }
            p.copy(id = id, name = p.name.trim().take(MAX_PROFILE_NAME).ifBlank { "Profile" }, packages = validPackages(p.packages))
        }
    }

    private fun load() {
        val stored = prefs.getString(KEY_PROFILES_JSON, null)?.let { json ->
            runCatching {
                val array = JSONArray(json)
                List(array.length()) { SecurityProfile.fromJson(array.getJSONObject(it)) }
            }.getOrNull()
        } ?: SecurityProfile.DEFAULTS
        // Enforce the invariants on whatever was stored.
        val version = prefs.getInt(KEY_MODEL_VERSION, 1)
        val migrating = version < MODEL_VERSION
        val clean = cleanProfiles(sanitizeProfiles(stored)).let { if (version < 3) followSettings(it) else it }
        var selected = PasswordManager.expand(validPackages(prefs.getStringSet(KEY_LOCKED, emptySet()).orEmpty()))
        // Before version 2 every app in a profile was locked: keep each of them switched on.
        if (version < 2) selected = selected + clean.flatMap { it.packages }
        _profilesFlow.value = clean
        _selectedAppsFlow.value = selected
        _lockedAppsFlow.value = effectiveLocks(selected, clean)
        autoSortExcluded = PasswordManager.expand(validPackages(prefs.getStringSet(KEY_AUTO_SORT_EXCLUDED, emptySet()).orEmpty()))
        rebuildProfileTimeouts()

        _timeoutModeFlow.value = LockTimeoutMode.fromName(prefs.getString(KEY_TIMEOUT, null))
        // Off until the owner agrees to it in BioKey: granting notification access elsewhere doesn't switch it on.
        _notificationShieldFlow.value = prefs.getBoolean(KEY_NOTIFICATION_SHIELD, false)
        _biometricOnlyFlow.value = prefs.getBoolean(KEY_BIOMETRIC_ONLY, false)
        _setupCompletedFlow.value = prefs.getBoolean(KEY_SETUP_COMPLETED, false)
        // Opt-in (Play policy: no blocking of uninstall/disable unless the user chose it).
        // Off in a build without it, even if an earlier build saved it on.
        _accessibilitySelfProtectionFlow.value = prefs.getBoolean(KEY_SELF_PROTECTION, false) && selfProtectionAvailable
        _autoSortProfilesFlow.value = prefs.getBoolean(KEY_AUTO_SORT, true)

        // A timed pause survives a restart only while its time is left.
        val enabled = prefs.getBoolean(KEY_GLOBAL, true)
        val pausedWall = prefs.getLong(KEY_PAUSED_UNTIL, 0L)
        var pauseExpired = false
        var pauseRestored = false
        when {
            enabled -> _globalLockEnabledFlow.value = true
            pausedWall == 0L -> _globalLockEnabledFlow.value = false // paused until turned back on
            else -> {
                val left = restoredPauseLeft(pausedWall)
                if (left <= 0L) {
                    _globalLockEnabledFlow.value = true
                    pauseExpired = true
                } else {
                    _globalLockEnabledFlow.value = false
                    _pausedUntilFlow.value = System.currentTimeMillis() + left
                    pausedUntilElapsed = SystemClock.elapsedRealtime() + left
                    pauseDurationMs = prefs.getLong(KEY_PAUSE_DURATION, 0L)
                    schedulePauseExpiry(left)
                    // Saved with this boot's monotonic end, so a later restart in it is exact
                    // rather than trusting the wall clock again.
                    pauseRestored = true
                }
            }
        }
        // Only now is everything read: saving any earlier would write defaults over the owner's
        // settings (setup, self-protection, re-lock delay, auto-sort and its exclusions).
        if (migrating || pauseExpired || pauseRestored || clean != stored) persist()
    }

    /**
     * How much of a stored timed pause is left. Within the same boot the monotonic end is exact.
     * After a reboot only the wall clock is left, which can lie (set back while paused), so the
     * pause never runs longer than it was chosen for, nor [MAX_RESTORED_PAUSE_MS].
     */
    private fun restoredPauseLeft(pausedWall: Long): Long {
        val chosen = prefs.getLong(KEY_PAUSE_DURATION, 0L).takeIf { it > 0L } ?: MAX_RESTORED_PAUSE_MS
        val elapsedEnd = prefs.getLong(KEY_PAUSED_UNTIL_ELAPSED, 0L)
        if (bootCount >= 0 && elapsedEnd > 0L && prefs.getInt(KEY_PAUSED_BOOT, -1) == bootCount) {
            // Capped too: should the boot count not move on a reboot, an end from the last boot's
            // clock could be hours away on this one's.
            return minOf(elapsedEnd - SystemClock.elapsedRealtime(), chosen)
        }
        return minOf(pausedWall - System.currentTimeMillis(), chosen, MAX_RESTORED_PAUSE_MS)
    }

    /**
     * Snapshot now, write on the vault's own thread: committed there, so once it has run the
     * change is on disk. The returned future completes then.
     */
    private fun persist(): Future<*> {
        val selected = _selectedAppsFlow.value
        val excluded = autoSortExcluded
        val profilesJson = JSONArray().apply { _profilesFlow.value.forEach { put(it.toJson()) } }.toString()
        val global = _globalLockEnabledFlow.value
        val pausedWall = _pausedUntilFlow.value
        val pausedElapsed = pausedUntilElapsed
        val pauseDuration = pauseDurationMs
        val timeout = _timeoutModeFlow.value.name
        val shield = _notificationShieldFlow.value
        val biometricOnly = _biometricOnlyFlow.value
        val selfProtection = _accessibilitySelfProtectionFlow.value
        val setup = _setupCompletedFlow.value
        val autoSort = _autoSortProfilesFlow.value
        return io.submit {
            prefs.edit(commit = true) {
                putStringSet(KEY_LOCKED, selected)
                putInt(KEY_MODEL_VERSION, MODEL_VERSION)
                putStringSet(KEY_AUTO_SORT_EXCLUDED, excluded)
                putString(KEY_PROFILES_JSON, profilesJson)
                putBoolean(KEY_GLOBAL, global)
                putLong(KEY_PAUSED_UNTIL, pausedWall)
                putLong(KEY_PAUSED_UNTIL_ELAPSED, pausedElapsed)
                putInt(KEY_PAUSED_BOOT, bootCount)
                putLong(KEY_PAUSE_DURATION, pauseDuration)
                putString(KEY_TIMEOUT, timeout)
                putBoolean(KEY_NOTIFICATION_SHIELD, shield)
                putBoolean(KEY_BIOMETRIC_ONLY, biometricOnly)
                putBoolean(KEY_SELF_PROTECTION, selfProtection)
                putBoolean(KEY_SETUP_COMPLETED, setup)
                putBoolean(KEY_AUTO_SORT, autoSort)
            }
        }
    }

    /** Waits until every write queued so far is on disk. */
    @VisibleForTesting
    internal fun awaitPendingWrites() {
        io.submit {}.get()
    }
}
