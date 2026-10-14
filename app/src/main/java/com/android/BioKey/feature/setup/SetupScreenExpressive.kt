package com.android.BioKey.feature.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.android.BioKey.core.security.rejectObscuredTouches
import com.android.BioKey.feature.lock.BioKeyAccessibilityService
import com.android.BioKey.feature.main.HeaderMatrixDotsExpressive
import com.android.BioKey.feature.main.IntentNavigationHelper
import com.android.BioKey.ui.theme.springPress

/**
 * Clean 1-step setup for BioKey matching Google native App Lock standard.
 * Fluid full-screen canvas layout with zero squircle container boxes.
 */
@Composable
fun SetupScreenExpressive(
    isAuthenticating: Boolean = false,
    authError: String? = null,
    onSetupComplete: () -> Unit
) {
    val context = LocalContext.current
    var a11yActive by remember { mutableStateOf(BioKeyAccessibilityService.isServiceEnabled(context)) }
    var hasAutoPrompted by remember { mutableStateOf(false) }

    // Auto-advance once as soon as permission is granted; don't infinite-loop if user cancels prompt
    LifecycleResumeEffect(Unit) {
        val active = BioKeyAccessibilityService.isServiceEnabled(context)
        a11yActive = active
        if (active && !hasAutoPrompted && authError == null) {
            hasAutoPrompted = true
            onSetupComplete()
        } else if (!active) {
            hasAutoPrompted = false
        }
        onPauseOrDispose { }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .rejectObscuredTouches()
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center
    ) {
        // Living ambient particle field across top
        HeaderMatrixDotsExpressive(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
                .align(Alignment.TopCenter)
        )

        Column(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(76.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(38.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            Text(
                text = if (a11yActive) "Confirm your identity" else "Set up BioKey",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = if (a11yActive) {
                    "Accessibility protection is active. Verify your identity to finish setup."
                } else {
                    "Turn on the accessibility service in system settings to let BioKey detect and lock apps."
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            if (authError != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = authError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
                if (authError.contains("screen lock", ignoreCase = true)) {
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(
                        onClick = { IntentNavigationHelper.openScreenLockSetup(context) },
                        modifier = Modifier.springPress()
                    ) {
                        Text(
                            text = "Set up screen lock",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(36.dp))

            Button(
                onClick = {
                    if (a11yActive) {
                        onSetupComplete()
                    } else {
                        IntentNavigationHelper.openAccessibilitySettings(context)
                    }
                },
                enabled = !isAuthenticating,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .springPress()
            ) {
                Icon(
                    imageVector = if (a11yActive) Icons.Default.Fingerprint else Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = when {
                        isAuthenticating -> "Confirming..."
                        a11yActive -> "Confirm with Biometrics"
                        else -> "Turn on in Settings"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            if (!a11yActive && RestrictedSettingsHelper.isRestrictedSettingsEnforced(context)) {
                Spacer(modifier = Modifier.height(14.dp))

                TextButton(
                    onClick = {
                        IntentNavigationHelper.openAppDetailsSettings(context)
                    },
                    modifier = Modifier.springPress()
                ) {
                    Text(
                        text = "Need to allow restricted settings? Open App Info",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}
