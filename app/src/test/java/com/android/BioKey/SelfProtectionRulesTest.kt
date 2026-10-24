package com.android.BioKey

import com.android.BioKey.feature.lock.SelfProtectionDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Self-protection's text rules: BioKey's own pages and dialogs are recognised, in any language;
 * other apps that merely look alike ("Bio Keyboard") or sentences that mention BioKey are not.
 */
class SelfProtectionRulesTest {

    private val names = SelfProtectionDetector.namePattern(
        listOf("Bio Key", "BioKey", "com.android.BioKey", "Bio Key App Lock Service", "Bio Key notification shield")
    )

    @Test
    fun bioKeysNamesMatchAsWholeNames() {
        assertTrue(names.containsMatchIn("Bio Key"))
        assertTrue(names.containsMatchIn("Stop Bio Key App Lock Service?"))
        assertTrue(names.containsMatchIn("bio key's settings"))
        assertTrue(names.containsMatchIn("com.android.BioKey"))
        assertTrue(names.containsMatchIn("Version 4.5 · com.android.biokey"))
        // Chinese and Japanese put no space next to a name.
        assertTrue(names.containsMatchIn("要停用Bio Key App Lock Service吗？"))
        assertTrue(names.containsMatchIn("BioKeyを停止しますか"))
    }

    @Test
    fun lookalikeNamesDontMatch() {
        assertFalse(names.containsMatchIn("Bio Keyboard"))
        assertFalse(names.containsMatchIn("Use Bio Keyboard"))
        assertFalse(names.containsMatchIn("BioKeys Vault"))
        assertFalse(names.containsMatchIn("MyBioKey"))
        assertFalse(names.containsMatchIn("com.android.BioKeyboard"))
    }

    @Test
    fun aNodeThatIsAControlForBioKeyIsOne() {
        val localized = setOf("dienst „bio key app lock service“ beenden?")
        assertTrue(SelfProtectionDetector.isControlPrompt("Use Bio Key App Lock Service", emptySet()))
        assertTrue(SelfProtectionDetector.isControlPrompt("Stop Bio Key App Lock Service?", emptySet()))
        assertTrue(SelfProtectionDetector.isControlPrompt("Bio Key App Lock Service shortcut", emptySet()))
        assertTrue(SelfProtectionDetector.isControlPrompt("Turn off notification access for Bio Key notification shield?", emptySet()))
        assertTrue(SelfProtectionDetector.isControlPrompt("Dienst „Bio Key App Lock Service“ beenden?", localized))
        assertTrue(SelfProtectionDetector.isControlPrompt("要关闭 Bio Key notification shield 的通知使用权吗？", emptySet()))
    }

    @Test
    fun aSentenceThatMentionsBioKeyIsNoControl() {
        assertFalse(SelfProtectionDetector.isControlPrompt("Bio Key", emptySet()))
        assertFalse(SelfProtectionDetector.isControlPrompt("Bio Key App Lock Service", emptySet()))
        assertFalse(SelfProtectionDetector.isControlPrompt("I use BioKey too", emptySet()))
        assertFalse(SelfProtectionDetector.isControlPrompt("Works great, I stopped using other locks for Bio Key", emptySet()))
    }

    @Test
    fun eachHostIsOnlyAskedForViewIdsItCanHave() {
        val ids = listOf(
            "com.android.settings:id/entity_header_title",
            "com.google.android.permissioncontroller:id/entity_header_title",
            "com.android.permissioncontroller:id/entity_header_title",
            "android:id/alertTitle",
            "androidx.appcompat:id/alertTitle",
            "android:id/message"
        )
        val framework = listOf("android:id/alertTitle", "androidx.appcompat:id/alertTitle", "android:id/message")
        assertEquals(framework, SelfProtectionDetector.viewIdsFor("com.android.vending", ids))
        assertEquals(
            listOf("com.android.settings:id/entity_header_title") + framework,
            SelfProtectionDetector.viewIdsFor("com.android.settings", ids)
        )
        // Google's build of the permission controller keeps AOSP's resource package.
        assertEquals(
            listOf(
                "com.google.android.permissioncontroller:id/entity_header_title",
                "com.android.permissioncontroller:id/entity_header_title"
            ) + framework,
            SelfProtectionDetector.viewIdsFor("com.google.android.permissioncontroller", ids)
        )
    }

    @Test
    fun installersAreOnlySearchedForNamesNoShorterOneContains() {
        val labels = listOf("Bio Key", "BioKey", "com.android.BioKey", "Bio Key App Lock Service", "Bio Key notification shield")
        // Android's text search finds parts of texts: "Bio Key" already finds the service's name.
        assertEquals(listOf("BioKey", "Bio Key"), SelfProtectionDetector.textSearchLabels(labels))
        // A name that is no part of another is kept, wherever it comes in the list.
        assertEquals(
            listOf("Clé Bio", "Bio Key"),
            SelfProtectionDetector.textSearchLabels(listOf("Clé Bio", "Bio Key", "Service Bio Key"))
        )
        // Only exact containment counts: a case-folded match is searched for on its own.
        assertEquals(listOf("BioKey", "com.android.biokey"), SelfProtectionDetector.textSearchLabels(listOf("BioKey", "com.android.biokey")))
    }

    @Test
    fun thePlayStoreIsReadLessOftenThanSettings() {
        assertEquals(500L, SelfProtectionDetector.contentCheckIntervalMs("com.android.vending"))
        assertEquals(120L, SelfProtectionDetector.contentCheckIntervalMs("com.android.settings"))
        assertEquals(120L, SelfProtectionDetector.contentCheckIntervalMs("com.google.android.packageinstaller"))
    }

    @Test
    fun anAccessibilityShortcutForBioKeyIsFound() {
        val pkg = "com.android.BioKey"
        val cls = "com.android.BioKey.feature.lock.BioKeyAccessibilityService"
        assertTrue(SelfProtectionDetector.shortcutTargetsInclude("$pkg/$cls", pkg, cls))
        assertTrue(SelfProtectionDetector.shortcutTargetsInclude("$pkg/.feature.lock.BioKeyAccessibilityService", pkg, cls))
        assertTrue(
            SelfProtectionDetector.shortcutTargetsInclude(
                "com.android.talkback/.TalkBackService:$pkg/.feature.lock.BioKeyAccessibilityService",
                pkg,
                cls
            )
        )
        assertFalse(SelfProtectionDetector.shortcutTargetsInclude("com.android.talkback/.TalkBackService", pkg, cls))
        assertFalse(SelfProtectionDetector.shortcutTargetsInclude("$pkg/.feature.lock.OtherService", pkg, cls))
        assertFalse(SelfProtectionDetector.shortcutTargetsInclude("", pkg, cls))
        assertFalse(SelfProtectionDetector.shortcutTargetsInclude(null, pkg, cls))
    }
}
