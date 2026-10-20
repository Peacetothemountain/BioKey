package com.android.BioKey

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import com.android.BioKey.core.data.PasswordManager
import com.android.BioKey.feature.lock.ForegroundRules
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.w3c.dom.Element

/**
 * What counts as a home screen or a helper app. Robolectric doesn't enforce the manifest's
 * `<queries>`, so the queries are checked against the manifest itself: one missing there would
 * only show on a phone (a photo picker no longer a helper, a second authentication after
 * attaching a photo).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ForegroundRulesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun reset() = ForegroundRules.resetSystemLists()

    private class Query(val action: String, val categories: Set<String>, val mimeTypes: Set<String>)

    private fun manifestQueries(): List<Query> {
        val ns = "http://schemas.android.com/apk/res/android"
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val queries = doc.getElementsByTagName("queries").item(0) as Element
        val intents = queries.getElementsByTagName("intent")
        return (0 until intents.length).map { i ->
            val intent = intents.item(i) as Element
            fun values(tag: String, attr: String): Set<String> {
                val nodes = intent.getElementsByTagName(tag)
                return (0 until nodes.length).map { (nodes.item(it) as Element).getAttributeNS(ns, attr) }.filter { it.isNotEmpty() }.toSet()
            }
            Query(values("action", "name").single(), values("category", "name"), values("data", "mimeType"))
        }
    }

    /** Whether a `<queries>` MIME type makes the handlers of an intent of [type] visible. */
    private fun covers(query: String, type: String) =
        query == "*/*" || query == type || (query.endsWith("/*") && type.startsWith(query.removeSuffix("*")))

    @Test
    fun everyPickerIntentIsVisibleToBioKey() {
        val queries = manifestQueries()
        for (intent in ForegroundRules.PICKER_INTENTS) {
            val type = intent.type
            val visible = queries.any { q ->
                q.action == intent.action && (if (type == null) q.mimeTypes.isEmpty() else q.mimeTypes.any { covers(it, type) })
            }
            assertTrue("<queries> lacks ${intent.action} ${type.orEmpty()}", visible)
        }
    }

    @Test
    fun launchersAndKeyboardsAreVisibleToBioKey() {
        val queries = manifestQueries()
        assertTrue(queries.any { it.action == Intent.ACTION_MAIN && Intent.CATEGORY_HOME in it.categories })
        assertTrue(queries.any { it.action == Intent.ACTION_MAIN && Intent.CATEGORY_LAUNCHER in it.categories })
        assertTrue(queries.any { it.action == "android.view.InputMethod" })
    }

    @Test
    fun aCustomTabIsAHelperButTheBrowserIsNot() {
        assertFalse(ForegroundRules.isHopHelper(context, "com.android.chrome", "org.chromium.chrome.browser.ChromeTabbedActivity"))
        assertTrue(ForegroundRules.isHopHelper(context, "com.android.chrome", "org.chromium.chrome.browser.customtabs.CustomTabActivity"))
    }

    @Test
    fun googlePasswordManagerIsAHelper() {
        // Copying a password across from it keeps the app's session.
        assertTrue(ForegroundRules.isHopHelper(context, PasswordManager.VIRTUAL_PACKAGE, null))
        assertTrue(ForegroundRules.isHopHelper(context, PasswordManager.CREDENTIAL_MANAGER, null))
    }

    /** Registers [pkg] as a home screen on this "device". */
    private fun installHome(pkg: String, system: Boolean = false) {
        val pm = shadowOf(context.packageManager)
        pm.installPackage(PackageInfo().apply {
            packageName = pkg
            applicationInfo = ApplicationInfo().apply {
                packageName = pkg
                flags = if (system) ApplicationInfo.FLAG_SYSTEM else 0
            }
        })
        val component = ComponentName(pkg, "$pkg.Home")
        pm.addActivityIfNotPresent(component)
        pm.addIntentFilterForActivity(component, IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addCategory(Intent.CATEGORY_DEFAULT)
        })
    }

    @Test
    fun settingsFallbackHomeIsNotALauncher() {
        installHome("com.android.settings", system = true)
        assertFalse(ForegroundRules.isLauncher(context, "com.android.settings") { false })
    }

    @Test
    fun aLockedAppWithAHomeScreenIsAnAppUnlessItIsTheHomeScreen() {
        installHome("com.vendor.launcher", system = true) // the phone's own, which also hosts Recents
        installHome("com.example.kidsmode")
        installHome("com.example.nova")
        // Nova is the home screen in use.
        @Suppress("DEPRECATION")
        context.packageManager.addPreferredActivity(
            IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addCategory(Intent.CATEGORY_DEFAULT)
            },
            IntentFilter.MATCH_CATEGORY_EMPTY,
            null,
            ComponentName("com.example.nova", "com.example.nova.Home")
        )
        val locked: (String) -> Boolean = { true }
        // Locking these never locks the user out of Home.
        assertTrue(ForegroundRules.isLauncher(context, "com.vendor.launcher", locked))
        assertTrue(ForegroundRules.isLauncher(context, "com.example.nova", locked))
        // Locked by the user: covered like any app.
        assertFalse(ForegroundRules.isLauncher(context, "com.example.kidsmode", locked))
        // Not locked: still a home screen (leaving to it is going Home).
        assertTrue(ForegroundRules.isLauncher(context, "com.example.kidsmode") { false })
        assertFalse(ForegroundRules.isLauncher(context, "com.example.game") { false })
    }
}
