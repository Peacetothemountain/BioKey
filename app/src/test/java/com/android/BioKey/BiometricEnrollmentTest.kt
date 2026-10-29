package com.android.BioKey

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.android.BioKey.core.security.BiometricEnrollment
import com.android.BioKey.core.security.BiometricEnrollment.Change
import com.android.BioKey.core.security.ProtectionGaps
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.core.security.UnlockAttempts
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A face or fingerprint added, or all of them removed, shows in security activity. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BiometricEnrollmentTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var has = true
    private var key = BiometricEnrollment.KeyState.VALID

    @Before
    fun reset() {
        BiometricEnrollment.backgroundChecks = false
        BiometricEnrollment.awaitBackgroundForTests()
        listOf("biokey_biometric_enrollment", "biokey_protection_gaps", "biokey_unlock_attempts").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        ProtectionGaps.resetForTests()
        UnlockAttempts.resetForTests()
        ProtectionGaps.load(context)
        SecurityVault.getInstance(context).setSetupCompletedSync(true)
        BiometricEnrollment.hasBiometric = { has }
        BiometricEnrollment.keyState = { key }
        BiometricEnrollment.renewKeyIfNeeded = { key = BiometricEnrollment.KeyState.VALID }
        BiometricEnrollment.check(context)
    }

    @After
    fun restore() {
        BiometricEnrollment.resetForTests()
        BiometricEnrollment.backgroundChecks = true
    }

    private val events get() = ProtectionGaps.gaps.value.map { it.kind }

    @Test
    fun nothingChangedNothingRecorded() {
        assertEquals(Change.NONE, BiometricEnrollment.check(context))
        assertTrue(events.isEmpty())
    }

    @Test
    fun aFingerprintAddedIsRecorded() {
        key = BiometricEnrollment.KeyState.INVALIDATED
        assertEquals(Change.CHANGED, BiometricEnrollment.check(context))
        assertEquals(listOf(ProtectionGaps.Kind.BIOMETRICS_CHANGED), events)
    }

    @Test
    fun aKeyGoneOnceMadeIsAChange() {
        // Removing the screen lock and setting it again destroys the key: that's when a fingerprint can be slipped in.
        key = BiometricEnrollment.KeyState.MISSING
        assertEquals(Change.CHANGED, BiometricEnrollment.check(context))
    }

    @Test
    fun theFirstOneAddedIsRecorded() {
        has = false
        BiometricEnrollment.check(context)
        val removedFirst = events.size
        has = true
        assertEquals(Change.CHANGED, BiometricEnrollment.check(context))
        assertEquals(removedFirst + 1, events.size)
    }

    @Test
    fun aFirstOneOnAPinOnlyPhoneIsRecorded() {
        // BioKey set up on a phone with only a PIN; someone who knows it adds a fingerprint.
        context.getSharedPreferences("biokey_biometric_enrollment", Context.MODE_PRIVATE).edit().clear().commit()
        has = false
        assertEquals(Change.NONE, BiometricEnrollment.check(context))
        has = true
        assertEquals(Change.CHANGED, BiometricEnrollment.check(context))
        assertEquals(listOf(ProtectionGaps.Kind.BIOMETRICS_CHANGED), events)
    }

    @Test
    fun oneSetUpDuringBioKeysOwnSetupIsNotRecorded() {
        context.getSharedPreferences("biokey_biometric_enrollment", Context.MODE_PRIVATE).edit().clear().commit()
        val vault = SecurityVault.getInstance(context)
        vault.setSetupCompletedSync(false)
        try {
            has = false
            BiometricEnrollment.check(context)
            // Setup sent the owner to set up a screen lock and a fingerprint.
            has = true
            assertEquals(Change.NONE, BiometricEnrollment.check(context))
            assertTrue(events.isEmpty())
        } finally {
            vault.setSetupCompletedSync(true)
        }
    }

    @Test
    fun theyCantBeClearedAway() {
        key = BiometricEnrollment.KeyState.INVALIDATED
        BiometricEnrollment.check(context)
        ProtectionGaps.clear(context)
        assertEquals(listOf(ProtectionGaps.Kind.BIOMETRICS_CHANGED), events)
    }

    @Test
    fun allOfThemRemovedIsRecorded() {
        has = false
        assertEquals(Change.REMOVED, BiometricEnrollment.check(context))
        assertEquals(listOf(ProtectionGaps.Kind.BIOMETRICS_REMOVED), events)
        // Once: still none next time is no new event.
        BiometricEnrollment.check(context)
        assertEquals(1, events.size)
    }
}
