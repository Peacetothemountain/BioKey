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
import androidx.compose.material.icons.filled.AppSettingsAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Layers
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
import com.android.BioKey.core.security.AdvancedProtection
import com.android.BioKey.core.security.ProtectionEngine
import com.android.BioKey.core.security.rejectObscuredTouches
import com.android.BioKey.feature.lock.BioKeyAccessibilityService
import com.android.BioKey.feature.main.BioKeyLogo
import com.android.BioKey.feature.main.Disclosure
import com.android.BioKey.feature.main.HeaderMatrixDotsExpressive
import com.android.BioKey.feature.main.IntentNavigationHelper
import com.android.BioKey.feature.main.rememberDisclosureGate
import com.android.BioKey.ui.theme.Group
import com.android.BioKey.ui.theme.GroupRow
import com.android.BioKey.ui.theme.springPress

/**
 * First-run setup, as a short checklist: give BioKey a way to see which app is open, then confirm
 * identity once. Normally that's the Accessibility service; with Android's Advanced Protection on
 * (which reserves accessibility for accessibility tools) it's compatibility mode instead: usage
 * access plus display over other apps. Every permission is preceded by its disclosure.
 */
@Composable
fun SetupScreenExpressive(
    isAuthenticating: Boolean = false,
    authError: String? = null,
    onSetupComplete: () -> Unit
) {
    val context = LocalContext.current
    val disclosures = rememberDisclosureGate()
    var a11yActive by remember { mutableStateOf(BioKeyAccessibilityService.isServiceEnabled(context)) }
    var compatibilityMode by remember { mutableStateOf(false) }
    var usageAccess by remember { mutableStateOf(false) }
    var overlay by remember { mutableStateOf(false) }
    var restrictedSettingsBlocked by remember { mutableStateOf(false) }
    var hasAutoPrompted by remember { mutableStateOf(false) }
    val ready = a11yActive || (compatibilityMode && usageAccess && overlay)

    // Advance by itself once BioKey can see apps; don't loop if the user cancels the prompt.
    LifecycleResumeEffect(Unit) {
        a11yActive = BioKeyAccessibilityService.isServiceEnabled(context)
        compatibilityMode = !a11yActive && AdvancedProtection.blocksBioKey(context)
        usageAccess = ProtectionEngine.hasUsageAccess(context)
        overlay = ProtectionEngine.canDrawOverlays(context)
        restrictedSettingsBlocked = !a11yActive && !compatibilityMode && RestrictedSettingsHelper.isRestrictedSettingsEnforced(context)
        val nowReady = a11yActive || (compatibilityMode && usageAccess && overlay)
        if (nowReady && !hasAutoPrompted && authError == null) {
            hasAutoPrompted = true
            onSetupComplete()
        } else if (!nowReady) {
            hasAutoPrompted = false
        }
        onPauseOrDispose { }
    }

    val openAccessibility = { disclosures.request(Disclosure.ACCESSIBILITY) { IntentNavigationHelper.openAccessibilitySettings(context) } }
    val openCompatibility = { disclosures.request(Disclosure.COMPATIBILITY) { IntentNavigationHelper.openNextCompatibilityPermission(context) } }
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
        if (compatibilityMode) {
            add(
                Step(
                    Icons.Default.AppSettingsAlt, "Allow usage access",
                    "Advanced Protection is on, so BioKey uses compatibility mode: this tells it which app was opened.",
                    done = usageAccess, onClick = openCompatibility
                )
            )
            add(
                Step(
                    Icons.Default.Layers, "Allow display over other apps",
                    "Lets BioKey cover a locked app as it opens.",
                    done = overlay, onClick = openCompatibility
                )
            )
        } else {
            add(
                Step(
                    Icons.Default.Accessibility, "Turn on BioKey in Accessibility",
                    "Lets BioKey see which app is open so it can cover locked ones.",
                    done = a11yActive, onClick = openAccessibility
                )
            )
        }
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
                onClick = if (ready && !isAuthenticating) onSetupComplete else null
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
            onClick = {
                when {
                    ready -> onSetupComplete()
                    compatibilityMode -> openCompatibility()
                    else -> openAccessibility()
                }
            },
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
                if (ready) Icons.Default.Fingerprint else Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                when {
                    isAuthenticating -> "Confirming…"
                    ready -> "Confirm and finish"
                    compatibilityMode -> "Allow next permission"
                    else -> "Turn on Accessibility"
                },
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}
