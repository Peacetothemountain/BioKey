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
    private var invalidated = false

    @Before
    fun reset() {
        listOf("biokey_biometric_enrollment", "biokey_protection_gaps", "biokey_unlock_attempts").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        ProtectionGaps.resetForTests()
        UnlockAttempts.resetForTests()
        ProtectionGaps.load(context)
        SecurityVault.getInstance(context).setSetupCompletedSync(true)
        BiometricEnrollment.hasBiometric = { has }
        BiometricEnrollment.keyInvalidated = { invalidated }
        BiometricEnrollment.check(context)
    }

    @After
    fun restore() = BiometricEnrollment.resetForTests()

    private val events get() = ProtectionGaps.gaps.value.map { it.kind }

    @Test
    fun nothingChangedNothingRecorded() {
        assertEquals(Change.NONE, BiometricEnrollment.check(context))
        assertTrue(events.isEmpty())
    }

    @Test
    fun aFingerprintAddedIsRecorded() {
        invalidated = true
        assertEquals(Change.CHANGED, BiometricEnrollment.check(context))
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
