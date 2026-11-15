package com.android.BioKey

import com.android.BioKey.feature.main.HomeOffer
import com.android.BioKey.feature.main.HomeState
import com.android.BioKey.feature.main.chooseOffer
import com.android.BioKey.feature.main.eligibleOffers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Home makes at most one calm offer at a time, in a set order, and the one showing stays until it's answered. */
class HomeOffersTest {

    private val all = HomeOffer.entries.toSet()

    @Test
    fun theOffersComeInTheirOrder() {
        assertEquals(HomeOffer.AUTO_SORT, chooseOffer(all, null))
        assertEquals(HomeOffer.ADD_BIOMETRIC, chooseOffer(all - HomeOffer.AUTO_SORT, null))
        assertEquals(HomeOffer.LOCK_SETTINGS, chooseOffer(setOf(HomeOffer.LOCK_SETTINGS, HomeOffer.FAILED_ALERTS), null))
        assertEquals(HomeOffer.FAILED_ALERTS, chooseOffer(setOf(HomeOffer.FAILED_ALERTS), null))
        assertNull(chooseOffer(emptySet(), null))
    }

    @Test
    fun theOneShowingStaysUntilAnsweredThenTheNextComes() {
        // Failed unlock alerts showing; locking Settings becomes possible meanwhile: no swap.
        assertEquals(HomeOffer.FAILED_ALERTS, chooseOffer(setOf(HomeOffer.LOCK_SETTINGS, HomeOffer.FAILED_ALERTS), HomeOffer.FAILED_ALERTS))
        // Answered: it's no longer offered, and the next one comes.
        assertEquals(HomeOffer.LOCK_SETTINGS, chooseOffer(setOf(HomeOffer.LOCK_SETTINGS), HomeOffer.FAILED_ALERTS))
    }

    @Test
    fun whatAutoSortJustDidGoesFirstWhileItLasts() {
        assertEquals(HomeOffer.AUTO_SORT, chooseOffer(setOf(HomeOffer.AUTO_SORT, HomeOffer.FAILED_ALERTS), HomeOffer.FAILED_ALERTS))
    }

    private fun state(
        strong: Boolean = true,
        canAdd: Boolean = false,
        addDismissed: Boolean? = false,
        lockSettingsDismissed: Boolean? = false,
        autoSort: Int = 0
    ) = HomeState(
        hasStrongBiometric = strong, alertsMuted = false, remindersStopped = false, lockSettingsDismissed = lockSettingsDismissed,
        canAddBiometric = canAdd, addBiometricDismissed = addDismissed, autoSortLocked = autoSort
    )

    @Test
    fun eachOfferIsMadeOnlyWhereItCanHelp() {
        // A face or fingerprint set up, alerts not yet asked about, Settings worth locking: both.
        assertEquals(
            setOf(HomeOffer.LOCK_SETTINGS, HomeOffer.FAILED_ALERTS),
            eligibleOffers(state(), setupCompleted = true, attemptsChosen = false, screenLockMissing = false, lockSettingsWanted = true)
        )
        // None set up: only adding one is offered (the others need it).
        assertEquals(
            setOf(HomeOffer.ADD_BIOMETRIC),
            eligibleOffers(state(strong = false, canAdd = true), setupCompleted = true, attemptsChosen = false, screenLockMissing = false, lockSettingsWanted = true)
        )
        // Put away, or not yet read: not offered.
        assertEquals(
            emptySet<HomeOffer>(),
            eligibleOffers(state(lockSettingsDismissed = null), setupCompleted = true, attemptsChosen = true, screenLockMissing = false, lockSettingsWanted = true)
        )
        assertEquals(
            setOf(HomeOffer.AUTO_SORT),
            eligibleOffers(state(strong = false, autoSort = 4), setupCompleted = true, attemptsChosen = true, screenLockMissing = false, lockSettingsWanted = false)
        )
    }
}
