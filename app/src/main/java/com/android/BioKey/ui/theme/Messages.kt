package com.android.BioKey.ui.theme

import android.app.Activity
import android.os.SystemClock
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update

/**
 * BioKey's short messages ("Chase unlocked · Undo", "Protection paused"), in place of Android
 * toasts: a Material 3 bar in the theme's colours with an icon and an optional action, shown
 * at the bottom of whichever BioKey screen is open ([BioKeyMessageHost], placed by
 * [BioKeyTheme]). A new message replaces the one showing.
 *
 * Each message is shown once, by the host on top. One raised while no host is listening (during
 * a rotation, say) waits a few seconds for one; after that it would be out of context.
 */
object BioKeyMessages {

    internal class Message(
        override val message: String,
        val icon: ImageVector?,
        override val actionLabel: String?,
        val onAction: (() -> Unit)?,
        override val duration: SnackbarDuration,
        /** When it was raised, on the uptime clock. */
        val postedAt: Long = SystemClock.uptimeMillis()
    ) : SnackbarVisuals {
        override val withDismissAction = false

        fun isStale(now: Long) = now - postedAt > MAX_WAIT_MS

        /** The same message, raised again now. */
        fun again() = Message(message, icon, actionLabel, onAction, duration)
    }

    /** How long a message may wait for a host to show it. */
    internal const val MAX_WAIT_MS = 5_000L

    private val channel = Channel<Message>(capacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    internal val pending = channel.receiveAsFlow()

    /** Hosts on screen, the one showing messages last: the screen's own, then any dialog's or sheet's over it. */
    internal val hosts = MutableStateFlow<List<Any>>(emptyList())

    /** Shows [message]; with [actionLabel], [onAction] runs if the user taps it. Safe from any thread. */
    fun show(message: String, icon: ImageVector? = null, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
        val duration = if (actionLabel != null) SnackbarDuration.Long else SnackbarDuration.Short
        channel.trySend(Message(message, icon, actionLabel, onAction, duration))
    }

    internal fun resend(message: Message) {
        channel.trySend(message.again())
    }
}

/**
 * Where [BioKeyMessages] appear. [BioKeyTheme] places one for the whole screen. A full-screen
 * dialog or a bottom sheet that raises messages places its own, which shows them while it's open
 * (the screen's would be hidden behind it).
 */
@Composable
fun BioKeyMessageHost(modifier: Modifier = Modifier) = MessageHost(modifier, isScreen = false)

@Composable
internal fun MessageHost(modifier: Modifier, isScreen: Boolean) {
    val state = remember { SnackbarHostState() }
    val token = remember { Any() }
    val activity = LocalActivity.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(token) {
        // The screen's own host stays under any dialog's, whichever was composed first.
        BioKeyMessages.hosts.update { if (isScreen) listOf(token) + it else it + token }
        onDispose { BioKeyMessages.hosts.update { it - token } }
    }
    LaunchedEffect(state, lifecycle) {
        // Only while BioKey is on screen: a message showing when it leaves (the screen turning
        // off, say) is gone when it comes back, behind its lock, rather than offering its action there.
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            BioKeyMessages.hosts.map { it.lastOrNull() === token }.distinctUntilChanged().collectLatest { onTop ->
                if (onTop) BioKeyMessages.pending.collectLatest { message -> showOnce(state, message, activity) }
            }
        }
    }
    SnackbarHost(state, modifier) { data -> MessageBar(data) }
}

private suspend fun showOnce(state: SnackbarHostState, message: BioKeyMessages.Message, activity: Activity?) {
    if (message.isStale(SystemClock.uptimeMillis())) return
    try {
        if (state.showSnackbar(message) == SnackbarResult.ActionPerformed) message.onAction?.invoke()
    } catch (e: CancellationException) {
        // A rotation recreates the screen: the message (an Undo, say) carries over to the new one.
        if (activity?.isChangingConfigurations == true) BioKeyMessages.resend(message)
        throw e
    }
}

@Composable
internal fun MessageBar(data: SnackbarData) {
    val visuals = data.visuals
    val icon = (visuals as? BioKeyMessages.Message)?.icon
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = colors.inverseSurface,
        contentColor = colors.inverseOnSurface,
        shadowElevation = 6.dp,
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .widthIn(max = 560.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .heightIn(min = 56.dp)
                .padding(start = if (icon != null) 18.dp else 24.dp, end = if (visuals.actionLabel != null) 8.dp else 24.dp)
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = colors.inversePrimary, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(14.dp))
            }
            Text(
                text = visuals.message,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .padding(vertical = 14.dp)
            )
            visuals.actionLabel?.let { label ->
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = data::performAction,
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.inversePrimary)
                ) {
                    Text(label, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
