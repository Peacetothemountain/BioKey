package com.android.BioKey

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.android.BioKey.feature.main.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Only BioKey's own Quick Settings tile can open the pause sheet: other apps can start BioKey
 * (it is the launcher entry), but not through the alias the tile uses.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PauseEntryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun onlyTheTilesEntryAsksForTheSheet() {
        // What the tile sends.
        val fromTile = MainActivity.pauseIntent(context)
        assertEquals(MainActivity.PAUSE_PROTECTION_ALIAS, fromTile.component?.className)
        assertTrue(MainActivity.isPauseRequest(fromTile))

        // What any other app can send to the exported launcher activity.
        val fromAnyApp = Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_PAUSE_PROTECTION)
        assertFalse(MainActivity.isPauseRequest(fromAnyApp))

        assertFalse(MainActivity.isPauseRequest(Intent().setClassName(context, MainActivity.PAUSE_PROTECTION_ALIAS)))
        assertFalse(MainActivity.isPauseRequest(null))
    }

    @Test
    fun theTilesEntryIsNotExported() {
        val entry = context.packageManager.getActivityInfo(ComponentName(context, MainActivity.PAUSE_PROTECTION_ALIAS), 0)
        assertFalse(entry.exported)
        assertEquals(MainActivity::class.java.name, entry.targetActivity)
    }
}
