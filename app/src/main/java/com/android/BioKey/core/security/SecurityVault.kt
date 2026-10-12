package com.android.BioKey.core.security

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import android.provider.Settings
import android.text.format.DateFormat
import android.util.Base64
import android.util.Log
import androidx.biometric.BiometricManager
import com.android.BioKey.core.data.LockTimeoutMode
import com.android.BioKey.core.data.SecurityProfile
import com.android.BioKey.feature.lock.InstantShieldOverlay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import java.security.MessageDigest
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * BioKey's configuration: which apps are locked and how.
 *
 * Reads are synchronous and served from memory so the accessibility service can decide in
 * microseconds. Every change is persisted atomically (all keys in one commit) together with an
 * HMAC-SHA256 seal computed by a hardware-backed Keystore key. If the stored settings are edited
 * outside BioKey (root, backup restore, file tampering) the seal no longer matches: BioKey
 * records the event, re-arms protection, and warns the owner.
 *
 * Changes that reduce protection require an active, authenticated BioKey session in the
 * foreground ([LockStateTracker.hasActiveOwnerSession]); those setters return `false` and do
 * nothing otherwise.
 */
class SecurityVault private constructor(private val context: Context) {

    companion object {
        private const val TAG = "SecurityVault"
        private const val PREF_NAME = "biokey_vault_sync"
        private const val KEY_LOCKED = "locked_apps_set"
        private const val KEY_GLOBAL = "global_lock_enabled"
        private const val KEY_PAUSED_UNTIL = "global_lock_paused_until"
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
        private const val KEY_NOTIFICATION_SHIELD = "notification_shield_enabled"
        private const val KEY_RECENTS_SHIELD = "recents_shield_enabled"
        private const val KEY_IDENTITY_CHECK = "identity_check_enabled"
        private const val KEY_TAMPER_PENDING = "tamper_pending"
        private const val KEY_ID_HOLD_BOOT = "identity_hold_boot"
        private const val KEY_ID_HOLD_START = "identity_hold_start_elapsed"
        private const val KEY_SEAL = "integrity_seal_v1"
        private const val KEY_TAMPER_LOCKDOWN = "tamper_lockdown"

        /** Identity Check's security delay after a biometric change. */
        private const val IDENTITY_HOLD_MS = 30 * 60_000L
        private const val NO_HOLD = Int.MIN_VALUE

        const val PROFILE_ALL = "all"

        /** Longest timed pause offered (1 hour), plus a minute of slack. */
        private const val MAX_TIMED_PAUSE_MS = 61 * 60_000L

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

        private val EXCLUDED_CATEGORIES = setOf(
            ApplicationInfo.CATEGORY_GAME,
            ApplicationInfo.CATEGORY_AUDIO,
            ApplicationInfo.CATEGORY_MAPS,
            ApplicationInfo.CATEGORY_NEWS
        )

        private val EXCLUDED_KEYWORDS = listOf(
            // Games
            "2048", "game", "puzzle", "arcade", "unity3d", "roblox", "supercell", "sudoku", "chess",
            "solitaire", "candycrush", "minecraft", "pokemon", "epicgames", "playrix", "king.com",
            "riotgames", "gameloft", "blizzard", "mojang", "brawlstars", "genshin", "angrybirds",
            "templerun", "tetris", "mahjong", "crossword", "wordle",
            // Media and benign utilities
            "spotify", "podcast", "music", "radio", "pandora", "deezer", "soundcloud", "shazam",
            "tidal", "tunein", "audible", "netflix", "hulu", "disney", "primevideo", "twitch",
            "crunchyroll", "youtube", "plex", "peacock", "wallpaper", "weather", "flashlight",
            "compass", "benchmark", "speedtest"
        )

        /**
         * Apps that auto-sort should not *suggest* locking (games, media, utilities).
         *
         * This is only a heuristic for suggestions: it never overrides a lock the user chose.
         */
        fun isExcludedFromSecurity(pkg: String, label: String = "", category: Int = -1): Boolean {
            if (category in EXCLUDED_CATEGORIES) return true
            val p = pkg.lowercase()
            val l = label.lowercase()
            return EXCLUDED_KEYWORDS.any { p.contains(it) || l.contains(it) }
        }

        /** Strictness rank of a timeout mode; higher = stays unlocked longer. */
        private fun LockTimeoutMode.leniency(): Int = when (this) {
            LockTimeoutMode.IMMEDIATE -> 0
            LockTimeoutMode.GRACE_30_SECONDS -> 1
            LockTimeoutMode.GRACE_1_MINUTE -> 2
            LockTimeoutMode.GRACE_5_MINUTES -> 3
            LockTimeoutMode.GRACE_15_MINUTES -> 4
            LockTimeoutMode.ON_SCREEN_OFF -> 5
        }
    }

    private val sharedPrefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "BioKeyVault") }
    private val profileLock = Any()

    private val lockedAppsSet = ConcurrentHashMap.newKeySet<String>()
    private val stealthAppsSet = ConcurrentHashMap.newKeySet<String>()

    private val _lockedAppsFlow = MutableStateFlow<Set<String>>(emptySet())
    val lockedAppsFlow: StateFlow<Set<String>> = _lockedAppsFlow.asStateFlow()

    private val _stealthAppsFlow = MutableStateFlow<Set<String>>(emptySet())
    val stealthAppsFlow: StateFlow<Set<String>> = _stealthAppsFlow.asStateFlow()

    private val _globalLockEnabledFlow = MutableStateFlow(true)
    val globalLockEnabledFlow: StateFlow<Boolean> = _globalLockEnabledFlow.asStateFlow()

    private val _pausedUntilFlow = MutableStateFlow(0L)
    /** Wall-clock time protection auto-resumes, or 0 if not on a timer. */
    val pausedUntilFlow: StateFlow<Long> = _pausedUntilFlow.asStateFlow()

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

    private val _activeProfileIdFlow = MutableStateFlow<String?>(PROFILE_ALL)
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

    private val _tamperDetectedFlow = MutableStateFlow(false)
    /** True after the settings seal failed to verify, until the owner acknowledges it. */
    val tamperDetectedFlow: StateFlow<Boolean> = _tamperDetectedFlow.asStateFlow()

    // Identity Check security delay (see [identityHoldRemaining]): the boot it started in and its
    // start on the elapsed-realtime clock, or NO_HOLD.
    @Volatile private var identityHoldBoot = NO_HOLD
    @Volatile private var identityHoldStart = 0L

    @Volatile private var isTamperLockdown = false
    fun isTamperLockdown(): Boolean = isTamperLockdown

    init {
        isTamperLockdown = sharedPrefs.getBoolean(KEY_TAMPER_LOCKDOWN, false)
        val savedLocked = sharedPrefs.getStringSet(KEY_LOCKED, emptySet()).orEmpty()
        lockedAppsSet.addAll(savedLocked)
        _lockedAppsFlow.value = savedLocked.toSet()

        val savedStealth = sharedPrefs.getStringSet(KEY_STEALTH_APPS, emptySet()).orEmpty()
        stealthAppsSet.addAll(savedStealth)
        _stealthAppsFlow.value = savedStealth.toSet()

        _globalLockEnabledFlow.value = sharedPrefs.getBoolean(KEY_GLOBAL, true)
        _pausedUntilFlow.value = sharedPrefs.getLong(KEY_PAUSED_UNTIL, 0L)
        _timeoutModeFlow.value = LockTimeoutMode.fromName(sharedPrefs.getString(KEY_TIMEOUT, null))
        _stealthModeGlobalFlow.value = sharedPrefs.getBoolean(KEY_STEALTH_GLOBAL, false)
        _intruderDetectionFlow.value = sharedPrefs.getBoolean(KEY_INTRUDER, true)
        _overlayShieldFlow.value = sharedPrefs.getBoolean(KEY_OVERLAY, true)
        _selfProtectionFlow.value = sharedPrefs.getBoolean(KEY_SELF_PROTECT, true)
        _autoSortProfilesFlow.value = sharedPrefs.getBoolean(KEY_AUTO_SORT, false)
        _notificationShieldFlow.value = sharedPrefs.getBoolean(KEY_NOTIFICATION_SHIELD, true)
        _recentsShieldFlow.value = sharedPrefs.getBoolean(KEY_RECENTS_SHIELD, true)
        _identityCheckFlow.value = sharedPrefs.getBoolean(KEY_IDENTITY_CHECK, false)
        _tamperDetectedFlow.value = sharedPrefs.getBoolean(KEY_TAMPER_PENDING, false)
        identityHoldBoot = sharedPrefs.getInt(KEY_ID_HOLD_BOOT, NO_HOLD)
        identityHoldStart = sharedPrefs.getLong(KEY_ID_HOLD_START, 0L)

        // Older builds wrote the setup flag under several names.
        _setupCompletedFlow.value = sharedPrefs.getBoolean(KEY_SETUP_COMPLETED, false) ||
            sharedPrefs.getBoolean("setup_complete", false) ||
            context.getSharedPreferences("biokey_prefs", Context.MODE_PRIVATE).getBoolean("setup_complete", false)

        _profilesFlow.value = sharedPrefs.getString(KEY_PROFILES_JSON, null)
            ?.let { json ->
                runCatching {
                    val array = JSONArray(json)
                    (0 until array.length()).map { SecurityProfile.fromJson(array.getJSONObject(it)) }
                }.onFailure { Log.w(TAG, "Profiles unreadable; keeping defaults: ${it.message}") }.getOrNull()
            }
            ?: defaultProfiles()
        _activeProfileIdFlow.value = sharedPrefs.getString(KEY_ACTIVE_PROFILE_ID, PROFILE_ALL) ?: PROFILE_ALL

        io.execute(::verifyIntegrity)
    }

    // ---------------------------------------------------------------------------------------
    // Persistence and integrity
    // ---------------------------------------------------------------------------------------

    /** Canonical, order-independent serialization of every security-relevant setting. */
    private fun canonicalState(includeRecents: Boolean = true): String = buildString {
        appendLine("locked=${lockedAppsSet.sorted().joinToString(",")}")
        appendLine("stealthApps=${stealthAppsSet.sorted().joinToString(",")}")
        appendLine("global=${_globalLockEnabledFlow.value}")
        appendLine("pausedUntil=${_pausedUntilFlow.value}")
        appendLine("timeout=${_timeoutModeFlow.value.name}")
        appendLine("stealthGlobal=${_stealthModeGlobalFlow.value}")
        appendLine("intruder=${_intruderDetectionFlow.value}")
        appendLine("overlay=${_overlayShieldFlow.value}")
        appendLine("selfProtect=${_selfProtectionFlow.value}")
        appendLine("notifShield=${_notificationShieldFlow.value}")
        if (includeRecents) appendLine("recentsShield=${_recentsShieldFlow.value}")
        appendLine("identity=${_identityCheckFlow.value}")
        appendLine("profiles=${profilesJson()}")
        appendLine("activeProfile=${_activeProfileIdFlow.value}")
        appendLine("setup=${_setupCompletedFlow.value}")
        appendLine("tamper=${_tamperDetectedFlow.value}")
        // Only while a delay exists, so seals written before this field existed still verify.
        if (identityHoldBoot != NO_HOLD) appendLine("identityHold=$identityHoldBoot@$identityHoldStart")
    }

    private fun seal(state: String): String? =
        HardwareKeyManager.integrityMac(context, state.toByteArray(Charsets.UTF_8))
            ?.let { Base64.encodeToString(it, Base64.NO_WRAP) }

    private fun verifyIntegrity() {
        val stored = sharedPrefs.getString(KEY_SEAL, null)
        if (stored == null && !HardwareKeyManager.hasIntegrityKey()) {
            // First run, or upgrade from a build without sealing: establish the seal.
            writeAll()
            return
        }
        if (stored != null) {
            val expected = seal(canonicalState(includeRecents = true)) ?: return // Keystore unavailable: can't judge either way.
            if (MessageDigest.isEqual(expected.toByteArray(), stored.toByteArray())) return
            // Backwards compatibility with previous seal without recentsShield line:
            val legacyExpected = seal(canonicalState(includeRecents = false))
            if (legacyExpected != null && MessageDigest.isEqual(legacyExpected.toByteArray(), stored.toByteArray())) {
                writeAll()
                return
            }
        }

        // A mismatch, or a seal deleted after one was written (the integrity key outlives it).
        Log.e(TAG, "Settings integrity check failed: configuration was modified outside BioKey")
        SecurityEventLog.record(context, SecurityEventLog.Type.SETTINGS_TAMPERED, "Protection re-armed automatically")
        // Fail closed: the seal can't say what changed, so every setting that can reduce protection
        // goes back to its protective value. (Apps removed from the lock list can't be recovered.)
        _globalLockEnabledFlow.value = true
        _pausedUntilFlow.value = 0L
        _timeoutModeFlow.value = LockTimeoutMode.IMMEDIATE
        _selfProtectionFlow.value = true
        _overlayShieldFlow.value = true
        _notificationShieldFlow.value = true
        _recentsShieldFlow.value = true
        _intruderDetectionFlow.value = true
        // Clearing the setup flag would switch off self-protection and BioKey's own lock gate.
        _setupCompletedFlow.value = true
        _tamperDetectedFlow.value = true
        if (lockedAppsSet.isEmpty() && (stored != null || HardwareKeyManager.hasIntegrityKey())) {
            Log.w(TAG, "Integrity check failed with wiped/empty lock list: entering tamper lockdown mode")
            isTamperLockdown = true
            sharedPrefs.edit().putBoolean(KEY_TAMPER_LOCKDOWN, true).apply()
        } else {
            writeAll()
        }
        ProtectionAlerts.tamperDetected(context)
    }

    private fun persist() {
        io.execute(::writeAll)
    }

    private val writeLock = Any()

    private fun writeAll() = synchronized(writeLock) {
        val currentLocked = lockedAppsSet.toSet()
        val currentStealth = stealthAppsSet.toSet()
        val currentProfiles = _profilesFlow.value
        val currentProfilesJson = profilesJson()
        val activeProfileId = _activeProfileIdFlow.value
        val globalLock = _globalLockEnabledFlow.value
        val pausedUntil = _pausedUntilFlow.value
        val timeoutMode = _timeoutModeFlow.value
        val stealthGlobal = _stealthModeGlobalFlow.value
        val intruder = _intruderDetectionFlow.value
        val overlay = _overlayShieldFlow.value
        val selfProtect = _selfProtectionFlow.value
        val autoSort = _autoSortProfilesFlow.value
        val notifShield = _notificationShieldFlow.value
        val recentsShield = _recentsShieldFlow.value
        val identity = _identityCheckFlow.value
        val setupCompleted = _setupCompletedFlow.value
        val tamperPending = _tamperDetectedFlow.value
        val holdBoot = identityHoldBoot
        val holdStart = identityHoldStart

        val canonical = canonicalState(includeRecents = true)
        val mac = seal(canonical)
        val editor = sharedPrefs.edit()
            .putStringSet(KEY_LOCKED, currentLocked)
            .putStringSet(KEY_STEALTH_APPS, currentStealth)
            .putBoolean(KEY_GLOBAL, globalLock)
            .putLong(KEY_PAUSED_UNTIL, pausedUntil)
            .putString(KEY_TIMEOUT, timeoutMode.name)
            .putBoolean(KEY_STEALTH_GLOBAL, stealthGlobal)
            .putBoolean(KEY_INTRUDER, intruder)
            .putBoolean(KEY_OVERLAY, overlay)
            .putBoolean(KEY_SELF_PROTECT, selfProtect)
            .putBoolean(KEY_AUTO_SORT, autoSort)
            .putBoolean(KEY_NOTIFICATION_SHIELD, notifShield)
            .putBoolean(KEY_RECENTS_SHIELD, recentsShield)
            .putBoolean(KEY_IDENTITY_CHECK, identity)
            .putString(KEY_PROFILES_JSON, currentProfilesJson)
            .putString(KEY_ACTIVE_PROFILE_ID, activeProfileId)
            .putBoolean(KEY_SETUP_COMPLETED, setupCompleted)
            .putBoolean(KEY_TAMPER_PENDING, tamperPending)
            .putInt(KEY_ID_HOLD_BOOT, holdBoot)
            .putLong(KEY_ID_HOLD_START, holdStart)

        if (mac != null) editor.putString(KEY_SEAL, mac)
        editor.commit()
    }

    private fun profilesJson(): String =
        JSONArray().apply {
            _profilesFlow.value.sortedBy { it.id }.forEach { put(it.toJson()) }
        }.toString()

    /** Gate for ambient owner sessions. */
    private fun authorized(action: String): Boolean {
        if (LockStateTracker.hasActiveOwnerSession()) return true
        Log.w(TAG, "Refused '$action': no active owner session")
        return false
    }

    /** Gate for security downgrades requiring explicit Step-Up auth rather than ambient session. */
    private fun authorizedForDowngrade(action: String): Boolean {
        if (LockStateTracker.hasActiveStepUpLease()) return true
        Log.w(TAG, "Refused dangerous downgrade '$action': no active step-up lease")
        return false
    }

    fun acknowledgeTamper() {
        if (!_tamperDetectedFlow.value && !isTamperLockdown) return
        _tamperDetectedFlow.value = false
        isTamperLockdown = false
        sharedPrefs.edit().putBoolean(KEY_TAMPER_LOCKDOWN, false).apply()
        persist()
    }

    // ---------------------------------------------------------------------------------------
    // Enforcement reads (hot path)
    // ---------------------------------------------------------------------------------------

    private fun aliasesOf(pkg: String): Set<String> = if (pkg in PASSWORD_MANAGER_ALIASES) PASSWORD_MANAGER_ALIASES else setOf(pkg)

    private fun activeProfile(): SecurityProfile? {
        val id = _activeProfileIdFlow.value ?: return null
        if (id == PROFILE_ALL) return null
        return _profilesFlow.value.firstOrNull { it.id == id }
    }

    /** Whether [packageName] is protected right now. When a profile is active, its packages govern. */
    fun isAppLocked(packageName: String): Boolean {
        if (isTamperLockdown) {
            return packageName != context.packageName
        }
        if (!isGlobalLockEnabled()) return false
        val aliases = aliasesOf(packageName)
        val profile = activeProfile()
        return if (profile != null) {
            aliases.any { it in profile.packages }
        } else {
            aliases.any { it in lockedAppsSet }
        }
    }

    fun isStealthCrash(packageName: String): Boolean {
        if (_stealthModeGlobalFlow.value) return true
        val aliases = aliasesOf(packageName)
        if (aliases.any { it in stealthAppsSet }) return true
        val profile = activeProfile() ?: return false
        return profile.isStealth && aliases.any { it in lockedAppsSet || it in profile.packages }
    }

    fun isStealthModeGlobal(): Boolean = _stealthModeGlobalFlow.value

    /** False while protection is paused. Auto-resumes once a timed pause expires. */
    fun isGlobalLockEnabled(): Boolean {
        if (_globalLockEnabledFlow.value) return true
        val until = _pausedUntilFlow.value
        val now = System.currentTimeMillis()
        // The deadline is wall-clock time (it must survive a reboot), so also end the pause if the
        // clock was set back far enough that more time remains than the longest timed pause.
        if (until > 0L && (now >= until || until - now > MAX_TIMED_PAUSE_MS)) {
            resumeProtection(reason = "Timed pause ended")
            return true
        }
        return false
    }

    fun getTimeoutMode(): LockTimeoutMode = _timeoutModeFlow.value
    fun isOverlayShieldEnabled(): Boolean = _overlayShieldFlow.value
    fun isSelfProtectionEnabled(): Boolean = _selfProtectionFlow.value
    fun isIntruderDetectionEnabled(): Boolean = _intruderDetectionFlow.value
    fun isNotificationShieldEnabled(): Boolean = _notificationShieldFlow.value
    fun isRecentsShieldEnabled(): Boolean = _recentsShieldFlow.value
    fun isIdentityCheckEnabled(): Boolean = _identityCheckFlow.value
    fun isAutoSortProfilesEnabled(): Boolean = _autoSortProfilesFlow.value
    fun isSetupCompleted(): Boolean = _setupCompletedFlow.value

    // ---------------------------------------------------------------------------------------
    // Locked apps
    // ---------------------------------------------------------------------------------------

    /** Locking is always allowed. Unlocking requires recent authentication. */
    fun setAppLock(packageName: String, locked: Boolean): Boolean {
        if (!locked && !authorized("unlock $packageName")) return false
        val targets = aliasesOf(packageName)
        if (locked) lockedAppsSet.addAll(targets) else lockedAppsSet.removeAll(targets)
        // Either way, no stale session may survive a change to the lock list.
        targets.forEach(LockStateTracker::lockPackage)
        _lockedAppsFlow.value = lockedAppsSet.toSet()

        // While a profile is active, the per-app toggle edits that profile too.
        val activeId = _activeProfileIdFlow.value
        if (activeId != null && activeId != PROFILE_ALL) {
            updateProfiles { profiles ->
                profiles.map { p ->
                    if (p.id == activeId) p.copy(packages = if (locked) p.packages + targets else p.packages - targets) else p
                }
            }
        }
        persist()
        return true
    }

    fun setMultipleAppLocks(packages: Collection<String>, locked: Boolean): Boolean {
        if (!locked && !authorized("bulk unlock")) return false
        val targets = packages.flatMap(::aliasesOf).toSet()
        if (locked) lockedAppsSet.addAll(targets) else lockedAppsSet.removeAll(targets)
        targets.forEach(LockStateTracker::lockPackage)
        _lockedAppsFlow.value = lockedAppsSet.toSet()
        persist()
        return true
    }

    fun setAppStealthCrash(packageName: String, enabled: Boolean) {
        val targets = aliasesOf(packageName)
        if (enabled) stealthAppsSet.addAll(targets) else stealthAppsSet.removeAll(targets)
        _stealthAppsFlow.value = stealthAppsSet.toSet()
        persist()
    }

    // ---------------------------------------------------------------------------------------
    // Global protection
    // ---------------------------------------------------------------------------------------

    /**
     * Pauses protection. [durationMillis] = null pauses until the owner resumes it manually.
     * Requires recent authentication.
     */
    fun pauseProtection(durationMillis: Long?): Boolean {
        if (!authorizedForDowngrade("pause protection")) return false
        _globalLockEnabledFlow.value = false
        _pausedUntilFlow.value = durationMillis?.let { System.currentTimeMillis() + it } ?: 0L
        InstantShieldOverlay.dismiss()
        val detail = durationMillis?.let { "For ${it / 60_000} min" } ?: "Until resumed manually"
        SecurityEventLog.record(context, SecurityEventLog.Type.PROTECTION_PAUSED, detail)
        persist()
        com.android.BioKey.feature.tile.BioKeyTileService.requestTileUpdate(context)
        return true
    }

    fun resumeProtection(reason: String = "Resumed by owner") {
        val wasPaused = !_globalLockEnabledFlow.value
        _globalLockEnabledFlow.value = true
        _pausedUntilFlow.value = 0L
        // Ends every app session, but not BioKey's own: resuming only strengthens protection, and
        // the owner may still be in BioKey (where a lost session would silently break step-up).
        LockStateTracker.lockAll(includeOwnApp = false)
        if (wasPaused) SecurityEventLog.record(context, SecurityEventLog.Type.PROTECTION_RESUMED, reason)
        persist()
        com.android.BioKey.feature.tile.BioKeyTileService.requestTileUpdate(context)
    }

    /** Compatibility wrapper: enabling always works, disabling pauses indefinitely (needs auth). */
    fun setGlobalLockEnabled(enabled: Boolean): Boolean {
        if (enabled) {
            resumeProtection()
            return true
        }
        return pauseProtection(null)
    }

    // ---------------------------------------------------------------------------------------
    // Settings
    // ---------------------------------------------------------------------------------------

    /** Shortening the timeout is always allowed; lengthening it requires recent authentication. */
    fun setTimeoutMode(mode: LockTimeoutMode): Boolean {
        if (mode.leniency() > _timeoutModeFlow.value.leniency() && !authorizedForDowngrade("lengthen timeout")) return false
        val old = _timeoutModeFlow.value
        _timeoutModeFlow.value = mode
        SecurityEventLog.record(context, SecurityEventLog.Type.SETTINGS_MODIFIED, "Timeout changed from ${old.name} to ${mode.name}")
        persist()
        return true
    }

    fun setStealthCrashMode(enabled: Boolean): Boolean {
        if (!enabled && !authorizedForDowngrade("disable stealth crash mode")) return false
        _stealthModeGlobalFlow.value = enabled
        SecurityEventLog.record(context, SecurityEventLog.Type.SETTINGS_MODIFIED, "Stealth crash mode ${if (enabled) "enabled" else "disabled"}")
        persist()
        return true
    }

    fun setIntruderDetection(enabled: Boolean): Boolean {
        if (!enabled && !authorizedForDowngrade("disable intruder detection")) return false
        _intruderDetectionFlow.value = enabled
        SecurityEventLog.record(context, SecurityEventLog.Type.SETTINGS_MODIFIED, "Intruder detection ${if (enabled) "enabled" else "disabled"}")
        persist()
        return true
    }

    fun setSelfProtection(enabled: Boolean): Boolean {
        if (!enabled && !authorizedForDowngrade("disable self-protection")) return false
        _selfProtectionFlow.value = enabled
        SecurityEventLog.record(context, SecurityEventLog.Type.SETTINGS_MODIFIED, "Self-protection ${if (enabled) "enabled" else "disabled"}")
        persist()
        return true
    }

    fun setOverlayShield(enabled: Boolean): Boolean {
        if (!enabled && !authorizedForDowngrade("disable instant shield")) return false
        _overlayShieldFlow.value = enabled
        SecurityEventLog.record(context, SecurityEventLog.Type.SETTINGS_MODIFIED, "Instant shield ${if (enabled) "enabled" else "disabled"}")
        persist()
        return true
    }

    fun setNotificationShield(enabled: Boolean): Boolean {
        if (!enabled && !authorizedForDowngrade("disable notification shield")) return false
        _notificationShieldFlow.value = enabled
        SecurityEventLog.record(context, SecurityEventLog.Type.SETTINGS_MODIFIED, "Notification shield ${if (enabled) "enabled" else "disabled"}")
        persist()
        return true
    }

    fun setRecentsShield(enabled: Boolean): Boolean {
        if (!enabled && !authorizedForDowngrade("disable recents shield")) return false
        _recentsShieldFlow.value = enabled
        SecurityEventLog.record(context, SecurityEventLog.Type.SETTINGS_MODIFIED, "Recents shield ${if (enabled) "enabled" else "disabled"}")
        persist()
        return true
    }

    /**
     * Identity Check can only be turned on while a Class 3 biometric is enrolled (otherwise the
     * owner would lock themselves out); turning it off requires recent authentication.
     */
    fun setIdentityCheckEnabled(enabled: Boolean): Boolean {
        if (enabled) {
            val status = BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            if (status != BiometricManager.BIOMETRIC_SUCCESS) return false
        } else if (!authorizedForDowngrade("disable Identity Check")) {
            return false
        }
        _identityCheckFlow.value = enabled
        identityHoldBoot = NO_HOLD
        identityHoldStart = 0L
        SecurityEventLog.record(context, SecurityEventLog.Type.SETTINGS_MODIFIED, "Identity Check ${if (enabled) "enabled" else "disabled"}")
        persist()
        return true
    }

    /**
     * Identity Check's security delay after a biometric change: milliseconds until protected apps
     * may be unlocked again (0 = over). Starts the delay, logging it and alerting the owner, if
     * none is running. Measured on the elapsed-realtime clock so changing the time can't shorten
     * it; a reboot restarts it.
     */
    @Synchronized
    fun identityHoldRemaining(): Long {
        val now = SystemClock.elapsedRealtime()
        val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        if (identityHoldBoot != boot || identityHoldStart > now) {
            val restarted = identityHoldBoot != NO_HOLD
            identityHoldBoot = boot
            identityHoldStart = now
            persist()
            SecurityEventLog.record(
                context,
                SecurityEventLog.Type.BIOMETRICS_CHANGED,
                if (restarted) "Identity Check delay restarted after a reboot" else "Identity Check security delay started (30 min)"
            )
            val until = DateFormat.getTimeFormat(context).format(Date(System.currentTimeMillis() + IDENTITY_HOLD_MS))
            ProtectionAlerts.identityCheckDelayStarted(context, until)
        }
        return (identityHoldStart + IDENTITY_HOLD_MS - now).coerceAtLeast(0L)
    }

    /** The owner confirmed the biometric change after the delay. */
    fun clearIdentityHold() {
        if (identityHoldBoot == NO_HOLD) return
        identityHoldBoot = NO_HOLD
        identityHoldStart = 0L
        persist()
    }

    /**
     * Identity Check can't work with no biometric enrolled. Once the security delay is over and the
     * owner has confirmed with the device credential, it is turned off (no step-up possible: there
     * is no biometric left to step up with).
     */
    fun endIdentityCheckAfterHold() {
        if (identityHoldBoot == NO_HOLD || identityHoldRemaining() > 0L) return
        _identityCheckFlow.value = false
        identityHoldBoot = NO_HOLD
        identityHoldStart = 0L
        SecurityEventLog.record(
            context,
            SecurityEventLog.Type.BIOMETRICS_CHANGED,
            "Identity Check turned off: no biometrics left after the security delay"
        )
        persist()
    }

    fun setAutoSortProfilesEnabled(enabled: Boolean) {
        _autoSortProfilesFlow.value = enabled
        persist()
    }

    /** Setup can only be completed; reopening it would skip BioKey's own lock. */
    fun setSetupCompleted(completed: Boolean) {
        if (!completed && !authorized("reset setup")) return
        _setupCompletedFlow.value = completed
        persist()
    }

    // ---------------------------------------------------------------------------------------
    // Profiles
    // ---------------------------------------------------------------------------------------

    private inline fun updateProfiles(transform: (List<SecurityProfile>) -> List<SecurityProfile>) {
        synchronized(profileLock) {
            _profilesFlow.value = transform(_profilesFlow.value)
        }
    }

    private fun defaultProfiles() = listOf(
        SecurityProfile(id = "profile_personal", name = "Personal", iconKey = "shield", isPreset = true),
        SecurityProfile(id = "profile_finance", name = "Finance", iconKey = "finance", isPreset = true, timeoutMode = LockTimeoutMode.IMMEDIATE),
        SecurityProfile(id = "profile_social", name = "Social", iconKey = "social", isPreset = true),
        SecurityProfile(id = "profile_work", name = "Work", iconKey = "work", isPreset = true, timeoutMode = LockTimeoutMode.GRACE_5_MINUTES),
        SecurityProfile(id = "profile_home", name = "Home", iconKey = "shield", isPreset = true, timeoutMode = LockTimeoutMode.GRACE_15_MINUTES),
        SecurityProfile(id = "profile_night", name = "Night", iconKey = "privacy", isPreset = true, isStealth = true, timeoutMode = LockTimeoutMode.IMMEDIATE)
    )

    /**
     * Creates or updates a profile. Apps removed from the *active* profile are unlocked (needs
     * auth); removing apps from an inactive profile never changes what is locked right now.
     */
    fun saveProfile(profile: SecurityProfile): Boolean {
        val existing = _profilesFlow.value.firstOrNull { it.id == profile.id }
        val removed = existing?.packages.orEmpty() - profile.packages
        val isActive = _activeProfileIdFlow.value == profile.id
        if (isActive && removed.isNotEmpty() && !authorized("remove apps from active profile")) return false

        updateProfiles { profiles ->
            if (existing == null) profiles + profile else profiles.map { if (it.id == profile.id) profile else it }
        }
        if (isActive) {
            val stillProfiled = _profilesFlow.value.filter { it.id != profile.id }.flatMap { it.packages }.toSet()
            val toUnlock = removed - stillProfiled
            if (toUnlock.isNotEmpty()) {
                lockedAppsSet.removeAll(toUnlock)
                _lockedAppsFlow.value = lockedAppsSet.toSet()
            }
            activateProfile(profile.id)
        }
        persist()
        return true
    }

    /** Deleting a profile never unlocks anything; unlock apps individually if wanted. */
    fun deleteProfile(profileId: String) {
        updateProfiles { profiles -> profiles.filterNot { it.id == profileId } }
        if (_activeProfileIdFlow.value == profileId) _activeProfileIdFlow.value = PROFILE_ALL
        persist()
    }

    fun addAppToProfile(profileId: String, packageName: String) {
        updateProfiles { profiles ->
            profiles.map { if (it.id == profileId) it.copy(packages = it.packages + packageName) else it }
        }
        setAppLock(packageName, true)
    }

    fun removeAppFromProfile(profileId: String, packageName: String): Boolean {
        val profile = _profilesFlow.value.firstOrNull { it.id == profileId } ?: return false
        return saveProfile(profile.copy(packages = profile.packages - packageName))
    }

    /**
     * Applies a profile: locks its apps and applies its stealth/timeout settings. Switching to a
     * profile with a more lenient timeout requires recent authentication.
     */
    fun activateProfile(profileId: String?): Boolean {
        val targetId = profileId ?: PROFILE_ALL
        val profile = _profilesFlow.value.firstOrNull { it.id == targetId }
        val targetTimeout = profile?.timeoutMode ?: LockTimeoutMode.fromName(sharedPrefs.getString(KEY_TIMEOUT, null))

        if (targetTimeout.leniency() > _timeoutModeFlow.value.leniency() &&
            !authorizedForDowngrade("activate lenient profile")
        ) {
            return false
        }
        _activeProfileIdFlow.value = targetId

        // Wipe lingering sessions so switching profiles never leaks unlock status
        LockStateTracker.lockAll(includeOwnApp = false)

        if (profile != null) {
            _stealthModeGlobalFlow.value = profile.isStealth
            _timeoutModeFlow.value = profile.timeoutMode
        } else {
            // Restore global preferences when returning to "All Apps"
            _stealthModeGlobalFlow.value = sharedPrefs.getBoolean(KEY_STEALTH_GLOBAL, false)
            _timeoutModeFlow.value = LockTimeoutMode.fromName(sharedPrefs.getString(KEY_TIMEOUT, null))
        }
        persist()
        return true
    }

    /**
     * Suggests profile membership for installed apps by name. Only adds apps to profiles; it
     * never unlocks anything and skips games/media.
     */
    fun autoSortAppsIntoProfiles(appList: List<Pair<String, String>>, pm: android.content.pm.PackageManager? = null) {
        if (!_autoSortProfilesFlow.value || appList.isEmpty() || _profilesFlow.value.isEmpty()) return

        val buckets = mapOf(
            "finance" to mutableSetOf<String>(),
            "social" to mutableSetOf(),
            "work" to mutableSetOf(),
            "shield" to mutableSetOf()
        )
        val financeWords = listOf("bank", "crypto", "wallet", "pay", "cash", "binance", "coinbase", "robinhood", "revolut",
            "chase", "wellsfargo", "citi", "fidelity", "schwab", "vanguard", "sofi", "metamask", "kraken", "venmo", "paypal",
            "finance", "invest", "money", "credit")
        val socialWords = listOf("whatsapp", "telegram", "signal", "instagram", "facebook", "messenger", "twitter", "tiktok",
            "snapchat", "discord", "reddit", "threads", "bluesky", "wechat", "viber", "jp.naver.line", "tinder", "bumble", "hinge",
            "messages", "chat", "social")
        val workWords = listOf("gmail", "outlook", "mail", "slack", "teams", "zoom", "drive", "docs", "sheets", "slides",
            "notion", "trello", "asana", "jira", "office", "dropbox", "onedrive", "authenticator", "duosecurity", "bitwarden",
            "1password", "onepassword", "lastpass", "dashlane", "password")
        val personalWords = listOf("photo", "gallery", "camera", "files", "filemanager", "vault", "notes")

        for ((pkg, label) in appList) {
            val category = runCatching { pm?.getApplicationInfo(pkg, 0)?.category }.getOrNull() ?: -1
            if (isExcludedFromSecurity(pkg, label, category)) continue
            val haystack = "${pkg.lowercase()} ${label.lowercase()}"
            when {
                financeWords.any(haystack::contains) -> buckets.getValue("finance") += pkg
                category == ApplicationInfo.CATEGORY_SOCIAL || socialWords.any(haystack::contains) -> buckets.getValue("social") += pkg
                category == ApplicationInfo.CATEGORY_PRODUCTIVITY || workWords.any(haystack::contains) -> buckets.getValue("work") += pkg
                category == ApplicationInfo.CATEGORY_IMAGE || personalWords.any(haystack::contains) -> buckets.getValue("shield") += pkg
            }
        }

        var modified = false
        updateProfiles { profiles ->
            profiles.map { profile ->
                val key = when {
                    profile.id == "profile_finance" || profile.iconKey == "finance" -> "finance"
                    profile.id == "profile_social" || profile.iconKey == "social" -> "social"
                    profile.id == "profile_work" || profile.iconKey == "work" -> "work"
                    profile.id == "profile_personal" || profile.iconKey == "shield" -> "shield"
                    else -> null
                }
                val additions = key?.let { buckets.getValue(it) }.orEmpty()
                val union = profile.packages + additions
                if (union != profile.packages) {
                    modified = true
                    profile.copy(packages = union)
                } else {
                    profile
                }
            }
        }
        if (!modified) return
        Log.i(TAG, "Auto-sorted installed apps into profiles")
        activeProfile()?.let { active ->
            lockedAppsSet.addAll(active.packages)
            _lockedAppsFlow.value = lockedAppsSet.toSet()
        }
        persist()
    }
}
