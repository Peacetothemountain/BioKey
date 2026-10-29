package com.android.BioKey

import android.graphics.Bitmap
import android.os.Looper
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.android.BioKey.core.data.AppCategory
import com.android.BioKey.feature.main.AppIconCache
import com.android.BioKey.feature.main.AppItem
import com.android.BioKey.feature.main.PendingBackup
import java.time.Duration
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The home screen's own state: icons that stay within a few megabytes of the always-running
 * process, and a backup password that doesn't linger.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HomeScreenStateTest {

    private fun app(i: Int) = AppItem("com.example.app$i", "App $i", AppCategory.GENERAL)

    private fun icon() = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888).asImageBitmap()

    @Test
    fun iconsStayWithinTheirBudget() {
        // 120 px is a 40 dp icon at xxhdpi: ~56 KB each, so 200 of them would be ~11 MB.
        val key0 = AppIconCache.key(app(0), 120)
        for (i in 0 until 200) AppIconCache.put(AppIconCache.key(app(i), 120), icon())
        assertNull("the oldest icons make room", AppIconCache.peek(key0))
        assertNotNull("the newest stay", AppIconCache.peek(AppIconCache.key(app(199), 120)))
    }

    @Test
    fun iconsAreDroppedAFewMinutesAfterBioKeyLeavesTheScreen() {
        val key = AppIconCache.key(app(1), 120)
        AppIconCache.put(key, icon())

        AppIconCache.releaseLater()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(1))
        AppIconCache.keep() // back on screen within the minute
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(10))
        assertNotNull(AppIconCache.peek(key))

        AppIconCache.releaseLater()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(6))
        assertNull(AppIconCache.peek(key))
    }

    @Test
    fun iconsAreKeptPerProfileAndSize() {
        val personal = app(2)
        val work = personal.copy(user = android.os.UserHandle.getUserHandleForUid(10 * 100_000 + 10_123))
        assertTrue(work.isWorkProfile)
        assertTrue(AppIconCache.key(personal, 120) != AppIconCache.key(work, 120))
        assertTrue(AppIconCache.key(personal, 120) != AppIconCache.key(personal, 192))
    }

    @Test
    fun aPendingBackupPasswordIsWipedWhenBioKeysScreenGoesForGood() {
        val store = ViewModelStore()
        val pending = ViewModelProvider(store, ViewModelProvider.NewInstanceFactory())[PendingBackup::class.java]
        val password = "correct horse".toCharArray()
        pending.password = password

        store.clear()
        assertTrue(password.all { it == ' ' })
        assertNull(pending.password)
    }
}
