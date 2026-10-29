package com.android.BioKey

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.android.BioKey.core.security.ProtectionGaps
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.core.security.UnlockAttempts
import com.android.BioKey.feature.notification.BioKeyNotificationShieldService
import com.android.BioKey.feature.notification.ShieldAccessWatch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Notification access taken away under the shield is recorded once; the owner's own switch-off isn't. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShieldAccessWatchTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var access = true
    private val vault get() = SecurityVault.getInstance(context)
    private val revoked get() = ProtectionGaps.gaps.value.count { it.kind == ProtectionGaps.Kind.SHIELD_ACCESS_REVOKED }

    @Before
    fun reset() {
        vault.awaitPendingWrites()
        listOf("biokey_vault", "biokey_shield_access", "biokey_protection_gaps", "biokey_unlock_attempts").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        SecurityVault.resetForTests()
        ProtectionGaps.resetForTests()
        UnlockAttempts.resetForTests()
        vault.setSetupCompletedSync(true)
        ShieldAccessWatch.hasAccess = { access }
    }

    @After
    fun restore() {
        ShieldAccessWatch.hasAccess = BioKeyNotificationShieldService::isNotificationAccessGranted
    }

    @Test
    fun accessTakenAwayUnderTheShieldIsRecordedOnce() {
        vault.setNotificationShield(true)
        ShieldAccessWatch.check(context)
        access = false
        assertTrue(ShieldAccessWatch.shieldWithoutAccess(context))
        ShieldAccessWatch.check(context)
        ShieldAccessWatch.check(context)
        assertEquals(1, revoked)
    }

    @Test
    fun accessBackEndsTheRecordWithItsTime() {
        var now = 1_800_000_000_000L
        ProtectionGaps.clock = { now }
        try {
            vault.setNotificationShield(true)
            ShieldAccessWatch.check(context)
            access = false
            ShieldAccessWatch.check(context)
            now += 10 * 60_000L
            access = true
            ShieldAccessWatch.check(context)
            val record = ProtectionGaps.gaps.value.single { it.kind == ProtectionGaps.Kind.SHIELD_ACCESS_REVOKED }
            assertEquals(10 * 60_000L, record.to - record.from)
        } finally {
            ProtectionGaps.clock = com.android.BioKey.core.security.TrustedClock::now
        }
    }

    @Test
    fun undoingASwitchOffReopensOnlyTheRecordItEnded() {
        var now = 1_800_000_000_000L
        ProtectionGaps.clock = { now }
        try {
            vault.setNotificationShield(true)
            ShieldAccessWatch.check(context)
            access = false
            ShieldAccessWatch.check(context)
            now += 60_000L
            vault.setNotificationShield(false)
            val ended = ShieldAccessWatch.switchOffSoon(context)
            ShieldAccessWatch.awaitForTests()
            val record = ProtectionGaps.gaps.value.single { it.kind == ProtectionGaps.Kind.SHIELD_ACCESS_REVOKED }
            assertTrue(record.to > record.from)
            // Another look while hiding is off (home resumed, say) doesn't lose what Undo reopens.
            ShieldAccessWatch.check(context)
            // Undo: on again, still without access: the same record is open again.
            vault.setNotificationShield(true)
            ShieldAccessWatch.undoSwitchOffSoon(context, ended)
            ShieldAccessWatch.awaitForTests()
            val reopened = ProtectionGaps.gaps.value.single { it.kind == ProtectionGaps.Kind.SHIELD_ACCESS_REVOKED }
            assertEquals(reopened.from, reopened.to)
            // Access back: it ends then.
            now += 60_000L
            access = true
            ShieldAccessWatch.check(context)
            val closed = ProtectionGaps.gaps.value.single { it.kind == ProtectionGaps.Kind.SHIELD_ACCESS_REVOKED }
            assertEquals(2 * 60_000L, closed.to - closed.from)
        } finally {
            ProtectionGaps.clock = com.android.BioKey.core.security.TrustedClock::now
        }
    }

    @Test
    fun undoingASwitchOffThatEndedNothingReopensNothing() {
        var now = 1_800_000_000_000L
        ProtectionGaps.clock = { now }
        try {
            // An earlier record, ended by an earlier switch-off.
            vault.setNotificationShield(true)
            ShieldAccessWatch.check(context)
            access = false
            ShieldAccessWatch.check(context)
            now += 60_000L
            vault.setNotificationShield(false)
            ShieldAccessWatch.switchOffSoon(context).get()
            // On again without access (no record: it never had it this time), off again, Undo.
            vault.setNotificationShield(true)
            ShieldAccessWatch.check(context)
            now += 60_000L
            vault.setNotificationShield(false)
            val ended = ShieldAccessWatch.switchOffSoon(context)
            vault.setNotificationShield(true)
            ShieldAccessWatch.undoSwitchOffSoon(context, ended)
            ShieldAccessWatch.awaitForTests()
            val record = ProtectionGaps.gaps.value.single { it.kind == ProtectionGaps.Kind.SHIELD_ACCESS_REVOKED }
            assertTrue(record.to > record.from)
        } finally {
            ProtectionGaps.clock = com.android.BioKey.core.security.TrustedClock::now
        }
    }

    @Test
    fun theShieldSwitchedOffInBioKeyIsntRecorded() {
        vault.setNotificationShield(true)
        ShieldAccessWatch.check(context)
        vault.setNotificationShield(false)
        ShieldAccessWatch.check(context)
        access = false
        ShieldAccessWatch.check(context)
        assertEquals(0, revoked)
        assertFalse(ShieldAccessWatch.shieldWithoutAccess(context))
    }
}
