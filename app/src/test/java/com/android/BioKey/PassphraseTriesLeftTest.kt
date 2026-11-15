package com.android.BioKey

import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.os.UserManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.android.BioKey.core.security.OwnerPassphrase
import com.android.BioKey.feature.lock.BioKeyLockScreen
import com.android.BioKey.feature.lock.LockCoverView
import com.android.BioKey.feature.lock.PassphraseGateState
import com.android.BioKey.feature.lock.longestPassphraseNote
import com.android.BioKey.feature.lock.passphraseMessage
import com.android.BioKey.ui.theme.BioKeyTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A wrong passphrase that leaves two tries or fewer before a wait says so under the field, in the
 * same words on both lock screens, so a wait never starts out of nowhere.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PassphraseTriesLeftTest {

    @get:Rule
    val rule = createComposeRule()

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val wrong get() = context.getString(R.string.lock_passphrase_wrong)

    @Before
    fun setUp() {
        shadowOf(context.getSystemService(UserManager::class.java)).setUserUnlocked(true)
        OwnerPassphrase.resetForTests()
    }

    @After
    fun tearDown() {
        prefs().edit().clear().commit()
        OwnerPassphrase.resetForTests()
    }

    private fun prefs() = context.getSharedPreferences("biokey_owner_passphrase", Context.MODE_PRIVATE)

    /** A passphrase set (only that its check value is there matters here) and [misses] wrong tries in a row. */
    private fun seed(misses: Int) {
        prefs().edit().putString("verifier", "set").putInt("misses", misses).commit()
    }

    @Test
    fun theWordsForEachCount() {
        val res = context.resources
        assertEquals("Wrong passphrase. 2 more tries before a wait.", passphraseMessage(res, wrong, 2, waiting = false))
        assertEquals("Wrong passphrase. 1 more try before a wait.", passphraseMessage(res, wrong, 1, waiting = false))
        // Further off, unknown, during a wait (the countdown says it), or about something else: as it is.
        assertEquals(wrong, passphraseMessage(res, wrong, 3, waiting = false))
        assertEquals(wrong, passphraseMessage(res, wrong, null, waiting = false))
        assertEquals(wrong, passphraseMessage(res, wrong, 1, waiting = true))
        assertEquals("Something else", passphraseMessage(res, "Something else", 1, waiting = false))
        // The line's room is kept for the longest of what it says.
        assertTrue(longestPassphraseNote(res).length >= "Wrong passphrase. 2 more tries before a wait.".length)
    }

    @Test
    fun bioKeysOwnLockSaysHowManyTriesAreLeft() {
        val gate = PassphraseGateState().apply { offered = true; open = true }
        rule.setContent { BioKeyTheme { BioKeyLockScreen(onUnlockClicked = {}, onCancelClicked = {}, passphrase = gate) } }

        seed(misses = 2)
        gate.error = wrong
        rule.waitForIdle()
        rule.onNodeWithText(wrong).assertIsDisplayed()

        seed(misses = 3)
        gate.error = wrong
        rule.waitForIdle()
        // Shown, and what screen readers are given: the line's own text.
        rule.onNodeWithText("Wrong passphrase. 2 more tries before a wait.").assertIsDisplayed()

        seed(misses = 4)
        gate.error = wrong
        rule.waitForIdle()
        rule.onNodeWithText("Wrong passphrase. 1 more try before a wait.").assertIsDisplayed()

        // The fifth starts a wait: the countdown says what happens now.
        seed(misses = 5)
        gate.error = wrong
        gate.waitUntilElapsed = SystemClock.elapsedRealtime() + 30_000L
        rule.waitForIdle()
        rule.onNodeWithText("Wrong passphrase. Try again in 30 seconds.").assertIsDisplayed()
    }

    @Test
    fun theCoverSaysTheSame() {
        val cover = LockCoverView(context).apply { bind("Notes", null, isBioKey = false, accent = null) }
        cover.showPassphraseEntry({}, {})
        val note = cover.passphraseEntryForTests.note

        seed(misses = 3)
        cover.setPassphraseError(wrong)
        assertEquals("Wrong passphrase. 2 more tries before a wait.", note.text.toString())

        seed(misses = 4)
        cover.setPassphraseError(wrong)
        assertEquals("Wrong passphrase. 1 more try before a wait.", note.text.toString())

        seed(misses = 5)
        cover.setPassphraseError(wrong)
        cover.setPassphraseWait(SystemClock.elapsedRealtime() + 30_000L)
        assertEquals("Wrong passphrase. Try again in 30 seconds.", note.text.toString())

        seed(misses = 1)
        cover.setPassphraseWait(0L)
        cover.setPassphraseError(wrong)
        assertEquals(wrong, note.text.toString())
    }
}
