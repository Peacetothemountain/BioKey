package com.android.BioKey.feature.lock

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
    Box(
        modifier = Modifier
            .fillMaxSize()
            .rejectObscuredTouches()
            // Announced as it appears; the logo already says "BioKey" when moving through it.
            .semantics { paneTitle = "BioKey is locked" }
            .background(coverWash())
    ) {

        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
                .padding(bottom = 200.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val status = statusMessage?.takeIf { it.isNotBlank() }
            val statusStyle = MaterialTheme.typography.bodyMedium
            // With nothing to say, the status's empty line below is balanced by one above, so the
            // logo stays in the middle.
            if (status == null) Spacer(Modifier.height(with(LocalDensity.current) { statusStyle.lineHeight.toDp() }))
            BioKeyLogo(Modifier.size(LOGO_WIDTH, LOGO_HEIGHT))
            // No "is locked" caption: the logo says what is locked; screen readers get it above.
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

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .systemBarsPadding()
                .widthIn(max = 360.dp)
                .fillMaxWidth()
                .padding(horizontal = 32.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (!isPermanentLockout || canLockPhone) {
                Button(
                    onClick = if (isPermanentLockout) onLockDeviceClicked else onUnlockClicked,
                    shape = CircleShape,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .springPress()
                ) {
                    if (!isPermanentLockout) {
                        Icon(Icons.Default.Fingerprint, contentDescription = null, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(if (isPermanentLockout) "Lock screen" else "Unlock", style = MaterialTheme.typography.titleMedium)
                }
            }
            TextButton(onClick = onCancelClicked, modifier = Modifier.height(48.dp)) {
                Text("Cancel", style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}

/** The lock cover's background: the wallpaper's colour washing down into the surface, as on [LockCoverView]. */
@Composable
private fun coverWash(): Brush {
    val surface = MaterialTheme.colorScheme.surface
    val wash = MaterialTheme.colorScheme.primaryContainer
    return remember(surface, wash) { Brush.verticalGradient(listOf(lerp(surface, wash, 0.5f), lerp(surface, wash, 0.16f), surface)) }
}
