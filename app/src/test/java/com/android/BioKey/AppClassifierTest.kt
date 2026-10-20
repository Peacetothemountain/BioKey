package com.android.BioKey

import com.android.BioKey.core.data.AppCategory
import com.android.BioKey.core.data.AppClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * What auto-sort recognises. It is on by default and locks what it files, so a false match
 * means an app that suddenly asks for a fingerprint.
 */
class AppClassifierTest {

    private fun category(pkg: String, label: String) = AppClassifier.categorize(pkg, label)

    @Test
    fun theAppsItIsForAreRecognised() {
        val expected = listOf(
            Triple("com.chase.sig.android", "Chase", AppCategory.FINANCE),
            Triple("com.wf.wellsfargomobile", "Wells Fargo", AppCategory.FINANCE),
            Triple("com.google.android.apps.walletnfcrel", "Google Wallet", AppCategory.FINANCE),
            Triple("com.squareup.cash", "Cash App", AppCategory.FINANCE),
            Triple("com.google.android.apps.nbu.paisa.user", "Google Pay", AppCategory.FINANCE),
            // "pay" counts only as a word, so the brands that run it into theirs are named.
            Triple("net.one97.paytm", "Paytm", AppCategory.FINANCE),
            Triple("jp.ne.paypay.android.app", "PayPay", AppCategory.FINANCE),
            Triple("com.google.android.apps.authenticator2", "Authenticator", AppCategory.AUTH),
            Triple("com.duosecurity.duomobile", "Duo Mobile", AppCategory.AUTH),
            Triple("com.whatsapp", "WhatsApp", AppCategory.SOCIAL),
            Triple("org.thoughtcrime.securesms", "Signal", AppCategory.SOCIAL),
            Triple("com.google.android.apps.messaging", "Messages", AppCategory.SOCIAL),
            Triple("jp.naver.line.android", "LINE", AppCategory.SOCIAL),
            Triple("com.twitter.android", "X", AppCategory.SOCIAL),
            Triple("com.Slack", "Slack", AppCategory.WORK),
            Triple("com.google.android.gm", "Gmail", AppCategory.WORK),
            Triple("com.microsoft.office.word", "Word", AppCategory.WORK),
            Triple("com.google.android.apps.docs", "Drive", AppCategory.WORK),
            Triple("com.google.android.apps.photos", "Photos", AppCategory.PRIVACY),
            Triple("com.google.android.keep", "Keep Notes", AppCategory.PRIVACY),
            Triple("com.android.chrome", "Chrome", AppCategory.PRIVACY),
            Triple("org.torproject.torbrowser", "Tor Browser", AppCategory.PRIVACY)
        )
        for ((pkg, label, category) in expected) assertEquals(pkg, category, category(pkg, label))
    }

    @Test
    fun keywordsInsideOtherWordsDontCount() {
        assertEquals(AppCategory.SYSTEM, category("com.google.android.calculator", "Calculator"))
        assertEquals(AppCategory.SYSTEM, category("com.android.vending", "Google Play Store"))
        assertEquals(AppCategory.SYSTEM, category("com.google.android.deskclock", "Clock"))
        assertEquals(AppCategory.GENERAL, category("com.sec.android.app.popupcalculator", "Calculator"))
        assertEquals(AppCategory.GENERAL, category("com.motorola.launcher3", "Moto App Launcher"))
        assertEquals(AppCategory.GENERAL, category("com.example.radio", "Online Radio"))
        assertEquals(AppCategory.GENERAL, category("com.example.game", "Goalkeeper Hero"))
        assertNotEquals(AppCategory.AUTH, category("com.duolingo", "Duolingo"))
        assertNotEquals(AppCategory.SOCIAL, category("org.lineageos.recorder", "Recorder"))
        assertNotEquals(AppCategory.PRIVACY, category("com.google.android.apps.chromecast.app", "Google Home"))
        // Its own category, not Social through "x.android".
        assertEquals(AppCategory.WORK, category("com.dropbox.android", "Dropbox"))
    }
}
