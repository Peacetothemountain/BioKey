package com.android.BioKey.feature.main

import com.android.BioKey.core.security.SecurityVault

/** Android's Settings app: where someone who knows the PIN could add their own fingerprint. */
internal const val SETTINGS_PACKAGE = "com.android.settings"

/** Android's Settings app is locked right now (its switch on, and not in a profile that's off). */
internal fun SecurityVault.isSettingsAppLocked(): Boolean = SETTINGS_PACKAGE in getLockedPackages()

/**
 * Locks Android's Settings app, taking it out of a profile that's switched off if that's what kept
 * it unlocked. Returns what puts things back as they were, for Undo.
 */
internal fun SecurityVault.lockSettingsApp(): () -> Unit {
    val wasOn = SETTINGS_PACKAGE in selectedAppsFlow.value
    val formerProfile = profileOf(SETTINGS_PACKAGE)
    if (formerProfile != null && !formerProfile.enabled) removeAppFromProfile(SETTINGS_PACKAGE)
    setAppLock(SETTINGS_PACKAGE, true)
    return {
        if (formerProfile != null && profileOf(SETTINGS_PACKAGE)?.id != formerProfile.id) {
            moveAppToProfile(SETTINGS_PACKAGE, formerProfile.id, switchOn = false)
        }
        setAppLock(SETTINGS_PACKAGE, wasOn)
    }
}
