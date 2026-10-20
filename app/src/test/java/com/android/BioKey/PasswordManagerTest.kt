package com.android.BioKey

import com.android.BioKey.core.data.PasswordManager
import com.android.BioKey.core.data.PasswordManager.CREDENTIAL_MANAGER
import com.android.BioKey.core.data.PasswordManager.GMS
import com.android.BioKey.core.data.PasswordManager.VIRTUAL_PACKAGE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Google Password Manager is locked as one app; the sign-in sheets of the same packages never are. */
class PasswordManagerTest {

    @Test
    fun theTwoPackagesAreOneApp() {
        assertEquals(setOf(VIRTUAL_PACKAGE, CREDENTIAL_MANAGER), PasswordManager.aliasesOf(CREDENTIAL_MANAGER))
        assertEquals(setOf("com.example.bank"), PasswordManager.aliasesOf("com.example.bank"))
        assertEquals(setOf(VIRTUAL_PACKAGE, CREDENTIAL_MANAGER, "a"), PasswordManager.expand(listOf(VIRTUAL_PACKAGE, "a")))
    }

    @Test
    fun playServicesScreensOfThePasswordManagerAreThePasswordManager() {
        assertEquals(VIRTUAL_PACKAGE, PasswordManager.resolve(GMS, "com.google.android.gms.credential.manager.PasswordManagerActivity"))
        assertEquals(VIRTUAL_PACKAGE, PasswordManager.resolve(GMS, "com.google.android.gms.autofill.ui.AutofillManagePasswordsActivity"))
        assertEquals(VIRTUAL_PACKAGE, PasswordManager.resolve(GMS, "android.widget.FrameLayout", "Google Password Manager"))
    }

    @Test
    fun otherPlayServicesScreensStayPlayServices() {
        assertEquals(GMS, PasswordManager.resolve(GMS, "com.google.android.gms.auth.uiflows.addaccount.SignInActivity"))
        assertEquals(GMS, PasswordManager.resolve(GMS, "com.google.android.gms.app.settings.GoogleSettingsActivity", "Password Manager"))
        assertEquals(GMS, PasswordManager.resolve(GMS, null))
    }

    @Test
    fun credentialManagerSheetsAreOverlaysAndEverythingElseIsThePasswordManager() {
        assertNull(PasswordManager.resolve(CREDENTIAL_MANAGER, "com.google.android.apps.credentialmanager.ui.PasskeyBottomSheetActivity"))
        assertNull(PasswordManager.resolve(CREDENTIAL_MANAGER, "com.google.android.apps.credentialmanager.AccountPickerActivity"))
        // Every class of the package has "credential" in it: that alone must not make it a sheet.
        assertEquals(VIRTUAL_PACKAGE, PasswordManager.resolve(CREDENTIAL_MANAGER, "com.google.android.apps.credentialmanager.MainActivity"))
        // Unknown screens stay covered.
        assertEquals(VIRTUAL_PACKAGE, PasswordManager.resolve(CREDENTIAL_MANAGER, null))
    }

    @Test
    fun otherAppsAreThemselves() {
        assertEquals("com.example.bank", PasswordManager.resolve("com.example.bank", "com.example.bank.PickerActivity"))
    }
}
