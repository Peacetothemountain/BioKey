package com.android.BioKey.feature.main

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.annotation.StringRes
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.AppSettingsAlt
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.android.BioKey.R
import com.android.BioKey.core.security.LockStateTracker
import com.android.BioKey.core.security.ProtectDialogWindow
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.core.security.rejectObscuredTouches
import com.android.BioKey.ui.theme.IconBadge

/**
 * Google Play's prominent-disclosure rule: before BioKey sends the user to grant a sensitive
 * permission, it says in the app what it reads, what for, and what it never does, and continues
 * only on an explicit "Agree". Dismissing the sheet or pressing Back is not consent.
 */
enum class Disclosure(
    val icon: ImageVector,
    @StringRes val title: Int,
    val reads: List<Int>,
    val usedFor: List<Int>,
    /** How switching it off works, when that's more than Android's settings switch. */
    @StringRes val turningOff: Int = R.string.disclosure_turning_off
) {
    ACCESSIBILITY(
        Icons.Default.Accessibility,
        R.string.disclosure_accessibility_title,
        reads = listOfNotNull(
            R.string.disclosure_accessibility_reads_app_on_screen,
            R.string.disclosure_accessibility_reads_tap_labels,
            R.string.disclosure_accessibility_reads_recents_names,
            R.string.disclosure_accessibility_reads_window_titles,
            R.string.disclosure_accessibility_reads_settings_pages
                .takeIf { SecurityVault.selfProtectionAvailable }
        ),
        usedFor = listOfNotNull(
            R.string.disclosure_accessibility_used_covering,
            R.string.disclosure_accessibility_used_home_back,
            R.string.disclosure_accessibility_used_lock_screen,
            R.string.disclosure_accessibility_used_pip,
            R.string.disclosure_accessibility_used_recents_session,
            R.string.disclosure_accessibility_used_self_protection
                .takeIf { SecurityVault.selfProtectionAvailable }
        )
    ),
    COMPATIBILITY(
        Icons.Default.AppSettingsAlt,
        R.string.disclosure_compatibility_title,
        reads = listOf(R.string.disclosure_compatibility_reads),
        usedFor = listOf(
            R.string.disclosure_compatibility_used_covering,
            R.string.disclosure_compatibility_used_drawing
        )
    ),
    NOTIFICATIONS(
        Icons.Default.Notifications,
        R.string.disclosure_notifications_title,
        // Android hands every new notification to the listener; say so, not just the locked apps' ones.
        reads = listOf(R.string.disclosure_notifications_reads),
        usedFor = listOf(R.string.disclosure_notifications_used),
        // Hidden notifications are snoozed for up to a day, and only BioKey's access can bring them back early.
        turningOff = R.string.disclosure_notifications_turning_off
    );
}

private val KEEPS = listOf(
    R.string.disclosure_keeps_log,
    R.string.disclosure_keeps_security_activity
)

private val NEVER = listOf(
    R.string.disclosure_never_leaves_phone,
    R.string.disclosure_never_reads
)

@Stable
class DisclosureGate {
    internal var pending by mutableStateOf<Pair<Disclosure, () -> Unit>?>(null)

    /** Shows [disclosure]; runs [onAgree] only if the user taps Agree. */
    fun request(disclosure: Disclosure, onAgree: () -> Unit) {
        pending = disclosure to onAgree
    }
}

/** A gate whose sheet is shown wherever this is called. */
@Composable
fun rememberDisclosureGate(): DisclosureGate {
    val gate = remember { DisclosureGate() }
    gate.pending?.let { (disclosure, onAgree) ->
        DisclosureSheet(
            disclosure,
            onAgree = {
                gate.pending = null
                onAgree()
            },
            onDecline = { gate.pending = null }
        )
    }
    return gate
}

/**
 * Android 13+'s permission to post notifications, which protection alerts and the notification
 * shield's stand-ins need; without it both are silently dropped. A normal runtime permission, so
 * no disclosure: just Android's own question. [allowed] follows Android's setting, rechecked on
 * every resume.
 */
@Stable
class NotificationPermission internal constructor(private val context: Context, private val activity: Activity?) {
    var allowed by mutableStateOf(notificationsAllowed(context))
        private set

    internal lateinit var launcher: ActivityResultLauncher<String>
    private var then: (() -> Unit)? = null
    private var thenOnResume: (() -> Unit)? = null
    private var userAsked = false
    private var rationaleBefore = false

    /**
     * Asks Android's question if it still may, then runs [then] once BioKey is back on screen.
     * For moments BioKey chooses (end of setup, notification access just granted): never opens Settings.
     */
    fun request(then: () -> Unit = {}) {
        if (allowed || !canAsk()) {
            then()
            return
        }
        this.then = then
        userAsked = false
        ask()
    }

    /** The user tapped "allow": asks, or opens BioKey's notification settings once Android won't ask any more. */
    fun requestOrOpenSettings() {
        if (allowed) return
        if (!canAsk()) {
            openSettings()
            return
        }
        then = null
        userAsked = true
        rationaleBefore = rationale()
        ask()
    }

    internal fun onResult(granted: Boolean, resumed: Boolean) {
        LockStateTracker.clearSelfProtection()
        allowed = notificationsAllowed(context)
        // Android answered without asking: it was turned down for good, and only Settings can change it.
        if (!granted && userAsked && !rationaleBefore && !rationale()) openSettings()
        userAsked = false
        val next = then ?: return
        then = null
        if (resumed) next() else thenOnResume = next
    }

    internal fun onResume() {
        allowed = notificationsAllowed(context)
        thenOnResume?.let {
            thenOnResume = null
            it()
        }
    }

    /** Android 13+ and not granted yet. Earlier versions, or a granted one switched off, only change in Settings. */
    private fun canAsk() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

    private fun ask() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        // Android's question names BioKey, so self-protection would take it for a page that could
        // switch BioKey off and ask for authentication over it. Asked from inside BioKey's session,
        // it's pre-authorised like the settings pages BioKey opens; the answer ends that.
        if (LockStateTracker.hasActiveOwnerSession()) {
            LockStateTracker.setSelfProtectionGranted(granted = true, visitedGuardedScreen = false)
        }
        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun rationale() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && activity != null &&
        ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS)

    private fun openSettings() = IntentNavigationHelper.launchFallbackCascade(
        context,
        listOf(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        )
    )
}

private fun notificationsAllowed(context: Context) = NotificationManagerCompat.from(context).areNotificationsEnabled()

@Composable
fun rememberNotificationPermission(): NotificationPermission {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val permission = remember { NotificationPermission(context, activity) }
    permission.launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // The answer can arrive just before BioKey resumes; a follow-up prompt has to wait for that.
        permission.onResult(granted, resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    LifecycleResumeEffect(permission) {
        permission.onResume()
        onPauseOrDispose { }
    }
    return permission
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DisclosureSheet(disclosure: Disclosure, onAgree: () -> Unit, onDecline: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDecline,
        // Hidden or fully open: never half-open, so the whole disclosure is in view.
        sheetState = rememberBottomSheetState(SheetValue.Hidden, setOf(SheetValue.Hidden, SheetValue.Expanded))
    ) {
        ProtectDialogWindow()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .rejectObscuredTouches()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp)
                .navigationBarsPadding()
                .padding(bottom = 24.dp)
        ) {
            IconBadge(disclosure.icon, size = 52.dp)
            Spacer(Modifier.height(16.dp))
            Text(stringResource(disclosure.title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(20.dp))
            DisclosureSection(stringResource(R.string.disclosure_section_reads), disclosure.reads)
            DisclosureSection(stringResource(R.string.disclosure_section_used_for), disclosure.usedFor)
            DisclosureSection(stringResource(R.string.disclosure_section_keeps), KEEPS)
            DisclosureSection(stringResource(R.string.disclosure_section_never), NEVER)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(disclosure.turningOff),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // The whole policy, before agreeing, not only after setup in Settings.
            val sheetContext = LocalContext.current
            TextButton(onClick = { IntentNavigationHelper.openWebPage(sheetContext, IntentNavigationHelper.PRIVACY_POLICY_URL) }) {
                Text(stringResource(R.string.disclosure_read_privacy_policy))
            }
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onDecline, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.common_not_now)) }
                Button(onClick = onAgree, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.disclosure_agree)) }
            }
        }
    }
}

@Composable
private fun DisclosureSection(title: String, lines: List<Int>) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.semantics { heading() }
    )
    Spacer(Modifier.height(6.dp))
    lines.forEach { line ->
        Row(Modifier.padding(bottom = 6.dp)) {
            // Only drawn: screen readers go from line to line, not stopping at each bullet.
            Text("•", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(16.dp).clearAndSetSemantics { })
            Text(stringResource(line), style = MaterialTheme.typography.bodyMedium)
        }
    }
    Spacer(Modifier.height(12.dp))
}
