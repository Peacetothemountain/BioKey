package com.android.BioKey.ui.theme

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest

/**
 * BioKey's short messages ("Chase unlocked · Undo", "Protection paused"), in place of Android
 * toasts: a Material 3 bar in the theme's colours with an icon and an optional action, shown
 * at the bottom of whichever BioKey screen is open ([BioKeyMessageHost], placed by
 * [BioKeyTheme]). A new message replaces the one showing.
 */
object BioKeyMessages {

    internal class Message(
        override val message: String,
        val icon: ImageVector?,
        override val actionLabel: String?,
        val onAction: (() -> Unit)?,
        override val duration: SnackbarDuration
    ) : SnackbarVisuals {
        override val withDismissAction = false
    }

    internal val pending = MutableSharedFlow<Message>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Shows [message]; with [actionLabel], [onAction] runs if the user taps it. Safe from any thread. */
    fun show(message: String, icon: ImageVector? = null, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
        val duration = if (actionLabel != null) SnackbarDuration.Long else SnackbarDuration.Short
        pending.tryEmit(Message(message, icon, actionLabel, onAction, duration))
    }
}

/** Where [BioKeyMessages] appear. */
@Composable
fun BioKeyMessageHost(modifier: Modifier = Modifier) {
    val state = remember { SnackbarHostState() }
    LaunchedEffect(state) {
        BioKeyMessages.pending.collectLatest { message ->
            if (state.showSnackbar(message) == SnackbarResult.ActionPerformed) message.onAction?.invoke()
        }
    }
    SnackbarHost(state, modifier) { data -> MessageBar(data) }
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
