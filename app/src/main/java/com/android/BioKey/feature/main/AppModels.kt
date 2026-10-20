package com.android.BioKey.feature.main

import android.os.UserHandle
import com.android.BioKey.core.data.AppCategory

/**
 * A launchable app as shown in BioKey's lists. Its icon is drawn on demand ([AppIconCache]), so
 * the list itself stays small.
 */
data class AppItem(
    val packageName: String,
    val label: String,
    val category: AppCategory,
    /** The profile it's installed in, when that isn't BioKey's own (a work profile, say). */
    val user: UserHandle? = null,
    /**
     * Also (or only) installed in another profile. Compatibility mode can't see apps there:
     * Android shares app-usage events only within one profile.
     */
    val inOtherProfile: Boolean = false
) {
    val isWorkProfile: Boolean get() = user != null
}
