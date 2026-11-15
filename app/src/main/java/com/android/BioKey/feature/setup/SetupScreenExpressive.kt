package com.android.BioKey.feature.setup

import android.os.Build
import com.android.BioKey.ui.disclosure.ReadingColumnWidth
import com.android.BioKey.feature.main.markAutoSortNotePending
import com.android.BioKey.core.security.SecurityVault
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.layout.Row
import androidx.annotation.StringRes
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.stateDescription
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.android.BioKey.core.security.UnlockAttempts
import com.android.BioKey.ui.theme.SectionHeader
import java.text.NumberFormat
import kotlinx.coroutines.delay
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import com.android.BioKey.R
import com.android.BioKey.core.security.AdvancedProtection
import com.android.BioKey.core.security.ProtectionEngine
import com.android.BioKey.core.security.UnlockMethods
import com.android.BioKey.core.security.ProtectionGaps
import com.android.BioKey.feature.lock.BioKeyAccessibilityService
import com.android.BioKey.ui.brand.BioKeyLogo
import com.android.BioKey.ui.brand.HeaderMatrixDotsExpressive
import com.android.BioKey.ui.disclosure.Disclosure
import com.android.BioKey.ui.disclosure.rememberDisclosureGate
import com.android.BioKey.ui.disclosure.rememberNotificationPermission
import com.android.BioKey.ui.format.attemptTime
import com.android.BioKey.ui.navigation.IntentNavigationHelper
import com.android.BioKey.ui.security.rejectObscuredTouches
import com.android.BioKey.ui.theme.Group
import com.android.BioKey.ui.theme.GroupInset
import com.android.BioKey.ui.theme.GroupRow
import com.android.BioKey.ui.theme.scaledWithText
import com.android.BioKey.ui.theme.springPress

/**
 * First-run setup, as a short checklist: give BioKey a way to see which app is open, then confirm
 * it's the owner once. Normally that's the Accessibility service; with Android's Advanced Protection on
 * (which reserves accessibility for accessibility tools) it's compatibility mode instead: usage
 * access plus display over other apps. Every permission is preceded by its disclosure (agreed
 * once per setup). Notifications (BioKey's protection alerts) are an optional step of their own.
 *
 * Nothing here opens a system page, question or prompt by itself: back from Android's settings,
 * the list shows the step done and the button moves to the next one, for the owner to tap.
 *
 * [needsScreenLock]: the final check failed because the phone has no screen lock.
 */
@Composable
fun SetupScreenExpressive(
    isAuthenticating: Boolean = false,
    authError: String? = null,
    needsScreenLock: Boolean = false,
    /** BioKey's storage was cleared, if that's why it's being set up ([ProtectionGaps.checkStorageCleared]). */
    cleared: ProtectionGaps.Cleared? = null,
    onSetupComplete: () -> Unit
) {
    val context = LocalContext.current
    // An Agree holds for the rest of setup: "Open Accessibility settings" again doesn't ask again.
    val disclosures = rememberDisclosureGate(rememberAgreement = true)
    val notifications = rememberNotificationPermission()
    // Home's first look after this setup says how many apps auto-sort locked (the note below).
    LaunchedEffect(Unit) { markAutoSortNotePending(context) }
    /** The final check: only ever from the owner's tap. */
    val finish = onSetupComplete
    var a11yActive by remember { mutableStateOf(BioKeyAccessibilityService.isServiceEnabled(context)) }
    var compatibilityMode by remember { mutableStateOf(false) }
    var usageAccess by remember { mutableStateOf(false) }
    var overlay by remember { mutableStateOf(false) }
    var restricted by remember { mutableStateOf(RestrictedSettingsHelper.Verdict.NOT_BLOCKED) }
    /** Accessibility settings were opened from here (after the disclosure's Agree). */
    var triedAccessibility by rememberSaveable { mutableStateOf(false) }
    /** Back from Accessibility settings with the service still off. */
    var cameBackWithoutService by rememberSaveable { mutableStateOf(false) }
    /** "Turn on" opens BioKey's own page there, not the whole list (where the step says what to tap). */
    var opensOwnPage by remember { mutableStateOf(false) }
    /** What a locked app will ask for, in BioKey's words ("your face, fingerprint or screen lock"). */
    var asksFor by remember { mutableStateOf(UnlockMethods.asksFor(context)) }
    val ready = a11yActive || (compatibilityMode && usageAccess && overlay)
    val autoSortOn by remember { SecurityVault.getInstance(context).autoSortProfilesFlow }.collectAsStateWithLifecycle()

    // Read when the screen resumes (back from Android's settings): the steps follow, nothing more.
    LifecycleResumeEffect(Unit) {
        a11yActive = BioKeyAccessibilityService.isServiceEnabled(context)
        compatibilityMode = !a11yActive && AdvancedProtection.blocksBioKey(context)
        usageAccess = ProtectionEngine.hasUsageAccess(context)
        overlay = ProtectionEngine.canDrawOverlays(context)
        asksFor = UnlockMethods.asksFor(context)
        cameBackWithoutService = triedAccessibility && !a11yActive
        val usesAccessibility = !a11yActive && !compatibilityMode
        restricted = if (usesAccessibility) RestrictedSettingsHelper.verdict(context) else RestrictedSettingsHelper.Verdict.NOT_BLOCKED
        opensOwnPage = usesAccessibility && IntentNavigationHelper.opensOwnAccessibilityPage(context)
        onPauseOrDispose { }
    }

    // Nothing here takes focus by itself. Without touch (a phone just started, a keyboard
    // attached) Android hands a new window's first control focus, and the reset card's expander
    // came up drawn as focused before anything was pressed; let go, it was handed straight back.
    // So the page itself holds it, which draws nothing, as the screen comes up, comes back or
    // its window gains focus; a key that moves focus goes on from there to the first control.
    val pageFocus = remember { FocusRequester() }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumes++
        onPauseOrDispose { }
    }
    LaunchedEffect(resumes, windowFocused) {
        if (!windowFocused) return@LaunchedEffect
        repeat(2) { withFrameNanos { } }
        runCatching { pageFocus.requestFocus() }
    }

    val openAccessibility = {
        disclosures.request(Disclosure.ACCESSIBILITY) {
            if (IntentNavigationHelper.openAccessibilitySettings(context)) triedAccessibility = true
        }
    }
    val serviceName = stringResource(R.string.accessibility_service_label)
    val openCompatibility = { disclosures.request(Disclosure.COMPATIBILITY) { IntentNavigationHelper.openNextCompatibilityPermission(context) } }

    val steps = buildList {
        if (compatibilityMode) {
            add(
                Step(
                    Icons.Default.AppSettingsAlt, stringResource(R.string.setup_usage_access_title),
                    stringResource(R.string.setup_usage_access_text),
                    done = usageAccess, doneText = stringResource(R.string.setup_usage_access_done), onClick = openCompatibility
                )
            )
            add(
                Step(
                    Icons.Default.Layers, stringResource(R.string.setup_overlay_title),
                    stringResource(R.string.setup_overlay_text),
                    done = overlay, doneText = stringResource(R.string.setup_overlay_done), onClick = openCompatibility
                )
            )
        } else {
            add(
                Step(
                    Icons.Default.Accessibility, stringResource(R.string.setup_accessibility_title),
                    // Where the list opens rather than BioKey's own page, which entry to tap; back
                    // without it on, that it isn't yet.
                    when {
                        cameBackWithoutService && opensOwnPage -> stringResource(R.string.setup_accessibility_text_not_on)
                        cameBackWithoutService -> stringResource(R.string.setup_accessibility_text_not_on_list, serviceName)
                        opensOwnPage -> stringResource(R.string.setup_accessibility_text)
                        else -> stringResource(R.string.setup_accessibility_text_list, serviceName)
                    },
                    done = a11yActive, doneText = stringResource(R.string.setup_accessibility_done), onClick = openAccessibility
                )
            )
        }
        if (!compatibilityMode && !a11yActive && RestrictedSettingsHelper.showStep(restricted, cameBackWithoutService)) {
            val sure = restricted == RestrictedSettingsHelper.Verdict.BLOCKED
            add(
                Step(
                    Icons.Default.Policy,
                    stringResource(if (sure) R.string.setup_restricted_title else R.string.setup_restricted_help_title),
                    // Android offers the ⋮ item only once its "restricted setting" dialog has been seen.
                    stringResource(if (sure) R.string.setup_restricted_text else R.string.setup_restricted_help_text),
                    done = false,
                    part = SetupPart.HELP,
                    ifNeeded = !sure
                ) { IntentNavigationHelper.openAppDetailsSettings(context) }
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
                    // Under its own "Optional" heading, so its title needn't say so.
                    Icons.Default.Notifications, stringResource(R.string.setup_notifications_step_title),
                    stringResource(notificationsText(cleared != null, notifications.allowed, Build.VERSION.SDK_INT)),
                    done = notifications.allowed,
                    doneText = stringResource(R.string.setup_notifications_done),
                    part = SetupPart.OPTIONAL,
                    // Android's own question from 13 on; before that, only its settings page.
                    opensExternally = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU,
                    onClick = notifications::requestOrOpenSettings
                )
            )
        }
        add(
            Step(
                Icons.Default.Fingerprint, stringResource(R.string.setup_confirm_title),
                // BioKey's own words for what the step is for, not the prompt's instruction.
                stringResource(R.string.setup_confirm_text, asksFor),
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
            .focusRequester(pageFocus)
            .focusTarget()
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

        // The button's bar, as tall as it is (the button, its margins, the navigation bar; more
        // with large text): the list ends that far up, so its last line scrolls clear of it.
        var barHeight by remember { mutableIntStateOf(0) }
        val density = LocalDensity.current
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = ReadingColumnWidth)
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(bottom = with(density) { barHeight.toDp() } + 8.dp),
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
                stringResource(R.string.setup_subtitle, asksFor),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp).onGloballyPositioned { quietZones[2] = it.boundsInRoot() }
            )
            if (cleared != null) {
                Spacer(Modifier.height(20.dp))
                // Why BioKey is being set up again, lined up with the steps below: what happened in
                // a line or two, the rest (what waits a day, and when a restore is possible again)
                // behind an expander, so the steps stay in view.
                val what = if (cleared.known) stringResource(R.string.setup_storage_cleared, attemptTime(context, cleared.after))
                else stringResource(R.string.setup_storage_cleared_unknown)
                ResetCard(
                    headline = stringResource(R.string.storage_cleared_notification_title),
                    what = what,
                    wait = restoreWaitText(),
                    modifier = Modifier.padding(horizontal = GroupInset)
                )
            }
            Spacer(Modifier.height(28.dp))
            // The steps setup needs, numbered; help for one of them on a card of its own under
            // them; the optional step under a heading of its own. The count is of the numbered ones.
            val required = steps.filter { it.part == SetupPart.STEP }
            val help = steps.filter { it.part == SetupPart.HELP }
            val optional = steps.filter { it.part == SetupPart.OPTIONAL }
            // The step to do now: the first one not done (the main button below does it).
            val current = required.indexOfFirst { !it.done }
            Text(
                pluralStringResource(R.plurals.setup_progress, required.size, required.count { it.done }, required.size),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = GroupInset + 16.dp)
                    .padding(bottom = 8.dp)
                    // Read out as a step is done, back from Android's settings.
                    .semantics { liveRegion = LiveRegionMode.Polite }
                    .testTag(SETUP_PROGRESS_TAG)
            )
            Group(count = required.size) { i ->
                val step = required[i]
                val state = when {
                    step.done -> StepState.DONE
                    i == current -> StepState.CURRENT
                    // Only the last step is ever without an action: until the ones above are
                    // done, or while it runs (then it's the current one).
                    step.onClick == null -> StepState.BLOCKED
                    else -> StepState.LATER
                }
                StepRow(
                    step = step,
                    index = i,
                    count = required.size,
                    // A number in its circle, a tick once done: a sequence at a glance.
                    leading = { StepBadge(state, number = i + 1) },
                    // One that can't be done yet looks it, but stays readable: it says what's coming.
                    modifier = (if (state == StepState.BLOCKED) Modifier.alpha(0.85f) else Modifier).testTag(SETUP_STEP_TAG),
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
                )
            }
            help.forEach { step ->
                Spacer(Modifier.height(12.dp))
                HelpCard(step, stateDescription = if (step.ifNeeded) stringResource(R.string.setup_step_if_needed) else null)
            }
            if (optional.isNotEmpty()) {
                SectionHeader(stringResource(R.string.setup_optional_heading), Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Group(count = optional.size) { i ->
                    val step = optional[i]
                    StepRow(
                        step = step,
                        index = i,
                        count = optional.size,
                        // Its own icon, not a number: it isn't one of the steps counted above.
                        leading = { StepBadge(if (step.done) StepState.DONE else StepState.LATER, icon = step.icon) },
                        modifier = Modifier.testTag(SETUP_OPTIONAL_TAG),
                        stateDescription = stringResource(if (step.done) R.string.setup_step_done else R.string.setup_step_optional)
                    )
                }
            }
            // Before finishing, what finishing does besides: auto-sort locks the apps it knows.
            if (autoSortOn) {
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = GroupInset + 16.dp)
                        .semantics(mergeDescendants = true) { }
                ) {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 1.dp).size(20.dp.scaledWithText())
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        stringResource(R.string.setup_auto_sort_note, stringResource(R.string.settings_auto_sort_title)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            // Always there, so an error coming up doesn't move anything above it.
            Spacer(Modifier.height(16.dp))
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

        // The button on a bar of the page's own colour, faded in at its top edge: the list scrolls
        // under it without showing through.
        val page = MaterialTheme.colorScheme.surface
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .onSizeChanged { barHeight = it.height }
        ) {
            Spacer(
                Modifier
                    .fillMaxWidth()
                    .height(24.dp)
                    .background(Brush.verticalGradient(listOf(page.copy(alpha = 0f), page)))
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(page)
                    .navigationBarsPadding()
                    .padding(start = 32.dp, end = 32.dp, bottom = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Button(
                    onClick = {
                        when {
                            // No screen lock yet: setting one is what's next, not finishing again.
                            ready && needsScreenLock -> IntentNavigationHelper.openScreenLockSetup(context)
                            ready -> finish()
                            compatibilityMode -> openCompatibility()
                            else -> openAccessibility()
                        }
                    },
                    enabled = !isAuthenticating,
                    shape = CircleShape,
                    modifier = Modifier
                        .widthIn(max = 360.dp)
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .springPress(enabled = !isAuthenticating)
                ) {
                    Icon(
                        if (ready && !needsScreenLock) Icons.Default.Fingerprint else Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResource(
                            when {
                                isAuthenticating -> R.string.setup_button_confirming
                                ready && needsScreenLock -> R.string.setup_screen_lock_title
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
    }
}

/**
 * What the notifications step says before it's done: after a reset, that they're off now (not
 * how they got that way: the history went with BioKey's storage, and they may never have been
 * allowed), from Android 13, where they are a permission.
 */
@StringRes
internal fun notificationsText(cleared: Boolean, allowed: Boolean, sdk: Int): Int =
    if (cleared && !allowed && sdk >= Build.VERSION_CODES.TIRAMISU) R.string.setup_notifications_text_reset
    else R.string.setup_notifications_text

/** [opensExternally]: tapping it leaves BioKey for a system settings page. */
private data class Step(
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
    val done: Boolean,
    /** What it says once done, in [subtitle]'s place: not how to do what's been done. */
    val doneText: String? = null,
    val opensExternally: Boolean = true,
    /** A numbered step, help for one, or the optional one: each drawn as what it is ([SetupPart]). */
    val part: SetupPart = SetupPart.STEP,
    /** Help only needed if something went wrong ([SetupPart.HELP]): said as such. */
    val ifNeeded: Boolean = false,
    val onClick: (() -> Unit)?
)

/** What a card in setup's list is: a numbered step it needs, help for one, or the optional step. */
internal enum class SetupPart { STEP, HELP, OPTIONAL }

/** Test tags: each numbered step, the optional one, the help card and the count over the steps. */
internal const val SETUP_STEP_TAG = "setup_step"
internal const val SETUP_OPTIONAL_TAG = "setup_optional"
internal const val SETUP_HELP_TAG = "setup_help"
internal const val SETUP_PROGRESS_TAG = "setup_progress"

/**
 * When a backup can be restored (and a passphrase set) after a reset, and why it waits. The day's
 * hold starts when the reset is recorded, which is only once setup is done
 * (ProtectionGaps records it only once BioKey is set up): until then, it's a day from finishing, said
 * as the time it would end if setup were finished now, kept current on the minute. Shown again
 * after setup (protection's access lost within the day), the hold's own end.
 */
@Composable
private fun restoreWaitText(): String {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                now = System.currentTimeMillis()
                delay(60_000L - now % 60_000L + 50L)
            }
        }
    }
    val running = remember(now) {
        UnlockAttempts.heldUntil(context)?.takeIf { UnlockAttempts.heldBy(context) == ProtectionGaps.Kind.DATA_CLEARED }
    }
    return if (running != null) stringResource(R.string.setup_restore_wait_running, attemptTime(context, running))
    else stringResource(R.string.setup_restore_wait, attemptTime(context, now + UnlockAttempts.BIOMETRIC_HOLD_MS))
}

/**
 * A card of setup's list: a numbered step or the optional one. A trailing mark says what a tap
 * does: one that leaves BioKey for Android's settings, an arrow out; one that asks here
 * (Android's question, the face or fingerprint check), a chevron.
 */
@Composable
private fun StepRow(
    step: Step,
    index: Int,
    count: Int,
    leading: @Composable () -> Unit,
    modifier: Modifier,
    stateDescription: String
) {
    val s = step
    GroupRow(
        index = index,
        count = count,
        title = s.title,
        subtitle = if (s.done) s.doneText else s.subtitle,
        leading = leading,
        onClick = s.onClick.takeIf { !s.done },
        modifier = modifier,
        stateDescription = stateDescription
    ) {
        if (!s.done && s.onClick != null) ActionMark(s.opensExternally)
    }
}

@Composable
private fun ActionMark(opensExternally: Boolean) {
    Icon(
        if (opensExternally) Icons.AutoMirrored.Filled.OpenInNew else Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(if (opensExternally) 20.dp else 24.dp)
    )
}

/**
 * Help for a step (Android blocking the accessibility switch): not a step, so no number and no
 * place in the count, and drawn as help is, an outlined card with a question mark, under the steps.
 */
@Composable
private fun HelpCard(step: Step, stateDescription: String?) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = { step.onClick?.invoke() },
        shape = RoundedCornerShape(24.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = GroupInset)
            .testTag(SETUP_HELP_TAG)
            .semantics(mergeDescendants = true) { if (stateDescription != null) this.stateDescription = stateDescription }
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp.scaledWithText()), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = null, tint = colors.primary, modifier = Modifier.size(24.dp.scaledWithText()))
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(step.title, style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
                Text(step.subtitle, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
            ActionMark(step.opensExternally)
        }
    }
}

/** Where a setup step stands: done, the one to do now, one after it, or one that can't be done yet. */
private enum class StepState { DONE, CURRENT, LATER, BLOCKED }

/**
 * A step's circle: a tick on the primary colour when done; else its [number] (a numbered step) or
 * [icon] (the optional one); the step to do now in the primary container with a ring round it; the
 * rest in a quiet neutral circle.
 */
@Composable
private fun StepBadge(state: StepState, number: Int? = null, icon: ImageVector? = null) {
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
        when {
            state == StepState.DONE -> Icon(Icons.Default.Check, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.55f))
            number != null -> Text(
                NumberFormat.getIntegerInstance().format(number),
                style = MaterialTheme.typography.titleMedium,
                color = tint,
                // Grows with the text, up to the circle's own growth: never larger than its circle.
                fontSize = MaterialTheme.typography.titleMedium.fontSize.let { if (LocalDensity.current.fontScale > 1.5f) it * (1.5f / LocalDensity.current.fontScale) else it }
            )
            icon != null -> Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.55f))
        }
    }
}
