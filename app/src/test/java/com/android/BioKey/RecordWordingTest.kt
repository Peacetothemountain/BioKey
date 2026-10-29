package com.android.BioKey

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.android.BioKey.core.security.ProtectionGaps
import com.android.BioKey.core.security.UnlockAttempts
import com.android.BioKey.core.security.ProtectionGaps.Change
import com.android.BioKey.core.security.ProtectionGaps.Gap
import com.android.BioKey.core.security.ProtectionGaps.Kind
import com.android.BioKey.feature.main.gapLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Records hold codes and names; they're worded when shown, and older worded ones still read right. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecordWordingTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val at = System.currentTimeMillis()

    private fun line(kind: Kind, detail: String = "", unknownStart: Boolean = false, from: Long = at) =
        gapLine(context, Gap(from, at, kind, 1L, detail, unknownStart = unknownStart))

    @Test
    fun aChangeIsWordedWithItsNamesAsTheyAre() {
        val text = line(Kind.CHANGED_WITH_SCREEN_LOCK, Change.LOCK_OFF.encode("WhatsApp"))
        assertTrue(text, "WhatsApp's lock switched off" in text)
        assertTrue(text, "screen lock" in text)
    }

    @Test
    fun theAlertSaysWhatTheRecordsAreWhenNoneChangesProtection() {
        fun gap(change: Change) = Gap(at, at, Kind.CHANGED_WITH_SCREEN_LOCK, 1L, change.encode())
        fun text(vararg gaps: Gap) = UnlockAttempts.notificationText(emptyList(), gaps.toList(), emptyList())
        assertEquals(R.string.log_shared_notification_text, text(gap(Change.LOG_SHARED)))
        assertEquals(R.string.biometric_unavailable_notification_text, text(gap(Change.BIOMETRIC_UNAVAILABLE)))
        // Both together: neither alert's wording fits the other, and neither is a weakening.
        assertEquals(R.string.activity_recorded_notification_text, text(gap(Change.LOG_SHARED), gap(Change.BIOMETRIC_UNAVAILABLE)))
        // Anything that weakened protection among them: said as a change.
        assertEquals(R.string.changes_notification_text, text(gap(Change.LOG_SHARED), gap(Change.PAUSED)))
    }

    @Test
    fun manyIsAPlural() {
        assertTrue(line(Kind.CHANGED_WITH_SCREEN_LOCK, Change.ADDED_MANY_TO_OFF_PROFILE.encode("3", "Work")).contains("3 locked apps added to Work"))
        assertTrue(line(Kind.CHANGED_WITH_SCREEN_LOCK, Change.ADDED_MANY_TO_OFF_PROFILE.encode("1", "Work")).contains("1 locked app added to Work"))
    }

    @Test
    fun aDelayIsWordedFromItsName() {
        val text = line(Kind.CHANGED_WITH_SCREEN_LOCK, Change.DEFAULT_DELAY_LONGER.encode("GRACE_5_MINUTES"))
        assertTrue(text, "5" in text && "minutes" in text)
    }

    @Test
    fun anOlderWordedRecordReadsAsWritten() {
        val text = line(Kind.CHANGED_WITH_SCREEN_LOCK, "Chase's lock switched off")
        assertTrue(text, "Chase's lock switched off" in text)
    }

    @Test
    fun notFromASessionSaysNothingOfOne() {
        val text = line(Kind.CHANGED_WITH_SCREEN_LOCK, Change.BIOMETRIC_UNAVAILABLE.encode())
        assertTrue(text, "screen lock was allowed" in text && "opened with" !in text)
    }

    @Test
    fun theLogSharedSaysWhatHappened() {
        val text = line(Kind.CHANGED_WITH_SCREEN_LOCK, Change.LOG_SHARED.encode())
        assertTrue(text, "Diagnostic log opened for sharing" in text)
    }

    @Test
    fun aForceStopWhoseStartCantBeToldSaysSo() {
        assertTrue(line(Kind.FORCE_STOPPED, unknownStart = true).contains("can't be told"))
    }

    @Test
    fun accessRevokedAndBackIsARange() {
        val text = line(Kind.SHIELD_ACCESS_REVOKED, from = at - 30 * 60_000L)
        assertTrue(text, "was off" in text)
    }
}
