package com.android.BioKey

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.hardware.biometrics.BiometricManager
import android.os.Looper
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ApplicationProvider
import com.android.BioKey.core.data.LockTimeoutMode
import com.android.BioKey.core.security.LockStateTracker
import com.android.BioKey.core.security.OwnerPassphrase
import com.android.BioKey.core.security.SecurityEngine
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.feature.lock.LockScreenActivity
import java.time.Duration
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * The passphrase on a locked app's lock screen opens the app it was typed for, and nothing else:
 * not after the screen has become another app's lock, not after its entry was backed out of, and
 * not after the phone was locked while it was being checked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [InstallTimePermissions::class])
class LockScreenPassphraseTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val notes = "com.example.notes"
    private val diary = "com.example.diary"
    private val right = "lantern tide 9"
    private lateinit var vault: SecurityVault
    private lateinit var asked: (Context) -> Int?

    @Before
    fun start() {
        asked = SecurityEngine.canAuthenticateStrong
        SecurityVault.getInstance(context).awaitPendingWrites()
        context.getSharedPreferences("biokey_vault", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("biokey_owner_passphrase", Context.MODE_PRIVATE).edit().clear().commit()
        SecurityVault.resetForTests()
        OwnerPassphrase.resetForTests()
        LockStateTracker.resetForTests()
        LockScreenActivity.forgetResumeForTests()
        install(notes, "Notes")
        install(diary, "Diary")
        vault = SecurityVault.getInstance(context)
        vault.setSetupCompletedSync(true)
        vault.setAppLock(notes, true)
        vault.setAppLock(diary, true)
        vault.setBiometricOnly(true)
        // The Keystore's part, which Robolectric hasn't got: an ordinary key in its place.
        OwnerPassphrase.pepper = { bytes, _ ->
            Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(ByteArray(32) { 5 }, "HmacSHA256"))
                doFinal(bytes)
            }
        }
        OwnerPassphrase.rounds = 100_000
        assertTrue(OwnerPassphrase.set(context, right.toCharArray()))
        // No face or fingerprint to ask for: the passphrase is the way in.
        SecurityEngine.canAuthenticateStrong = { BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED }
    }

    @After
    fun stop() {
        SecurityEngine.canAuthenticateStrong = asked
        SecurityEngine.INSTANCE.resetAuthenticatingState()
        vault.setSetupCompletedSync(false)
        vault.awaitPendingWrites()
        LockScreenActivity.setStateForTests(null)
        context.getSharedPreferences("biokey_owner_passphrase", Context.MODE_PRIVATE).edit().clear().commit()
        OwnerPassphrase.resetForTests()
        LockStateTracker.resetForTests()
    }

    private fun install(pkg: String, label: String) {
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = pkg
            applicationInfo = ApplicationInfo().apply {
                packageName = pkg
                nonLocalizedLabel = label
            }
        })
    }

    private fun idle(ms: Long = 0) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun lockFor(pkg: String) = Intent(context, LockScreenActivity::class.java).putExtra(LockScreenActivity.EXTRA_LOCKED_PACKAGE, pkg)

    private fun lockScreenFor(pkg: String): ActivityController<LockScreenActivity> =
        Robolectric.buildActivity(LockScreenActivity::class.java, lockFor(pkg)).setup().also { idle() }

    private fun LockScreenActivity.openEntry() {
        LockScreenActivity::class.java.getDeclaredMethod("openPassphrase").apply { isAccessible = true }.invoke(this)
        idle()
        assertTrue(coverForTests.isPassphraseEntryShowing)
    }

    /** Types into the entry and presses Done. The check then runs off the main thread; its answer waits for [idle]. */
    private fun LockScreenActivity.submit(text: String) {
        val field = coverForTests.passphraseEntryForTests.field
        field.text.append(text)
        field.onEditorAction(EditorInfo.IME_ACTION_DONE)
    }

    /** Long enough for the check itself to finish, without letting its answer be handled yet. */
    private fun whileTheCheckRuns() = Thread.sleep(2_500)

    /** Lets answers be handled until [done], or a few seconds have passed. */
    private fun settle(done: () -> Boolean) {
        repeat(120) {
            idle()
            if (done()) return
            Thread.sleep(50)
        }
    }

    private fun unlocked(pkg: String) = LockStateTracker.isAppUnlocked(pkg, LockTimeoutMode.IMMEDIATE)

    @Test
    fun theRightPassphraseOpensTheAppItWasTypedFor() {
        val screen = lockScreenFor(notes)
        screen.get().openEntry()
        screen.get().submit(right)
        settle { unlocked(notes) }
        assertTrue(unlocked(notes))
        assertFalse(unlocked(diary))
        assertTrue(screen.get().isFinishing)
    }

    @Test
    fun aWrongOneOpensNothingAndIsCounted() {
        val screen = lockScreenFor(notes)
        screen.get().openEntry()
        screen.get().submit("not the passphrase")
        settle { context.getSharedPreferences("biokey_owner_passphrase", Context.MODE_PRIVATE).getInt("misses", 0) == 1 }
        idle()
        assertEquals(1, context.getSharedPreferences("biokey_owner_passphrase", Context.MODE_PRIVATE).getInt("misses", 0))
        assertFalse(unlocked(notes))
        assertFalse(screen.get().isFinishing)
        assertTrue(screen.get().coverForTests.isPassphraseEntryShowing)
    }

    @Test
    fun onceTheScreenIsAnotherAppsLockAnAnswerForTheOneBeforeOpensNeither() {
        val screen = lockScreenFor(notes)
        screen.get().openEntry()
        screen.get().submit(right)
        whileTheCheckRuns()
        // Diary came forward meanwhile: the same screen is its lock now.
        screen.newIntent(lockFor(diary))
        assertFalse("its entry went with the app it was for", screen.get().coverForTests.isPassphraseEntryShowing)
        settle { false }
        assertFalse(unlocked(notes))
        assertFalse(unlocked(diary))
        assertFalse("Diary's cover stays up", screen.get().isFinishing)
    }

    @Test
    fun backedOutOfWhileItsCheckedTheAnswerOpensNothingAndTheNextEntryIsntLeftWaiting() {
        val screen = lockScreenFor(notes)
        val activity = screen.get()
        activity.openEntry()
        activity.submit(right)
        whileTheCheckRuns()
        activity.coverForTests.passphraseEntryForTests.backButton.performClick()
        // Opened again before the first answer is handled: that answer isn't this entry's.
        activity.openEntry()
        settle { false }
        assertFalse(unlocked(notes))
        assertFalse(activity.isFinishing)
        assertTrue("not left on Checking…", activity.coverForTests.passphraseEntryForTests.field.isEnabled)
    }

    @Test
    fun thePhoneLockedWhileItsCheckedVoidsTheUnlock() {
        val screen = lockScreenFor(notes)
        screen.get().openEntry()
        screen.get().submit(right)
        whileTheCheckRuns()
        LockStateTracker.lockAll()
        settle { false }
        assertFalse(unlocked(notes))
    }

    @Test
    fun leftForAnotherScreenWhileItsCheckedTheAnswerOpensNothing() {
        val screen = lockScreenFor(notes)
        screen.get().openEntry()
        screen.get().submit(right)
        whileTheCheckRuns()
        screen.pause().stop()
        settle { false }
        assertFalse(unlocked(notes))
    }
}
