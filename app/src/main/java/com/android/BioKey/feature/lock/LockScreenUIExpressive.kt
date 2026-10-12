package com.android.BioKey.feature.lock

import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.core.security.rejectObscuredTouches
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.BioKey.R
import com.android.BioKey.ui.theme.ExpressiveTokens
import com.android.BioKey.ui.theme.M3XBorderDefaults
import com.android.BioKey.ui.theme.M3XProgressiveBlurTokens
import com.android.BioKey.ui.theme.M3XShapes
import com.android.BioKey.ui.theme.MeshGradientAmbientHalo
import com.android.BioKey.ui.theme.ProgressiveBlurDirection
import com.android.BioKey.ui.theme.expressiveClickable
import com.android.BioKey.ui.theme.m3xBackdropBlur
import com.android.BioKey.ui.theme.m3xProgressiveBlur
import com.android.BioKey.ui.theme.springPress

/**
 * Modern Material 3 Expressive Lock Screen UI (Android Canal / 16 / 17 Standards).
 * Features:
 * - Dynamic harmonic multi-frequency biometric aura
 * - Material 3 container geometry with Monet-adapted tinting
 * - Tactile spring unlock button with dual biometric iconography
 * - Responsive layout adapting seamlessly from portrait (Pixel 11 Pro XL) to wide (Pixel Fold)
 */
@Composable
fun LockScreenUIExpressive(
    appName: String,
    appIcon: ImageBitmap?,
    auraColor: Color,
    onUnlockClicked: () -> Unit,
    onCancelClicked: () -> Unit
) {
    val context = LocalContext.current
    val vault = remember { SecurityVault.getInstance(context) }
    val isIdentityCheck by vault.identityCheckFlow.collectAsState()
    val isBioKeyApp = appName.equals("Bio Key", ignoreCase = true) || appName.equals("BioKey", ignoreCase = true)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .rejectObscuredTouches()
            .m3xBackdropBlur(radius = M3XProgressiveBlurTokens.Prominent)
            .m3xProgressiveBlur(radius = M3XProgressiveBlurTokens.Subtle, direction = ProgressiveBlurDirection.TopToBottom)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
            .systemBarsPadding(),
        contentAlignment = Alignment.Center
    ) {
        val isFoldableWide = maxWidth >= 600.dp
        val auraSize = if (isFoldableWide) 320.dp else 240.dp

        // Dynamic Multi-Harmonic Mesh Gradient Ambient Halo (Monet Chromatic Dispersion)
        MeshGradientAmbientHalo(
            auraColor = auraColor,
            size = auraSize * 1.35f
        )

        val containerColor = MaterialTheme.colorScheme.primaryContainer
            .copy(alpha = 0.40f)
            .compositeOver(MaterialTheme.colorScheme.surfaceContainerHigh)

        // Expressive Unlock Pill Button with LTPO-synced Spring Press
        val unlockButton: @Composable () -> Unit = {
            Surface(
                modifier = Modifier
                    .springPress(pressScale = 0.965f, releasePopScale = 1.02f)
                    .expressiveClickable(
                        hapticStyle = ExpressiveTokens.HapticFeedbackStyle.CONFIRM_SUCCESS,
                        onClick = onUnlockClicked
                    )
                    .height(68.dp),
                shape = M3XShapes.TriggerPill,
                color = containerColor,
                border = BorderStroke(
                    1.5.dp,
                    if (isIdentityCheck) MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                    else MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 32.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Face,
                        contentDescription = "Face Unlock",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Icon(
                        imageVector = Icons.Default.Fingerprint,
                        contentDescription = "Fingerprint",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = "Face Unlock • Fingerprint",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // App Identity Header
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

                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.7f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "HARDWARE SECURITY SEALED",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            } else {
                if (appIcon != null) {
                    Surface(
                        shape = M3XShapes.BentoTile,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.45f),
                        border = M3XBorderDefaults.subtleBorderStroke(),
                        modifier = Modifier
                            .size(if (isFoldableWide) 104.dp else 92.dp)
                            .padding(4.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Image(
                                bitmap = appIcon,
                                contentDescription = "App Icon",
                                modifier = Modifier
                                    .size(if (isFoldableWide) 92.dp else 80.dp)
                                    .clip(RoundedCornerShape(20.dp)),
                                filterQuality = FilterQuality.High
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = if (appName.isBlank()) "Protected Application" else appName,
                    style = if (isFoldableWide) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(8.dp))

                if (isIdentityCheck) {
                    Surface(
                        shape = M3XShapes.FullPill,
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "IDENTITY CHECK ACTIVE",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                } else {
                    Surface(
                        shape = M3XShapes.FullPill,
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                        border = M3XBorderDefaults.subtleBorderStroke()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Shield,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Secured by BioKey Hardware Shield",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
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
                    unlockButton()
                    Spacer(modifier = Modifier.height(24.dp))
                    FilledTonalButton(
                        onClick = onCancelClicked,
                        shape = M3XShapes.FullPill,
                        border = M3XBorderDefaults.subtleBorderStroke(),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        modifier = Modifier.springPress(pressScale = 0.965f, releasePopScale = 1.02f)
                    ) {
                        Text(
                            text = "Cancel",
                            style = MaterialTheme.typography.titleMedium,
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
                Spacer(modifier = Modifier.height(60.dp))
                unlockButton()
                Spacer(modifier = Modifier.height(28.dp))
                FilledTonalButton(
                    onClick = onCancelClicked,
                    shape = M3XShapes.FullPill,
                    border = M3XBorderDefaults.subtleBorderStroke(),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    modifier = Modifier.springPress(pressScale = 0.965f, releasePopScale = 1.02f)
                ) {
                    Text(
                        text = "Cancel",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}
