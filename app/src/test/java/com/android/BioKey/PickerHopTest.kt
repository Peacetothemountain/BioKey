package com.android.BioKey

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.test.core.app.ApplicationProvider
import com.android.BioKey.core.data.LockTimeoutMode
import com.android.BioKey.core.security.LockStateTracker
import com.android.BioKey.feature.lock.ForegroundRules
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Picking a photo from a locked app (the system photo picker, a gallery, a camera) and coming
 * straight back must not ask for authentication again, even with "Re-lock immediately".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PickerHopTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val chat = "com.example.chat"

    @Before
    fun reset() {
        LockStateTracker.resetForTests()
        // The picker list is cached process-wide: one read by another test, before these apps
        // were registered, mustn't be the one asked here.
        ForegroundRules.resetSystemLists()
    }

    /**
     * Registers [pkg] as handling [action] (with [mime], if any) on this "device": one of the
     * phone's own apps unless [fromAStore], which is something installed on it.
     */
    private fun install(pkg: String, action: String, mime: String? = null, fromAStore: Boolean = false) {
        val component = ComponentName(pkg, "$pkg.PickActivity")
        val pm = shadowOf(context.packageManager)
        val info = pm.addActivityIfNotPresent(component)
        info.applicationInfo = (info.applicationInfo ?: android.content.pm.ApplicationInfo()).apply {
            packageName = pkg
            flags = if (fromAStore) flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM.inv() else flags or android.content.pm.ApplicationInfo.FLAG_SYSTEM
        }
        pm.addIntentFilterForActivity(component, IntentFilter(action).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            if (mime != null) addDataType(mime)
        })
    }

    @Test
    fun anyPhotoPickerOnTheDeviceIsAHelper() {
        install("com.vendor.gallery", Intent.ACTION_PICK, "image/*")
        install("com.vendor.camera", "android.media.action.IMAGE_CAPTURE")
        assertTrue(ForegroundRules.isHopHelper(context, "com.vendor.gallery", null))
        assertTrue(ForegroundRules.isHopHelper(context, "com.vendor.camera", null))
        // The system photo picker is a helper even before it's discovered.
        assertTrue(ForegroundRules.isHopHelper(context, "com.google.android.photopicker", null))
        assertFalse(ForegroundRules.isHopHelper(context, "com.example.game", null))
    }

    @Test
    fun anAppThatOnlySaysItPicksFilesIsNotAHelper() {
        // Any app can declare this. One that did, to be counted a helper, would keep a locked
        // app unlocked behind it: only the phone's own pickers and cameras count.
        install("com.example.sneaky", Intent.ACTION_GET_CONTENT, "*/*", fromAStore = true)
        install("com.vendor.files", Intent.ACTION_GET_CONTENT, "*/*")
        assertFalse(ForegroundRules.isHopHelper(context, "com.example.sneaky", null))
        assertTrue(ForegroundRules.isHopHelper(context, "com.vendor.files", null))
    }

    /**
     * Installs [pkg] as an ordinary app, by [installer] (an app store's package, or null for a file
     * installed by hand); [askedBy] is who asked for the install, the installer itself unless said.
     */
    private fun installApp(pkg: String, installer: String?, askedBy: String? = installer, partOfThePhone: Boolean = false) {
        val pm = shadowOf(context.packageManager)
        pm.installPackage(android.content.pm.PackageInfo().apply {
            packageName = pkg
            applicationInfo = android.content.pm.ApplicationInfo().apply {
                packageName = pkg
                if (partOfThePhone) flags = flags or android.content.pm.ApplicationInfo.FLAG_SYSTEM
            }
        })
        pm.setInstallSourceInfo(pkg, askedBy, installer)
    }

    @Test
    fun aHelperKnownByNameMustComeFromAStoreOrThePhone() {
        val authenticator = "com.beemdevelopment.aegis"
        val play = "com.android.vending"
        fun counts(): Boolean {
            ForegroundRules.resetSystemLists()
            return ForegroundRules.isHopHelper(context, authenticator, null)
        }
        // Not installed at all: there is nothing to distrust (and nothing that could come to the front).
        assertTrue(counts())
        // Installed from a file by hand: anyone can give a file that name.
        installApp(authenticator, installer = null)
        assertFalse(counts())
        // Installed by the phone's app store: the app of that name.
        installApp(play, installer = null, partOfThePhone = true)
        installApp(authenticator, installer = play)
        assertTrue(counts())
        // Over adb in the store's name (`adb install -i`): the shell asked for it.
        installApp(authenticator, installer = play, askedBy = "com.android.shell")
        assertFalse(counts())
    }

    @Test
    fun aStoreKnownByNameMustBeTheStore() {
        val authenticator = "com.beemdevelopment.aegis"
        fun counts(): Boolean {
            ForegroundRules.resetSystemLists()
            return ForegroundRules.isHopHelper(context, authenticator, null)
        }
        // A file installed by hand under a store's name vouches for nothing.
        installApp("com.heytap.market", installer = null)
        installApp(authenticator, installer = "com.heytap.market")
        assertFalse(counts())
        // Nor does a store that isn't on the phone at all.
        installApp(authenticator, installer = "com.vivo.appstore")
        assertFalse(counts())
        // F-Droid doesn't come with a phone: an app of its name signed by someone else isn't it.
        installApp("org.fdroid.fdroid", installer = null)
        installApp(authenticator, installer = "org.fdroid.fdroid")
        assertFalse(counts())
    }

    @Test
    fun anAnswerIsAskedAgainWhenTheAppsOnThePhoneChange() {
        val authenticator = "com.beemdevelopment.aegis"
        installApp("com.android.vending", installer = null, partOfThePhone = true)
        installApp(authenticator, installer = "com.android.vending")
        ForegroundRules.resetSystemLists()
        assertTrue(ForegroundRules.isHopHelper(context, authenticator, null))
        // Replaced by a file installed by hand: within the same five minutes, but apps changed.
        installApp(authenticator, installer = null)
        assertTrue("until the phone says its apps changed, the last answer stands", ForegroundRules.isHopHelper(context, authenticator, null))
        com.android.BioKey.BioKeyApplication.notePackagesChangedForTests()
        assertFalse(ForegroundRules.isHopHelper(context, authenticator, null))
    }

    @Test
    fun everyAppKnownByNameCanBeSeen() {
        // Android hides apps a manifest doesn't ask to see: a hidden one couldn't be checked.
        val manifest = java.io.File("src/main/AndroidManifest.xml").readText()
        val missing = ForegroundRules.namedApps.filterNot { "<package android:name=\"$it\" />" in manifest }
        assertTrue("no <queries> entry for $missing", missing.isEmpty())
    }

    @Test
    fun pickingAPhotoAndComingBackKeepsTheSession() {
        LockStateTracker.onAppUnlocked(chat)
        LockStateTracker.onAppLeft(chat, LockTimeoutMode.IMMEDIATE, hopTarget = "com.google.android.photopicker")
        assertTrue(LockStateTracker.isAppUnlocked(chat, LockTimeoutMode.IMMEDIATE))

        LockStateTracker.onAppVisible(chat) // back in the app with the photo
        assertTrue(LockStateTracker.isAppUnlocked(chat, LockTimeoutMode.IMMEDIATE))
    }

    @Test
    fun aSignInThatSendsYouToYourAuthenticatorKeepsTheSession() {
        // App -> Google sign-in sheet -> the authenticator (locked too: BioKey's lock screen first) -> back.
        LockStateTracker.onAppUnlocked(chat)
        LockStateTracker.onAppLeft(chat, LockTimeoutMode.IMMEDIATE, hopTarget = "com.google.android.gms")
        val continues: (String) -> Boolean = { ForegroundRules.isHopTarget(context, it, null) }
        LockStateTracker.onForegroundChanged(context.packageName, { LockTimeoutMode.IMMEDIATE }, continues)
        LockStateTracker.onForegroundChanged("com.google.android.apps.authenticator2", { LockTimeoutMode.IMMEDIATE }, continues)
        assertTrue(LockStateTracker.isAppUnlocked(chat, LockTimeoutMode.IMMEDIATE))
        LockStateTracker.onAppVisible(chat)
        assertTrue(LockStateTracker.isAppUnlocked(chat, LockTimeoutMode.IMMEDIATE))
    }

    @Test
    fun aCallOrAPermissionPageInTheMiddleIsNotLeaving() {
        for (between in listOf("com.google.android.dialer", "com.google.android.permissioncontroller", "com.android.settings")) {
            LockStateTracker.lockAll()
            LockStateTracker.onAppUnlocked(chat)
            assertTrue(ForegroundRules.isHopTarget(context, between, null))
            LockStateTracker.onAppLeft(chat, LockTimeoutMode.IMMEDIATE, hopTarget = between)
            LockStateTracker.onAppVisible(chat)
            assertTrue(between, LockStateTracker.isAppUnlocked(chat, LockTimeoutMode.IMMEDIATE))
        }
    }

    @Test
    fun goingSomewhereElseFromThePickerEndsTheSession() {
        LockStateTracker.onAppUnlocked(chat)
        LockStateTracker.onAppLeft(chat, LockTimeoutMode.IMMEDIATE, hopTarget = "com.google.android.photopicker")
        LockStateTracker.onForegroundChanged("com.example.game", { LockTimeoutMode.IMMEDIATE })
        assertFalse(LockStateTracker.isAppUnlocked(chat, LockTimeoutMode.IMMEDIATE))
    }
}
