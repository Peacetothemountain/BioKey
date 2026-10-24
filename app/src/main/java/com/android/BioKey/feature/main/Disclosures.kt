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
import com.android.BioKey.core.security.LockStateTracker
import com.android.BioKey.core.security.ProtectDialogWindow
import com.android.BioKey.core.security.rejectObscuredTouches
import com.android.BioKey.ui.theme.IconBadge

/**
 * Google Play's prominent-disclosure rule: before BioKey sends the user to grant a sensitive
 * permission, it says in the app what it reads, what for, and what it never does, and continues
 * only on an explicit "Agree". Dismissing the sheet or pressing Back is not consent.
 */
enum class Disclosure(
    val icon: ImageVector,
    val title: String,
    val reads: List<String>,
    val usedFor: List<String>,
    /** How switching it off works, when that's more than Android's settings switch. */
    val turningOff: String = "You can turn this off at any time in Android settings."
) {
    ACCESSIBILITY(
        Icons.Default.Accessibility,
        "BioKey uses the Accessibility service",
        reads = listOf(
            "Which app is on screen",
            "The label of what you tap on the home screen, in its search or in Recents",
            "The names of apps on Recents cards, while Recents is open and an app you've locked is unlocked",
            "The text of system Settings pages about BioKey itself (App info, Accessibility, notification access, uninstall)"
        ),
        usedFor = listOf(
            "Covering apps you've locked until you authenticate, even before they open",
            "Ending a locked app's session when you close it from Recents",
            "Asking for authentication before BioKey can be switched off, if you turn that on"
        )
    ),
    COMPATIBILITY(
        Icons.Default.AppSettingsAlt,
        "BioKey uses usage access and display over other apps",
        reads = listOf("Which app was just opened, and whether a locked app is still on screen (app usage events on this phone)"),
        usedFor = listOf(
            "Covering apps you've locked until you authenticate, without the Accessibility service",
            "Drawing BioKey's lock cover over a locked app as it opens"
        )
    ),
    NOTIFICATIONS(
        Icons.Default.Notifications,
        "BioKey uses notification access",
        reads = listOf("Notifications from apps you've locked"),
        usedFor = listOf("Hiding their content until you unlock the app, then putting them back"),
        // Hidden notifications are snoozed for up to a day, and only BioKey's access can bring them back early.
        turningOff = "You can turn this off at any time in Android settings. To bring hidden notifications back, " +
            "switch off “Hide notification content” in BioKey's settings first: revoking notification access " +
            "instead can leave them hidden for up to a day."
    );
}

private val NEVER = listOf(
    "Nothing BioKey sees leaves this phone: it has no internet access. It keeps a short on-phone log of its own " +
        "lock decisions (which locked apps it locked or unlocked) for 3 days, which you can turn off in Settings.",
    "BioKey never reads what you type, your messages, or the content of the apps you use."
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
            Text(disclosure.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(20.dp))
            DisclosureSection("What BioKey reads", disclosure.reads)
            DisclosureSection("What it's used for", disclosure.usedFor)
            DisclosureSection("What it never does", NEVER)
            Spacer(Modifier.height(8.dp))
            Text(
                disclosure.turningOff,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onDecline, modifier = Modifier.weight(1f)) { Text("Not now") }
                Button(onClick = onAgree, modifier = Modifier.weight(1f)) { Text("Agree") }
            }
        }
    }
}

@Composable
private fun DisclosureSection(title: String, lines: List<String>) {
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
            Text(line, style = MaterialTheme.typography.bodyMedium)
        }
    }
    Spacer(Modifier.height(12.dp))
}
