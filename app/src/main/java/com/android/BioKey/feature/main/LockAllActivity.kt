package com.android.BioKey.feature.main

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.android.BioKey.core.security.LockAllNow

/**
 * The launcher shortcut "Lock all now" (long-press BioKey's icon): every locked app asks again
 * the next time it opens, BioKey included, without waiting for the screen to turn off. Handy
 * before handing the phone to someone. See [LockAllNow]: paused, it resumes protection; with
 * nothing protecting, it opens BioKey to show what's missing rather than claim apps are locked.
 */
class LockAllActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val outcome = LockAllNow.run(this, includeOwnApp = true, reason = "lock-all shortcut")
        Toast.makeText(this, LockAllNow.message(this, outcome), Toast.LENGTH_SHORT).show()
        if (outcome == LockAllNow.Outcome.NOT_PROTECTING) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        }
        // Theme.NoDisplay: it must finish before it would be shown.
        finish()
    }
}
