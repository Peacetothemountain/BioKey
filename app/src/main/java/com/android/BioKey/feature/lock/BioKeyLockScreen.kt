package com.android.BioKey.feature.lock

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.android.BioKey.R
import com.android.BioKey.core.security.rejectObscuredTouches
import com.android.BioKey.ui.brand.BioKeyLogo
import com.android.BioKey.ui.theme.springPress

/** The logo's size, as on [LockCoverView]. */
private val LOGO_WIDTH = 120.dp
private val LOGO_HEIGHT = 136.dp

/**
 * BioKey's own lock, drawn in Compose. Laid out like [LockCoverView] showing BioKey (the logo,
 * the same size, in the middle; a primary button and Cancel at the bottom), so every lock in
 * BioKey looks like one product.
 *
 * After a permanent lockout the button locks the phone, so that the PIN can bring biometrics
 * back; only the accessibility service can do that, so without it ([canLockPhone] false) Cancel
 * is the way out and the message says what to do.
 */
@Composable
fun BioKeyLockScreen(
    onUnlockClicked: () -> Unit,
    onCancelClicked: () -> Unit,
    statusMessage: String? = null,
    isPermanentLockout: Boolean = false,
    canLockPhone: Boolean = true,
    onLockDeviceClicked: () -> Unit = {}
) {
    val lockedTitle = stringResource(R.string.lock_screen_locked)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .rejectObscuredTouches()
            // Announced as it appears; the logo already says "BioKey" when moving through it.
            .semantics { paneTitle = lockedTitle }
            .background(coverWash())
    ) {
        // The logo and its words centred in the room above the buttons, and scrolling rather than
        // running under the status bar when that room is short (landscape, large text, a lockout).
        Column(Modifier.fillMaxSize().systemBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .heightIn(min = maxHeight)
                        .padding(horizontal = 32.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    LockedMessage(lockedTitle, statusMessage, Modifier.widthIn(max = 420.dp))
                }
            }
            Column(
                modifier = Modifier
                    // The same width as LockCoverView's buttons: the screen less 32dp each side, at most 360dp.
                    .padding(horizontal = 32.dp)
                    .widthIn(max = 360.dp)
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!isPermanentLockout || canLockPhone) {
                    Button(
                        onClick = if (isPermanentLockout) onLockDeviceClicked else onUnlockClicked,
                        shape = CircleShape,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .springPress()
                    ) {
                        if (!isPermanentLockout) {
                            Icon(Icons.Default.Fingerprint, contentDescription = null, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(10.dp))
                        }
                        Text(stringResource(if (isPermanentLockout) R.string.lock_screen_lock_phone else R.string.lock_screen_unlock), style = MaterialTheme.typography.titleMedium)
                    }
                }
                TextButton(onClick = onCancelClicked, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.prompt_cancel), style = MaterialTheme.typography.titleSmall)
                }
            }
        }
    }
}

/** The logo, "BioKey is locked", and what the last prompt said (a lockout, say). */
@Composable
private fun LockedMessage(lockedTitle: String, statusMessage: String?, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        val status = statusMessage?.takeIf { it.isNotBlank() }
        val statusStyle = MaterialTheme.typography.bodyMedium
        // With nothing to say, the status's empty line below is balanced by one above, so the
        // logo stays in the middle.
        if (status == null) Spacer(Modifier.height(with(LocalDensity.current) { statusStyle.lineHeight.toDp() }))
        BioKeyLogo(Modifier.size(LOGO_WIDTH, LOGO_HEIGHT))
        // Said in words too, not only by the mark (screen readers hear the pane title).
        Spacer(Modifier.height(16.dp))
        Text(
            lockedTitle,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.clearAndSetSemantics { }
        )
        if (status != null) Spacer(Modifier.height(16.dp))
        // Always there, empty when there's nothing to say: a live region is read out when its
        // text changes, not when it first appears.
        Text(
            text = status.orEmpty(),
            style = statusStyle,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
        )
    }
}

/** The lock cover's background: the wallpaper's colour washing down into the surface, as on [LockCoverView]. */
@Composable
private fun coverWash(): Brush {
    val surface = MaterialTheme.colorScheme.surface
    val wash = MaterialTheme.colorScheme.primaryContainer
    return remember(surface, wash) { Brush.verticalGradient(listOf(lerp(surface, wash, 0.5f), lerp(surface, wash, 0.16f), surface)) }
}
