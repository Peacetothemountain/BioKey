package com.android.BioKey

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.android.BioKey.core.security.ProtectionGaps
import com.android.BioKey.core.security.TrustedClock
import com.android.BioKey.core.security.UnlockAttempts
import com.android.BioKey.feature.main.HomeViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Home's Review: opened once per tap, and what it shows counts as seen once the owner is proven. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HomeViewModelTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var vm: HomeViewModel

    @Before
    fun reset() {
        listOf("biokey_unlock_attempts", "biokey_protection_gaps").forEach {
            app.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        UnlockAttempts.resetForTests()
        ProtectionGaps.resetForTests()
        // No face or fingerprint set up here: the owner is proven unless a hold says otherwise.
        com.android.BioKey.core.security.SecurityEngine.enrolledForProof = { false }
        UnlockAttempts.hasStrongBiometric = { true }
        UnlockAttempts.setEnabled(app, true)
        vm = HomeViewModel(app)
    }

    @After
    fun restore() {
        com.android.BioKey.core.security.SecurityEngine.enrolledForProof = { com.android.BioKey.core.security.SecurityEngine.hasEnrolledStrongBiometric(it) }
        UnlockAttempts.hasStrongBiometric = com.android.BioKey.core.security.SecurityEngine::hasEnrolledStrongBiometric
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun bioKeyLockingItselfForgetsHowItWasOpened() {
        // Opened with a fingerprint, on a phone that has one: the owner is proven, until BioKey locks.
        val engine = com.android.BioKey.core.security.SecurityEngine
        engine.enrolledForProof = { true }
        engine.INSTANCE.ownAppOpenedWithBiometric = true
        assertTrue(engine.ownerProven(app))
        com.android.BioKey.core.security.LockStateTracker.lockOwnApp()
        assertTrue(!engine.ownerProven(app))
    }

    @Test
    fun aSecondTapWhileReviewIsWorkedOutOpensItOnce() {
        var opened = 0
        vm.openReview { opened++ }
        vm.openReview { opened++ }
        idle()
        Thread.sleep(200)
        idle()
        assertEquals(1, opened)
        assertNotNull(vm.review.value)
    }

    @Test
    fun whatReviewShowsCountsAsSeenWhenTheOwnerIsProven() {
        UnlockAttempts.onBiometricFailed(app, "com.example.bank", "Bank", null)
        val shown = UnlockAttempts.attempts.value.single().seq
        vm.openReview { }
        idle()
        Thread.sleep(200)
        idle()
        runBlocking { vm.reviewShowing(shown, shown) }
        assertEquals(shown, UnlockAttempts.seenSeq(app))
    }

    @Test
    fun whenTheHoldEndsWithReviewOpenWhatItShowsCountsAsSeen() {
        UnlockAttempts.onBiometricFailed(app, "com.example.bank", "Bank", null)
        val shown = UnlockAttempts.attempts.value.single().seq
        vm.openReview { }
        idle()
        Thread.sleep(200)
        idle()
        // A hold that has just ended: worked out again, the owner proven, so it counts as seen.
        runBlocking { vm.recheckWhenHoldEnds(TrustedClock.now() - 2_000L) { shown } }
        assertTrue(UnlockAttempts.seenSeq(app) >= shown)
    }
}
