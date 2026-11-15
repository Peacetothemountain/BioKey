package com.android.BioKey

import com.android.BioKey.core.security.VaultBackup
import com.android.BioKey.feature.main.backupPasswordMismatch
import com.android.BioKey.feature.main.backupPasswordReady
import com.android.BioKey.feature.main.diagnosticLineForDisplay
import com.android.BioKey.feature.main.diagnosticTimePattern
import com.android.BioKey.feature.main.needsPassphraseNotice
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The backup password's two fields, the notice after a restore, and how the log viewer shows a line. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupAndLogViewerTest {

    private val good = "a".repeat(VaultBackup.MIN_PASSWORD_LENGTH)

    @Test
    fun aNewBackupPasswordIsUsableOnlyOnceItIsLongEnoughAndTypedTheSameTwice() {
        assertTrue(backupPasswordReady(good, good))
        // One typo would make the backup impossible to open: the button waits.
        assertFalse(backupPasswordReady(good, good.dropLast(1) + "b"))
        assertFalse(backupPasswordReady(good, ""))
        // Too short, even typed twice.
        val short = "a".repeat(VaultBackup.MIN_PASSWORD_LENGTH - 1)
        assertFalse(backupPasswordReady(short, short))
        val long = "a".repeat(VaultBackup.MAX_PASSWORD_LENGTH + 1)
        assertFalse(backupPasswordReady(long, long))
    }

    @Test
    fun aMismatchIsSaidOnlyOnceTheSecondCanNoLongerMatch() {
        val first = "correct horse"
        // Empty, or still the start of the first: nothing said yet.
        assertFalse(backupPasswordMismatch(first, "", finished = false))
        assertFalse(backupPasswordMismatch(first, "correct", finished = false))
        // Can't match any more: a wrong letter, or as long as the first but different.
        assertTrue(backupPasswordMismatch(first, "corx", finished = false))
        assertTrue(backupPasswordMismatch(first, "correct horsx", finished = false))
        assertTrue(backupPasswordMismatch(first, "correct horse!", finished = false))
        // Left (or Done pressed) while still short of the first: said then.
        assertTrue(backupPasswordMismatch(first, "correct", finished = true))
        // The same: never.
        assertFalse(backupPasswordMismatch(first, first, finished = true))
        assertFalse(backupPasswordMismatch(first, "", finished = true))
    }

    @Test
    fun afterARestoreTheOwnerIsToldOnlyWhenFaceOrFingerprintOnlyIsOnWithNoPassphraseToSet() {
        assertTrue(needsPassphraseNotice(biometricOnly = true, passphraseSet = false, strongBiometric = true))
        // Off, or a passphrase already set here: nothing to say.
        assertFalse(needsPassphraseNotice(biometricOnly = false, passphraseSet = false, strongBiometric = true))
        assertFalse(needsPassphraseNotice(biometricOnly = true, passphraseSet = true, strongBiometric = true))
        // No face or fingerprint to set one with: Settings already says the screen lock works for now.
        assertFalse(needsPassphraseNotice(biometricOnly = true, passphraseSet = false, strongBiometric = false))
    }

    private fun format(locale: Locale, is24: Boolean): String {
        val at = Calendar.getInstance(TimeZone.getTimeZone("UTC"), locale).apply {
            clear()
            set(2026, Calendar.NOVEMBER, 14, 18, 29, 9)
        }.time
        return SimpleDateFormat(diagnosticTimePattern(locale, is24), locale).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(at)
    }

    @Test
    fun theLogViewerShowsTimesAsThePhoneIsSet() {
        val twelve = format(Locale.US, is24 = false)
        assertTrue(twelve, twelve.contains("6:29:09"))
        assertTrue(twelve, twelve.contains("PM"))
        assertTrue(twelve, twelve.contains("Nov"))
        val twentyFour = format(Locale.US, is24 = true)
        assertTrue(twentyFour, twentyFour.contains("18:29:09"))
        assertFalse(twentyFour, twentyFour.contains("PM"))
        // The phone's own order of month and day.
        val british = format(Locale.UK, is24 = true)
        assertTrue(british, british.startsWith("14"))
    }

    @Test
    fun theLogViewerNamesBioKeyAndLockedAppsByName() {
        val own = "com.nbdesigns.biokey"
        val labels = mapOf("com.google.android.calendar" to "Calendar")
        fun show(line: String) = diagnosticLineForDisplay(line, own) { labels[it] }

        assertEquals("A wrong passphrase on BioKey", show("A wrong passphrase on com.nbdesigns.biokey"))
        assertEquals(
            "Unlocked Calendar (com.google.android.calendar)",
            show("Unlocked com.google.android.calendar")
        )
        assertEquals(
            "Session ended: Calendar (com.google.android.calendar) (screen off)",
            show("Session ended: com.google.android.calendar (screen off)")
        )
        // An app whose name isn't known (uninstalled): its package name, as written.
        assertEquals("Unlocked com.example.gone", show("Unlocked com.example.gone"))
        // Words and numbers aren't package names.
        assertEquals("Protection was off for 4 minutes (data cleared)", show("Protection was off for 4 minutes (data cleared)"))
        assertEquals("BioKey's storage was cleared since it last ran", show("BioKey's storage was cleared since it last ran"))
        assertEquals("Version 2.0 · re-lock: 1.5 minutes", show("Version 2.0 · re-lock: 1.5 minutes"))
        // A package at the end of a sentence keeps its full stop.
        assertEquals("Kept BioKey.", show("Kept com.nbdesigns.biokey."))
    }
}
