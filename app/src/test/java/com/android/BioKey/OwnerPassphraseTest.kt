package com.android.BioKey

import android.content.Context
import android.os.UserManager
import androidx.test.core.app.ApplicationProvider
import com.android.BioKey.core.diagnostics.DiagnosticLog
import com.android.BioKey.core.security.OwnerPassphrase
import com.android.BioKey.core.security.OwnerPassphrase.Outcome
import com.android.BioKey.core.security.ProtectionGaps
import java.security.KeyStoreException
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Checks [OwnerPassphrase]: verifier storage, length bounds, pre-counted misses, escalating pauses
 * across clock changes and restarts, Keystore failures, and direct-boot lock state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OwnerPassphraseTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val pepperKeyBytes = ByteArray(32) { i -> (i * 7 + 13).toByte() }
    private var sinceBoot = 100_000L
    private var boot = 3
    private var pepperCalls = 0

    @Before
    fun setUp() {
        shadowOf(context.getSystemService(UserManager::class.java)).setUserUnlocked(true)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        DiagnosticLog.setEnabled(context, true)
        DiagnosticLog.clear()
        OwnerPassphrase.resetForTests()
        sinceBoot = 100_000L
        boot = 3
        pepperCalls = 0
        applyTestHooks()
        OwnerPassphrase.rounds = 100_000
    }

    @After
    fun tearDown() {
        shadowOf(context.getSystemService(UserManager::class.java)).setUserUnlocked(true)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        DiagnosticLog.clear()
        OwnerPassphrase.resetForTests()
    }

    private fun applyTestHooks() {
        OwnerPassphrase.elapsed = { sinceBoot }
        OwnerPassphrase.bootCount = { boot }
        OwnerPassphrase.pepper = { bytes, _ ->
            pepperCalls++
            softwareHmac(bytes)
        }
    }

    private fun softwareHmac(bytes: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(pepperKeyBytes, "HmacSHA256"))
            doFinal(bytes)
        }

    private fun prefs() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Test
    fun setVerifyAndClearRoundTripWithoutTouchingCallerArrays() {
        assertFalse(OwnerPassphrase.isSet(context))
        assertFalse(OwnerPassphrase.isSetFlow.value)
        assertEquals(0L, OwnerPassphrase.waitRemainingMs(context))
        assertEquals(Outcome.NotSet, OwnerPassphrase.verify(context, "correct horse".toCharArray()))

        val secret = "correct horse battery".toCharArray()
        val secretCopy = secret.copyOf()
        assertTrue(OwnerPassphrase.set(context, secret))
        assertArrayEquals("Caller owns the CharArray passed to set", secretCopy, secret)
        assertTrue(OwnerPassphrase.isSet(context))
        assertTrue(OwnerPassphrase.isSetFlow.value)

        val attemptRight = "correct horse battery".toCharArray()
        assertEquals(Outcome.Match, OwnerPassphrase.verify(context, attemptRight))
        assertArrayEquals("Caller owns the CharArray passed to verify", secretCopy, attemptRight)

        val attemptWrong = "correct horse batterx".toCharArray()
        val wrongCopy = attemptWrong.copyOf()
        assertEquals(Outcome.Mismatch(0L), OwnerPassphrase.verify(context, attemptWrong))
        assertArrayEquals("Caller owns the CharArray on mismatch too", wrongCopy, attemptWrong)

        OwnerPassphrase.clear(context)
        assertFalse(OwnerPassphrase.isSet(context))
        assertFalse(OwnerPassphrase.isSetFlow.value)
        assertEquals(Outcome.NotSet, OwnerPassphrase.verify(context, secret))
    }

    @Test
    fun removingThePassphraseRemovesItsKeystoreKeyTooAndAKeystoreFailureDoesntStopTheRemoval() {
        var forgotten = 0
        OwnerPassphrase.forgetPepper = { forgotten++ }
        assertTrue(OwnerPassphrase.set(context, "correct horse battery".toCharArray()))
        assertEquals("Setting one deletes no key", 0, forgotten)
        OwnerPassphrase.clear(context)
        assertEquals(1, forgotten)
        assertTrue(prefs().all.isEmpty())

        assertTrue(OwnerPassphrase.set(context, "correct horse battery".toCharArray()))
        OwnerPassphrase.forgetPepper = { error("Keystore failing") }
        OwnerPassphrase.clear(context)
        assertFalse(OwnerPassphrase.isSet(context))
        assertTrue(prefs().all.isEmpty())
    }

    @Test
    fun lengthBoundsRejectTooShortAndTooLongAndSkipKdfOnVerify() {
        assertFalse(OwnerPassphrase.set(context, CharArray(0)))
        assertFalse(OwnerPassphrase.set(context, CharArray(7) { 'a' }))
        assertFalse(OwnerPassphrase.set(context, CharArray(129) { 'a' }))
        assertFalse(OwnerPassphrase.isSet(context))
        assertTrue(prefs().all.isEmpty())

        assertTrue(OwnerPassphrase.set(context, CharArray(8) { 'b' }))
        assertEquals(Outcome.Match, OwnerPassphrase.verify(context, CharArray(8) { 'b' }))

        val max = CharArray(128) { i -> ('a'.code + (i % 26)).toChar() }
        assertTrue(OwnerPassphrase.set(context, max))
        assertEquals(Outcome.Match, OwnerPassphrase.verify(context, max))

        // Rejected replacement leaves the existing passphrase intact.
        assertFalse(OwnerPassphrase.set(context, CharArray(129) { 'z' }))
        assertEquals(Outcome.Match, OwnerPassphrase.verify(context, max))

        // Empty or overlong verify attempts still count as misses, without running the KDF.
        pepperCalls = 0
        assertEquals(Outcome.Mismatch(0L), OwnerPassphrase.verify(context, CharArray(0)))
        assertEquals(Outcome.Mismatch(0L), OwnerPassphrase.verify(context, CharArray(129) { 'z' }))
        assertEquals(0, pepperCalls)
        assertEquals(2, prefs().getInt("misses", 0))
    }

    @Test
    fun storedPreferencesNeverHoldThePassphraseInTextBase64OrHex() {
        val plain = "amber-lantern-harbor-91"
        val utf8 = plain.toByteArray(Charsets.UTF_8)
        val base64 = Base64.getEncoder().encodeToString(utf8)
        val base64NoPad = base64.trimEnd('=')
        val hex = utf8.joinToString("") { "%02x".format(it) }

        assertTrue(OwnerPassphrase.set(context, plain.toCharArray()))
        assertFalse(prefs().all.isEmpty())

        for ((key, value) in prefs().all) {
            val text = value.toString()
            assertFalse("Key $key leaked plaintext", text.contains(plain, ignoreCase = true))
            assertFalse("Key $key leaked Base64", text.contains(base64NoPad))
            assertFalse("Key $key leaked hex", text.lowercase().contains(hex))
        }
    }

    @Test
    fun settingTheSamePassphraseTwiceUsesFreshSaltAndVerifier() {
        val secret = "same passphrase twice".toCharArray()
        assertTrue(OwnerPassphrase.set(context, secret))
        val firstSalt = prefs().getString("salt", null)
        val firstVerifier = prefs().getString("verifier", null)
        assertNotNull(firstSalt)
        assertNotNull(firstVerifier)

        assertTrue(OwnerPassphrase.set(context, secret))
        val secondSalt = prefs().getString("salt", null)
        val secondVerifier = prefs().getString("verifier", null)

        assertNotEquals(firstSalt, secondSalt)
        assertNotEquals(firstVerifier, secondVerifier)
    }

    @Test
    fun waitScheduleBlocksEvenRightPassphraseUntilElapsedAndMatchResetsCount() {
        val right = "meadow-granite-42".toCharArray()
        val wrong = "wrong-attempt-00".toCharArray()
        assertTrue(OwnerPassphrase.set(context, right))

        // Misses 1..4 start no wait.
        repeat(4) {
            assertEquals(Outcome.Mismatch(0L), OwnerPassphrase.verify(context, wrong))
            assertEquals(0L, OwnerPassphrase.waitRemainingMs(context))
        }

        // Miss 5 starts a 30-second wait.
        assertEquals(Outcome.Mismatch(30_000L), OwnerPassphrase.verify(context, wrong))
        assertEquals(30_000L, OwnerPassphrase.waitRemainingMs(context))

        // During the wait, even the right passphrase gets Wait with no KDF and no counter change.
        sinceBoot += 12_000L
        pepperCalls = 0
        assertEquals(Outcome.Wait(18_000L), OwnerPassphrase.verify(context, right))
        assertEquals(0, pepperCalls)
        assertEquals(5, prefs().getInt("misses", 0))

        // Once the wait finishes, the right passphrase matches and resets the counter to zero.
        sinceBoot += 18_000L
        assertEquals(0L, OwnerPassphrase.waitRemainingMs(context))
        assertEquals(Outcome.Match, OwnerPassphrase.verify(context, right))
        assertEquals(0, prefs().getInt("misses", 0))

        // Proof the counter really reset: 4 more misses start no wait.
        repeat(4) {
            assertEquals(Outcome.Mismatch(0L), OwnerPassphrase.verify(context, wrong))
        }

        // Replacing or removing also clears misses and any running wait.
        assertEquals(Outcome.Mismatch(30_000L), OwnerPassphrase.verify(context, wrong))
        assertTrue(OwnerPassphrase.waitRemainingMs(context) > 0L)
        assertTrue(OwnerPassphrase.set(context, right))
        assertEquals(0L, OwnerPassphrase.waitRemainingMs(context))
        assertEquals(0, prefs().getInt("misses", 0))
    }

    @Test
    fun triesBeforeWaitCountsDownToTheWaitAndChangesNothing() {
        assertEquals(null, OwnerPassphrase.triesBeforeWait(context))
        val right = "meadow-granite-42".toCharArray()
        val wrong = "wrong-attempt-00".toCharArray()
        assertTrue(OwnerPassphrase.set(context, right))
        assertEquals(5, OwnerPassphrase.triesBeforeWait(context))
        // After each of the first four misses: 4, 3, 2, 1 (the fifth starts a wait).
        for (left in 4 downTo 1) {
            assertEquals(Outcome.Mismatch(0L), OwnerPassphrase.verify(context, wrong))
            assertEquals(left, OwnerPassphrase.triesBeforeWait(context))
        }
        // From then on each miss starts one.
        assertEquals(Outcome.Mismatch(30_000L), OwnerPassphrase.verify(context, wrong))
        assertEquals(1, OwnerPassphrase.triesBeforeWait(context))

        // Asking, however often, changes nothing: not the count, not the wait, not the clock it runs on.
        val before = prefs().all.toString()
        sinceBoot += 5_000L
        pepperCalls = 0
        repeat(10) { OwnerPassphrase.triesBeforeWait(context) }
        assertEquals(before, prefs().all.toString())
        assertEquals(0, pepperCalls)
        assertEquals(25_000L, OwnerPassphrase.waitRemainingMs(context))

        // A match clears the count, and so the tries.
        sinceBoot += 25_000L
        assertEquals(Outcome.Match, OwnerPassphrase.verify(context, right))
        assertEquals(5, OwnerPassphrase.triesBeforeWait(context))

        // A count out of bounds in the file is read within them.
        prefs().edit().putInt("misses", -7).commit()
        assertEquals(5, OwnerPassphrase.triesBeforeWait(context))
        prefs().edit().putInt("misses", Int.MAX_VALUE).commit()
        assertEquals(1, OwnerPassphrase.triesBeforeWait(context))

        OwnerPassphrase.clear(context)
        assertEquals(null, OwnerPassphrase.triesBeforeWait(context))
    }

    @Test
    fun eighthTenthAndEleventhMissesEscalateToFiveAndThirtyMinutes() {
        val right = "harbor-spruce-77".toCharArray()
        val wrong = "wrong-passphrase".toCharArray()
        assertTrue(OwnerPassphrase.set(context, right))

        // 1..4: 0 ms
        repeat(4) {
            assertEquals(Outcome.Mismatch(0L), OwnerPassphrase.verify(context, wrong))
        }
        // 5, 6, 7: 30 s each
        repeat(3) {
            assertEquals(Outcome.Mismatch(30_000L), OwnerPassphrase.verify(context, wrong))
            sinceBoot += 30_000L
        }
        // 8, 9: 5 min each
        assertEquals(Outcome.Mismatch(5 * 60_000L), OwnerPassphrase.verify(context, wrong))
        sinceBoot += 5 * 60_000L
        assertEquals(Outcome.Mismatch(5 * 60_000L), OwnerPassphrase.verify(context, wrong))
        sinceBoot += 5 * 60_000L
        // 10, 11: 30 min each
        assertEquals(Outcome.Mismatch(30 * 60_000L), OwnerPassphrase.verify(context, wrong))
        sinceBoot += 30 * 60_000L
        assertEquals(Outcome.Mismatch(30 * 60_000L), OwnerPassphrase.verify(context, wrong))
    }

    @Test
    fun missCounterIsPersistedBeforeKdfFinishesSoProcessDeathCannotWinAFreeTry() {
        val right = "cedar-compass-19".toCharArray()
        assertTrue(OwnerPassphrase.set(context, right))

        class SimulatedProcessKill : Error()

        var missesOnDiskDuringCheck = -1
        OwnerPassphrase.pepper = { _, _ ->
            // Forget in-memory state mid-check and inspect disk before verify can answer.
            OwnerPassphrase.resetForTests()
            missesOnDiskDuringCheck = prefs().getInt("misses", 0)
            throw SimulatedProcessKill()
        }

        var killed = false
        try {
            OwnerPassphrase.verify(context, "wrong-guess-here".toCharArray())
        } catch (_: SimulatedProcessKill) {
            killed = true
        }
        assertTrue(killed)
        assertEquals(1, missesOnDiskDuringCheck)

        // After the process starts back up, the miss is still counted on disk.
        applyTestHooks()
        OwnerPassphrase.rounds = 100_000
        assertEquals(1, prefs().getInt("misses", 0))
    }

    @Test
    fun phoneRestartRebuildsFullWaitFromNewBoot() {
        val right = "orchard-granite-8".toCharArray()
        val wrong = "not-the-passphrase".toCharArray()
        assertTrue(OwnerPassphrase.set(context, right))

        repeat(4) { OwnerPassphrase.verify(context, wrong) }
        assertEquals(Outcome.Mismatch(30_000L), OwnerPassphrase.verify(context, wrong))
        sinceBoot += 20_000L
        assertEquals(10_000L, OwnerPassphrase.waitRemainingMs(context))

        // Phone restarts: boot count advances and elapsed clock resets to a small value.
        boot = 4
        sinceBoot = 1_500L
        assertEquals(30_000L, OwnerPassphrase.waitRemainingMs(context))
        assertEquals(Outcome.Wait(30_000L), OwnerPassphrase.verify(context, right))

        sinceBoot += 30_000L
        assertEquals(Outcome.Match, OwnerPassphrase.verify(context, right))
    }

    @Test
    fun elapsedClockGoingBackwardsWithinSameBootRestartsWaitInFull() {
        val right = "quarry-thistle-55".toCharArray()
        val wrong = "wrong-passphrase".toCharArray()
        assertTrue(OwnerPassphrase.set(context, right))

        repeat(4) { OwnerPassphrase.verify(context, wrong) }
        assertEquals(Outcome.Mismatch(30_000L), OwnerPassphrase.verify(context, wrong))

        // Elapsed clock winds behind the recorded start on the same boot.
        sinceBoot -= 5_000L
        assertEquals(30_000L, OwnerPassphrase.waitRemainingMs(context))
        assertEquals(Outcome.Wait(30_000L), OwnerPassphrase.verify(context, right))

        sinceBoot += 10_000L
        assertEquals(20_000L, OwnerPassphrase.waitRemainingMs(context))
    }

    @Test
    fun processRestartPreservesIsSetMissCountRunningWaitAndPassphrase() {
        val right = "willow-lantern-63".toCharArray()
        val wrong = "wrong-passphrase".toCharArray()
        assertTrue(OwnerPassphrase.set(context, right))

        repeat(4) { OwnerPassphrase.verify(context, wrong) }
        assertEquals(Outcome.Mismatch(30_000L), OwnerPassphrase.verify(context, wrong))
        sinceBoot += 11_000L

        // Simulate BioKey's process stopping and starting again without clearing disk.
        OwnerPassphrase.resetForTests()
        applyTestHooks()
        OwnerPassphrase.rounds = 100_000

        assertTrue(OwnerPassphrase.isSet(context))
        assertTrue(OwnerPassphrase.isSetFlow.value)
        assertEquals(5, prefs().getInt("misses", 0))
        assertEquals(19_000L, OwnerPassphrase.waitRemainingMs(context))
        assertEquals(Outcome.Wait(19_000L), OwnerPassphrase.verify(context, right))

        sinceBoot += 19_000L
        assertEquals(Outcome.Match, OwnerPassphrase.verify(context, right))
    }

    @Test
    fun pepperFailureDuringVerifyUndoesMissKeepsIsSetTrueAndLogsFailure() {
        val right = "cobalt-feather-24".toCharArray()
        val wrong = "wrong-passphrase".toCharArray()
        assertTrue(OwnerPassphrase.set(context, right))
        assertEquals(Outcome.Mismatch(0L), OwnerPassphrase.verify(context, wrong))
        assertEquals(1, prefs().getInt("misses", 0))

        DiagnosticLog.clear()
        OwnerPassphrase.pepper = { _, _ -> throw KeyStoreException("Keystore unavailable") }

        assertEquals(Outcome.Mismatch(0L), OwnerPassphrase.verify(context, right))
        assertEquals(1, prefs().getInt("misses", 0))
        assertEquals(0L, OwnerPassphrase.waitRemainingMs(context))
        assertTrue("Missing or broken Keystore key must not silently clear passphrase", OwnerPassphrase.isSet(context))
        assertTrue(DiagnosticLog.entries().any { it.text == "Passphrase could not be checked" })
    }

    @Test
    fun pepperFailureDuringSetReturnsFalseAndStoresNothing() {
        OwnerPassphrase.pepper = { _, _ -> throw KeyStoreException("Cannot generate key") }
        assertFalse(OwnerPassphrase.set(context, "cobalt-feather-24".toCharArray()))
        assertFalse(OwnerPassphrase.isSet(context))
        assertFalse(OwnerPassphrase.isSetFlow.value)
        assertTrue(prefs().all.isEmpty())
    }

    @Test
    fun corruptStoredRoundCountReturnsMismatchWithoutRunningKdf() {
        val right = "silver-boulder-31".toCharArray()
        assertTrue(OwnerPassphrase.set(context, right))

        for (badRounds in listOf(50_000, 5_000_000)) {
            prefs().edit().putInt("rounds", badRounds).commit()
            pepperCalls = 0
            assertEquals("Rounds $badRounds", Outcome.Mismatch(0L), OwnerPassphrase.verify(context, right))
            assertEquals("KDF/pepper must not run for $badRounds", 0, pepperCalls)
        }
    }

    @Test
    fun beforeFirstUnlockEveryCallReportsNotSetAndNothingIsStored() {
        assertTrue(OwnerPassphrase.set(context, "ridge-lantern-12".toCharArray()))

        OwnerPassphrase.resetForTests()
        applyTestHooks()
        OwnerPassphrase.rounds = 100_000
        shadowOf(context.getSystemService(UserManager::class.java)).setUserUnlocked(false)

        assertFalse(OwnerPassphrase.isSet(context))
        assertFalse(OwnerPassphrase.isSetFlow.value)
        assertEquals(0L, OwnerPassphrase.waitRemainingMs(context))
        assertEquals(Outcome.NotSet, OwnerPassphrase.verify(context, "ridge-lantern-12".toCharArray()))
        assertFalse(OwnerPassphrase.set(context, "another-passphrase".toCharArray()))
    }

    @Test
    fun defaultRoundCountIs210000AndStoredAs210000() {
        OwnerPassphrase.resetForTests()
        assertEquals(210_000, OwnerPassphrase.rounds)
        applyTestHooks()

        val right = "default-rounds-check".toCharArray()
        assertTrue(OwnerPassphrase.set(context, right))
        assertEquals(210_000, prefs().getInt("rounds", 0))
        assertEquals(Outcome.Match, OwnerPassphrase.verify(context, right))
    }

    @Test
    fun diagnosticLogRecordsExpectedLinesAndNeverLeaksSecretOrLength() {
        val secret = "uniqueSecretPhrase98765"
        DiagnosticLog.clear()

        assertTrue(OwnerPassphrase.set(context, secret.toCharArray()))
        assertEquals(listOf("Passphrase set"), DiagnosticLog.entries().map { it.text })

        // Match logs nothing.
        DiagnosticLog.clear()
        assertEquals(Outcome.Match, OwnerPassphrase.verify(context, secret.toCharArray()))
        assertTrue(DiagnosticLog.entries().isEmpty())

        // A wait starting is logged; each wrong try isn't here (failed unlocks are logged with the
        // others, and only when the owner has failed unlock alerts on).
        repeat(4) {
            OwnerPassphrase.verify(context, "wrong-secret-phrase-123".toCharArray())
        }
        assertTrue(DiagnosticLog.entries().isEmpty())
        OwnerPassphrase.verify(context, "wrong-secret-phrase-123".toCharArray())
        assertEquals(listOf("Passphrase tries paused for 30 seconds"), DiagnosticLog.entries().map { it.text })

        OwnerPassphrase.clear(context)
        val allLines = DiagnosticLog.entries().map { it.text }
        assertTrue(allLines.contains("Passphrase removed"))
        for (line in allLines) {
            assertFalse("Log leaked secret: $line", line.contains(secret, ignoreCase = true))
            assertFalse("Log leaked secret length: $line", line.contains(secret.length.toString()))
        }
    }

    companion object {
        private const val PREFS_NAME = "biokey_owner_passphrase"
    }

    @Test
    fun settingReplacingAndRemovingItAreEachWrittenIntoSecurityActivity() {
        // A passphrase is a way into every locked app: one set, replaced or removed is never silent.
        val recorded = mutableListOf<ProtectionGaps.Change>()
        OwnerPassphrase.record = { _, change -> recorded += change }
        assertTrue(OwnerPassphrase.set(context, "ridge-lantern-12".toCharArray()))
        assertTrue(OwnerPassphrase.set(context, "ridge-lantern-13".toCharArray()))
        OwnerPassphrase.clear(context)
        // Removing one that isn't there is nothing to record.
        OwnerPassphrase.clear(context)
        assertEquals(
            listOf(ProtectionGaps.Change.PASSPHRASE_SET, ProtectionGaps.Change.PASSPHRASE_CHANGED, ProtectionGaps.Change.PASSPHRASE_REMOVED),
            recorded
        )
        // One that couldn't be saved isn't recorded as set.
        assertFalse(OwnerPassphrase.set(context, "short".toCharArray()))
        assertEquals(3, recorded.size)
    }
}
