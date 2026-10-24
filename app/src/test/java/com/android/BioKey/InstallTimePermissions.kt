package com.android.BioKey

import android.app.Application
import android.app.Instrumentation
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowInstrumentation
import org.robolectric.util.reflector.Direct
import org.robolectric.util.reflector.ForType
import org.robolectric.util.reflector.Reflector.reflector

/**
 * Grants BioKey the permission a phone grants it at install, for tests on Android 12 (SDK 31).
 *
 * Before Android 13, ContextCompat.registerReceiver guards a receiver that isn't exported with a
 * permission of BioKey's own (androidx.core adds it to the manifest), and checks that BioKey holds
 * it. A phone grants it at install; Robolectric grants nothing, so BioKeyApplication.onCreate threw
 * before any test ran. This grants it just before onCreate, as installing would have. Robolectric
 * has one shadow per class, so this extends the one it has for Instrumentation. Use it with
 * `@Config(shadows = [InstallTimePermissions::class])`.
 */
@Implements(Instrumentation::class)
class InstallTimePermissions : ShadowInstrumentation() {

    @Implementation
    protected fun callApplicationOnCreate(app: Application) {
        shadowOf(app).grantPermissions("${app.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        reflector(InstrumentationReflector::class.java, realObject).callApplicationOnCreate(app)
    }

    @ForType(Instrumentation::class)
    private interface InstrumentationReflector {
        @Direct
        fun callApplicationOnCreate(app: Application)
    }
}
