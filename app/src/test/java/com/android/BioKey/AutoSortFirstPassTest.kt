package com.android.BioKey

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.android.BioKey.core.data.AppCategory
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.feature.main.AutoSortPass
import com.android.BioKey.feature.main.HomeViewModel
import com.android.BioKey.feature.main.lastAutoSortLocked
import com.android.BioKey.feature.main.markAutoSortNotePending
import com.android.BioKey.ui.disclosure.Disclosure
import com.android.BioKey.ui.disclosure.DisclosureAgreements
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The first home after setup: the apps auto-sort locks join "Locked" (the owner's own switches
 * still hold still), home says once how many it locked, and setup's Agree to a disclosure is
 * remembered for that setup only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutoSortFirstPassTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun reset() {
        listOf("biokey_vault", "biokey_home", "biokey_disclosures").forEach {
            app.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        SecurityVault.resetForTests()
        lastAutoSortLocked = null
    }

    private val installed = listOf("com.example.chase", "com.example.signal", "com.example.maps", "com.example.photos")

    @Test
    fun groupsFollowWhatAutoSortLocked() {
        val vault = SecurityVault.getInstance(app)
        // Taken as the list appeared: nothing locked yet (a fresh setup).
        val groups: Set<String> = emptySet()
        val before = vault.getLockedPackages()
        vault.autoSortAppsIntoProfiles(
            listOf(
                "com.example.chase" to AppCategory.FINANCE, "com.example.signal" to AppCategory.SOCIAL,
                "com.example.maps" to AppCategory.GENERAL, "com.example.photos" to AppCategory.PRIVACY
            )
        )
        val newly = AutoSortPass.newlyLocked(installed, before, vault.getLockedPackages())
        assertEquals(setOf("com.example.chase", "com.example.signal", "com.example.photos"), newly)
        assertEquals(newly, AutoSortPass.regroup(groups, newly))
    }

    @Test
    fun theOwnersOwnSwitchesStillHoldStill() {
        // Taken as the list appeared: Maps locked. Since then the owner unlocked Maps (it stays
        // under "Locked" while they're here), and auto-sort locked Chase (it joins at once).
        val groups = setOf("com.example.maps")
        val newly = AutoSortPass.newlyLocked(installed, before = setOf("com.example.maps"), after = setOf("com.example.chase"))
        assertEquals(setOf("com.example.chase"), newly)
        assertEquals(setOf("com.example.maps", "com.example.chase"), AutoSortPass.regroup(groups, newly))
        // Nothing new: the groups are left as they were. Not taken yet: the live state rules.
        assertEquals(groups, AutoSortPass.regroup(groups, emptySet()))
        assertNull(AutoSortPass.regroup(null, newly))
    }

    @Test
    fun homeSaysOnceHowManyAppsSetupsAutoSortLocked() {
        markAutoSortNotePending(app)
        val vm = HomeViewModel(app)
        vm.autoSortPassed(9)
        assertEquals(9, vm.state.value.autoSortLocked)
        // Kept for the next look at home until seen, then never again.
        assertEquals(9, HomeViewModel(app).state.value.autoSortLocked)
        vm.dismissAutoSortNote()
        assertEquals(0, vm.state.value.autoSortLocked)
        assertFalse(app.getSharedPreferences("biokey_home", Context.MODE_PRIVATE).contains("auto_sort_note"))
        // Later passes (a new app installed) aren't setup's: no card.
        vm.autoSortPassed(1)
        assertEquals(0, vm.state.value.autoSortLocked)
    }

    @Test
    fun withoutSetupAutoSortPassesSayNothing() {
        val vm = HomeViewModel(app)
        vm.autoSortPassed(5)
        assertEquals(0, vm.state.value.autoSortLocked)
    }

    @Test
    fun setupsPassLockingNothingShowsNoCard() {
        markAutoSortNotePending(app)
        val vm = HomeViewModel(app)
        vm.autoSortPassed(0)
        assertEquals(0, vm.state.value.autoSortLocked)
        vm.autoSortPassed(3)
        assertEquals(0, vm.state.value.autoSortLocked)
    }

    @Test
    fun anAgreeIsRememberedForThisSetupOnly() {
        assertFalse(DisclosureAgreements.agreed(app, Disclosure.ACCESSIBILITY))
        DisclosureAgreements.record(app, Disclosure.ACCESSIBILITY)
        assertTrue(DisclosureAgreements.agreed(app, Disclosure.ACCESSIBILITY))
        // Each disclosure on its own: agreeing to one isn't agreeing to the other.
        assertFalse(DisclosureAgreements.agreed(app, Disclosure.COMPATIBILITY))
        assertNotEquals(
            DisclosureAgreements.fingerprint(app, Disclosure.ACCESSIBILITY),
            DisclosureAgreements.fingerprint(app, Disclosure.COMPATIBILITY)
        )
        // What was agreed to changed: asked again.
        app.getSharedPreferences("biokey_disclosures", Context.MODE_PRIVATE).edit().putString(Disclosure.ACCESSIBILITY.name, "older words").commit()
        assertFalse(DisclosureAgreements.agreed(app, Disclosure.ACCESSIBILITY))
        // Setup finished: it counts for nothing (Settings and home always ask).
        DisclosureAgreements.record(app, Disclosure.ACCESSIBILITY)
        SecurityVault.getInstance(app).setSetupCompletedSync(true)
        assertFalse(DisclosureAgreements.agreed(app, Disclosure.ACCESSIBILITY))
    }
}
