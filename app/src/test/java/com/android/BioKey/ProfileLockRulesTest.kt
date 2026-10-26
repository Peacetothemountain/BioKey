package com.android.BioKey

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.android.BioKey.core.data.AppCategory
import com.android.BioKey.core.data.LockTimeoutMode
import com.android.BioKey.core.data.PasswordManager
import com.android.BioKey.core.data.SecurityProfile
import com.android.BioKey.core.security.SecurityVault
import java.time.Duration
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

/**
 * How an app's own lock switch, its profile and the profile's switch combine: an app is locked
 * when its switch is on and its profile (if any) is on; neither switch changes membership.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProfileLockRulesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs get() = context.getSharedPreferences("biokey_vault", Context.MODE_PRIVATE)
    private val bank = "com.example.bank"
    private val chat = "com.example.chat"
    private val other = "com.example.other"

    /** A vault read fresh from storage, as after a restart. */
    private fun freshVault(): SecurityVault {
        SecurityVault.resetForTests()
        return SecurityVault.getInstance(context)
    }

    @Before
    fun clear() {
        waitForWrites() // an earlier test's last write must not land in this one
        prefs.edit().clear().commit()
        SecurityVault.resetForTests()
    }

    /** The vault writes on its own thread; let it finish before reading storage back. */
    private fun waitForWrites() = SecurityVault.getInstance(context).awaitPendingWrites()

    private fun advance(ms: Long) = ShadowSystemClock.advanceBy(Duration.ofMillis(ms))

    @Test
    fun turningAnAppOffKeepsItInItsProfile() {
        val vault = freshVault()
        vault.addAppsToProfile(SecurityProfile.PRESET_FINANCE, listOf(bank))
        assertTrue(vault.isAppLocked(bank))

        vault.setAppLock(bank, false)

        assertFalse(vault.isAppLocked(bank))
        assertEquals(SecurityProfile.PRESET_FINANCE, vault.profileOf(bank)?.id)
        vault.setAppLock(bank, true)
        assertTrue(vault.isAppLocked(bank))
    }

    @Test
    fun aProfileSwitchedOffUnlocksItsAppsAndRemembersEachOne() {
        val vault = freshVault()
        vault.addAppsToProfile(SecurityProfile.PRESET_FINANCE, listOf(bank, chat))
        vault.setAppLock(chat, false)

        vault.setProfileEnabled(SecurityProfile.PRESET_FINANCE, false)
        assertFalse(vault.isAppLocked(bank))
        assertFalse(vault.isAppLocked(chat))
        assertTrue(bank in vault.selectedAppsFlow.value) // its own switch is still on

        vault.setProfileEnabled(SecurityProfile.PRESET_FINANCE, true)
        assertTrue(vault.isAppLocked(bank))
        assertFalse(vault.isAppLocked(chat)) // its own switch was off, and stays off
    }

    @Test
    fun removingAnAppFromItsProfileKeepsItsLock() {
        val vault = freshVault()
        vault.addAppsToProfile(SecurityProfile.PRESET_FINANCE, listOf(bank, chat))
        vault.setAppLock(chat, false)

        vault.removeAppFromProfile(bank)
        vault.removeAppFromProfile(chat)
        assertEquals(null, vault.profileOf(bank))
        assertTrue(vault.isAppLocked(bank)) // was on, stays on
        assertFalse(vault.isAppLocked(chat)) // was off, stays off

        // Auto-sort must not put it straight back.
        vault.autoSortAppsIntoProfiles(listOf(bank to AppCategory.FINANCE))
        assertEquals(null, vault.profileOf(bank))

        // Undo: back in the profile, lock untouched.
        vault.moveAppToProfile(chat, SecurityProfile.PRESET_FINANCE, switchOn = false)
        assertEquals(SecurityProfile.PRESET_FINANCE, vault.profileOf(chat)?.id)
        assertFalse(vault.isAppLocked(chat))
    }

    @Test
    fun anAppTakenOutOfItsProfileStaysOutAfterARestart() {
        val vault = freshVault()
        vault.addAppsToProfile(SecurityProfile.PRESET_FINANCE, listOf(bank))
        vault.removeAppFromProfile(bank)
        waitForWrites()

        val again = freshVault()
        again.autoSortAppsIntoProfiles(listOf(bank to AppCategory.FINANCE))
        assertEquals(null, again.profileOf(bank))
    }

    @Test
    fun autoSortFilesRecognisedAppsAndLocksThem() {
        val vault = freshVault()
        val game = "com.example.game"
        vault.autoSortAppsIntoProfiles(listOf(bank to AppCategory.FINANCE, game to AppCategory.GENERAL, context.packageName to AppCategory.AUTH))
        assertEquals(SecurityProfile.PRESET_FINANCE, vault.profileOf(bank)?.id)
        assertTrue(vault.isAppLocked(bank))
        assertEquals(null, vault.profileOf(game))
        assertEquals(null, vault.profileOf(context.packageName)) // never BioKey itself

        // An app switched off in its profile stays off: auto-sort only files apps in no profile.
        vault.setAppLock(bank, false)
        vault.autoSortAppsIntoProfiles(listOf(bank to AppCategory.FINANCE))
        assertFalse(vault.isAppLocked(bank))
    }

    @Test
    fun aDeletedProfilesAppsAreSwitchedOffAndStayOutOfAutoSort() {
        val vault = freshVault()
        val profile = vault.createProfile("Money", "wallet", LockTimeoutMode.IMMEDIATE)
        vault.addAppsToProfile(profile.id, listOf(bank, chat))
        vault.setAppLock(chat, false)

        vault.deleteProfile(profile.id)
        vault.autoSortAppsIntoProfiles(listOf(bank to AppCategory.FINANCE, chat to AppCategory.FINANCE))

        assertEquals(null, vault.profileOf(bank))
        assertEquals(null, vault.profileOf(chat))
        assertFalse(vault.isAppLocked(bank))
        assertFalse(bank in vault.selectedAppsFlow.value) // its switch shows off, as it is
        assertFalse(vault.isAppLocked(chat)) // auto-sort didn't switch it on
    }

    @Test
    fun deletingAProfileThatIsOffDoesNotLockItsApps() {
        val vault = freshVault()
        vault.addAppsToProfile(SecurityProfile.PRESET_FINANCE, listOf(bank))
        vault.setProfileEnabled(SecurityProfile.PRESET_FINANCE, false)

        vault.deleteProfile(SecurityProfile.PRESET_FINANCE)

        assertFalse(vault.isAppLocked(bank))
        assertTrue(vault.selectedAppsFlow.value.isEmpty())
    }

    @Test
    fun undoingADeleteBringsBackTheProfileItsAppsAndTheirSwitches() {
        val vault = freshVault()
        val before = vault.profilesFlow.value.map { it.id }
        vault.addAppsToProfile(SecurityProfile.PRESET_FINANCE, listOf(bank, chat))
        vault.setAppLock(chat, false)

        val deleted = vault.deleteProfile(SecurityProfile.PRESET_FINANCE)!!
        vault.restoreDeletedProfile(deleted)

        assertEquals(before, vault.profilesFlow.value.map { it.id }) // back in its place
        assertEquals(SecurityProfile.PRESET_FINANCE, vault.profileOf(bank)?.id)
        assertEquals(SecurityProfile.PRESET_FINANCE, vault.profileOf(chat)?.id)
        assertTrue(vault.isAppLocked(bank))
        assertFalse(vault.isAppLocked(chat))
        assertEquals(null, vault.deleteProfile("profile_missing"))
    }

    @Test
    fun autoSortLeavesEverythingAloneWhenItIsOff() {
        val vault = freshVault()
        vault.setAutoSortProfilesEnabled(false)
        vault.autoSortAppsIntoProfiles(listOf(bank to AppCategory.FINANCE))
        assertEquals(null, vault.profileOf(bank))
        assertFalse(vault.isAppLocked(bank))
    }

    @Test
    fun anUninstalledAppIsForgotten() {
        val vault = freshVault()
        vault.addAppsToProfile(SecurityProfile.PRESET_FINANCE, listOf(bank))
        vault.onPackageUninstalled(bank)
        assertFalse(vault.isAppLocked(bank))
        assertEquals(null, vault.profileOf(bank))
    }

    @Test
    fun googlePasswordManagerStaysLockedWithoutItsApp() {
        // Its screens in Play services' settings are still there.
        val vault = freshVault()
        vault.setAppLock(PasswordManager.VIRTUAL_PACKAGE, true)
        vault.onPackageUninstalled(PasswordManager.CREDENTIAL_MANAGER)
        assertTrue(vault.isAppLocked(PasswordManager.VIRTUAL_PACKAGE))
        assertTrue(vault.isAppLocked(PasswordManager.CREDENTIAL_MANAGER))
    }

    @Test
    fun appsOutsideProfilesAreUnaffectedByProfileSwitches() {
        val vault = freshVault()
        vault.setAppLock(chat, true)
        vault.setProfileEnabled(SecurityProfile.PRESET_FINANCE, false)
        assertTrue(vault.isAppLocked(chat))
    }

    @Test
    fun switchesSurviveARestart() {
        val vault = freshVault()
        vault.addAppsToProfile(SecurityProfile.PRESET_FINANCE, listOf(bank))
        vault.setAppLock(bank, false)
        vault.setProfileEnabled(SecurityProfile.PRESET_SOCIAL, false)
        waitForWrites()

        val again = freshVault()
        assertFalse(again.isAppLocked(bank))
        assertEquals(SecurityProfile.PRESET_FINANCE, again.profileOf(bank)?.id)
        assertFalse(again.profilesFlow.value.first { it.id == SecurityProfile.PRESET_SOCIAL }.enabled)
    }

    @Test
    fun finishingSetupIsOnDiskAtOnce() {
        freshVault().setSetupCompletedSync(true)
        assertTrue(prefs.getBoolean("setup_completed", false))
    }

    @Test
    fun earlierDataKeepsEveryProfileAppLocked() {
        // Before profiles had switches, an app in a profile was locked without being in the set.
        seedEarlierProfiles()

        val vault = freshVault()
        assertTrue(vault.isAppLocked(bank))
        assertTrue(vault.isAppLocked(chat))
        assertTrue(vault.profilesFlow.value.all { it.enabled })
    }

    @Test
    fun upgradingKeepsEverySetting() {
        // Data as v4.5 left it: no model version, the owner's own settings.
        seedEarlierProfiles()
        prefs.edit()
            .putBoolean("setup_completed", true)
            .putBoolean("accessibility_self_protection_enabled", true)
            .putString("timeout_mode", "GRACE_5_MINUTES")
            .putBoolean("notification_shield_enabled", false)
            .putBoolean("auto_sort_profiles_enabled", false)
            .putStringSet("auto_sort_excluded_packages", setOf(other))
            .commit()

        freshVault()
        waitForWrites()
        assertEquals(3, prefs.getInt("lock_model_version", 0))
        assertEquals(setOf(bank, chat), prefs.getStringSet("locked_apps_set", null))
        assertTrue(prefs.getBoolean("setup_completed", false))
        assertTrue(prefs.getBoolean("accessibility_self_protection_enabled", false))
        assertEquals("GRACE_5_MINUTES", prefs.getString("timeout_mode", null))
        assertFalse(prefs.getBoolean("notification_shield_enabled", true))
        assertFalse(prefs.getBoolean("auto_sort_profiles_enabled", true))
        assertEquals(setOf(other), prefs.getStringSet("auto_sort_excluded_packages", null))

        val again = freshVault()
        assertTrue(again.isSetupCompleted())
        assertTrue(again.isAccessibilitySelfProtectionEnabled())
        assertEquals(LockTimeoutMode.GRACE_5_MINUTES, again.getTimeoutMode(chat))
        assertFalse(again.isNotificationShieldEnabled())
        assertFalse(again.isAutoSortProfilesEnabled())
        assertTrue(again.isAppLocked(bank))
        assertTrue(again.isAppLocked(chat))
    }

    @Test
    fun aPauseThatRanOutWhileStoppedIsSavedAsOver() {
        prefs.edit()
            .putInt("lock_model_version", 2)
            .putBoolean("global_lock_enabled", false)
            .putLong("paused_until_wall", System.currentTimeMillis() - 60_000L)
            .commit()

        assertTrue(freshVault().isProtectionActive())
        waitForWrites()
        assertTrue(prefs.getBoolean("global_lock_enabled", false))
    }

    @Test
    fun aPauseRestoredInTheSameBootEndsExactlyOnTime() {
        Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 7)
        freshVault().pauseProtection(15 * 60_000L)
        waitForWrites()
        advance(5 * 60_000L)
        // The process dies and the wall clock is set back meanwhile.
        prefs.edit().putLong("paused_until_wall", System.currentTimeMillis() + 3 * 60 * 60_000L).commit()

        val vault = freshVault()
        assertFalse(vault.isProtectionActive())
        advance(10 * 60_000L - 1_000L)
        assertFalse(vault.isProtectionActive())
        advance(2_000L)
        assertTrue(vault.isProtectionActive())
    }

    @Test
    fun aPauseRestoredAfterARebootRunsNoLongerThanChosen() {
        Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 7)
        freshVault().pauseProtection(15 * 60_000L)
        waitForWrites()
        // A reboot, with the wall clock set back meanwhile.
        Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 8)
        prefs.edit().putLong("paused_until_wall", System.currentTimeMillis() + 3 * 60 * 60_000L).commit()

        val vault = freshVault()
        assertFalse(vault.isProtectionActive())
        advance(15 * 60_000L - 1_000L)
        assertFalse(vault.isProtectionActive())
        advance(2_000L)
        assertTrue(vault.isProtectionActive())
    }

    @Test
    fun aRestartAfterARebootKeepsTheRestoredPausesEnd() {
        Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 7)
        freshVault().pauseProtection(15 * 60_000L)
        waitForWrites()
        Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 8)
        freshVault() // after the reboot: restored from the wall clock
        waitForWrites()
        advance(10 * 60_000L)
        // The process dies and the wall clock is set back meanwhile.
        prefs.edit().putLong("paused_until_wall", System.currentTimeMillis() + 3 * 60 * 60_000L).commit()

        val vault = freshVault()
        assertFalse(vault.isProtectionActive())
        advance(5 * 60_000L + 1_000L)
        assertTrue(vault.isProtectionActive())
    }

    @Test
    fun aPauseNeverOutlastsItsChoiceShouldTheBootCountStandStill() {
        Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 7)
        freshVault().pauseProtection(15 * 60_000L)
        waitForWrites()
        // A reboot that left the boot count as it was: the stored end is on the last boot's clock.
        prefs.edit().putLong("paused_until_elapsed", SystemClock.elapsedRealtime() + 10 * 60 * 60_000L).commit()

        val vault = freshVault()
        assertFalse(vault.isProtectionActive())
        advance(15 * 60_000L + 1_000L)
        assertTrue(vault.isProtectionActive())
    }

    @Test
    fun aPauseUntilTurnedBackOnSurvivesARestart() {
        freshVault().pauseProtection(null)
        waitForWrites()
        val vault = freshVault()
        advance(24 * 60 * 60_000L)
        assertFalse(vault.isProtectionActive())
    }

    @Test
    fun storedProfilesWithClashingIdsAreRepaired() {
        val profiles = JSONArray()
            .put(SecurityProfile(SecurityProfile.PRESET_FINANCE, "Finance", packages = setOf(bank)).toJson())
            .put(SecurityProfile(SecurityProfile.PRESET_FINANCE, "Copy", packages = setOf(chat)).toJson())
            .put(SecurityProfile("new_profile", " ").toJson())
        prefs.edit().putInt("lock_model_version", 2).putString("security_profiles_json", profiles.toString()).commit()

        val vault = freshVault()
        val ids = vault.profilesFlow.value.map { it.id }
        assertEquals(3, ids.toSet().size)
        assertTrue(ids.all { it.startsWith("profile_") })
        assertEquals(SecurityProfile.PRESET_FINANCE, vault.profileOf(bank)?.id)
        assertEquals("Copy", vault.profileOf(chat)?.name)
        assertEquals("Profile", vault.profilesFlow.value[2].name)
    }

    @Test
    fun theDefaultReLockDelayReachesAppsInBuiltInProfiles() {
        val vault = freshVault()
        vault.addAppsToProfile(SecurityProfile.PRESET_SOCIAL, listOf(chat))
        vault.addAppsToProfile(SecurityProfile.PRESET_WORK, listOf(bank))

        vault.setTimeoutMode(LockTimeoutMode.ON_SCREEN_OFF)

        assertEquals(LockTimeoutMode.ON_SCREEN_OFF, vault.getTimeoutMode(chat))
        assertEquals(LockTimeoutMode.ON_SCREEN_OFF, vault.getTimeoutMode(bank))
        assertEquals(LockTimeoutMode.ON_SCREEN_OFF, vault.getTimeoutMode(other)) // in no profile
    }

    @Test
    fun aProfilesOwnDelayWinsUntilItFollowsSettingsAgain() {
        val vault = freshVault()
        vault.addAppsToProfile(SecurityProfile.PRESET_FINANCE, listOf(bank))
        vault.setTimeoutMode(LockTimeoutMode.ON_SCREEN_OFF)

        vault.setProfileTimeout(SecurityProfile.PRESET_FINANCE, LockTimeoutMode.IMMEDIATE)
        assertEquals(LockTimeoutMode.IMMEDIATE, vault.getTimeoutMode(bank))
        waitForWrites()
        val again = freshVault()
        assertEquals(LockTimeoutMode.IMMEDIATE, again.getTimeoutMode(bank)) // kept

        again.setProfileTimeout(SecurityProfile.PRESET_FINANCE, null)
        assertEquals(LockTimeoutMode.ON_SCREEN_OFF, again.getTimeoutMode(bank))
        waitForWrites()
        assertEquals(LockTimeoutMode.ON_SCREEN_OFF, freshVault().getTimeoutMode(bank))
    }

    @Test
    fun aNewProfileFollowsSettings() {
        val vault = freshVault()
        val profile = vault.createProfile("Money", "wallet")
        vault.addAppsToProfile(profile.id, listOf(bank))
        vault.setTimeoutMode(LockTimeoutMode.GRACE_1_MINUTE)
        assertEquals(LockTimeoutMode.GRACE_1_MINUTE, vault.getTimeoutMode(bank))
    }

    @Test
    fun upgradingLetsUntouchedBuiltInProfilesFollowSettings() {
        // As version 2 stored them: every profile with a delay of its own.
        val profiles = JSONArray()
            .put(SecurityProfile(SecurityProfile.PRESET_SOCIAL, "Social", "social", setOf(chat), LockTimeoutMode.IMMEDIATE).toJson())
            .put(SecurityProfile(SecurityProfile.PRESET_WORK, "Work", "work", setOf(bank), LockTimeoutMode.GRACE_5_MINUTES).toJson())
            .put(SecurityProfile(SecurityProfile.PRESET_PERSONAL, "Personal", "shield", setOf(other), LockTimeoutMode.GRACE_15_MINUTES).toJson())
        val custom = "com.example.custom"
        profiles.put(SecurityProfile("profile_mine", "Mine", "shield", setOf(custom), LockTimeoutMode.IMMEDIATE).toJson())
        prefs.edit()
            .putString("security_profiles_json", profiles.toString())
            .putStringSet("locked_apps_set", setOf(chat, bank, other, custom))
            .putString("timeout_mode", "ON_SCREEN_OFF")
            .putInt("lock_model_version", 2)
            .commit()

        val vault = freshVault()
        assertEquals(LockTimeoutMode.ON_SCREEN_OFF, vault.getTimeoutMode(chat)) // was the built-in default
        assertEquals(LockTimeoutMode.ON_SCREEN_OFF, vault.getTimeoutMode(bank)) // Work's built-in 5 minutes
        assertEquals(LockTimeoutMode.GRACE_15_MINUTES, vault.getTimeoutMode(other)) // chosen by the owner
        assertEquals(LockTimeoutMode.IMMEDIATE, vault.getTimeoutMode(custom)) // the owner's own profile
        assertEquals(setOf(chat, bank, other, custom), vault.lockedAppsFlow.value)
        waitForWrites()
        assertEquals(3, prefs.getInt("lock_model_version", 0))
        assertEquals(LockTimeoutMode.ON_SCREEN_OFF, freshVault().getTimeoutMode(chat))
    }

    @Test
    fun anOlderBackupsUntouchedBuiltInProfilesFollowSettings() {
        val vault = freshVault()
        vault.addAppsToProfile(SecurityProfile.PRESET_SOCIAL, listOf(chat))
        vault.setTimeoutMode(LockTimeoutMode.ON_SCREEN_OFF)
        val backup = vault.exportState()
        // As version 2 wrote it: no model version, and the built-in delay spelled out.
        backup.remove("modelVersion")
        val social = backup.getJSONArray("profiles").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
            .first { it.getString("id") == SecurityProfile.PRESET_SOCIAL }
        social.put("timeoutMode", "IMMEDIATE")

        vault.restoreState(backup)
        assertEquals(LockTimeoutMode.ON_SCREEN_OFF, vault.getTimeoutMode(chat))
    }

    private fun seedEarlierProfiles() {
        val profiles = JSONArray().put(SecurityProfile.DEFAULTS[1].copy(packages = setOf(bank)).toJson().apply { remove("enabled") })
        prefs.edit()
            .putString("security_profiles_json", profiles.toString())
            .putStringSet("locked_apps_set", setOf(chat))
            .commit()
    }
}
