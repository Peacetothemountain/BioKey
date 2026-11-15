package com.android.BioKey

import android.app.Application
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.test.core.app.ApplicationProvider
import com.android.BioKey.core.security.AlsoLockedByAndroid
import com.android.BioKey.core.security.NewAppSuggestions
import com.android.BioKey.core.security.ReinstallMemory
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.core.security.UnlockAttempts
import org.junit.After
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
 * A locked app uninstalled with its data and installed again within 30 days is locked again, in
 * its old profile, when the same developer signed it; signed by anyone else it's only offered.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReinstallMemoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val bank = "com.example.bank"
    private lateinit var vault: SecurityVault
    /** Each package's signing certificates, as the test's phone has them installed now. */
    private val installed = HashMap<String, Set<String>>()
    private var now = 1_800_000_000_000L

    @Before
    fun reset() {
        SecurityVault.getInstance(context).awaitPendingWrites()
        context.getSharedPreferences("biokey_vault", Context.MODE_PRIVATE).edit().clear().commit()
        SecurityVault.resetForTests()
        ReinstallMemory.resetForTests(context)
        ReinstallMemory.signersOf = { _, pkg -> installed[pkg] }
        ReinstallMemory.now = { now }
        // As on a phone that has the network's time: its records are aged by it.
        ReinstallMemory.onNetworkTime = { true }
        vault = SecurityVault.getInstance(context)
        vault.setSetupCompletedSync(true)
        installed[bank] = setOf("aa11")
    }

    @After
    fun restore() = ReinstallMemory.resetForTests(context)

    /** Locked (its certificate noted as it's locked), then uninstalled with its data. */
    private fun lockThenUninstall(profileId: String? = null) {
        if (profileId != null) vault.moveAppToProfile(bank, profileId) else vault.setAppLock(bank, true)
        ReinstallMemory.note(context, listOf(bank))
        installed.remove(bank)
        ReinstallMemory.onUninstalled(context, bank)
        vault.onPackageUninstalled(bank)
        assertFalse("Dropped from BioKey on the uninstall", bank in vault.selectedAppsFlow.value)
    }

    @Test
    fun reinstalledByTheSameDeveloperItIsLockedAgainInItsProfile() {
        val profile = vault.createProfile("Money", "bank")
        lockThenUninstall(profile.id)
        installed[bank] = setOf("aa11")
        assertEquals(ReinstallMemory.Outcome.LOCKED_AGAIN, ReinstallMemory.onInstalled(context, bank))
        assertTrue(bank in vault.getLockedPackages())
        assertEquals(profile.id, vault.profileOf(bank)?.id)
        assertTrue("Remembered once only", ReinstallMemory.rememberedPackages(context).isEmpty())
    }

    @Test
    fun withItsProfileGoneItIsLockedWithNone() {
        val profile = vault.createProfile("Money", "bank")
        lockThenUninstall(profile.id)
        vault.deleteProfile(profile.id)
        installed[bank] = setOf("aa11")
        assertEquals(ReinstallMemory.Outcome.LOCKED_AGAIN, ReinstallMemory.onInstalled(context, bank))
        assertTrue(bank in vault.getLockedPackages())
        assertNull(vault.profileOf(bank))
    }

    @Test
    fun thePhonesDateSetAheadDoesNotMakeItForget() {
        // No network time (airplane mode since a restart, or Android 12): the note is dated on the
        // phone's own clock. Someone then sets the date ahead by more than 30 days and restarts
        // BioKey, so its "now" starts from the new date.
        ReinstallMemory.onNetworkTime = { false }
        val boot = UnlockAttempts.bootCount
        val elapsed = UnlockAttempts.elapsed
        try {
            UnlockAttempts.bootCount = { 7 }
            UnlockAttempts.elapsed = { 1_000_000L }
            lockThenUninstall()
            now += 40L * 24 * 60 * 60_000
            UnlockAttempts.elapsed = { 1_600_000L }
            ReinstallMemory.prune(context)
            assertEquals("Ten minutes later by the clock since boot: still remembered", listOf(bank), ReinstallMemory.rememberedPackages(context))
            installed[bank] = setOf("aa11")
            assertEquals(ReinstallMemory.Outcome.LOCKED_AGAIN, ReinstallMemory.onInstalled(context, bank))
        } finally {
            UnlockAttempts.bootCount = boot
            UnlockAttempts.elapsed = elapsed
        }
    }

    @Test
    fun thirtyDaysOnTheClockSinceBootIsThirtyDays() {
        // The same boot, and 31 days really gone by: forgotten, with or without the network's time.
        ReinstallMemory.onNetworkTime = { false }
        val boot = UnlockAttempts.bootCount
        val elapsed = UnlockAttempts.elapsed
        try {
            UnlockAttempts.bootCount = { 7 }
            UnlockAttempts.elapsed = { 1_000_000L }
            lockThenUninstall()
            UnlockAttempts.elapsed = { 1_000_000L + 31L * 24 * 60 * 60_000 }
            assertTrue(ReinstallMemory.rememberedPackages(context).isEmpty())
        } finally {
            UnlockAttempts.bootCount = boot
            UnlockAttempts.elapsed = elapsed
        }
    }

    @Test
    fun signedBySomeoneElseItIsOfferedNotLocked() {
        lockThenUninstall()
        installed[bank] = setOf("ff99")
        assertEquals(ReinstallMemory.Outcome.OFFER, ReinstallMemory.onInstalled(context, bank))
        assertFalse(bank in vault.selectedAppsFlow.value)
    }

    @Test
    fun withNoCertificateNotedItIsOfferedNotLocked() {
        // Never noted: it couldn't be read while installed.
        installed.remove(bank)
        vault.setAppLock(bank, true)
        ReinstallMemory.onUninstalled(context, bank)
        vault.onPackageUninstalled(bank)
        installed[bank] = setOf("aa11")
        assertEquals(ReinstallMemory.Outcome.OFFER, ReinstallMemory.onInstalled(context, bank))
        assertFalse(bank in vault.selectedAppsFlow.value)
    }

    @Test
    fun afterThirtyDaysItIsForgotten() {
        lockThenUninstall()
        now += ReinstallMemory.KEEP_MS + 1
        installed[bank] = setOf("aa11")
        assertEquals(ReinstallMemory.Outcome.NOTHING, ReinstallMemory.onInstalled(context, bank))
        assertFalse(bank in vault.selectedAppsFlow.value)
    }

    @Test
    fun afterThirtyDaysItIsGoneFromThePhoneAtTheNextUnlock() {
        lockThenUninstall()
        val kept = { context.getSharedPreferences("biokey_reinstall", Context.MODE_PRIVATE).getString("remembered", null).orEmpty() }
        assertTrue(kept(), bank in kept())
        // Within the month an unlock of the phone changes nothing.
        now += ReinstallMemory.KEEP_MS - 1
        ReinstallMemory.prune(context)
        assertTrue(kept(), bank in kept())
        // After it, no app need be installed or uninstalled for it to go: the next unlock does it.
        now += 2
        ReinstallMemory.prune(context)
        assertFalse(kept(), bank in kept())
    }

    @Test
    fun whatIsKeptThirtyDaysIsAgedEachTimeThePhoneIsUnlocked() {
        // The phone being unlocked is heard of by BioKey's own receiver, which ages both lists
        // on a thread of its own.
        lockThenUninstall()
        val start = System.currentTimeMillis()
        val seen = context.getSharedPreferences("biokey_android_app_lock", Context.MODE_PRIVATE)
        try {
            AlsoLockedByAndroid.clear(context)
            AlsoLockedByAndroid.clock = { start }
            AlsoLockedByAndroid.note(context, bank)
            now += ReinstallMemory.KEEP_MS + 1
            AlsoLockedByAndroid.clock = { start + AlsoLockedByAndroid.FORGET_AFTER_MS + 1 }
            context.sendBroadcast(Intent(Intent.ACTION_USER_PRESENT))
            val until = System.nanoTime() + 5_000_000_000L
            val remembered = { context.getSharedPreferences("biokey_reinstall", Context.MODE_PRIVATE).getString("remembered", null).orEmpty() }
            while (bank in remembered() || seen.all.isNotEmpty()) {
                check(System.nanoTime() < until) { "Nothing was aged at the unlock: ${remembered()} ${seen.all}" }
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                Thread.sleep(10)
            }
        } finally {
            AlsoLockedByAndroid.clock = System::currentTimeMillis
            AlsoLockedByAndroid.clear(context)
        }
    }

    @Test
    fun anAppThatWasntLockedIsntRemembered() {
        ReinstallMemory.onUninstalled(context, bank)
        assertTrue(ReinstallMemory.rememberedPackages(context).isEmpty())
        assertEquals(ReinstallMemory.Outcome.NOTHING, ReinstallMemory.onInstalled(context, bank))
    }

    @Test
    fun aRestoredBackupSaysAfreshWhatsLocked() {
        lockThenUninstall()
        vault.restoreState(vault.exportState())
        installed[bank] = setOf("aa11")
        assertEquals(ReinstallMemory.Outcome.NOTHING, ReinstallMemory.onInstalled(context, bank))
        assertFalse(bank in vault.selectedAppsFlow.value)
    }

    @Test
    fun aRotatedCertificateStillCountsAsTheSameDeveloper() {
        vault.setAppLock(bank, true)
        ReinstallMemory.note(context, listOf(bank))
        // An update signed with a new key that vouches for the old one.
        installed[bank] = setOf("aa11", "bb22")
        ReinstallMemory.onReplaced(context, bank)
        installed.remove(bank)
        ReinstallMemory.onUninstalled(context, bank)
        vault.onPackageUninstalled(bank)
        installed[bank] = setOf("bb22")
        assertEquals(ReinstallMemory.Outcome.LOCKED_AGAIN, ReinstallMemory.onInstalled(context, bank))
    }

    @Test
    fun theOfferForAnAppLockedBeforeComesEvenWithOffersOff() {
        shadowOf(context as Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        NewAppSuggestions.worker = java.util.concurrent.Executor { it.run() }
        val activity = ComponentName(bank, "$bank.Main")
        shadowOf(context.packageManager).addActivityIfNotPresent(activity)
        shadowOf(context.packageManager).addIntentFilterForActivity(activity, IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) })
        NewAppSuggestions.setEnabled(context, false)
        try {
            NewAppSuggestions.onInstalled(context, bank, "Bank", lockedBefore = true)
            val posted = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.single()
            assertEquals("Lock Bank again?", posted.extras.getCharSequence("android.title").toString())
        } finally {
            NewAppSuggestions.setEnabled(context, true)
        }
    }
}
