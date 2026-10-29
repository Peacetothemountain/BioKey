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
