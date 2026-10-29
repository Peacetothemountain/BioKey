package com.android.BioKey

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.android.BioKey.core.security.ProtectionGaps
import com.android.BioKey.core.security.SecurityVault
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
 * Times app locking was off: switched off, or stopped while the phone was in use. Not a restart
 * of the phone, an update of BioKey, Android stopping it while the screen was off, or a moment's
 * handover.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProtectionGapsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var now = 1_800_000_000_000L
    private var boot = 7
    private var installed = 1_000L

    @Before
    fun reset() {
        context.getSharedPreferences("biokey_protection_gaps", Context.MODE_PRIVATE).edit().clear().commit()
        ProtectionGaps.resetForTests()
        ProtectionGaps.clock = { now }
        ProtectionGaps.bootCount = { boot }
        ProtectionGaps.installTime = { installed }
        SecurityVault.getInstance(context).setSetupCompletedSync(true)
        ProtectionGaps.onEngineUp(context)
    }

    @After
    fun restore() {
        ProtectionGaps.clock = System::currentTimeMillis
        ProtectionGaps.resetForTests()
    }

    private val gaps get() = ProtectionGaps.gaps.value

    @Test
    fun switchedOffThenBackOnIsAGap() {
        now += 60_000L
        ProtectionGaps.onEngineDown(context)
        now += 8 * 60_000L
        ProtectionGaps.onEngineUp(context)
        val gap = gaps.single()
        assertEquals(8 * 60_000L, gap.to - gap.from)
        assertFalse(gap.unannounced)
    }

    @Test
    fun aHandoverIsNoGap() {
        ProtectionGaps.onEngineDown(context)
        now += 3_000L
        ProtectionGaps.onEngineUp(context)
        assertTrue(gaps.isEmpty())
    }

    @Test
    fun stoppedWithoutAWordWhileInUseIsAGap() {
        now += 30_000L
        ProtectionGaps.touch(context, screenOn = true)
        // Force-stopped here: nothing is told. Back five minutes later.
        now += 5 * 60_000L
        ProtectionGaps.onEngineUp(context)
        assertTrue(gaps.single().unannounced)
    }

    @Test
    fun stoppedWhileTheScreenWasOffIsNoGap() {
        ProtectionGaps.touch(context, screenOn = false)
        now += 60 * 60_000L
        ProtectionGaps.onEngineUp(context)
        assertTrue(gaps.isEmpty())
    }

    @Test
    fun aRestartOfThePhoneIsNoGap() {
        ProtectionGaps.touch(context, screenOn = true)
        boot++
        now += 10 * 60_000L
        ProtectionGaps.onEngineUp(context)
        assertTrue(gaps.isEmpty())
    }

    @Test
    fun anUpdateOfBioKeyIsNoGap() {
        ProtectionGaps.touch(context, screenOn = true)
        installed += 1
        now += 10 * 60_000L
        ProtectionGaps.onEngineUp(context)
        assertTrue(gaps.isEmpty())
    }

    @Test
    fun clearCanBeUndone() {
        ProtectionGaps.onEngineDown(context)
        now += 5 * 60_000L
        ProtectionGaps.onEngineUp(context)
        val removed = ProtectionGaps.clear(context)
        assertTrue(gaps.isEmpty())
        ProtectionGaps.restore(context, removed)
        assertEquals(1, gaps.size)
    }
}
