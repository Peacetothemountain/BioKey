package com.android.BioKey

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.android.BioKey.core.data.OldBuildLeftovers
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What the unreleased 2.0.0-fortress build left on a phone goes as BioKey starts; nothing else does. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OldBuildLeftoversTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val real = OldBuildLeftovers.deleteKeys
    private val deleted = mutableListOf<String>()

    private fun folder() = File(context.createDeviceProtectedStorageContext().noBackupFilesDir, "vault_v2")

    @Before
    fun setUp() {
        OldBuildLeftovers.deleteKeys = { deleted += it }
        folder().deleteRecursively()
    }

    @After
    fun tearDown() {
        OldBuildLeftovers.deleteKeys = real
        folder().deleteRecursively()
    }

    @Test
    fun onAPhoneThatNeverHadThatBuildNothingIsTouched() {
        assertFalse(OldBuildLeftovers.clear(context))
        assertTrue(deleted.isEmpty())
    }

    @Test
    fun itsSettingsAndItsKeysGo() {
        folder().mkdirs()
        File(folder(), "policy.bin").writeBytes(byteArrayOf(1, 2, 3))
        File(folder(), "tamper.bin").writeBytes(byteArrayOf(4))
        assertTrue(OldBuildLeftovers.clear(context))
        assertFalse(folder().exists())
        // BioKey's own start-up may have looked at the same moment: the same two, however often.
        assertEquals(listOf("biokey_v2_vault_aead", "biokey_v2_biometric_gate"), deleted.distinct())
        // And the next start finds nothing.
        assertFalse(OldBuildLeftovers.clear(context))
    }

    @Test
    fun keysThatCouldntBeRemovedAreTriedAgainNextStart() {
        // BioKey's own start-up clears too, on a thread of its own, and may run at any point here:
        // the Keystore fails before there is anything to clear, and what's checked afterwards is
        // how things end up, whichever of the two calls did the clearing.
        OldBuildLeftovers.deleteKeys = { error("Keystore failing") }
        folder().mkdirs()
        File(folder(), "policy.bin").writeBytes(byteArrayOf(1))
        assertTrue(OldBuildLeftovers.clear(context))
        // The folder is what says there is still something to clear.
        assertTrue(folder().exists())
        OldBuildLeftovers.deleteKeys = { synchronized(deleted) { deleted += it } }
        OldBuildLeftovers.clear(context)
        assertFalse(folder().exists())
        assertTrue("the keys were asked for again", synchronized(deleted) { deleted.isNotEmpty() })
    }

    @Test
    fun BioKeysOwnSettingsAreLeftAlone() {
        folder().mkdirs()
        val prefs = context.getSharedPreferences("biokey_vault", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("setup_completed", true).commit()
        OldBuildLeftovers.clear(context)
        assertTrue(prefs.getBoolean("setup_completed", false))
        prefs.edit().clear().commit()
    }
}
