package com.android.BioKey.feature.setup

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
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.android.BioKey.core.security.rejectObscuredTouches
import com.android.BioKey.feature.lock.BioKeyAccessibilityService
import com.android.BioKey.feature.main.BioKeyLogo
import com.android.BioKey.feature.main.HeaderMatrixDotsExpressive
import com.android.BioKey.feature.main.IntentNavigationHelper
import com.android.BioKey.ui.theme.Group
import com.android.BioKey.ui.theme.GroupRow
import com.android.BioKey.ui.theme.springPress

/**
 * First-run setup: turn on the accessibility service, then confirm identity once.
 * Shown as a short checklist so it's always clear what's done and what's next.
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
    var restrictedSettingsBlocked by remember { mutableStateOf(false) }

    // Advance by itself once the service is on; don't loop if the user cancels the prompt.
    LifecycleResumeEffect(Unit) {
        val active = BioKeyAccessibilityService.isServiceEnabled(context)
        a11yActive = active
        restrictedSettingsBlocked = !active && RestrictedSettingsHelper.isRestrictedSettingsEnforced(context)
        if (active && !hasAutoPrompted && authError == null) {
            hasAutoPrompted = true
            onSetupComplete()
        } else if (!active) {
            hasAutoPrompted = false
        }
        onPauseOrDispose { }
    }

    val needsScreenLock = authError?.contains("screen lock", ignoreCase = true) == true

    data class Step(val icon: ImageVector, val title: String, val subtitle: String, val done: Boolean, val onClick: (() -> Unit)?)
    val steps = buildList {
        if (restrictedSettingsBlocked) {
            add(
                Step(
                    Icons.Default.Policy, "Allow restricted settings",
                    "Android blocks this for apps installed outside the Play Store. In App info, tap ⋮ then “Allow restricted settings”.",
                    done = false
                ) { IntentNavigationHelper.openAppDetailsSettings(context) }
            )
        }
        add(
            Step(
                Icons.Default.Accessibility, "Turn on BioKey in Accessibility",
                "Lets BioKey see which app is open so it can cover locked ones.",
                done = a11yActive
            ) { IntentNavigationHelper.openAccessibilitySettings(context) }
        )
        if (needsScreenLock) {
            add(
                Step(
                    Icons.Default.Password, "Set a screen lock",
                    "BioKey uses your PIN, pattern or password as the fallback.",
                    done = false
                ) { IntentNavigationHelper.openScreenLockSetup(context) }
            )
        }
        add(
            Step(
                Icons.Default.Fingerprint, "Confirm it's you",
                "One check with your face, fingerprint or screen lock to finish.",
                done = false,
                onClick = if (a11yActive && !isAuthenticating) onSetupComplete else null
            )
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .rejectObscuredTouches()
    ) {
        HeaderMatrixDotsExpressive(Modifier.fillMaxWidth().height(360.dp).align(Alignment.TopCenter))

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = 520.dp)
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 120.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(48.dp))
            BioKeyLogo(Modifier.size(280.dp, 96.dp))
            Spacer(Modifier.height(28.dp))
            Text("Set up BioKey", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "Lock any app behind your face, fingerprint or screen lock.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp)
            )
            Spacer(Modifier.height(28.dp))
            Group(count = steps.size) { i ->
                val step = steps[i]
                GroupRow(
                    index = i,
                    count = steps.size,
                    title = step.title,
                    subtitle = step.subtitle,
                    icon = if (step.done) Icons.Default.CheckCircle else step.icon,
                    iconTint = if (step.done) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
                    iconContainer = if (step.done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                    onClick = step.onClick.takeIf { !step.done }
                ) {
                    if (!step.done && step.onClick != null && step.icon != Icons.Default.Fingerprint) {
                        Icon(
                            Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
            if (authError != null) {
                Spacer(Modifier.height(16.dp))
                Text(
                    authError,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp)
                )
            }
        }

        Button(
            onClick = { if (a11yActive) onSetupComplete() else IntentNavigationHelper.openAccessibilitySettings(context) },
            enabled = !isAuthenticating,
            shape = CircleShape,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 32.dp, vertical = 24.dp)
                .widthIn(max = 360.dp)
                .fillMaxWidth()
                .height(56.dp)
                .springPress()
        ) {
            Icon(
                if (a11yActive) Icons.Default.Fingerprint else Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                when {
                    isAuthenticating -> "Confirming…"
                    a11yActive -> "Confirm and finish"
                    else -> "Open Accessibility settings"
                },
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}
