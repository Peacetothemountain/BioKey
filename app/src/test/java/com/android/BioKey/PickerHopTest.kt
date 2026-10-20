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
        LockStateTracker.lockAll()
        // The picker list is cached process-wide: one read by another test, before these apps
        // were registered, mustn't be the one asked here.
        ForegroundRules.resetSystemLists()
    }

    /** Registers [pkg] as handling [action] (with [mime], if any) on this "device". */
    private fun install(pkg: String, action: String, mime: String? = null) {
        val component = ComponentName(pkg, "$pkg.PickActivity")
        val pm = shadowOf(context.packageManager)
        pm.addActivityIfNotPresent(component)
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
