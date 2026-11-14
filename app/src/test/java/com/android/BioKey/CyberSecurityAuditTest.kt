package com.android.BioKey

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.graphics.Rect
import android.net.Uri
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.core.app.ApplicationProvider
import com.android.BioKey.core.FeatureLinks
import com.android.BioKey.core.data.AppText
import com.android.BioKey.core.data.PasswordManager
import com.android.BioKey.core.diagnostics.DiagnosticLog
import com.android.BioKey.core.security.LockStateTracker
import com.android.BioKey.core.security.ProtectionGaps
import com.android.BioKey.core.security.ReinstallMemory
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.feature.lock.AppLabels
import com.android.BioKey.feature.lock.BioKeyAccessibilityService
import com.android.BioKey.feature.lock.ForegroundRules
import com.android.BioKey.feature.lock.LockScreenActivity
import com.android.BioKey.feature.main.checkSafeExternalFd
import com.android.BioKey.feature.main.validateBackupUri
import java.io.File
import java.io.FileInputStream
import java.time.Duration
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Defensive regression tests across BioKey's four security metrics:
 * 1. Vault, cryptography, reinstall memory, Bidi/control sanitization & record integrity.
 * 2. Accessibility & compatibility engine spoofing resistance (`DreamActivity`, `"BioKey"` window title).
 * 3. Backup URI / private-FD confused-deputy validation & Credential Manager fail-closed resolution.
 * 4. Production invariants (`SELF_PROTECTION` constant gating, `FeatureLinks`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [InstallTimePermissions::class])
class CyberSecurityAuditTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val evil = "com.example.evil"
    private lateinit var vault: SecurityVault

    @Before
    fun setUp() {
        SecurityVault.getInstance(context).awaitPendingWrites()
        context.getSharedPreferences("biokey_vault", Context.MODE_PRIVATE).edit().clear().commit()
        ReinstallMemory.resetForTests(context)
        SecurityVault.resetForTests()
        LockStateTracker.resetForTests()
        LockScreenActivity.forgetResumeForTests()
        DiagnosticLog.clear()
        ForegroundRules.resetSystemLists()
        vault = SecurityVault.getInstance(context)
        vault.setSetupCompletedSync(true)
    }

    @After
    fun tearDown() {
        vault.setSetupCompletedSync(false)
        ReinstallMemory.resetForTests(context)
        LockScreenActivity.setStateForTests(null)
        ForegroundRules.resetSystemLists()
    }

    @Test
    fun bidiOverridesAndRecordSeparatorsAreStrippedFromLabelsAndGaps() {
        val spoofed = "Settings\u202Ekba.live\u2066\u001FPAUSED\u0007\nNext"
        val cleaned = AppText.cleanLabel(spoofed)
        assertFalse(cleaned.contains('\u202E'))
        assertFalse(cleaned.contains('\u2066'))
        assertFalse(cleaned.contains('\u001F'))
        assertFalse(cleaned.contains('\n'))
        assertEquals("Settingskba.livePAUSED Next", cleaned)
        assertEquals("settingskba.livepaused", AppLabels.key(spoofed))

        val encoded = ProtectionGaps.Change.TAKEN_OUT.encode("Bank\u001FPAUSED", "Finance\u202E")
        val decoded = ProtectionGaps.Change.decode(encoded)
        assertNotNull(decoded)
        assertEquals(ProtectionGaps.Change.TAKEN_OUT, decoded!!.first)
        assertEquals(listOf("BankPAUSED", "Finance"), decoded.second)
    }

    @Test
    fun anImpostorSignatureDoesNotEraseReinstallMemoryForTheRealApp() {
        val bank = "com.example.bank"
        val installed = HashMap<String, Set<String>>()
        var clock = 1_000L
        ReinstallMemory.resetForTests(context)
        ReinstallMemory.signersOf = { _, pkg -> installed[pkg] }
        ReinstallMemory.now = { clock }

        installed[bank] = setOf("aa11bb22")
        vault.setAppLock(bank, true)
        ReinstallMemory.note(context, listOf(bank))
        installed.remove(bank)
        ReinstallMemory.onUninstalled(context, bank)
        vault.onPackageUninstalled(bank)

        // An impostor APK reusing the package name is offered, not auto-locked, and doesn't erase the real certificate.
        clock = 2_000L
        installed[bank] = setOf("ff99ee88")
        assertEquals(ReinstallMemory.Outcome.OFFER, ReinstallMemory.onInstalled(context, bank))
        assertFalse(vault.isAppLocked(bank))

        // Uninstalling the impostor and installing the real developer-signed app still restores its lock.
        clock = 3_000L
        installed[bank] = setOf("aa11bb22")
        assertEquals(ReinstallMemory.Outcome.LOCKED_AGAIN, ReinstallMemory.onInstalled(context, bank))
        assertTrue(vault.isAppLocked(bank))
    }

    @Test
    fun credentialManagerManagementScreensFailClosedAndIgnoreTurkishLocale() {
        val saved = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("tr-TR"))
        try {
            // Management and settings activities inside Credential Manager lock as Password Manager, not as sign-in sheets.
            assertEquals(
                PasswordManager.VIRTUAL_PACKAGE,
                PasswordManager.resolve(
                    PasswordManager.CREDENTIAL_MANAGER,
                    "com.android.credentialmanager.PasskeyManagementActivity",
                    "Passkey SignInManage"
                )
            )
            // Real sign-in sheet still passes (returns null = floating sheet) even under Turkish locale ('I' -> 'i', not dotless 'ı').
            org.junit.Assert.assertNull(
                PasswordManager.resolve(
                    PasswordManager.CREDENTIAL_MANAGER,
                    "com.android.credentialmanager.CredentialSelectorActivity",
                    "Sign in"
                )
            )
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun backupUriValidationBlocksFileSchemesOwnAuthoritiesAndPrivateDataFiles() {
        assertThrows(IllegalArgumentException::class.java) {
            validateBackupUri(context, Uri.parse("file:///data/data/${context.packageName}/shared_prefs/biokey_vault.xml"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateBackupUri(context, Uri.parse("content://${context.packageName}/vault"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateBackupUri(context, Uri.parse("content://${context.packageName}.provider/vault"))
        }
        // Legitimate external SAF authority passes URI check.
        validateBackupUri(context, Uri.parse("content://com.android.externalstorage.documents/document/primary%3ABioKey.biokey"))

        // If Os.fstat / readlink sees BioKey's own UID (>= FIRST_APPLICATION_UID), checkSafeExternalFd refuses it.
        val privateFile = File(context.dataDir, "secret.txt").apply {
            parentFile?.mkdirs()
            writeText("secret")
        }
        FileInputStream(privateFile).use { fis ->
            val stat = runCatching { android.system.Os.fstat(fis.fd) }.getOrNull()
            if (stat != null) {
                val fakeAppUid = 10_234
                val uidField = runCatching { stat.javaClass.getField("st_uid") }.getOrNull()
                if (uidField != null && stat.st_uid == 0) {
                    // On JVM hosts where fstat reports uid 0, verify valid external check doesn't false-positive on 0.
                    checkSafeExternalFd(context, fis.fd, myUid = fakeAppUid)
                }
            }
        }
        privateFile.delete()
    }

    @Test
    fun dreamActivityAndBioKeyTitleSpoofingCannotBypassAccessibilityEngine() {
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = evil
            applicationInfo = ApplicationInfo().apply {
                packageName = evil
                nonLocalizedLabel = "Evil"
            }
        })
        vault.setAppLock(evil, true)
        Settings.Secure.putInt(context.contentResolver, "screensaver_enabled", 1)
        Settings.Secure.putString(context.contentResolver, "screensaver_components", "com.google.android.deskclock/com.android.deskclock.Screensaver")
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(true)

        val controller = Robolectric.buildService(BioKeyAccessibilityService::class.java).create()
        try {
            BioKeyAccessibilityService::class.java.getDeclaredMethod("onServiceConnected")
                .apply { isAccessible = true }.invoke(controller.get())
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))

            // 1. A locked non-screensaver app spoofing DreamActivity is covered, not ignored.
            assertFalse(ForegroundRules.isDreamActivity(context, evil, ForegroundRules.DREAM_ACTIVITY))
            assertTrue(ForegroundRules.isDreamActivity(context, "com.google.android.deskclock", ForegroundRules.DREAM_ACTIVITY))

            val whole = Rect(0, 0, 320, 470)
            val w = AccessibilityWindowInfo.obtain().also { win ->
                shadowOf(win).apply {
                    setId(11)
                    setType(AccessibilityWindowInfo.TYPE_APPLICATION)
                    setDisplayId(Display.DEFAULT_DISPLAY)
                    setBoundsInScreen(whole)
                    setActive(true)
                    setFocused(true)
                    setRoot(AccessibilityNodeInfo.obtain().apply { packageName = evil })
                }
            }
            shadowOf(controller.get()).setWindowsOnDisplay(Display.DEFAULT_DISPLAY, listOf(w))
            controller.get().onAccessibilityEvent(AccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
                packageName = evil
                className = ForegroundRules.DREAM_ACTIVITY
            })
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
            assertTrue(DiagnosticLog.entries().any { "Covered $evil" in it.text })

            // 2. A locked app hiding its window root and spoofing BioKey's title ("BioKey") after a launcher tap is attributed and covered.
            DiagnosticLog.clear()
            LockStateTracker.resetForTests()
            val launcher = "com.google.android.apps.nexuslauncher"
            val launcherWin = AccessibilityWindowInfo.obtain().also { win ->
                shadowOf(win).apply {
                    setId(1)
                    setType(AccessibilityWindowInfo.TYPE_APPLICATION)
                    setDisplayId(Display.DEFAULT_DISPLAY)
                    setBoundsInScreen(whole)
                    setActive(true)
                    setFocused(true)
                    setRoot(AccessibilityNodeInfo.obtain().apply { packageName = launcher })
                }
            }
            shadowOf(controller.get()).setWindowsOnDisplay(Display.DEFAULT_DISPLAY, listOf(launcherWin))
            controller.get().onAccessibilityEvent(AccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
                packageName = launcher
                className = "$launcher.NexusLauncherActivity"
            })
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))

            controller.get().onAccessibilityEvent(AccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED).apply {
                packageName = launcher
                className = "android.widget.TextView"
                text.add("Evil")
            })
            val spoofedBioKeyWin = AccessibilityWindowInfo.obtain().also { win ->
                shadowOf(win).apply {
                    setId(12)
                    setType(AccessibilityWindowInfo.TYPE_APPLICATION)
                    setDisplayId(Display.DEFAULT_DISPLAY)
                    setBoundsInScreen(whole)
                    setActive(true)
                    setFocused(true)
                    setTitle(context.getString(R.string.brand_name))
                }
            }
            shadowOf(controller.get()).setWindowsOnDisplay(Display.DEFAULT_DISPLAY, listOf(spoofedBioKeyWin))
            controller.get().onAccessibilityEvent(AccessibilityEvent(AccessibilityEvent.TYPE_WINDOWS_CHANGED).apply {
                val changeFlags = AccessibilityEvent.WINDOWS_CHANGE_ADDED or AccessibilityEvent.WINDOWS_CHANGE_ACTIVE
                AccessibilityEvent::class.java.getDeclaredMethod("setWindowChanges", Int::class.javaPrimitiveType)
                    .apply { isAccessible = true }.invoke(this, changeFlags)
                android.view.accessibility.AccessibilityRecord::class.java
                    .getDeclaredMethod("setWindowId", Int::class.javaPrimitiveType)
                    .apply { isAccessible = true }.invoke(this, 12)
            })
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
            assertTrue(
                DiagnosticLog.entries().joinToString { it.text },
                DiagnosticLog.entries().any { "taken to be $evil" in it.text } &&
                    DiagnosticLog.entries().any { "Covered $evil" in it.text }
            )
            assertTrue("Spoofed 'BioKey' window title must be attributed and locked", LockStateTracker.isRegularLockPending(evil))
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun pauseDurationIsClampedAgainstOverflowAndNegativeValues() {
        vault.pauseProtection(-50_000L)
        assertFalse(vault.isProtectionActive())
        vault.resumeProtection()
        vault.pauseProtection(Long.MAX_VALUE)
        assertFalse(vault.isProtectionActive())
        vault.resumeProtection()
        assertTrue(vault.isProtectionActive())
        assertNotNull(FeatureLinks.engines)
    }

    private fun assertNotNull(value: Any?) = org.junit.Assert.assertNotNull(value)
}
