package com.android.BioKey

import android.os.SystemClock
import com.android.BioKey.core.data.LockTimeoutMode
import com.android.BioKey.core.security.LockStateTracker
import com.android.BioKey.feature.lock.SessionRules
import com.android.BioKey.feature.lock.SessionRules.Companion.EXIT_SETTLE_MS
import com.android.BioKey.feature.lock.SessionRules.Companion.LEAVE_CONFIRM_MS
import com.android.BioKey.feature.lock.SessionRules.Companion.UNIDENTIFIED_APP
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

/**
 * The accessibility engine's session rules, situation by situation: no second authentication
 * while the user is still in an unlocked app; a lock once they have really left (re-lock
 * "immediately" here, the strictest setting).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionRulesTest {

    private val chat = "com.example.chat"
    private val bank = "com.example.bank"
    private val launcher = "com.example.launcher"
    private val dialer = "com.google.android.dialer"
    private val game = "com.example.game"

    private val locked = setOf(chat, bank)
    private val hopTargets = setOf(dialer, "com.google.android.permissioncontroller", UNIDENTIFIED_APP)

    private val rules = SessionRules(object : SessionRules.Env {
        override fun isLocked(pkg: String) = pkg in locked
        override fun timeoutOf(pkg: String) = LockTimeoutMode.IMMEDIATE
        override fun isLauncher(pkg: String) = pkg == launcher
        override fun isHopTarget(pkg: String) = pkg in hopTargets
        override fun mayCover(pkg: String) =
            pkg in locked && !LockStateTracker.isAppUnlocked(pkg, LockTimeoutMode.IMMEDIATE)
    })

    @Before
    fun reset() {
        LockStateTracker.lockAll()
        rules.reset()
    }

    private fun advance(ms: Long) = ShadowSystemClock.advanceBy(Duration.ofMillis(ms))

    private fun screen(vararg visible: String, front: String = visible.first(), last: String? = null, pip: Set<String> = emptySet()) =
        rules.reconcile(SessionRules.Screen(visible.toCollection(LinkedHashSet()), pip, front, last, SystemClock.elapsedRealtime()))

    private fun unlockAndUse(pkg: String) {
        LockStateTracker.onAppUnlocked(pkg)
        rules.noteOnScreen(pkg)
        screen(pkg)
    }

    private fun unlocked(pkg: String) = LockStateTracker.isAppUnlocked(pkg, LockTimeoutMode.IMMEDIATE)

    @Test
    fun aWindowMissingForAMomentIsNotLeaving() {
        // Rotation, or the app restarting its own screen.
        unlockAndUse(chat)
        screen(game)
        advance(LEAVE_CONFIRM_MS - 100)
        screen(chat)
        advance(1_000)
        screen(chat)
        assertTrue(unlocked(chat))
    }

    @Test
    fun goingHomeIsLeaving() {
        unlockAndUse(chat)
        val first = screen(launcher)
        assertNotNull(first.recheckInMs)
        advance(LEAVE_CONFIRM_MS + 50)
        screen(launcher)
        assertFalse(unlocked(chat))
    }

    @Test
    fun anUnreadableScreenWithNoOtherAppIsStillTheApp() {
        // Apps that hide their windows from accessibility services.
        unlockAndUse(bank)
        screen(UNIDENTIFIED_APP, last = bank)
        advance(5_000)
        screen(UNIDENTIFIED_APP, last = bank)
        assertTrue(unlocked(bank))
    }

    @Test
    fun aCallInTheMiddleKeepsTheSession() {
        unlockAndUse(chat)
        screen(dialer)
        advance(LEAVE_CONFIRM_MS + 50)
        screen(dialer)
        advance(60_000)
        screen(chat)
        assertTrue(unlocked(chat))
    }

    @Test
    fun goingElsewhereFromAHopEndsTheSession() {
        unlockAndUse(chat)
        screen(dialer)
        advance(LEAVE_CONFIRM_MS + 50)
        screen(dialer)
        screen(launcher)
        assertFalse(unlocked(chat))
    }

    @Test
    fun aLockedAppOnScreenWithoutASessionIsCovered() {
        assertEquals(bank, screen(bank).cover)
    }

    @Test
    fun picturInPictureIsCoveredAtOnce() {
        val outcome = screen(launcher, bank, front = launcher, pip = setOf(bank))
        assertEquals(bank, outcome.cover)
        assertTrue(outcome.coverInPip)
    }

    @Test
    fun aJustLeftAppFlickeringBackNextToTheLauncherMustSettleFirst() {
        unlockAndUse(chat)
        screen(launcher)
        advance(LEAVE_CONFIRM_MS + 50)
        screen(launcher) // left: "immediately" ends the session
        assertFalse(unlocked(chat))

        // Dismissing it in Recents: its window turns up next to the launcher for a moment.
        val flicker = screen(launcher, chat)
        assertNull(flicker.cover)
        assertNotNull(flicker.recheckInMs)
        screen(launcher) // gone again: nothing to cover

        // Really back (it stays): covered.
        screen(launcher, chat)
        advance(EXIT_SETTLE_MS + 50)
        assertEquals(chat, screen(launcher, chat).cover)
    }

    @Test
    fun nothingIsCoveredWhileACoverIsAlreadyUp() {
        val outcome = rules.reconcile(SessionRules.Screen(linkedSetOf(bank), now = SystemClock.elapsedRealtime(), coverAllowed = false))
        assertNull(outcome.cover)
    }
}
