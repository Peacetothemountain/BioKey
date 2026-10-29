package com.android.BioKey.feature.main

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import com.android.BioKey.core.security.LockStateTracker

/**
 * The launcher shortcut "Lock all now" (long-press BioKey's icon): every locked app asks again
 * the next time it opens, BioKey included, without waiting for the screen to turn off. Handy
 * before handing the phone to someone. It only ever locks, so it needs no authentication.
 */
class LockAllActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LockStateTracker.lockAll(reason = "lock-all shortcut")
        Toast.makeText(this, "All apps locked", Toast.LENGTH_SHORT).show()
        // Theme.NoDisplay: it must finish before it would be shown.
        finish()
    }
}
