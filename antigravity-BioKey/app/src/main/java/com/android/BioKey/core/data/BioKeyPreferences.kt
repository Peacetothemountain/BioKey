package com.android.BioKey.core.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore by preferencesDataStore(name = "biokey_preferences")

class BioKeyPreferences(private val context: Context) {

    companion object {
        val KEY_LOCKED_APPS = stringSetPreferencesKey("locked_apps")
        val KEY_GLOBAL_LOCK_ENABLED = booleanPreferencesKey("global_lock_enabled")
        val KEY_TIMEOUT_MODE = stringPreferencesKey("timeout_mode")
        val KEY_STEALTH_CRASH_MODE = booleanPreferencesKey("stealth_crash_mode")
        val KEY_STEALTH_CRASH_APPS = stringSetPreferencesKey("stealth_crash_apps")
        val KEY_INTRUDER_DETECTION = booleanPreferencesKey("intruder_detection")
        val KEY_SELF_PROTECTION = booleanPreferencesKey("self_protection")
        val KEY_OVERLAY_SHIELD = booleanPreferencesKey("overlay_shield")
        val KEY_AUTO_SORT_PROFILES = booleanPreferencesKey("auto_sort_profiles")
        val KEY_SETUP_COMPLETED = booleanPreferencesKey("setup_completed")
        val KEY_APP_TIMEOUT_OVERRIDES = stringPreferencesKey("app_timeout_overrides")
        val KEY_NOTIFICATION_SHIELD = booleanPreferencesKey("notification_shield_enabled")
        val KEY_RECENTS_SHIELD = booleanPreferencesKey("recents_shield_enabled")
        val KEY_IDENTITY_CHECK = booleanPreferencesKey("identity_check_enabled")

        @Volatile
        private var INSTANCE: BioKeyPreferences? = null

        fun getInstance(context: Context): BioKeyPreferences {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: BioKeyPreferences(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    val lockedAppsFlow: Flow<Set<String>> = context.dataStore.data.map { preferences ->
        preferences[KEY_LOCKED_APPS] ?: emptySet()
    }

    val globalLockEnabledFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_GLOBAL_LOCK_ENABLED] ?: true
    }

    val timeoutModeFlow: Flow<LockTimeoutMode> = context.dataStore.data.map { preferences ->
        val name = preferences[KEY_TIMEOUT_MODE]
        LockTimeoutMode.fromName(name)
    }

    val stealthCrashModeFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_STEALTH_CRASH_MODE] ?: false
    }

    val stealthCrashAppsFlow: Flow<Set<String>> = context.dataStore.data.map { preferences ->
        preferences[KEY_STEALTH_CRASH_APPS] ?: emptySet()
    }

    val intruderDetectionFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_INTRUDER_DETECTION] ?: true
    }

    val selfProtectionFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_SELF_PROTECTION] ?: true
    }

    val overlayShieldFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_OVERLAY_SHIELD] ?: true
    }

    val autoSortProfilesFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_AUTO_SORT_PROFILES] ?: false
    }

    val setupCompletedFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_SETUP_COMPLETED] ?: false
    }

    suspend fun setAppLock(packageName: String, locked: Boolean) {
        context.dataStore.edit { preferences ->
            val current = preferences[KEY_LOCKED_APPS]?.toMutableSet() ?: mutableSetOf()
            if (locked) {
                current.add(packageName)
            } else {
                current.remove(packageName)
            }
            preferences[KEY_LOCKED_APPS] = current
        }
    }

    suspend fun setMultipleAppLocks(packages: Collection<String>, locked: Boolean) {
        context.dataStore.edit { preferences ->
            val current = preferences[KEY_LOCKED_APPS]?.toMutableSet() ?: mutableSetOf()
            if (locked) {
                current.addAll(packages)
            } else {
                current.removeAll(packages.toSet())
            }
            preferences[KEY_LOCKED_APPS] = current
        }
    }

    suspend fun setAppStealthCrash(packageName: String, enabled: Boolean) {
        context.dataStore.edit { preferences ->
            val current = preferences[KEY_STEALTH_CRASH_APPS]?.toMutableSet() ?: mutableSetOf()
            if (enabled) {
                current.add(packageName)
            } else {
                current.remove(packageName)
            }
            preferences[KEY_STEALTH_CRASH_APPS] = current
        }
    }

    suspend fun setGlobalLockEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_GLOBAL_LOCK_ENABLED] = enabled
        }
    }

    suspend fun setTimeoutMode(mode: LockTimeoutMode) {
        context.dataStore.edit { preferences ->
            preferences[KEY_TIMEOUT_MODE] = mode.name
        }
    }

    suspend fun setStealthCrashMode(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_STEALTH_CRASH_MODE] = enabled
        }
    }

    suspend fun setIntruderDetection(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_INTRUDER_DETECTION] = enabled
        }
    }

    suspend fun setSelfProtection(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_SELF_PROTECTION] = enabled
        }
    }

    suspend fun setOverlayShield(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_OVERLAY_SHIELD] = enabled
        }
    }

    suspend fun setAutoSortProfiles(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_AUTO_SORT_PROFILES] = enabled
        }
    }

    suspend fun setSetupCompleted(completed: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_SETUP_COMPLETED] = completed
        }
    }

    val notificationShieldFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_NOTIFICATION_SHIELD] ?: true
    }

    val recentsShieldFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_RECENTS_SHIELD] ?: true
    }

    val identityCheckFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_IDENTITY_CHECK] ?: true
    }

    suspend fun setNotificationShield(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_NOTIFICATION_SHIELD] = enabled
        }
    }

    suspend fun setRecentsShield(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_RECENTS_SHIELD] = enabled
        }
    }

    suspend fun setIdentityCheck(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_IDENTITY_CHECK] = enabled
        }
    }
}
