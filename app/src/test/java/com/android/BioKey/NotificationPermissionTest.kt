package com.android.BioKey

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import com.android.BioKey.core.security.LockStateTracker
import com.android.BioKey.feature.main.NotificationPermission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Android's "Allow BioKey to send you notifications?" names BioKey, like the pages self-protection
 * guards. Asked from inside BioKey, it must never bring up a second authentication.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationPermissionTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val asked = mutableListOf<String>()

    @Before
    fun reset() {
        LockStateTracker.lockAll()
        shadowOf(context.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(false)
    }

    private fun permission() = NotificationPermission(context, activity = null).also {
        it.launcher = object : ActivityResultLauncher<String>() {
            override val contract: ActivityResultContract<String, *> = ActivityResultContracts.RequestPermission()
            override fun launch(input: String, options: ActivityOptionsCompat?) {
                asked += input
            }
            override fun unregister() = Unit
        }
    }

    @Test
    fun askingFromInsideBioKeyPreAuthorisesTheQuestionUntilItIsAnswered() {
        LockStateTracker.onOwnAppUnlocked() // in BioKey's Settings, say
        val permission = permission()

        permission.request()
        assertEquals(listOf(Manifest.permission.POST_NOTIFICATIONS), asked)
        assertTrue(LockStateTracker.isSelfProtectionGranted())

        permission.onResult(granted = true, resumed = true)
        assertFalse(LockStateTracker.isSelfProtectionGranted())
    }

    @Test
    fun withoutBioKeysSessionNothingIsPreAuthorised() {
        permission().request()
        assertEquals(listOf(Manifest.permission.POST_NOTIFICATIONS), asked)
        assertFalse(LockStateTracker.isSelfProtectionGranted())
    }
}
