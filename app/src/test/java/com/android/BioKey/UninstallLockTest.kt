package com.android.BioKey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An app that's uninstalled loses its lock only when its data went with it and no copy is left in
 * another profile: an archived app, one uninstalled with "keep app data" and a work twin all still
 * have data a reinstall (or the twin) would open, so they keep theirs.
 */
class UninstallLockTest {

    private var profilesAsked = 0

    private fun keepsLock(archival: Boolean, dataRemoved: Boolean, installedElsewhere: Boolean): Boolean =
        BioKeyApplication.keepsLockAfterRemoval(archival, dataRemoved) { profilesAsked++; installedElsewhere }

    @Test
    fun anArchivedAppKeepsItsLock() {
        assertTrue(keepsLock(archival = true, dataRemoved = false, installedElsewhere = false))
        assertTrue(keepsLock(archival = true, dataRemoved = true, installedElsewhere = false))
        assertEquals("The broadcast settled it: no need to look through the profiles", 0, profilesAsked)
    }

    @Test
    fun anAppUninstalledWithItsDataKeptKeepsItsLock() {
        assertTrue(keepsLock(archival = false, dataRemoved = false, installedElsewhere = false))
        assertEquals(0, profilesAsked)
    }

    @Test
    fun anAppStillInstalledInAnotherProfileKeepsItsLock() {
        assertTrue(keepsLock(archival = false, dataRemoved = true, installedElsewhere = true))
        assertEquals(1, profilesAsked)
    }

    @Test
    fun anAppGoneWithItsDataFromEveryProfileLosesItsLock() {
        assertFalse(keepsLock(archival = false, dataRemoved = true, installedElsewhere = false))
        assertEquals(1, profilesAsked)
    }
}
