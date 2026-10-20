package com.android.BioKey

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import com.android.BioKey.core.data.PasswordManager
import com.android.BioKey.feature.lock.AppLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Telling which locked app a tapped icon, a Recents card or a window title is about. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppLabelsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun onlyTheNamePartOfALabelCounts() {
        assertEquals("messages", AppLabels.key("Messages, 3 notifications"))
        assertEquals("bank", AppLabels.key(" Bank \nLast opened yesterday"))
        assertNull(AppLabels.key("   "))
        assertNull(AppLabels.key(null))
    }

    @Test
    fun aLockedAppIsKnownByItsNameEvenBeforeTheFirstRead() {
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = "com.example.bank"
            applicationInfo = ApplicationInfo().apply {
                packageName = "com.example.bank"
                nonLocalizedLabel = "Bank"
            }
        })
        val labels = AppLabels(context)
        val locked = setOf("com.example.bank", PasswordManager.VIRTUAL_PACKAGE)
        assertEquals(listOf("com.example.bank"), labels.lockedNamed("bank", locked))
        assertEquals(listOf(PasswordManager.VIRTUAL_PACKAGE), labels.lockedNamed("google password manager", locked))
        assertTrue(labels.lockedNamed("game", locked).isEmpty())
        labels.shutdown()
    }
}
