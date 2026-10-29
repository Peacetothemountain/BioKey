package com.android.BioKey

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.feature.lock.UsageReader
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.shadows.ShadowUsageStatsManager

/** Compatibility mode's look at the screen: idle with nothing to protect, a look back first, then only what's new. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UsageReaderTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val usage = context.getSystemService(UsageStatsManager::class.java)
    private val vault get() = SecurityVault.getInstance(context)
    private var lockChecks = 0
    private val reader by lazy { UsageReader(context, usage, vault, epoch = { 7 }, deviceLockedNow = { lockChecks++; false }) }

    @Before
    fun reset() {
        // A vault of its own: no app locked by an earlier test.
        vault.awaitPendingWrites()
        context.getSharedPreferences("biokey_vault", Context.MODE_PRIVATE).edit().clear().commit()
        SecurityVault.resetForTests()
        // A look back never reaches before this boot: the phone has been up a while.
        ShadowSystemClock.advanceBy(Duration.ofMinutes(10))
        vault.setSetupCompletedSync(true)
        vault.setGlobalLockEnabled(true)
    }

    private fun resumed(pkg: String, at: Long) {
        shadowOf(usage).addEvent(
            ShadowUsageStatsManager.EventBuilder.buildEvent()
                .setPackage(pkg).setClass("$pkg.Main").setTimeStamp(at).setEventType(UsageEvents.Event.ACTIVITY_RESUMED).build()
        )
    }

    @Test
    fun nothingLockedIsIdleAndReadsNothing() {
        vault.getLockedPackages().forEach { vault.setAppLock(it, false) }
        resumed("com.example.bank", System.currentTimeMillis() - 1_000L)
        val batch = reader.read()
        assertTrue(batch.idle)
        assertTrue(batch.events.isEmpty())
        assertEquals(7, batch.epoch)
    }

    @Test
    fun theFirstLookIsALookBackThenOnlyWhatsNew() {
        vault.setAppLock("com.example.bank", true)
        val now = System.currentTimeMillis()
        resumed("com.example.bank", now - 5_000L)
        reader.lookBackNext()
        val first = reader.read()
        assertTrue(first.fresh)
        assertEquals(listOf("com.example.bank"), first.events.map { it.pkg })
        // A look back asks whether the phone is locked at once.
        assertEquals(1, lockChecks)
        // The next look reads only from where that one ended (with its overlap): nothing new.
        val second = reader.read()
        assertFalse(second.fresh)
        assertTrue(second.from >= first.readAt - UsageReader.OVERLAP_MS)
    }

    @Test
    fun aChangedClockReadsAfreshFromNow() {
        vault.setAppLock("com.example.bank", true)
        reader.read()
        reader.rebase()
        assertNotNull(reader.read().rebasedFrom)
    }

    @Test
    fun theLockStateIsAskedWhenToldTo() {
        vault.setAppLock("com.example.bank", true)
        reader.read()
        val before = lockChecks
        reader.askLockStateNext()
        reader.read()
        assertEquals(before + 1, lockChecks)
    }
}
