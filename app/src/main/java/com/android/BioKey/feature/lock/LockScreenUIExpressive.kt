package com.android.BioKey.feature.lock

import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.android.BioKey.core.security.rejectObscuredTouches
import com.android.BioKey.ui.brand.BioKeyLogo
import com.android.BioKey.ui.theme.springPress

/**
 * Lock screen drawn in Compose: BioKey's own lock (when [isBioKeyLock]) or a locked app.
 * Same layout as [LockCoverView] (identity in the middle, actions at the bottom) so every lock
 * in BioKey looks like one product.
 */
@Composable
fun LockScreenUIExpressive(
    appName: String,
    appIcon: ImageBitmap?,
    onUnlockClicked: () -> Unit,
    onCancelClicked: (() -> Unit)? = null,
    isBioKeyLock: Boolean = false,
    statusMessage: String? = null,
    isPermanentLockout: Boolean = false,
    onLockDeviceClicked: () -> Unit = {}
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .rejectObscuredTouches()
            .semantics { contentDescription = if (isBioKeyLock) "BioKey is locked" else "${appName.ifBlank { "This app" }} is locked" }
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
            when {
                isBioKeyLock -> BioKeyLogo(Modifier.size(280.dp, 96.dp), animated = false)
                appIcon != null -> Image(bitmap = appIcon, contentDescription = null, modifier = Modifier.size(72.dp))
                else -> Box(
                    Modifier
                        .size(72.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
            }
            // No "is locked" caption: the logo or icon says what is locked; screen readers get it below.
            if (!statusMessage.isNullOrBlank()) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = statusMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                )
            }
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
            Button(
                onClick = if (isPermanentLockout) onLockDeviceClicked else onUnlockClicked,
                shape = CircleShape,
                colors = if (isPermanentLockout) {
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)
                } else ButtonDefaults.buttonColors(),
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
            if (onCancelClicked != null) {
                TextButton(onClick = onCancelClicked, modifier = Modifier.height(48.dp)) {
                    Text("Cancel", style = MaterialTheme.typography.titleSmall)
                }
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
