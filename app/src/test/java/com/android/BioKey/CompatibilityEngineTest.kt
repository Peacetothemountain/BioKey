package com.android.BioKey

import android.app.Application
import android.app.NotificationManager
import android.app.usage.UsageEvents
import android.app.usage.UsageEvents.Event.ACTIVITY_PAUSED
import android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED
import android.app.usage.UsageEvents.Event.ACTIVITY_STOPPED
import android.app.usage.UsageEventsQuery
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.HandlerThread
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.android.BioKey.core.data.LockTimeoutMode
import com.android.BioKey.core.data.PasswordManager
import com.android.BioKey.core.diagnostics.DiagnosticLog
import com.android.BioKey.core.security.LockStateTracker
import com.android.BioKey.core.security.SecurityEngine
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.feature.lock.AppInfoCache
import com.android.BioKey.feature.lock.ForegroundRules
import com.android.BioKey.feature.lock.InstantShieldOverlay
import com.android.BioKey.feature.lock.LockScreenActivity
import com.android.BioKey.feature.lock.UsageWatchService
import com.android.BioKey.feature.main.MainActivity
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.ClassName
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowSettings
import org.robolectric.shadows.ShadowUsageStatsManager

/**
 * Compatibility mode end to end, from usage events to what it covers: every app that comes to the
 * screen counts, not only the newest; an unlocked app stays in use while it shows; nothing floating
 * over an unlocked app asks for authentication.
 *
 * Also on Android 17, the owner's phone's, where usage access is asked only for the events (and,
 * in a look back, the apps) it needs (Android 15 and later do).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], shadows = [CompatibilityEngineTest.PackageFilteringUsageStats::class, InstallTimePermissions::class])
class CompatibilityEngineTest {

    /**
     * Usage access as Android 15 and later answer a query that names packages: only their events.
     * Robolectric's own shadow keeps to the event types asked for, but not to the packages. Each
     * query's packages are kept ([packageQueries]).
     */
    @Implements(UsageStatsManager::class)
    class PackageFilteringUsageStats : ShadowUsageStatsManager() {
        companion object {
            val packageQueries = mutableListOf<Set<String>>()
        }

        @Implementation(minSdk = 35)
        override fun queryEvents(@ClassName("android.app.usage.UsageEventsQuery") query: Any?): UsageEvents {
            val packages = (query as UsageEventsQuery).packageNames.toSet()
            packageQueries += packages
            val events = super.queryEvents(query)
            if (packages.isEmpty()) return events
            val kept = ArrayList<UsageEvents.Event>()
            while (events.hasNextEvent()) {
                val event = UsageEvents.Event()
                events.getNextEvent(event)
                if (event.packageName in packages) kept += event
            }
            return ShadowUsageStatsManager::class.java.getDeclaredMethod("createUsageEvents", List::class.java)
                .apply { isAccessible = true }.invoke(null, kept) as UsageEvents
        }
    }

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val bank = "com.example.bank"
    private val chat = "com.example.chat"
    private lateinit var vault: SecurityVault
    private lateinit var engine: ServiceController<UsageWatchService>
    private lateinit var worker: Looper

    @Before
    fun start() {
        // As the other test classes do: whatever the last vault was writing lands first.
        SecurityVault.getInstance(context).awaitPendingWrites()
        context.getSharedPreferences("biokey_vault", Context.MODE_PRIVATE).edit().clear().commit()
        SecurityVault.resetForTests()
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "")
        ShadowSettings.setCanDrawOverlays(true)
        LockStateTracker.lockAll()
        vault = SecurityVault.getInstance(context)
        vault.setSetupCompletedSync(true)
        vault.setAppLock(bank, true)
        DiagnosticLog.clear()
        PackageFilteringUsageStats.packageQueries.clear()
        startEngine()
    }

    /** Compatibility mode starts (BioKey set up, or the accessibility engine handing over). */
    private fun startEngine() {
        engine = Robolectric.buildService(UsageWatchService::class.java).create()
        worker = (UsageWatchService::class.java.getDeclaredField("worker").apply { isAccessible = true }.get(engine.get()) as HandlerThread).looper
        step(200)
    }

    @After
    fun stop() {
        engine.destroy()
        lockScreenUp(null)
        promptUpFor(null)
        // The engine asked which apps are launchers and pickers: leave no cached answer to other tests.
        ForegroundRules.resetSystemLists()
        AppInfoCache.invalidate(bank)
    }

    /** Lets [ms] pass: polls run, and what they read is acted on. */
    private fun step(ms: Long = 200) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
        shadowOf(worker).idle()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun event(type: Int, pkg: String, cls: String = "$pkg.MainActivity") {
        val usage = context.getSystemService(UsageStatsManager::class.java)
        shadowOf(usage).addEvent(
            ShadowUsageStatsManager.EventBuilder.buildEvent()
                .setPackage(pkg).setClass(cls).setEventType(type).setTimeStamp(System.currentTimeMillis())
                .build()
        )
        // Usage access stamps events with the wall clock (not the test clock): keep them apart.
        Thread.sleep(2)
    }

    private fun covered(pkg: String) = DiagnosticLog.entries().map { it.text }.filter { it.startsWith("Covered $pkg ") }

    /** The app BioKey's lock screen is up for, as the real activity reports it. */
    private fun lockScreenFor(pkg: String?) {
        LockScreenActivity::class.java.getDeclaredField("activeLockedTarget").apply { isAccessible = true }.set(null, pkg)
    }

    /** The app BioKey's lock screen is up and in front for (null: none), as the real activity reports it. */
    private fun lockScreenUp(pkg: String?) {
        lockScreenFor(pkg)
        LockScreenActivity::class.java.getDeclaredField("isLockScreenResumed").apply { isAccessible = true }.setBoolean(null, pkg != null)
    }

    /** BioKey's prompt is up for [pkg] (null: none is), as SecurityEngine reports it. */
    private fun promptUpFor(pkg: String?) {
        SecurityEngine.INSTANCE.setPromptForTests(pkg)
    }

    private val lockScreen = LockScreenActivity::class.java.name
    private val launcher = "com.google.android.apps.nexuslauncher"
    private val bioKeysScreen = MainActivity::class.java.name

    /** BioKey's lock screen for [pkg] comes up over [front], and its cover, once drawn, asks the shield to go. */
    private fun lockScreenOpensOver(front: String, pkg: String, frontClass: String = "$front.MainActivity") {
        lockScreenUp(pkg)
        event(ACTIVITY_PAUSED, front, frontClass)
        event(ACTIVITY_RESUMED, context.packageName, lockScreen)
        event(ACTIVITY_STOPPED, front, frontClass)
        step()
        InstantShieldOverlay.dismiss(pkg)
    }

    /** The lock screen for [pkg] closes onto [front], unlocked ([unlocked]) or left. */
    private fun lockScreenClosesOnto(front: String, pkg: String, unlocked: Boolean, frontClass: String = "$front.MainActivity") {
        if (unlocked) LockStateTracker.onAppUnlocked(pkg) else LockStateTracker.markLockAbandoned(pkg)
        InstantShieldOverlay.dismiss(pkg)
        lockScreenUp(null)
        event(ACTIVITY_PAUSED, context.packageName, lockScreen)
        event(ACTIVITY_RESUMED, front, frontClass)
        step()
    }

    /**
     * [pkg] is installed, named [label], with a launch screen, so opening it starts an activity.
     * The engine caches its name for the covers, for the whole test run: it is named as an app is.
     */
    private fun installWithLaunchScreen(pkg: String, label: String) {
        val pm = shadowOf(context.packageManager)
        pm.installPackage(PackageInfo().apply {
            packageName = pkg
            applicationInfo = ApplicationInfo().apply {
                packageName = pkg
                nonLocalizedLabel = label
            }
        })
        val main = ComponentName(pkg, "$pkg.MainActivity")
        pm.addActivityIfNotPresent(main)
        pm.addIntentFilterForActivity(main, IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) })
    }

    /** The activities BioKey started since the last call. */
    private fun started(): List<Intent> = generateSequence { shadowOf(context).nextStartedActivity }.toList()

    private fun lockScreensFor(starts: List<Intent>, pkg: String) =
        starts.count { it.component?.className == lockScreen && it.getStringExtra(LockScreenActivity.EXTRA_LOCKED_PACKAGE) == pkg }

    private fun isHeldOpen(pkg: String) =
        UsageWatchService::class.java.getDeclaredMethod("isHeldOpen", String::class.java).apply { isAccessible = true }.invoke(engine.get(), pkg) as Boolean

    /** What the cover says: its text that is showing. */
    private fun coverTexts(): List<String> {
        val cover = InstantShieldOverlay::class.java.getDeclaredField("cover").apply { isAccessible = true }.get(shield()) as View
        fun texts(view: View): List<String> = when {
            view.visibility != View.VISIBLE -> emptyList()
            view is TextView -> listOf(view.text.toString()).filter { it.isNotBlank() }
            view is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
            else -> emptyList()
        }
        return texts(cover)
    }

    private fun shield() =
        UsageWatchService::class.java.getDeclaredField("shield").apply { isAccessible = true }.get(engine.get()) as InstantShieldOverlay

    @Test
    fun aLockedAppIsCovered() {
        event(ACTIVITY_RESUMED, bank)
        step()
        assertEquals(listOf("Covered $bank (opened)"), covered(bank))
    }

    @Test
    fun splitScreenCoversTheLockedHalfEvenWhenTheOtherOpenedLast() {
        event(ACTIVITY_RESUMED, bank)
        event(ACTIVITY_RESUMED, chat)
        step()
        assertEquals(listOf("Covered $bank (on screen beside another app)"), covered(bank))
    }

    @Test
    fun aDialogOverALockedAppAtLaunchStillCoversIt() {
        event(ACTIVITY_RESUMED, bank)
        event(ACTIVITY_PAUSED, bank)
        event(ACTIVITY_RESUMED, "com.google.android.permissioncontroller", "com.android.permissioncontroller.permission.ui.GrantPermissionsActivity")
        step()
        assertEquals(listOf("Covered $bank (opened under a dialog)"), covered(bank))
    }

    @Test
    fun anAppThatHandsStraightOverToAnotherIsNotCovered() {
        event(ACTIVITY_RESUMED, bank)
        event(ACTIVITY_PAUSED, bank)
        event(ACTIVITY_RESUMED, chat)
        step()
        event(ACTIVITY_STOPPED, bank)
        repeat(8) { step() }
        assertTrue(covered(bank).isEmpty())
    }

    @Test
    fun anAppLeftShowingUnderASeeThroughOneIsCovered() {
        event(ACTIVITY_RESUMED, bank)
        event(ACTIVITY_PAUSED, bank)
        event(ACTIVITY_RESUMED, chat)
        repeat(8) { step() }
        assertEquals(listOf("Covered $bank (still showing under another app)"), covered(bank))
    }

    @Test
    fun googlePasswordManagerInsidePlayServicesIsCovered() {
        vault.setAppLock(PasswordManager.VIRTUAL_PACKAGE, true)
        event(ACTIVITY_RESUMED, PasswordManager.GMS, "com.google.android.gms.credential.manager.PasswordManagerActivity")
        step()
        assertEquals(listOf("Covered ${PasswordManager.VIRTUAL_PACKAGE} (opened)"), covered(PasswordManager.VIRTUAL_PACKAGE))
    }

    @Test
    fun aPasskeySheetOverAnUnlockedAppAsksForNothing() {
        vault.setAppLock(PasswordManager.VIRTUAL_PACKAGE, true)
        event(ACTIVITY_RESUMED, chat)
        step()
        event(ACTIVITY_PAUSED, chat)
        event(ACTIVITY_RESUMED, PasswordManager.CREDENTIAL_MANAGER, "${PasswordManager.CREDENTIAL_MANAGER}.ui.PasskeyBottomSheetActivity")
        repeat(8) { step() }
        assertTrue(DiagnosticLog.entries().none { it.text.startsWith("Covered") })
    }

    @Test
    fun anUnlockedAppBesideAnotherStaysUnlockedUntilItStops() {
        event(ACTIVITY_RESUMED, bank)
        step()
        LockStateTracker.onAppUnlocked(bank)
        event(ACTIVITY_RESUMED, bank) // Back from the lock screen.
        step()
        event(ACTIVITY_RESUMED, chat) // Split screen: the bank stays resumed beside it.
        repeat(3) { step() }
        assertTrue(LockStateTracker.isAppUnlocked(bank, LockTimeoutMode.IMMEDIATE))
        event(ACTIVITY_PAUSED, bank)
        event(ACTIVITY_STOPPED, bank)
        step()
        assertFalse(LockStateTracker.isAppUnlocked(bank, LockTimeoutMode.IMMEDIATE))
    }

    @Test
    fun aLockedAppLeftUncoveredInFrontIsCoveredAgain() {
        event(ACTIVITY_RESUMED, bank)
        step()
        // A lock screen on its way out takes the cover down and gives up, the app still in front.
        InstantShieldOverlay.dismiss(bank)
        LockStateTracker.markLockAbandoned(bank)
        step(LockStateTracker.ABANDONED_LOCK_COOLDOWN_MS + 100)
        repeat(3) { step() }
        assertEquals(listOf("Covered $bank (opened)", "Covered $bank (still in front, uncovered)"), covered(bank))
    }

    @Test
    fun whenTheLockScreenNeverAppearsTheAppStaysCoveredUntilSomethingElseIsInFront() {
        event(ACTIVITY_RESUMED, bank)
        repeat(16) { step() } // Every retry, and the give-up.
        val log = DiagnosticLog.entries().map { it.text }
        assertTrue(log.toString(), "Lock screen for $bank didn't appear; kept it covered" in log)
        // The last look before giving up only looks: it doesn't ask for the lock screen again.
        assertTrue(log.toString(), log.none { it.endsWith("asking again (4)") })
        assertTrue(shield().isShowing)
        // BioKey's own screen coming to the front (from its notification, say) takes it down too.
        event(ACTIVITY_RESUMED, context.packageName, "${context.packageName}.feature.main.MainActivity")
        step()
        assertFalse(shield().isShowing)
    }

    @Test
    fun unlockingOntoASignInScreenTheAppHadOpenCountsAsArriving() {
        event(ACTIVITY_RESUMED, bank)
        step()
        lockScreenFor(bank)
        event(ACTIVITY_PAUSED, bank)
        event(ACTIVITY_RESUMED, context.packageName, LockScreenActivity::class.java.name)
        event(ACTIVITY_STOPPED, bank)
        step()
        LockStateTracker.onAppUnlocked(bank)
        // The lock screen closes onto the Play services sign-in screen the bank had open over itself.
        event(ACTIVITY_PAUSED, context.packageName, LockScreenActivity::class.java.name)
        event(ACTIVITY_RESUMED, PasswordManager.GMS, "com.google.android.gms.auth.uiflows.addaccount.SignInActivity")
        step()
        // Signing in takes a while; back in the bank, nothing asks to unlock it again.
        step(LockStateTracker.ARRIVAL_GRACE_MS + 1_000)
        event(ACTIVITY_RESUMED, bank)
        step()
        assertEquals(listOf("Covered $bank (opened)"), covered(bank))
        assertTrue(LockStateTracker.isAppUnlocked(bank, LockTimeoutMode.IMMEDIATE))
    }

    @Test
    fun theAppInFrontWhenAScreensaverStartsIsCoveredWhenItEnds() {
        event(ACTIVITY_RESUMED, bank)
        step()
        LockStateTracker.onAppUnlocked(bank)
        event(ACTIVITY_RESUMED, bank)
        step()
        context.sendBroadcast(Intent(Intent.ACTION_DREAMING_STARTED))
        step()
        // A long screensaver: what came before it is too old to be read again when it ends, and
        // the app under it reports nothing new.
        shadowOf(context.getSystemService(UsageStatsManager::class.java)).simulateTimeChange(-60_000)
        context.sendBroadcast(Intent(Intent.ACTION_DREAMING_STOPPED))
        repeat(3) { step() }
        assertEquals(listOf("Covered $bank (opened)", "Covered $bank (still in front, uncovered)"), covered(bank))
    }

    @Test
    fun nothingIsCoveredOverAScreensaver() {
        event(ACTIVITY_RESUMED, bank)
        step()
        LockStateTracker.onAppUnlocked(bank)
        event(ACTIVITY_RESUMED, bank)
        step()
        context.sendBroadcast(Intent(Intent.ACTION_DREAMING_STARTED))
        repeat(3) { step() }
        assertEquals(1, covered(bank).size)
        assertFalse(LockStateTracker.isAppUnlocked(bank, LockTimeoutMode.GRACE_5_MINUTES))
    }

    @Test
    fun losingDisplayOverOtherAppsStopsTheEngineWithAnAlert() {
        LockStateTracker.onAppUnlocked(chat)
        ShadowSettings.setCanDrawOverlays(false)
        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_ON))
        step()
        assertTrue(shadowOf(engine.get()).isStoppedBySelf)
        assertFalse(LockStateTracker.isAppUnlocked(chat, LockTimeoutMode.ON_SCREEN_OFF))
        val alert = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications
            .map { it.extras.getCharSequence(NotificationCompat.EXTRA_TEXT).toString() }
        assertTrue(alert.toString(), alert.any { "lost display over other apps" in it })
    }

    /** The bank, unlocked and in front. */
    private fun unlockBank() {
        event(ACTIVITY_RESUMED, bank)
        step()
        LockStateTracker.onAppUnlocked(bank)
        event(ACTIVITY_RESUMED, bank) // Back from the lock screen.
        step()
    }

    @Test
    fun aTripToAPlayPageAndStraightBackKeepsTheSession() {
        unlockBank()
        // "Rate us" opens the bank's Play listing: a page of its own, so the bank stops.
        val listing = "com.google.android.finsky.activities.MainActivity"
        event(ACTIVITY_PAUSED, bank)
        event(ACTIVITY_RESUMED, "com.android.vending", listing)
        step()
        event(ACTIVITY_STOPPED, bank)
        repeat(3) { step() }
        step(30_000)
        event(ACTIVITY_PAUSED, "com.android.vending", listing)
        event(ACTIVITY_RESUMED, bank)
        step()
        assertEquals(listOf("Covered $bank (opened)"), covered(bank))
        assertTrue(LockStateTracker.isAppUnlocked(bank, LockTimeoutMode.IMMEDIATE))
    }

    @Test
    fun aLockedAppLeftInPictureInPictureIsCoveredOnceThePhoneIsUnlocked() {
        unlockBank()
        // Home: the bank goes into picture-in-picture (paused, never stopped) and keeps its session.
        event(ACTIVITY_PAUSED, bank)
        event(ACTIVITY_RESUMED, chat)
        repeat(3) { step() }
        assertTrue(LockStateTracker.isAppUnlocked(bank, LockTimeoutMode.IMMEDIATE))
        // The phone locks: every session ends, and the keyguard stops the window.
        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        step()
        event(ACTIVITY_STOPPED, bank)
        event(ACTIVITY_STOPPED, chat)
        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_ON))
        step()
        // Unlocked: the window is back, paused (it never resumes), over the app that was in front.
        event(ACTIVITY_RESUMED, chat)
        event(ACTIVITY_PAUSED, bank)
        repeat(8) { step() }
        assertEquals("Covered $bank (showing behind the app in front, as in picture-in-picture)", covered(bank).last())
        assertTrue(shield().isShowing)
        // Its lock screen comes up under the cover, and asks to take it down: it stays, as
        // nothing else hides the window. Going Home doesn't take it down either.
        lockScreenFor(bank)
        InstantShieldOverlay.dismiss(bank)
        shield().hide()
        assertTrue(shield().isShowing)
        // Unlocked: it goes.
        LockStateTracker.onAppUnlocked(bank)
        InstantShieldOverlay.dismiss(bank)
        assertFalse(shield().isShowing)
    }

    @Test
    fun aWindowThatComesBackPausedAndThenStopsIsNotCovered() {
        // Under something that hid it after all (it stopped within the settle time): nothing showed.
        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        step()
        event(ACTIVITY_STOPPED, bank)
        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_ON))
        step()
        event(ACTIVITY_RESUMED, chat)
        event(ACTIVITY_PAUSED, bank)
        step()
        event(ACTIVITY_STOPPED, bank)
        repeat(8) { step() }
        assertTrue(covered(bank).isEmpty())
    }

    @Test
    fun anUnlockedAppInPictureInPictureIsCoveredWhenEverySessionEnds() {
        unlockBank()
        event(ACTIVITY_PAUSED, bank)
        event(ACTIVITY_RESUMED, chat)
        repeat(3) { step() }
        // Protection resumed, or every app locked from the tile, with the screen on.
        LockStateTracker.lockAll(reason = "test")
        repeat(8) { step() }
        assertEquals("Covered $bank (showing behind the app in front, as in picture-in-picture)", covered(bank).last())
        assertTrue(shield().isShowing)
    }

    @Test
    fun bioKeysFilePickerTripEndsWhereItGoesElsewhere() {
        LockStateTracker.onOwnAppUnlocked()
        LockStateTracker.beginOwnPicker()
        LockStateTracker.onOwnAppStopped()
        event(ACTIVITY_RESUMED, "com.google.android.documentsui", "com.android.documentsui.picker.PickActivity")
        step()
        assertTrue(LockStateTracker.isOwnAppUnlocked())
        // Home, then another app, phone handed over: BioKey asks again.
        event(ACTIVITY_RESUMED, chat)
        step()
        assertFalse(LockStateTracker.isOwnAppUnlocked())
    }

    @Test
    fun bioKeysSettingsTripEndsWhereItGoesElsewhere() {
        LockStateTracker.onOwnAppUnlocked()
        LockStateTracker.beginExternalNavigation(preAuthorizeSelfProtection = true)
        LockStateTracker.onOwnAppStopped()
        event(ACTIVITY_RESUMED, "com.android.settings", "com.android.settings.SubSettings")
        step()
        assertTrue(LockStateTracker.isOwnAppUnlocked())
        event(ACTIVITY_RESUMED, chat)
        step()
        assertFalse(LockStateTracker.isOwnAppUnlocked())
    }

    @Test
    fun aLockedDigitalWellbeingIsCoveredLikeAnyApp() {
        // It floats over apps unless the user locked it (its launcher icon turned on).
        val wellbeing = "com.google.android.apps.wellbeing"
        vault.setAppLock(wellbeing, true)
        event(ACTIVITY_RESUMED, wellbeing, "com.google.android.apps.wellbeing.settings.TopLevelSettingsActivity")
        step()
        assertEquals(listOf("Covered $wellbeing (opened)"), covered(wellbeing))
    }

    /**
     * The bank, unlocked, goes into picture-in-picture over [front] (locked too, with
     * [frontLocked]) and the phone locks. Once it is unlocked, [front] is in front and the bank's
     * window is back, paused (it never resumes).
     */
    private fun bankLeftInPictureInPictureOver(front: String, frontLocked: Boolean = false) {
        unlockBank()
        event(ACTIVITY_PAUSED, bank)
        event(ACTIVITY_RESUMED, front)
        repeat(3) { step() }
        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        step()
        if (frontLocked) vault.setAppLock(front, true)
        event(ACTIVITY_STOPPED, bank)
        event(ACTIVITY_STOPPED, front)
        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_ON))
        step()
        event(ACTIVITY_RESUMED, front)
        event(ACTIVITY_PAUSED, bank)
        step()
    }

    private val pipCover = "Covered $bank (showing behind the app in front, as in picture-in-picture)"

    @Test
    fun aWindowInPictureInPictureOverAnotherLockedAppLeavesThatAppUnlocked() {
        installWithLaunchScreen(bank, "Bank")
        bankLeftInPictureInPictureOver(chat, frontLocked = true)
        // The chat app, in front, is asked for first, and unlocked at once (face unlock).
        assertEquals(listOf("Covered $chat (opened)"), covered(chat))
        lockScreenOpensOver(chat, chat)
        lockScreenClosesOnto(chat, chat, unlocked = true)
        started()
        repeat(6) { step() }
        assertEquals(pipCover, covered(bank).last())
        // Only the bank's lock screen comes up, over the chat app: opening the bank over it would
        // count as leaving the chat app, which would then ask again.
        val starts = started()
        assertTrue(starts.toString(), lockScreensFor(starts, bank) > 0)
        assertTrue(starts.toString(), starts.none { it.component?.packageName == bank })
        lockScreenOpensOver(chat, bank)
        lockScreenClosesOnto(chat, bank, unlocked = true)
        assertTrue(LockStateTracker.isAppUnlocked(chat, LockTimeoutMode.IMMEDIATE))
        assertFalse(shield().isShowing)
        // The bank's window floats on, unlocked: its session reached the screen.
        step(LockStateTracker.ARRIVAL_GRACE_MS + 1_000)
        assertTrue(LockStateTracker.isAppUnlocked(bank, LockTimeoutMode.IMMEDIATE))
        assertTrue(LockStateTracker.isAppUnlocked(chat, LockTimeoutMode.IMMEDIATE))
    }

    @Test
    fun aWindowInPictureInPictureWaitsForAnotherAppsPromptToGo() {
        installWithLaunchScreen(bank, "Bank")
        bankLeftInPictureInPictureOver(chat, frontLocked = true)
        // The chat app's lock screen is up, its prompt slow to finish (a fingerprint).
        lockScreenOpensOver(chat, chat)
        started()
        repeat(6) { step() }
        // The cover goes over both, and stays, but nothing starts that would take that prompt away.
        assertEquals(pipCover, covered(bank).last())
        repeat(20) { step() }
        var starts = started()
        assertEquals(starts.toString(), 0, lockScreensFor(starts, bank))
        assertTrue(starts.toString(), starts.none { it.component?.packageName == bank || it.hasCategory(Intent.CATEGORY_HOME) })
        assertTrue(shield().isShowing)
        assertEquals(bank, shield().targetPackage)
        // Unlocked: its lock screen goes, and only then the bank's comes up, under the cover.
        lockScreenClosesOnto(chat, chat, unlocked = true)
        step()
        starts = started()
        assertTrue(starts.toString(), lockScreensFor(starts, bank) > 0)
        assertTrue(starts.toString(), starts.none { it.component?.packageName == bank })
        assertTrue(shield().isShowing)
        assertTrue(LockStateTracker.isAppUnlocked(chat, LockTimeoutMode.IMMEDIATE))
    }

    /**
     * The bank, left in picture-in-picture over the chat app, is covered; its lock screen is
     * cancelled (Home), then the owner taps Unlock on the cover still held over Home and unlocks
     * it there, the window still floating.
     */
    private fun unlockBankFromTheHeldCoverOverHome() {
        bankLeftInPictureInPictureOver(chat)
        repeat(6) { step() }
        assertEquals(pipCover, covered(bank).last())
        lockScreenOpensOver(chat, bank)
        lockScreenClosesOnto(launcher, bank, unlocked = false)
        assertTrue(shield().isShowing)
        lockScreenOpensOver(launcher, bank)
        lockScreenClosesOnto(launcher, bank, unlocked = true)
    }

    @Test
    fun anAppUnlockedWhileItsWindowStaysInPictureInPictureStaysUnlocked() {
        unlockBankFromTheHeldCoverOverHome()
        val covers = covered(bank).size
        step(LockStateTracker.ARRIVAL_GRACE_MS + 5_000)
        repeat(8) { step() }
        assertTrue(LockStateTracker.isAppUnlocked(bank, LockTimeoutMode.IMMEDIATE))
        assertFalse(shield().isShowing)
        // Expanding the window asks for nothing.
        event(ACTIVITY_RESUMED, bank)
        repeat(3) { step() }
        assertEquals(covers, covered(bank).size)
        assertTrue(LockStateTracker.isAppUnlocked(bank, LockTimeoutMode.IMMEDIATE))
    }

    @Test
    fun anAppUnlockedInPictureInPictureLocksAgainOnceItsWindowCloses() {
        unlockBankFromTheHeldCoverOverHome()
        step()
        assertTrue(LockStateTracker.isAppUnlocked(bank, LockTimeoutMode.IMMEDIATE))
        event(ACTIVITY_STOPPED, bank)
        repeat(2) { step() }
        assertFalse(LockStateTracker.isAppUnlocked(bank, LockTimeoutMode.IMMEDIATE))
    }

    @Test
    fun aHeldCoverSaysWhatThePromptUnderItSaid() {
        bankLeftInPictureInPictureOver(chat)
        repeat(6) { step() }
        lockScreenOpensOver(chat, bank)
        assertTrue(shield().isShowing)
        // The prompt ends in a lockout: the held cover, all the owner sees, says so, and keeps
        // saying it when something tries to take it down.
        InstantShieldOverlay.onPrompt(bank, showing = true)
        InstantShieldOverlay.onPrompt(bank, showing = false, message = context.getString(com.android.BioKey.R.string.prompt_lockout_permanent), permanentLockout = true)
        assertTrue(coverTexts().toString(), context.getString(com.android.BioKey.R.string.prompt_lockout_permanent) in coverTexts())
        shield().hide()
        assertTrue(shield().isShowing)
        assertTrue(coverTexts().toString(), context.getString(com.android.BioKey.R.string.prompt_lockout_permanent) in coverTexts())
        // The next prompt clears it.
        InstantShieldOverlay.onPrompt(bank, showing = true)
        assertFalse(coverTexts().toString(), context.getString(com.android.BioKey.R.string.prompt_lockout_permanent) in coverTexts())
    }

    @Test
    fun aLockedAppInPictureInPictureIsCoveredWhenProtectionResumes() {
        vault.pauseProtection(15 * 60_000L)
        step()
        // Paused: the bank goes into picture-in-picture as the owner goes Home, a while ago (further
        // back than the first look reaches)...
        event(ACTIVITY_RESUMED, bank)
        event(ACTIVITY_PAUSED, bank)
        shadowOf(context.getSystemService(UsageStatsManager::class.java)).simulateTimeChange(-60_000)
        step(70_000)
        // ...and protection resumes while the owner opens something else.
        vault.resumeProtection()
        event(ACTIVITY_RESUMED, chat)
        repeat(8) { step() }
        assertEquals(pipCover, covered(bank).last())
        assertTrue(shield().isShowing)
    }

    /** The screen came on, as usage access logs it. */
    private fun screenCameOn() {
        shadowOf(context.getSystemService(UsageStatsManager::class.java)).addEvent(
            ShadowUsageStatsManager.EventBuilder.buildEvent()
                .setPackage("android").setEventType(UsageEvents.Event.SCREEN_INTERACTIVE).setTimeStamp(System.currentTimeMillis())
                .build()
        )
        Thread.sleep(2)
    }

    /**
     * Protection is paused while the bank is paused and its process then dies without a word,
     * then resumes as the owner opens the chat app; the screen comes on in between, a while ago
     * ([recentScreenOn] false) or just now.
     */
    private fun resumeAfterADeadPause(recentScreenOn: Boolean) {
        vault.pauseProtection(15 * 60_000L)
        step()
        event(ACTIVITY_RESUMED, bank)
        event(ACTIVITY_PAUSED, bank)
        if (!recentScreenOn) screenCameOn()
        shadowOf(context.getSystemService(UsageStatsManager::class.java)).simulateTimeChange(-60_000)
        step(70_000)
        if (recentScreenOn) screenCameOn()
        vault.resumeProtection()
        event(ACTIVITY_RESUMED, chat)
        repeat(8) { step() }
    }

    @Test
    fun aPauseFromBeforeTheScreenLastCameOnIsNoWindowStillShowing() {
        // The screen going off and on would have stopped any window still there, and paused it again.
        resumeAfterADeadPause(recentScreenOn = false)
        assertTrue(covered(bank).isEmpty())
        assertFalse(shield().isShowing)
    }

    @Test
    fun aPauseFromBeforeARecentScreenOnIsNoWindowStillShowingEither() {
        resumeAfterADeadPause(recentScreenOn = true)
        assertTrue(covered(bank).isEmpty())
        assertFalse(shield().isShowing)
    }

    @Test
    fun aLockedAppInPictureInPictureIsCoveredWhenTheEngineStarts() {
        // Compatibility mode isn't running (the accessibility engine had the phone, say) while
        // the bank goes into picture-in-picture, a while ago.
        engine.destroy()
        event(ACTIVITY_RESUMED, bank)
        event(ACTIVITY_PAUSED, bank)
        shadowOf(context.getSystemService(UsageStatsManager::class.java)).simulateTimeChange(-60_000)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(70_000))
        event(ACTIVITY_RESUMED, chat)
        // It takes over (or restarts) while the owner is in another app.
        startEngine()
        repeat(8) { step() }
        assertEquals(pipCover, covered(bank).last())
        assertTrue(shield().isShowing)
    }

    @Test
    fun aClockChangeKeepsTheCoverHeldOverAWindowInPictureInPicture() {
        bankLeftInPictureInPictureOver(chat)
        repeat(6) { step() }
        lockScreenOpensOver(chat, bank)
        assertTrue(shield().isShowing)
        // A while later (nothing left to read again), the clock is set.
        shadowOf(context.getSystemService(UsageStatsManager::class.java)).simulateTimeChange(-60_000)
        step()
        context.sendBroadcast(Intent(Intent.ACTION_TIME_CHANGED))
        repeat(3) { step() }
        assertTrue(shield().isShowing)
        assertTrue(isHeldOpen(bank))
    }

    @Test
    fun aLockedPlayStoreIsCoveredWhenALockScreenClosesOntoIt() {
        val play = "com.android.vending"
        vault.setAppLock(play, true)
        event(ACTIVITY_RESUMED, bank)
        step()
        lockScreenOpensOver(bank, bank)
        // Unlocked from a hidden notification's stand-in, whose link opens the bank's Play listing.
        lockScreenClosesOnto(play, bank, unlocked = true, frontClass = "com.google.android.finsky.activities.MainActivity")
        assertEquals(listOf("Covered $play (opened)"), covered(play))
    }

    private val besideCover = "Covered $bank (on screen beside another app)"

    @Test
    fun aLockedAppBesideAnotherIsCoveredWhenProtectionResumes() {
        vault.pauseProtection(15 * 60_000L)
        step()
        // Paused: the bank opens beside the chat app in split screen, a while ago, and stays
        // resumed there, saying nothing more...
        event(ACTIVITY_RESUMED, bank)
        shadowOf(context.getSystemService(UsageStatsManager::class.java)).simulateTimeChange(-60_000)
        step(70_000)
        // ...while the owner goes on opening screens of the chat app beside it.
        vault.resumeProtection()
        event(ACTIVITY_RESUMED, chat)
        repeat(8) { step() }
        assertEquals(listOf(besideCover), covered(bank))
        assertTrue(shield().isShowing)
    }

    @Test
    fun aLockedAppBesideAnotherIsCoveredWhenTheEngineStarts() {
        // Compatibility mode isn't running (the accessibility engine had the phone, say) while
        // the bank opens beside the chat app, a while ago.
        engine.destroy()
        event(ACTIVITY_RESUMED, bank)
        shadowOf(context.getSystemService(UsageStatsManager::class.java)).simulateTimeChange(-60_000)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(70_000))
        event(ACTIVITY_RESUMED, chat)
        startEngine()
        repeat(8) { step() }
        assertEquals(listOf(besideCover), covered(bank))
        assertTrue(shield().isShowing)
    }

    @Test
    fun aLockedAppThatLeftBeforeProtectionResumedIsNotCovered() {
        vault.pauseProtection(15 * 60_000L)
        step()
        event(ACTIVITY_RESUMED, bank)
        event(ACTIVITY_PAUSED, bank)
        event(ACTIVITY_STOPPED, bank)
        shadowOf(context.getSystemService(UsageStatsManager::class.java)).simulateTimeChange(-60_000)
        step(70_000)
        vault.resumeProtection()
        event(ACTIVITY_RESUMED, chat)
        repeat(8) { step() }
        assertTrue(covered(bank).isEmpty())
        assertFalse(shield().isShowing)
    }

    @Test
    @Config(sdk = [35])
    fun aLookBackAsksOnlyForLockedAppsAndTheScreenComingOn() {
        vault.setAppLock(PasswordManager.VIRTUAL_PACKAGE, true)
        engine.destroy()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(70_000))
        event(ACTIVITY_RESUMED, chat)
        PackageFilteringUsageStats.packageQueries.clear()
        startEngine()
        // Google Password Manager's screens are Play services' own; the screen coming on is the system's.
        val expected = PasswordManager.expand(setOf(bank, PasswordManager.VIRTUAL_PACKAGE)) + PasswordManager.GMS + "android"
        assertEquals(listOf(expected), PackageFilteringUsageStats.packageQueries.filter { it.isNotEmpty() })
    }

    @Test
    fun anAppUnlockedAsItGoesIntoPictureInPictureOverItsLockScreenStaysUnlocked() {
        installWithLaunchScreen(bank, "Bank")
        event(ACTIVITY_RESUMED, bank)
        step()
        // Its lock screen comes up, and the bank (a video playing) goes into picture-in-picture
        // over it: paused, never stopped.
        lockScreenUp(bank)
        event(ACTIVITY_PAUSED, bank)
        event(ACTIVITY_RESUMED, context.packageName, lockScreen)
        step()
        InstantShieldOverlay.dismiss(bank)
        // Face unlock, at once: the lock screen closes onto Home, the window floating on.
        lockScreenClosesOnto(launcher, bank, unlocked = true)
        started()
        step(LockStateTracker.ARRIVAL_GRACE_MS + 1_000)
        repeat(8) { step() }
        assertTrue(LockStateTracker.isAppUnlocked(bank, LockTimeoutMode.IMMEDIATE))
        assertFalse(shield().isShowing)
        assertEquals(emptyList<Intent>(), started())
        // The window closes: the bank has left.
        event(ACTIVITY_STOPPED, bank)
        repeat(2) { step() }
        assertFalse(LockStateTracker.isAppUnlocked(bank, LockTimeoutMode.IMMEDIATE))
    }

    @Test
    fun aClockChangeKeepsAWindowInPictureInPictureWaitingToBeCovered() {
        // As in bankLeftInPictureInPictureOver, with all but the window coming back long enough
        // ago that no poll reads it again.
        unlockBank()
        event(ACTIVITY_PAUSED, bank)
        event(ACTIVITY_RESUMED, chat)
        repeat(3) { step() }
        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        step()
        event(ACTIVITY_STOPPED, bank)
        event(ACTIVITY_STOPPED, chat)
        shadowOf(context.getSystemService(UsageStatsManager::class.java)).simulateTimeChange(-60_000)
        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_ON))
        step()
        event(ACTIVITY_RESUMED, chat)
        event(ACTIVITY_PAUSED, bank)
        step()
        // Within a second of the window coming back, the clock is set back a little: what was
        // just read is read again, on the new clock.
        shadowOf(context.getSystemService(UsageStatsManager::class.java)).simulateTimeChange(-500)
        context.sendBroadcast(Intent(Intent.ACTION_TIME_CHANGED))
        repeat(8) { step() }
        assertEquals(pipCover, covered(bank).last())
        assertTrue(shield().isShowing)
        assertTrue(isHeldOpen(bank))
    }

    @Test
    fun aWindowInPictureInPictureAskedForOverASignInTabLeavesTheHopAlone() {
        installWithLaunchScreen(bank, "Bank")
        val tab = "com.android.chrome"
        val tabClass = "org.chromium.chrome.browser.customtabs.CustomTabActivity"
        bankLeftInPictureInPictureOver(chat, frontLocked = true)
        // The chat app is asked for first; the bank's window, covered, waits for that prompt.
        lockScreenOpensOver(chat, chat)
        repeat(6) { step() }
        assertEquals(pipCover, covered(bank).last())
        started()
        // Unlocked, the chat app's lock screen closes onto the sign-in tab it had open over itself.
        lockScreenClosesOnto(tab, chat, unlocked = true, frontClass = tabClass)
        step()
        // Only the bank's lock screen comes up: opening the bank there would end the chat app's hop.
        val starts = started()
        assertTrue(starts.toString(), lockScreensFor(starts, bank) > 0)
        assertTrue(starts.toString(), starts.none { it.component?.packageName == bank })
        lockScreenOpensOver(tab, bank, frontClass = tabClass)
        lockScreenClosesOnto(tab, bank, unlocked = true, frontClass = tabClass)
        assertTrue(LockStateTracker.isAppUnlocked(chat, LockTimeoutMode.IMMEDIATE))
        // Signed in, back in the chat app: nothing asks again.
        event(ACTIVITY_PAUSED, tab, tabClass)
        event(ACTIVITY_RESUMED, chat)
        step()
        assertTrue(LockStateTracker.isAppUnlocked(chat, LockTimeoutMode.IMMEDIATE))
        assertEquals(listOf("Covered $chat (opened)"), covered(chat))
    }

    /** BioKey's screen stops, as MainActivity.onStop decides: only a trip BioKey started itself keeps its session. */
    private fun bioKeysScreenStops() {
        if (LockStateTracker.isOwnTripActive()) LockStateTracker.onOwnAppStopped() else LockStateTracker.lockOwnApp()
    }

    /**
     * The owner is in BioKey while the bank floats in picture-in-picture; the screen goes off and
     * on, and BioKey's prompt comes up. The bank's window is covered, its lock screen held back
     * until that prompt is done; BioKey is then unlocked. Returns what was started since.
     */
    private fun bankAskedForOverBioKey(): List<Intent> {
        installWithLaunchScreen(bank, "Bank")
        unlockBank()
        LockStateTracker.onOwnAppUnlocked()
        event(ACTIVITY_PAUSED, bank)
        event(ACTIVITY_RESUMED, context.packageName, bioKeysScreen)
        repeat(3) { step() }
        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        step()
        event(ACTIVITY_STOPPED, bank)
        event(ACTIVITY_STOPPED, context.packageName, bioKeysScreen)
        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_ON))
        step()
        assertFalse(LockStateTracker.isOwnAppUnlocked())
        // BioKey is back, asking for itself; the bank's window floats over it.
        promptUpFor(context.packageName)
        event(ACTIVITY_RESUMED, context.packageName, bioKeysScreen)
        event(ACTIVITY_PAUSED, bank)
        repeat(8) { step() }
        assertEquals(pipCover, covered(bank).last())
        started()
        promptUpFor(null)
        LockStateTracker.onOwnAppUnlocked()
        step()
        return started()
    }

    @Test
    fun aLockScreenBroughtUpOverBioKeyLeavesBioKeyUnlocked() {
        val starts = bankAskedForOverBioKey()
        // Only the bank's lock screen comes up, over BioKey: a trip BioKey started itself.
        assertTrue(starts.toString(), lockScreensFor(starts, bank) > 0)
        assertTrue(starts.toString(), starts.none { it.component?.packageName == bank })
        lockScreenOpensOver(context.packageName, bank, frontClass = bioKeysScreen)
        bioKeysScreenStops()
        lockScreenClosesOnto(context.packageName, bank, unlocked = true, frontClass = bioKeysScreen)
        // Back in BioKey (MainActivity.onResume): still unlocked, as the owner never left it.
        assertTrue(LockStateTracker.isOwnAppUnlocked())
        LockStateTracker.onOwnAppResumed()
        assertTrue(LockStateTracker.hasActiveOwnerSession())
        assertFalse(LockStateTracker.isOwnTripActive())
        assertTrue(LockStateTracker.isAppUnlocked(bank, LockTimeoutMode.IMMEDIATE))
    }

    @Test
    fun cancellingALockScreenBroughtUpOverBioKeyEndsBioKeysSession() {
        bankAskedForOverBioKey()
        lockScreenOpensOver(context.packageName, bank, frontClass = bioKeysScreen)
        bioKeysScreenStops()
        assertTrue(LockStateTracker.isOwnAppUnlocked())
        // Cancel: Home, and the phone may be in someone else's hands. BioKey asks again.
        lockScreenClosesOnto(launcher, bank, unlocked = false)
        assertFalse(LockStateTracker.isOwnAppUnlocked())
    }

    /**
     * The bank opens on a launch screen, its lock screen comes up over it, then the bank opens its
     * main screen over that lock screen, which goes to the background and gives up.
     */
    private fun bankOpensItsMainScreenOverItsLockScreen() {
        lockScreenOpensOver(bank, bank, frontClass = "$bank.StartActivity")
        started()
        LockStateTracker.markLockDisplaced(bank)
        lockScreenUp(null)
        event(ACTIVITY_PAUSED, context.packageName, lockScreen)
        event(ACTIVITY_RESUMED, bank)
        step()
    }

    @Test
    fun aLockedAppThatOpensItsMainScreenOverItsLockScreenIsCoveredAgainNotSentHome() {
        event(ACTIVITY_RESUMED, bank, "$bank.StartActivity")
        step()
        bankOpensItsMainScreenOverItsLockScreen()
        val starts = started()
        assertTrue(starts.toString(), starts.none { it.hasCategory(Intent.CATEGORY_HOME) })
        assertTrue(starts.toString(), lockScreensFor(starts, bank) > 0)
        assertEquals("Covered $bank (came back over its lock screen)", covered(bank).last())
        assertTrue(shield().isShowing)
        assertEquals(bank, shield().targetPackage)
    }

    @Test
    fun aLockedAppThatKeepsComingBackOverItsLockScreenGoesHome() {
        event(ACTIVITY_RESUMED, bank, "$bank.StartActivity")
        step()
        repeat(2) {
            bankOpensItsMainScreenOverItsLockScreen()
            assertTrue(started().none { it.hasCategory(Intent.CATEGORY_HOME) })
        }
        bankOpensItsMainScreenOverItsLockScreen()
        assertTrue(started().any { it.hasCategory(Intent.CATEGORY_HOME) })
    }

    @Test
    fun gantletAPermissionDialogOverTheLockScreenThenTheAppIsCoveredAgainNotSentHome() {
        event(ACTIVITY_RESUMED, bank)
        step()
        lockScreenOpensOver(bank, bank)
        started()
        // The bank asks for a permission as it starts: the dialog comes over its lock screen,
        // which gives up; the bank is back in front once it is answered.
        val permissions = "com.google.android.permissioncontroller"
        LockStateTracker.markLockDisplaced(bank)
        lockScreenUp(null)
        event(ACTIVITY_PAUSED, context.packageName, lockScreen)
        event(ACTIVITY_RESUMED, permissions, "com.android.permissioncontroller.permission.ui.GrantPermissionsActivity")
        step()
        event(ACTIVITY_PAUSED, permissions, "com.android.permissioncontroller.permission.ui.GrantPermissionsActivity")
        event(ACTIVITY_RESUMED, bank)
        step()
        val starts = started()
        assertTrue(starts.toString(), starts.none { it.hasCategory(Intent.CATEGORY_HOME) })
        assertTrue(starts.toString(), lockScreensFor(starts, bank) > 0)
        assertTrue(shield().isShowing)
    }

    @Test
    fun gantletFromEveryHomeScreenALockedAppIsCoveredOnceAndCancellingCoversNothing() {
        val homes = listOf(
            "com.sec.android.app.launcher" to "com.sec.android.app.launcher.activities.LauncherActivity",
            "com.teslacoilsw.launcher" to "com.teslacoilsw.launcher.NovaLauncher",
            launcher to "$launcher.NexusLauncherActivity"
        )
        val pm = shadowOf(context.packageManager)
        for ((pkg, cls) in homes) {
            val home = ComponentName(pkg, cls)
            pm.addActivityIfNotPresent(home)
            pm.addIntentFilterForActivity(home, IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addCategory(Intent.CATEGORY_DEFAULT)
            })
        }
        ForegroundRules.resetSystemLists()
        for ((home, cls) in homes) {
            DiagnosticLog.clear()
            event(ACTIVITY_RESUMED, home, cls)
            step()
            event(ACTIVITY_PAUSED, home, cls)
            event(ACTIVITY_RESUMED, bank)
            step()
            assertEquals(home, listOf("Covered $bank (opened)"), covered(bank))
            lockScreenOpensOver(bank, bank)
            started()
            // Cancel: the lock screen goes Home itself, and the home screen comes to the front.
            lockScreenClosesOnto(home, bank, unlocked = false, frontClass = cls)
            step(LockStateTracker.ABANDONED_LOCK_COOLDOWN_MS + 100)
            repeat(3) { step() }
            assertEquals(home, 1, covered(bank).size)
            assertFalse(home, shield().isShowing)
            assertTrue(home, started().none { it.hasCategory(Intent.CATEGORY_HOME) })
            event(ACTIVITY_STOPPED, bank)
            step(2_000)
        }
    }

    @Test
    fun gantletFoldingOrUnfoldingAnUnlockedAppKeepsItUnlockedAndUncovered() {
        event(ACTIVITY_RESUMED, bank)
        step()
        lockScreenOpensOver(bank, bank)
        lockScreenClosesOnto(bank, bank, unlocked = true)
        DiagnosticLog.clear()
        started()
        // Folded or unfolded, the bank is laid out again on the other screen: its activity is
        // paused, stopped and resumed again (recreated), with nothing else in between.
        repeat(3) {
            event(ACTIVITY_PAUSED, bank)
            event(ACTIVITY_STOPPED, bank)
            event(ACTIVITY_RESUMED, bank)
            step()
        }
        repeat(5) { step() }
        assertTrue(LockStateTracker.isAppUnlocked(bank, LockTimeoutMode.IMMEDIATE))
        assertEquals(emptyList<String>(), covered(bank))
        assertTrue(started().none { it.hasCategory(Intent.CATEGORY_HOME) })
    }

    @Test
    fun gantletFoldingOrUnfoldingUnderTheLockScreenNeverGoesHome() {
        event(ACTIVITY_RESUMED, bank)
        step()
        lockScreenOpensOver(bank, bank)
        started()
        // The lock screen handles the change itself; the bank under it is laid out again, stopped.
        repeat(3) {
            event(ACTIVITY_PAUSED, context.packageName, lockScreen)
            event(ACTIVITY_RESUMED, context.packageName, lockScreen)
            event(ACTIVITY_STOPPED, bank)
            step()
        }
        repeat(5) { step() }
        assertEquals(listOf("Covered $bank (opened)"), covered(bank))
        assertTrue(started().none { it.hasCategory(Intent.CATEGORY_HOME) })
        assertFalse(LockStateTracker.isAppUnlocked(bank, LockTimeoutMode.IMMEDIATE))
    }

    @Test
    fun aLockedAppOpenedAgainFromHomeAtOnceIsCoveredEveryTimeNeverSentHome() {
        // As in the report: the owner opens it, goes Home from its lock screen and opens it again
        // straight away, over and over. Compatibility mode sees no tap on the icon.
        event(ACTIVITY_RESUMED, bank)
        step()
        repeat(5) { attempt ->
            lockScreenOpensOver(bank, bank)
            started()
            LockStateTracker.markLockDisplaced(bank) // Home: the lock screen goes to the background.
            lockScreenUp(null)
            event(ACTIVITY_PAUSED, context.packageName, lockScreen)
            event(ACTIVITY_RESUMED, launcher, "$launcher.NexusLauncherActivity")
            step()
            event(ACTIVITY_PAUSED, launcher, "$launcher.NexusLauncherActivity")
            event(ACTIVITY_RESUMED, bank)
            step()
            val starts = started()
            assertTrue("attempt ${attempt + 1}: $starts", starts.none { it.hasCategory(Intent.CATEGORY_HOME) })
            assertTrue("attempt ${attempt + 1}: $starts", lockScreensFor(starts, bank) > 0)
            assertTrue(shield().isShowing)
        }
    }

    @Test
    fun aLockedAppBackOverALockScreenLeftOnPurposeGoesHome() {
        event(ACTIVITY_RESUMED, bank)
        step()
        lockScreenOpensOver(bank, bank)
        started()
        // Cancel, then a quick switch straight back to the bank.
        lockScreenClosesOnto(bank, bank, unlocked = false)
        assertTrue(started().any { it.hasCategory(Intent.CATEGORY_HOME) })
        assertEquals(listOf("Covered $bank (opened)"), covered(bank))
    }

    @Test
    fun aLockedAppBackBesideAnotherDuringItsCooldownIsCoveredOnceItEnds() {
        event(ACTIVITY_RESUMED, bank)
        step()
        // Its lock screen can't have the whole screen (split screen): it goes Home, given up.
        lockScreenOpensOver(bank, bank)
        lockScreenClosesOnto(launcher, bank, unlocked = false)
        // At once, a quick switch back to the pair, the chat app's resume the newer: nothing is
        // covered during the cooldown...
        event(ACTIVITY_PAUSED, launcher)
        event(ACTIVITY_RESUMED, bank)
        event(ACTIVITY_RESUMED, chat)
        step()
        assertEquals(listOf("Covered $bank (opened)"), covered(bank))
        // ...but the bank, still on screen beside it, is once it ends.
        step(LockStateTracker.ABANDONED_LOCK_COOLDOWN_MS)
        repeat(3) { step() }
        assertEquals("Covered $bank (still on screen, uncovered)", covered(bank).last())
        assertTrue(shield().isShowing)
    }

    private val notes = "com.example.notes"

    /** BioKey's lock screen for [pkg] comes up over a split screen of [halves] (package to activity): both pause, then stop under it. */
    private fun lockScreenOpensOverPair(pkg: String, vararg halves: Pair<String, String>) {
        lockScreenUp(pkg)
        for ((app, cls) in halves) event(ACTIVITY_PAUSED, app, cls)
        event(ACTIVITY_RESUMED, context.packageName, lockScreen)
        for ((app, cls) in halves) event(ACTIVITY_STOPPED, app, cls)
        step()
        InstantShieldOverlay.dismiss(pkg)
    }

    /** The lock screen for [pkg], unlocked, closes onto the split screen of [halves], the last one's resume the newest. */
    private fun lockScreenClosesOntoPair(pkg: String, vararg halves: Pair<String, String>) {
        LockStateTracker.onAppUnlocked(pkg)
        InstantShieldOverlay.dismiss(pkg)
        lockScreenUp(null)
        event(ACTIVITY_PAUSED, context.packageName, lockScreen)
        for ((app, cls) in halves) event(ACTIVITY_RESUMED, app, cls)
        step()
    }

    private fun half(pkg: String) = pkg to "$pkg.MainActivity"

    @Test
    fun aWindowInPictureInPictureAskedForBesideAnUnlockedAppLeavesThatAppUnlocked() {
        installWithLaunchScreen(bank, "Bank")
        // The bank floats in picture-in-picture over the notes app and the chat app (locked too),
        // side by side in split screen, and the phone locks.
        unlockBank()
        event(ACTIVITY_PAUSED, bank)
        event(ACTIVITY_RESUMED, chat)
        event(ACTIVITY_RESUMED, notes)
        repeat(3) { step() }
        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        step()
        vault.setAppLock(chat, true)
        event(ACTIVITY_STOPPED, bank)
        event(ACTIVITY_STOPPED, chat)
        event(ACTIVITY_STOPPED, notes)
        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_ON))
        step()
        // Unlocked: the pair is back, the notes app's resume the newer, the bank's window paused.
        event(ACTIVITY_RESUMED, chat)
        event(ACTIVITY_RESUMED, notes)
        event(ACTIVITY_PAUSED, bank)
        step()
        assertEquals(listOf("Covered $chat (on screen beside another app)"), covered(chat))
        // The chat app is asked for first; the bank's window, covered, waits for that prompt.
        lockScreenOpensOverPair(chat, half(chat), half(notes))
        repeat(6) { step() }
        assertEquals(pipCover, covered(bank).last())
        started()
        lockScreenClosesOntoPair(chat, half(chat), half(notes))
        // Only the bank's lock screen comes up: opening the bank would push the chat app, unlocked
        // beside the notes app, off screen, and it would ask again although it was never left.
        val starts = started()
        assertTrue(starts.toString(), lockScreensFor(starts, bank) > 0)
        assertTrue(starts.toString(), starts.none { it.component?.packageName == bank })
        lockScreenOpensOverPair(bank, half(chat), half(notes))
        assertTrue(LockStateTracker.isAppUnlocked(chat, LockTimeoutMode.IMMEDIATE))
        lockScreenClosesOntoPair(bank, half(chat), half(notes))
        assertTrue(LockStateTracker.isAppUnlocked(chat, LockTimeoutMode.IMMEDIATE))
        assertFalse(shield().isShowing)
        step(LockStateTracker.ARRIVAL_GRACE_MS + 1_000)
        assertTrue(LockStateTracker.isAppUnlocked(chat, LockTimeoutMode.IMMEDIATE))
        assertTrue(LockStateTracker.isAppUnlocked(bank, LockTimeoutMode.IMMEDIATE))
        assertEquals(1, covered(chat).size)
    }

    /** BioKey's screen comes back, as MainActivity.onResume decides. */
    private fun bioKeysScreenResumes() {
        LockStateTracker.isOwnLockScreenHopActive()
        if (LockStateTracker.isOwnAppUnlocked()) LockStateTracker.onOwnAppResumed()
    }

    @Test
    fun aLockScreenBroughtUpOverASplitScreenLeavesBioKeyBesideItUnlocked() {
        installWithLaunchScreen(bank, "Bank")
        val bioKey = context.packageName to bioKeysScreen
        // The owner has BioKey beside the notes app while the bank floats in picture-in-picture;
        // the screen goes off and on, and BioKey's prompt comes up.
        unlockBank()
        LockStateTracker.onOwnAppUnlocked()
        event(ACTIVITY_PAUSED, bank)
        event(ACTIVITY_RESUMED, context.packageName, bioKeysScreen)
        event(ACTIVITY_RESUMED, notes)
        repeat(3) { step() }
        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        step()
        event(ACTIVITY_STOPPED, bank)
        event(ACTIVITY_STOPPED, context.packageName, bioKeysScreen)
        event(ACTIVITY_STOPPED, notes)
        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_ON))
        step()
        promptUpFor(context.packageName)
        event(ACTIVITY_RESUMED, context.packageName, bioKeysScreen)
        event(ACTIVITY_RESUMED, notes)
        event(ACTIVITY_PAUSED, bank)
        repeat(8) { step() }
        assertEquals(pipCover, covered(bank).last())
        started()
        promptUpFor(null)
        LockStateTracker.onOwnAppUnlocked()
        step()
        // Only the bank's lock screen comes up, over the pair: a trip BioKey started itself.
        val starts = started()
        assertTrue(starts.toString(), lockScreensFor(starts, bank) > 0)
        assertTrue(starts.toString(), starts.none { it.component?.packageName == bank })
        lockScreenOpensOverPair(bank, bioKey, half(notes))
        bioKeysScreenStops()
        assertTrue(LockStateTracker.isOwnAppUnlocked())
        // Unlocked: the pair is back, the notes app's resume the newer, read before BioKey's
        // screen has said it is back.
        lockScreenClosesOntoPair(bank, bioKey, half(notes))
        assertTrue(LockStateTracker.isOwnAppUnlocked())
        bioKeysScreenResumes()
        assertTrue(LockStateTracker.hasActiveOwnerSession())
        assertFalse(LockStateTracker.isOwnTripActive())
        assertTrue(LockStateTracker.isAppUnlocked(bank, LockTimeoutMode.IMMEDIATE))
    }

    @Test
    fun anAppComingToTheFrontOverBioKeyStartsNoTripOfBioKeys() {
        LockStateTracker.onOwnAppUnlocked()
        event(ACTIVITY_RESUMED, context.packageName, bioKeysScreen)
        step()
        // A locked app's notification is tapped: it opens over BioKey, and is covered there.
        event(ACTIVITY_PAUSED, context.packageName, bioKeysScreen)
        event(ACTIVITY_RESUMED, bank)
        step()
        assertEquals(listOf("Covered $bank (opened)"), covered(bank))
        assertFalse(LockStateTracker.isOwnTripActive())
        bioKeysScreenStops()
        assertFalse(LockStateTracker.isOwnAppUnlocked())
    }
}
