package com.android.BioKey

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.test.core.app.ApplicationProvider
import com.android.BioKey.core.data.LockTimeoutMode
import com.android.BioKey.core.data.SecurityProfile
import com.android.BioKey.core.security.FrontApp
import com.android.BioKey.core.security.LockStateTracker
import com.android.BioKey.core.security.SecurityVault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * "Lock this app" from Quick Settings: the app the shade came down over is locked (where auto-sort
 * would file it, or with no profile), never a home screen, BioKey or the shade itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LockThisAppTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val bank = "com.example.bank"
    private val game = "com.example.puzzle"
    private val launcher = "com.example.launcher"
    private lateinit var vault: SecurityVault
    private val notHome: (String) -> Boolean = { it == launcher }

    @Before
    fun reset() {
        SecurityVault.getInstance(context).awaitPendingWrites()
        context.getSharedPreferences("biokey_vault", Context.MODE_PRIVATE).edit().clear().commit()
        SecurityVault.resetForTests()
        vault = SecurityVault.getInstance(context)
        vault.setSetupCompletedSync(true)
        LockStateTracker.lockAll()
        FrontApp.setForTests(null)
        for (pkg in listOf(bank, game, launcher)) {
            val activity = ComponentName(pkg, "$pkg.Main")
            shadowOf(context.packageManager).addActivityIfNotPresent(activity)
            shadowOf(context.packageManager).addIntentFilterForActivity(activity, IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) })
        }
    }

    @Test
    fun theShadeIsNeverTheAppInFront() {
        FrontApp.onFront(bank)
        FrontApp.onFront("com.android.systemui")
        FrontApp.onFront("android")
        assertEquals(bank, FrontApp.last)
    }

    @Test
    fun anAppAutoSortRecognisesGoesInItsProfile() {
        val outcome = FrontApp.lock(context, bank, notHome)
        assertTrue(outcome is FrontApp.Outcome.Locked)
        assertTrue(bank in vault.getLockedPackages())
        assertEquals(SecurityProfile.PRESET_FINANCE, vault.profileOf(bank)?.id)
    }

    @Test
    fun anAppItDoesntIsLockedWithNoProfile() {
        vault.setAutoSortProfilesEnabled(false)
        FrontApp.lock(context, bank, notHome)
        assertTrue(bank in vault.getLockedPackages())
        assertNull(vault.profileOf(bank))
        FrontApp.lock(context, game, notHome)
        assertTrue(game in vault.getLockedPackages())
    }

    @Test
    fun neverIntoAProfileThatsOff() {
        vault.setProfileEnabled(SecurityProfile.PRESET_FINANCE, false)
        FrontApp.lock(context, bank, notHome)
        assertTrue("Locked, with no profile, rather than filed where it wouldn't be", bank in vault.getLockedPackages())
        assertNull(vault.profileOf(bank))
    }

    @Test
    fun anAppInAProfileThatsOffSaysSo() {
        val profile = vault.createProfile("Games", "shield")
        vault.moveAppToProfile(game, profile.id, switchOn = false)
        vault.setProfileEnabled(profile.id, false)
        assertEquals(FrontApp.Outcome.ProfileOff(game, "Games"), FrontApp.lock(context, game, notHome))
        assertFalse(game in vault.getLockedPackages())
    }

    @Test
    fun alreadyLockedItsSessionEnds() {
        vault.setAppLock(bank, true)
        LockStateTracker.onAppUnlocked(bank)
        assertTrue(LockStateTracker.isAppUnlocked(bank, LockTimeoutMode.GRACE_5_MINUTES))
        FrontApp.lock(context, bank, notHome)
        assertFalse(LockStateTracker.isAppUnlocked(bank, LockTimeoutMode.GRACE_5_MINUTES))
    }

    @Test
    fun homeScreensBioKeyAndUnknownAppsAreNeverLocked() {
        assertEquals(FrontApp.Outcome.NothingToLock, FrontApp.lock(context, launcher, notHome))
        assertEquals(FrontApp.Outcome.NothingToLock, FrontApp.lock(context, context.packageName, notHome))
        assertEquals(FrontApp.Outcome.NothingToLock, FrontApp.lock(context, "com.example.nolauncher", notHome))
        assertEquals(FrontApp.Outcome.NothingToLock, FrontApp.lock(context, null, notHome))
        assertTrue(vault.selectedAppsFlow.value.isEmpty())
    }
}
