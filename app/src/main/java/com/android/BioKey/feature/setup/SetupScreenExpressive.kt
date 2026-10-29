package com.android.BioKey.feature.setup

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.android.BioKey.R
import com.android.BioKey.core.security.AdvancedProtection
import com.android.BioKey.core.security.ProtectionEngine
import com.android.BioKey.core.security.rejectObscuredTouches
import com.android.BioKey.feature.lock.BioKeyAccessibilityService
import com.android.BioKey.feature.main.Disclosure
import com.android.BioKey.feature.main.IntentNavigationHelper
import com.android.BioKey.feature.main.rememberDisclosureGate
import com.android.BioKey.feature.main.rememberNotificationPermission
import com.android.BioKey.ui.brand.BioKeyLogo
import com.android.BioKey.ui.brand.HeaderMatrixDotsExpressive
import com.android.BioKey.ui.theme.Group
import com.android.BioKey.ui.theme.GroupRow
import com.android.BioKey.ui.theme.scaledWithText
import com.android.BioKey.ui.theme.springPress

/**
 * First-run setup, as a short checklist: give BioKey a way to see which app is open, then confirm
 * identity once. Normally that's the Accessibility service; with Android's Advanced Protection on
 * (which reserves accessibility for accessibility tools) it's compatibility mode instead: usage
 * access plus display over other apps. Every permission is preceded by its disclosure. Just
 * before the final check, Android 13+ asks once whether BioKey may post notifications (its
 * protection alerts); that's optional.
 *
 * [needsScreenLock]: the final check failed because the phone has no screen lock.
 */
@Composable
fun SetupScreenExpressive(
    isAuthenticating: Boolean = false,
    authError: String? = null,
    needsScreenLock: Boolean = false,
    onSetupComplete: () -> Unit
) {
    val context = LocalContext.current
    val disclosures = rememberDisclosureGate()
    val notifications = rememberNotificationPermission()
    var askedNotifications by rememberSaveable { mutableStateOf(false) }
    /** The final check, after Android's notification question the first time. */
    val finish = {
        if (!askedNotifications && !notifications.allowed) {
            askedNotifications = true
            notifications.request(then = onSetupComplete)
        } else {
            onSetupComplete()
        }
    }
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
            finish()
        } else if (!nowReady) {
            hasAutoPrompted = false
        }
        onPauseOrDispose { }
    }

    val openAccessibility = { disclosures.request(Disclosure.ACCESSIBILITY) { IntentNavigationHelper.openAccessibilitySettings(context) } }
    val openCompatibility = { disclosures.request(Disclosure.COMPATIBILITY) { IntentNavigationHelper.openNextCompatibilityPermission(context) } }

    /** [opensExternally]: tapping it leaves BioKey for a system settings page. */
    data class Step(
        val icon: ImageVector,
        val title: String,
        val subtitle: String,
        val done: Boolean,
        val opensExternally: Boolean = true,
        val onClick: (() -> Unit)?
    )
    val steps = buildList {
        if (restrictedSettingsBlocked) {
            add(
                Step(
                    Icons.Default.Policy, stringResource(R.string.setup_restricted_title),
                    // Android offers the ⋮ item only once its "restricted setting" dialog has been seen.
                    stringResource(R.string.setup_restricted_text),
                    done = false
                ) { IntentNavigationHelper.openAppDetailsSettings(context) }
            )
        }
        if (compatibilityMode) {
            add(
                Step(
                    Icons.Default.AppSettingsAlt, stringResource(R.string.setup_usage_access_title),
                    stringResource(R.string.setup_usage_access_text),
                    done = usageAccess, onClick = openCompatibility
                )
            )
            add(
                Step(
                    Icons.Default.Layers, stringResource(R.string.setup_overlay_title),
                    stringResource(R.string.setup_overlay_text),
                    done = overlay, onClick = openCompatibility
                )
            )
        } else {
            add(
                Step(
                    Icons.Default.Accessibility, stringResource(R.string.setup_accessibility_title),
                    stringResource(R.string.setup_accessibility_text),
                    done = a11yActive, onClick = openAccessibility
                )
            )
        }
        if (needsScreenLock) {
            add(
                Step(
                    Icons.Default.Password, stringResource(R.string.setup_screen_lock_title),
                    stringResource(R.string.setup_screen_lock_text),
                    done = false
                ) { IntentNavigationHelper.openScreenLockSetup(context) }
            )
        }
        // Android 13+ asks; earlier versions allow notifications unless they were turned off.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU || !notifications.allowed) {
            add(
                Step(
                    Icons.Default.Notifications, stringResource(R.string.setup_notifications_title),
                    stringResource(R.string.setup_notifications_text),
                    done = notifications.allowed,
                    // Android's own question from 13 on; before that, only its settings page.
                    opensExternally = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU,
                    onClick = {
                        askedNotifications = true
                        notifications.requestOrOpenSettings()
                    }
                )
            )
        }
        add(
            Step(
                Icons.Default.Fingerprint, stringResource(R.string.setup_confirm_title),
                stringResource(R.string.setup_confirm_text),
                done = false,
                opensExternally = false,
                onClick = if (ready && !isAuthenticating) finish else null
            )
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .rejectObscuredTouches()
    ) {
        // The constellation thins out round the logo, the title and the line under it: no link
        // crosses the first words a new owner reads.
        var fieldOrigin by remember { mutableStateOf(Offset.Zero) }
        val quietZones = remember { mutableStateMapOf<Int, Rect>() }
        HeaderMatrixDotsExpressive(
            Modifier
                .fillMaxWidth()
                .height(360.dp)
                .align(Alignment.TopCenter)
                .onGloballyPositioned { fieldOrigin = it.positionInRoot() },
            quiet = { quietZones.values.map { it.translate(-fieldOrigin) } }
        )

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
            BioKeyLogo(Modifier.size(104.dp, 118.dp).onGloballyPositioned { quietZones[0] = it.boundsInRoot() })
            Spacer(Modifier.height(28.dp))
            Text(
                stringResource(R.string.setup_title),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() }.onGloballyPositioned { quietZones[1] = it.boundsInRoot() }
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.setup_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp).onGloballyPositioned { quietZones[2] = it.boundsInRoot() }
            )
            Spacer(Modifier.height(28.dp))
            // The step to do now: the first one not done.
            val current = steps.indexOfFirst { !it.done }
            Group(count = steps.size) { i ->
                val step = steps[i]
                val state = when {
                    step.done -> StepState.DONE
                    i == current -> StepState.CURRENT
                    // Only the last step is ever without an action: until the ones above are
                    // done, or while it runs (then it's the current one).
                    step.onClick == null -> StepState.BLOCKED
                    else -> StepState.LATER
                }
                GroupRow(
                    index = i,
                    count = steps.size,
                    title = step.title,
                    subtitle = step.subtitle,
                    leading = { StepBadge(if (step.done) Icons.Default.CheckCircle else step.icon, state) },
                    onClick = step.onClick.takeIf { !step.done },
                    // One that can't be done yet looks it, but stays readable: it says what's coming.
                    modifier = if (state == StepState.BLOCKED) Modifier.alpha(0.7f) else Modifier,
                    // Said aloud, as the tick, the ring and the colour only show it.
                    stateDescription = stringResource(
                        when {
                            state == StepState.DONE -> R.string.setup_step_done
                            isAuthenticating && step.onClick == null -> R.string.setup_step_confirming
                            state == StepState.CURRENT -> R.string.setup_step_current
                            state == StepState.LATER -> R.string.setup_step_later
                            else -> R.string.setup_step_waiting
                        }
                    )
                ) {
                    if (!step.done && step.onClick != null && step.opensExternally) {
                        Icon(
                            Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
            if (authError != null) Spacer(Modifier.height(16.dp))
            // Always there, empty without an error, so the error is read out as it comes up after
            // the system prompt: a live region is read when its text changes, not when it first appears.
            Text(
                authError.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(horizontal = 32.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite }
            )
        }

        Button(
            onClick = {
                when {
                    ready -> finish()
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
                .heightIn(min = 56.dp)
                .springPress(enabled = !isAuthenticating)
        ) {
            Icon(
                if (ready) Icons.Default.Fingerprint else Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(
                    when {
                        isAuthenticating -> R.string.setup_button_confirming
                        ready -> R.string.setup_button_finish
                        compatibilityMode -> R.string.setup_button_next_permission
                        else -> R.string.setup_button_accessibility
                    }
                ),
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}

/** Where a setup step stands: done, the one to do now, one after it, or one that can't be done yet. */
private enum class StepState { DONE, CURRENT, LATER, BLOCKED }

/**
 * A step's icon in its circle: a tick on the primary colour when done; the step to do now in
 * the primary container with a ring round it; the rest in a quiet neutral circle.
 */
@Composable
private fun StepBadge(icon: ImageVector, state: StepState) {
    val colors = MaterialTheme.colorScheme
    val (container, tint) = when (state) {
        StepState.DONE -> colors.primary to colors.onPrimary
        StepState.CURRENT -> colors.primaryContainer to colors.onPrimaryContainer
        else -> colors.surfaceContainerHighest to colors.onSurfaceVariant
    }
    val size = 40.dp.scaledWithText()
    Box(
        modifier = Modifier
            .size(size)
            .background(container, CircleShape)
            .then(if (state == StepState.CURRENT) Modifier.border(2.dp, colors.primary, CircleShape) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.55f))
    }
}
