package com.android.BioKey

import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

/**
 * Nothing of BioKey's leaves the phone through Android's backup or device-to-device transfer, and
 * the debug build's test activity can't be opened by other apps.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupAndExposureTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val allDomains = setOf(
        "root", "file", "database", "sharedpref", "external",
        "device_root", "device_file", "device_database", "device_sharedpref",
    )

    @Test
    fun backupIsOff() {
        assertEquals(0, context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
    }

    @Test
    fun cloudBackupAndDeviceTransferExcludeEveryDomain() {
        val excluded = mutableMapOf<String, MutableSet<String>>()
        var section: String? = null
        context.resources.getXml(R.xml.data_extraction_rules).use { xml ->
            while (xml.next() != XmlPullParser.END_DOCUMENT) {
                if (xml.eventType != XmlPullParser.START_TAG) continue
                when (xml.name) {
                    "cloud-backup", "device-transfer" -> section = xml.name
                    "exclude" -> excluded.getOrPut(section!!) { mutableSetOf() } += xml.getAttributeValue(null, "domain")
                    // An include would back up whatever it names, even with every domain excluded.
                    "include" -> throw AssertionError("data_extraction_rules must not include anything")
                }
            }
        }
        assertEquals(allDomains, excluded["cloud-backup"])
        assertEquals(allDomains, excluded["device-transfer"])
    }

    @Test
    fun debugTestActivityIsNotExported() {
        // ui-test-manifest declares it exported; src/debug/AndroidManifest.xml overrides that.
        // Release builds don't declare it at all.
        val info = runCatching {
            context.packageManager.getActivityInfo(ComponentName(context, "androidx.activity.ComponentActivity"), 0)
        }.getOrNull() ?: return
        assertFalse(info.exported)
    }
}
