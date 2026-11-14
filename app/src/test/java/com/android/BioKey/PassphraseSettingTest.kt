package com.android.BioKey

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.android.BioKey.core.security.OwnerPassphrase
import com.android.BioKey.feature.main.PassphraseRowState
import com.android.BioKey.feature.main.passphraseCanBeSet
import com.android.BioKey.feature.main.passphraseLengthOk
import com.android.BioKey.feature.main.passphraseRowState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Settings' passphrase row says what's true now: shown only where a passphrase can be asked for,
 * or where one is set (never hidden while kept), and never "On" when nothing would ask for it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PassphraseSettingTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun notSetShowsOnlyWhereItWouldBeAskedFor() {
        assertEquals(PassphraseRowState.NOT_SET, passphraseRowState(isSet = false, biometricOnly = true, strongBiometric = true))
        assertEquals(PassphraseRowState.HIDDEN, passphraseRowState(isSet = false, biometricOnly = false, strongBiometric = true))
        assertEquals(PassphraseRowState.HIDDEN, passphraseRowState(isSet = false, biometricOnly = true, strongBiometric = false))
        assertEquals(PassphraseRowState.HIDDEN, passphraseRowState(isSet = false, biometricOnly = false, strongBiometric = false))
    }

    @Test
    fun aSetPassphraseIsAlwaysShownAndOnOnlyWhileTheSwitchIs() {
        assertEquals(PassphraseRowState.ON, passphraseRowState(isSet = true, biometricOnly = true, strongBiometric = true))
        // With no face or fingerprint to use, the passphrase is what's asked for, not the screen lock.
        assertEquals(PassphraseRowState.ON_NO_BIOMETRIC, passphraseRowState(isSet = true, biometricOnly = true, strongBiometric = false))
        assertEquals(PassphraseRowState.UNUSED_SWITCH_OFF, passphraseRowState(isSet = true, biometricOnly = false, strongBiometric = true))
        assertEquals(PassphraseRowState.UNUSED_SWITCH_OFF, passphraseRowState(isSet = true, biometricOnly = false, strongBiometric = false))
        for (only in listOf(true, false)) for (strong in listOf(true, false)) {
            val state = passphraseRowState(isSet = true, biometricOnly = only, strongBiometric = strong)
            assertTrue(state.shown)
            assertTrue(state.isSet)
            // In use exactly while "Face or fingerprint only" is on.
            assertEquals(only, state.inUse)
        }
    }

    @Test
    fun withoutAFaceOrFingerprintItCanOnlyBeRemoved() {
        // The owner check would be the screen lock then: not enough to choose what stands in for it.
        for (state in PassphraseRowState.entries) assertFalse(passphraseCanBeSet(state, strongBiometric = false))
        assertFalse(passphraseCanBeSet(PassphraseRowState.ON_NO_BIOMETRIC, strongBiometric = true))
        assertTrue(passphraseCanBeSet(PassphraseRowState.NOT_SET, strongBiometric = true))
        assertTrue(passphraseCanBeSet(PassphraseRowState.ON, strongBiometric = true))
        assertTrue(passphraseCanBeSet(PassphraseRowState.UNUSED_SWITCH_OFF, strongBiometric = true))
    }

    @Test
    fun eachShownStateHasItsOwnSubtitle() {
        assertNull(PassphraseRowState.HIDDEN.subtitle)
        val shown = PassphraseRowState.entries.filter { it.shown }
        val subtitles = shown.map { context.getString(it.subtitle!!) }
        assertEquals(shown.size, subtitles.toSet().size)
        // Only the states where it's really asked for say "On".
        assertEquals(shown.filter { it.inUse }, shown.filter { context.getString(it.subtitle!!).startsWith("On") })
    }

    @Test
    fun lengthRule() {
        assertFalse(passphraseLengthOk(0))
        assertFalse(passphraseLengthOk(OwnerPassphrase.MIN_LENGTH - 1))
        assertTrue(passphraseLengthOk(OwnerPassphrase.MIN_LENGTH))
        assertTrue(passphraseLengthOk(OwnerPassphrase.MAX_LENGTH))
        assertFalse(passphraseLengthOk(OwnerPassphrase.MAX_LENGTH + 1))
        assertEquals(8, OwnerPassphrase.MIN_LENGTH)
    }
}
