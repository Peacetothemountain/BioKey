package com.android.BioKey.feature.main

import android.app.Application
import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.android.BioKey.R
import com.android.BioKey.core.security.ProtectionAlerts
import com.android.BioKey.core.security.ProtectionGaps
import com.android.BioKey.core.security.SecurityEngine
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.core.security.TrustedClock
import com.android.BioKey.core.security.UnlockAttempts
import com.android.BioKey.feature.notification.ShieldAccessWatch
import com.android.BioKey.ui.theme.BioKeyMessages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What home's cards go by that Android and BioKey's own records say, rather than the vault's flows:
 * read off the main thread on every visit ([HomeViewModel.refresh]).
 */
internal data class HomeState(
    /** A strong face or fingerprint is set up: the attempts question and the Lock Settings card need one. */
    val hasStrongBiometric: Boolean,
    /** One of BioKey's alert channels is silenced in Android's settings. */
    val alertsMuted: Boolean,
    /** The owner stopped the reminders that app locking is off. Read again on every visit: an engine coming back clears it. */
    val remindersStopped: Boolean,
    /** Hiding notification content is on, but its access was taken away. */
    val shieldWithoutAccess: Boolean,
    /** The Lock Settings card put away; null until read, so the card doesn't flash in and out. */
    val lockSettingsDismissed: Boolean?
)

/** What Review opens with: whether the owner is proven, and until when a face or fingerprint change is held. */
internal data class ReviewState(val ownerProven: Boolean, val heldUntil: Long?)

/**
 * Home's state from outside the vault ([HomeState]) and Review's ([ReviewState]), with the actions
 * that change them. Each starts from what this process already knows, so home's first frame shows
 * it rather than popping it in after a read.
 */
class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val app: Context get() = getApplication()

    private val _state = MutableStateFlow(
        HomeState(
            hasStrongBiometric = SecurityEngine.lastKnownEnrolledStrongBiometric() ?: false,
            alertsMuted = false,
            remindersStopped = false,
            shieldWithoutAccess = ShieldAccessWatch.lastWithoutAccess ?: false,
            lockSettingsDismissed = lastLockSettingsDismissed
        )
    )
    internal val state: StateFlow<HomeState> = _state.asStateFlow()

    /**
     * Looked at again, off the main thread: home calls this each time it's resumed, and cancels it
     * when paused.
     */
    fun refresh(): Job = viewModelScope.launch {
        launch(Dispatchers.IO) {
            val dismissed = homePrefs(app).getBoolean(KEY_LOCK_SETTINGS_DISMISSED, false).also { lastLockSettingsDismissed = it }
            _state.update { it.copy(lockSettingsDismissed = dismissed) }
            val stopped = ProtectionAlerts.remindersStopped(app)
            _state.update { it.copy(remindersStopped = stopped) }
            ShieldAccessWatch.check(app)
            val withoutAccess = ShieldAccessWatch.lastWithoutAccess ?: false
            _state.update { it.copy(shieldWithoutAccess = withoutAccess) }
        }
        launch(Dispatchers.IO) {
            val strong = UnlockAttempts.hasStrongBiometric(app)
            _state.update { it.copy(hasStrongBiometric = strong) }
            val muted = UnlockAttempts.channelMuted(app)
            _state.update { it.copy(alertsMuted = muted) }
            // Past their 7 days, gone from the disk too.
            UnlockAttempts.prune(app)
            ProtectionGaps.prune(app)
        }
    }

    /** "Remind me": only strengthens, so no authentication. */
    fun resumeReminders() {
        ProtectionAlerts.resumeReminders(app)
        _state.update { it.copy(remindersStopped = false) }
    }

    /** "Stop reminding", once the owner has confirmed it. */
    fun stopReminders() {
        ProtectionAlerts.stopReminders(app)
        ProtectionGaps.recordIfScreenLock(app, ProtectionGaps.Change.REMINDERS_STOPPED)
        _state.update { it.copy(remindersStopped = true) }
    }

    /** Hiding switched off from its card, as the switch in Settings does: recorded from a screen-lock session, with Undo. */
    fun switchShieldOff(vault: SecurityVault) {
        switchShieldOff(app, vault, onUndo = { withoutAccess -> _state.update { it.copy(shieldWithoutAccess = withoutAccess) } })
        _state.update { it.copy(shieldWithoutAccess = false) }
    }

    /** The Lock Settings card's "Not now": put away for good. */
    fun dismissLockSettings() {
        _state.update { it.copy(lockSettingsDismissed = true) }
        lastLockSettingsDismissed = true
        viewModelScope.launch(Dispatchers.IO) { homePrefs(app).edit { putBoolean(KEY_LOCK_SETTINGS_DISMISSED, true) } }
    }

    // ---- Review ------------------------------------------------------------------------------

    private val _review = MutableStateFlow<ReviewState?>(null)
    /** Review's state, worked out (off the main thread) before it opens, so it opens as it stays. */
    internal val review: StateFlow<ReviewState?> = _review.asStateFlow()

    /** Review being worked out: a second tap meanwhile does nothing. */
    private var opening: Job? = null

    private suspend fun lookAtReview() = withContext(Dispatchers.Default) {
        ReviewState(ownerProven = SecurityEngine.ownerProven(app), heldUntil = UnlockAttempts.heldUntil(app))
    }

    /** Works out Review's state, then [onOpen] (home shows it). */
    fun openReview(onOpen: () -> Unit) {
        if (opening != null) return
        opening = viewModelScope.launch {
            try {
                _review.value = lookAtReview()
                onOpen()
            } finally {
                opening = null
            }
        }
    }

    /** Review open without its state (back after a rotation, say): worked out again. */
    fun restoreReview(onOpen: () -> Unit) {
        if (_review.value == null) openReview(onOpen)
    }

    /** Review closed. */
    fun closeReview() {
        _review.value = null
    }

    /**
     * Home left the screen: Review's state goes with it (and any opening under way), so Review
     * reopened afterwards (after a rotation, say) is worked out again.
     */
    fun forgetReview() {
        opening?.cancel()
        opening = null
        _review.value = null
    }

    /**
     * Review shows everything up to [shownUpTo]; it opened showing up to [openedAt]. Something new
     * while it's open: worked out again, so the note and buttons stay true (just worked out on
     * opening, otherwise). Then, if the owner is proven, what it shows counts as seen.
     */
    suspend fun reviewShowing(shownUpTo: Long, openedAt: Long) {
        val current = _review.value ?: return
        val now = if (shownUpTo == openedAt) current else lookAtReview()
        if (now != current) _review.value = now
        if (now.ownerProven) withContext(Dispatchers.Default) { UnlockAttempts.markSeen(app, shownUpTo) }
    }

    /** The hold ([heldUntil]) ends while Review is open: worked out again then. */
    suspend fun recheckWhenHoldEnds(heldUntil: Long?) {
        val until = heldUntil ?: return
        delay((until - TrustedClock.now()).coerceAtLeast(0L) + 1_000L)
        _review.value = lookAtReview()
    }

    /** Mark reviewed: on the application's scope, so it isn't stopped halfway by the screen going. */
    fun markReviewed(shownUpTo: Long) {
        val app = app
        appScope.launch(Dispatchers.Default) { UnlockAttempts.markSeen(app, shownUpTo) }
    }

    /** Clear what Review showed, with Undo. The application's context: this, and its Undo, outlive the screen. */
    fun clearReviewed(shownUpTo: Long) {
        val app = app
        appScope.launch {
            val (oldAttempts, oldGaps) = withContext(Dispatchers.Default) {
                UnlockAttempts.markSeen(app, shownUpTo)
                UnlockAttempts.clear(app, shownUpTo) to ProtectionGaps.clear(app, shownUpTo)
            }
            // Said only when something went.
            if (oldAttempts.isNotEmpty() || oldGaps.isNotEmpty()) {
                BioKeyMessages.show(app.getString(R.string.home_cleared), Icons.Default.Delete, actionLabel = app.getString(R.string.common_undo)) {
                    UnlockAttempts.restore(app, oldAttempts)
                    ProtectionGaps.restore(app, oldGaps)
                }
            }
        }
    }
}

/** The home screen's own small memory: cards the owner put away. */
internal fun homePrefs(context: Context) = context.getSharedPreferences("biokey_home", Context.MODE_PRIVATE)
internal const val KEY_LOCK_SETTINGS_DISMISSED = "lock_settings_dismissed"
/** [KEY_LOCK_SETTINGS_DISMISSED] as last read in this process: home's first frame, before it reads again. */
@Volatile internal var lastLockSettingsDismissed: Boolean? = null
