package com.android.BioKey

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.android.BioKey.core.security.ProtectionGaps
import com.android.BioKey.core.security.ScreenLockWatch
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.core.security.TrustedClock
import com.android.BioKey.core.security.UnlockAttempts
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The screen lock removed after setup: locked apps open without a check rather than never, and
 * that's recorded from when it was noticed until it's back, and said on home meanwhile.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScreenLockWatchTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var screenLock = true
    private val records get() = ProtectionGaps.gaps.value.filter { it.kind == ProtectionGaps.Kind.NO_SCREEN_LOCK }

    @Before
    fun reset() {
        SecurityVault.getInstance(context).awaitPendingWrites()
        listOf("biokey_vault", "biokey_protection_gaps", "biokey_unlock_attempts").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        SecurityVault.resetForTests()
        ProtectionGaps.resetForTests()
        UnlockAttempts.resetForTests()
        ScreenLockWatch.resetForTests(context)
        SecurityVault.getInstance(context).setSetupCompletedSync(true)
        ScreenLockWatch.hasScreenLock = { screenLock }
    }

    @After
    fun restore() {
        ScreenLockWatch.resetForTests(context)
        ProtectionGaps.clock = TrustedClock::now
    }

    @Test
    fun aScreenLockRemovedIsRecordedOnceAndShownUntilItsBack() {
        var now = 1_800_000_000_000L
        ProtectionGaps.clock = { now }
        ScreenLockWatch.check(context)
        assertFalse(ScreenLockWatch.missing.value)

        screenLock = false
        ScreenLockWatch.check(context)
        ScreenLockWatch.check(context)
        assertTrue(ScreenLockWatch.missing.value)
        assertEquals(1, records.size)

        now += 20 * 60_000L
        screenLock = true
        ScreenLockWatch.check(context)
        assertFalse(ScreenLockWatch.missing.value)
        val record = records.single()
        assertEquals("From when it was noticed until it was back", 20 * 60_000L, record.to - record.from)
    }

    @Test
    fun aPhoneNeverSeenWithAScreenLockIsNotNewsUntilAPromptFails() {
        screenLock = false
        ScreenLockWatch.check(context)
        assertFalse(ScreenLockWatch.missing.value)
        assertTrue(records.isEmpty())

        assertTrue(ScreenLockWatch.letThroughWithoutScreenLock(context, "com.example.bank"))
        assertTrue(ScreenLockWatch.missing.value)
        assertEquals(1, records.size)
        // Every locked app it lets through belongs to the same record.
        assertTrue(ScreenLockWatch.letThroughWithoutScreenLock(context, "com.example.chat"))
        assertEquals(1, records.size)
    }

    @Test
    fun withAScreenLockAFailedPromptIsNeverLetThrough() {
        assertFalse(ScreenLockWatch.letThroughWithoutScreenLock(context, "com.example.bank"))
        assertTrue(records.isEmpty())
        assertFalse(ScreenLockWatch.missing.value)
    }

    @Test
    fun theAddAFingerprintCardComesBackOnceTheScreenLockGoes() {
        ScreenLockWatch.check(context)
        ScreenLockWatch.dismissAddBiometric(context)
        assertTrue(ScreenLockWatch.addBiometricDismissed(context))

        screenLock = false
        ScreenLockWatch.check(context)
        assertFalse(ScreenLockWatch.addBiometricDismissed(context))
    }
}
