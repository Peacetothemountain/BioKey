package com.android.BioKey.feature.lock

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.BioKey.R
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.core.security.rejectObscuredTouches
import com.android.BioKey.ui.theme.ExpressiveTokens
import com.android.BioKey.ui.theme.expressiveClickable
import com.android.BioKey.ui.theme.springPress

/**
 * Lock screen shown over a protected app, or over one of BioKey's own system-settings pages
 * when [isBioKeyLock] is true. Adapts cleanly to portrait and wide/foldable layouts.
 *
 * Implements Google's native Android 17 QPR3 App Lock design:
 * - Solid dark surface background (no blur distortion or haze)
 * - Large crisp centered app icon without container clutter or squircle borders
 * - Clean bold typography without artificial badge boxes or "App Locked" containers
 * - Material 3 Expressive borderless pill unlock button (Face & Fingerprint) and Cancel action
 */
@Composable
fun LockScreenUIExpressive(
    appName: String,
    appIcon: ImageBitmap?,
    auraColor: Color,
    onUnlockClicked: () -> Unit,
    onCancelClicked: () -> Unit,
    isBioKeyLock: Boolean = false,
    statusMessage: String? = null,
    isPermanentLockout: Boolean = false,
    onLockDeviceClicked: () -> Unit = {}
) {
    val context = LocalContext.current
    val vault = remember { SecurityVault.getInstance(context) }
    val isIdentityCheck by vault.identityCheckFlow.collectAsStateWithLifecycle()
    val isBioKeyApp = isBioKeyLock

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .rejectObscuredTouches()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .systemBarsPadding(),
        contentAlignment = Alignment.Center
    ) {
        val isFoldableWide = maxWidth >= 600.dp

        // Hardware Lockout Alert Card - guides the user when Titan M2 biometric hardware locks out
        val lockoutCard: @Composable () -> Unit = {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                shape = ExpressiveTokens.Shapes.BentoCardExtraLarge,
                color = MaterialTheme.colorScheme.errorContainer
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Hardware Sensor Locked",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = statusMessage ?: "Biometric sensor locked by Titan M2 hardware policy. Please lock your device screen and unlock with your PIN or pattern to reset the sensor.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    FilledTonalButton(
                        onClick = onLockDeviceClicked,
                        shape = RoundedCornerShape(20.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        ),
                        modifier = Modifier.height(48.dp)
                    ) {
                        Text("Lock Phone Screen", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Expressive Unlock Pill Button - clean, borderless native M3 pill
        val unlockButton: @Composable () -> Unit = {
            Surface(
                modifier = Modifier
                    .springPress(pressScale = 0.965f, releasePopScale = 1.02f)
                    .expressiveClickable(
                        hapticStyle = ExpressiveTokens.HapticFeedbackStyle.CONFIRM_SUCCESS,
                        onClick = onUnlockClicked
                    )
                    .height(64.dp),
                shape = RoundedCornerShape(32.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 32.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Face,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Icon(
                        imageVector = Icons.Default.Fingerprint,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = "Unlock",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }

        // App Identity Header - 100% faithful to native Android App Lock
        val appIdentityHeader: @Composable () -> Unit = {
            if (isBioKeyApp) {
                Box(
                    modifier = Modifier
                        .height(96.dp)
                        .padding(bottom = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_title_logo_letters),
                        contentDescription = "Bio Key Logo Letters",
                        modifier = Modifier.fillMaxHeight(),
                        contentScale = ContentScale.Fit,
                        colorFilter = ColorFilter.tint(
                            MaterialTheme.colorScheme.primary,
                            BlendMode.Modulate
                        )
                    )
                    Image(
                        painter = painterResource(R.drawable.ic_title_logo_tentacles),
                        contentDescription = "Bio Key Logo Tentacles",
                        modifier = Modifier.fillMaxHeight(),
                        contentScale = ContentScale.Fit,
                        colorFilter = ColorFilter.tint(
                            MaterialTheme.colorScheme.tertiary,
                            BlendMode.Modulate
                        )
                    )
                }

                Text(
                    text = "BioKey",
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "Verify to modify settings",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            } else {
                if (appIcon != null) {
                    Image(
                        bitmap = appIcon,
                        contentDescription = null,
                        modifier = Modifier
                            .size(if (isFoldableWide) 104.dp else 92.dp)
                            .clip(ExpressiveTokens.Shapes.BentoCardLarge),
                        filterQuality = FilterQuality.High
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = if (appName.isBlank()) "Protected Application" else appName,
                    style = if (isFoldableWide) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )

                // Pure native Android hierarchy: NO "App Locked" container box or badge.
                if (isIdentityCheck) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Identity Check Active",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        if (isFoldableWide) {
            // Adaptive horizontal dual-pane layout for unfolded Pixel Fold
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 48.dp, vertical = 32.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    appIdentityHeader()
                }

                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    if (isPermanentLockout) {
                        lockoutCard()
                    } else {
                        unlockButton()
                        StatusMessage(statusMessage)
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                    FilledTonalButton(
                        onClick = onCancelClicked,
                        shape = ExpressiveTokens.Shapes.BentoCardLarge,
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        modifier = Modifier
                            .height(48.dp)
                            .springPress(pressScale = 0.965f, releasePopScale = 1.02f)
                    ) {
                        Text(
                            text = "Cancel",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        } else {
            // Standard portrait layout for Pixel 11 Pro XL
            Column(
                modifier = Modifier.padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                appIdentityHeader()
                Spacer(modifier = Modifier.height(if (isPermanentLockout) 32.dp else 56.dp))
                if (isPermanentLockout) {
                    lockoutCard()
                } else {
                    unlockButton()
                    StatusMessage(statusMessage)
                }
                Spacer(modifier = Modifier.height(24.dp))
                FilledTonalButton(
                    onClick = onCancelClicked,
                    shape = ExpressiveTokens.Shapes.BentoCardLarge,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    modifier = Modifier
                        .height(48.dp)
                        .springPress(pressScale = 0.965f, releasePopScale = 1.02f)
                ) {
                    Text(
                        text = "Cancel",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusMessage(message: String?) {
    if (message.isNullOrBlank()) return
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Center
    )
}
