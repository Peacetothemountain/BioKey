package com.android.BioKey

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.android.BioKey.ui.disclosure.Disclosure
import com.android.BioKey.ui.disclosure.DisclosureAgreements
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The disclosure sheet: an Agree is tied to every word it shows, so each `disclosure_` string
 * (its buttons aside) is in what the agreement's hash is taken over; and its wording keeps the
 * terms decided for it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DisclosureWordingTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Shown on the sheet but not part of what it discloses: its buttons. */
    private val buttons = setOf("disclosure_agree", "disclosure_read_privacy_policy")

    @Test
    fun everyDisclosureStringIsCoveredByTheAgreement() {
        val shown = Disclosure.entries.flatMap { DisclosureAgreements.shown(it) }.toSet()
        val missing = R.string::class.java.fields
            .filter { it.name.startsWith("disclosure_") && it.name !in buttons }
            // Self-protection's lines are shown only where it's built in.
            .filter { it.name != "disclosure_accessibility_reads_settings_pages" && it.name != "disclosure_accessibility_used_self_protection" }
            .filter { it.getInt(null) !in shown }
            .map { it.name }
        assertTrue("not covered by the agreement: $missing", missing.isEmpty())
    }

    @Test
    fun theWordingKeepsItsTerms() {
        val all = R.string::class.java.fields.filter { it.name.startsWith("disclosure_") }.map { context.getString(it.getInt(null)) }
        all.forEach { words ->
            assertFalse(words, "'" in words)
            assertFalse(words, "in front" in words)
            assertFalse(words, " noted" in words)
        }
        // Past 500 unseen, not every record is kept until it's seen: the limit is said.
        assertTrue("500" in context.getString(R.string.disclosure_keeps_security_activity_kept))
        // The heading over the "never" lines agrees with them: no "unless" under "never".
        assertFalse("unless" in context.getString(R.string.disclosure_never_leaves_phone))
    }
}
