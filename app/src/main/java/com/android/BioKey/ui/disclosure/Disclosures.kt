package com.android.BioKey.ui.disclosure

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
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.AppSettingsAlt
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.android.BioKey.R
import com.android.BioKey.core.security.LockStateTracker
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.ui.navigation.IntentNavigationHelper
import com.android.BioKey.ui.security.ProtectDialogWindow
import com.android.BioKey.ui.security.rejectObscuredTouches
import com.android.BioKey.ui.theme.IconBadge
import com.android.BioKey.ui.theme.BioKeySheet

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
            R.string.disclosure_accessibility_reads_app_lock_prompt,
            R.string.disclosure_accessibility_reads_settings_pages
                .takeIf { SecurityVault.selfProtectionAvailable }
        ),
        usedFor = listOfNotNull(
            R.string.disclosure_accessibility_used_covering,
            R.string.disclosure_accessibility_used_home_back,
            R.string.disclosure_accessibility_used_lock_screen,
            R.string.disclosure_accessibility_used_pip,
            R.string.disclosure_accessibility_used_recents_session,
            R.string.disclosure_accessibility_used_lock_this_app,
            R.string.disclosure_accessibility_used_self_protection
                .takeIf { SecurityVault.selfProtectionAvailable }
        )
    ),
    COMPATIBILITY(
        Icons.Default.AppSettingsAlt,
        R.string.disclosure_compatibility_title,
        reads = listOf(R.string.disclosure_compatibility_reads, R.string.disclosure_compatibility_reads_recent),
        usedFor = listOf(
            R.string.disclosure_compatibility_used_covering,
            R.string.disclosure_compatibility_used_drawing,
            R.string.disclosure_compatibility_used_lock_this_app
        )
    );
}

/** A bulleted line of the sheet, with [items] as a list of its own under it (none for most). */
internal class DisclosureLine(@StringRes val text: Int, val items: List<Int> = emptyList()) {
    /** Every string it shows, in order. */
    val ids: List<Int> get() = listOf(text) + items
}

private val KEEPS = listOf(
    DisclosureLine(R.string.disclosure_keeps_log),
    DisclosureLine(
        R.string.disclosure_keeps_security_activity,
        items = listOf(
            R.string.disclosure_keeps_security_activity_protection_off,
            R.string.disclosure_keeps_security_activity_biometrics,
            R.string.disclosure_keeps_security_activity_no_screen_lock,
            R.string.disclosure_keeps_security_activity_unproved,
            R.string.disclosure_keeps_security_activity_passphrase,
            R.string.disclosure_keeps_security_activity_sensor_unusable,
            R.string.disclosure_keeps_security_activity_failed_unlocks
        )
    ),
    DisclosureLine(R.string.disclosure_keeps_security_activity_changes),
    DisclosureLine(R.string.disclosure_keeps_security_activity_kept),
    DisclosureLine(R.string.disclosure_keeps_app_lock_seen),
    DisclosureLine(R.string.disclosure_keeps_signing_code),
    DisclosureLine(R.string.disclosure_keeps_reinstall)
)

private val NEVER = listOf(
    R.string.disclosure_never_leaves_phone,
    R.string.disclosure_never_reads
)

/** The sheet's section headings, in order. */
private val SECTIONS = listOf(
    R.string.disclosure_section_reads,
    R.string.disclosure_section_used_for,
    R.string.disclosure_section_keeps,
    R.string.disclosure_section_never
)

@Stable
class DisclosureGate internal constructor(
    /** Setup's gate: an Agree is remembered for the rest of setup ([DisclosureAgreements]). */
    private val context: Context? = null
) {
    internal var pending by mutableStateOf<Pair<Disclosure, () -> Unit>?>(null)

    /**
     * Shows [disclosure]; runs [onAgree] only if the user taps Agree. During setup, a disclosure
     * already agreed to in this setup, worded as it is now, isn't asked again: [onAgree] runs at once.
     */
    fun request(disclosure: Disclosure, onAgree: () -> Unit) {
        val setup = context
        if (setup != null && DisclosureAgreements.agreed(setup, disclosure)) {
            onAgree()
            return
        }
        pending = disclosure to onAgree
    }

    internal fun agreed(disclosure: Disclosure) {
        context?.let { DisclosureAgreements.record(it, disclosure) }
    }
}

/**
 * A gate whose sheet is shown wherever this is called. [rememberAgreement]: setup's, where an
 * Agree holds until setup is finished (or BioKey's storage is cleared, which takes it with
 * everything else); elsewhere every request shows the sheet.
 */
@Composable
fun rememberDisclosureGate(rememberAgreement: Boolean = false): DisclosureGate {
    val context = LocalContext.current
    val gate = remember(rememberAgreement) { DisclosureGate(context.applicationContext.takeIf { rememberAgreement }) }
    gate.pending?.let { (disclosure, onAgree) ->
        DisclosureSheet(
            disclosure,
            onAgree = {
                gate.pending = null
                gate.agreed(disclosure)
                onAgree()
            },
            onDecline = { gate.pending = null }
        )
    }
    return gate
}

/**
 * Agreements given during setup. Kept on disk, so leaving for Android's settings (and BioKey's
 * process going meanwhile) doesn't ask again; tied to the disclosure's words as shown, so a
 * change to what it says (an update, another language) asks again; and only while setup isn't
 * finished: once it is, they count for nothing, and a storage clear deletes them.
 */
object DisclosureAgreements {
    private const val PREFS = "biokey_disclosures"

    /** [disclosure] was agreed to in this setup, in the words it has now. */
    fun agreed(context: Context, disclosure: Disclosure): Boolean {
        if (SecurityVault.getInstance(context).isSetupCompleted()) return false
        return prefs(context).getString(disclosure.name, null) == fingerprint(context, disclosure)
    }

    fun record(context: Context, disclosure: Disclosure) {
        prefs(context).edit { putString(disclosure.name, fingerprint(context, disclosure)) }
    }

    /** Everything the sheet says, as one value: any change to it is a different disclosure. */
    internal fun fingerprint(context: Context, disclosure: Disclosure): String {
        val text = shown(disclosure).joinToString("\u0000") { context.getString(it) }
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Every string [disclosure]'s sheet shows as what it says (its buttons aside), in order. */
    internal fun shown(disclosure: Disclosure): List<Int> =
        listOf(disclosure.title) + SECTIONS + disclosure.reads + disclosure.usedFor + KEEPS.flatMap { it.ids } + NEVER + disclosure.turningOff

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * The widest the disclosure's text and setup's column run on a wide window (an unfolded foldable,
 * a tablet): lines stay a comfortable length to read.
 */
val ReadingColumnWidth = 520.dp

/**
 * Android 13+'s permission to post notifications, which protection alerts, security activity
 * and new-app offers need; without it they are silently dropped. A normal runtime permission, so
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
     * For moments BioKey chooses (the end of setup): never opens Settings.
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
        LockStateTracker.preAuthorizeSelfProtection()
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
    BioKeySheet(
        onDismissRequest = onDecline,
        // Hidden or fully open: never half-open, so the whole disclosure is in view.
        sheetState = rememberBottomSheetState(SheetValue.Hidden, setOf(SheetValue.Hidden, SheetValue.Expanded))
    ) {
        ProtectDialogWindow()
        Column(
            modifier = Modifier
                // As wide as setup's column behind it, at most, and centred in a wider sheet.
                .align(Alignment.CenterHorizontally)
                .widthIn(max = ReadingColumnWidth)
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
            DisclosureSection(stringResource(R.string.disclosure_section_reads), disclosure.reads.map(::DisclosureLine))
            DisclosureSection(stringResource(R.string.disclosure_section_used_for), disclosure.usedFor.map(::DisclosureLine))
            DisclosureSection(stringResource(R.string.disclosure_section_keeps), KEEPS)
            DisclosureSection(stringResource(R.string.disclosure_section_never), NEVER.map(::DisclosureLine))
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(disclosure.turningOff),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // The whole policy, before agreeing, not only after setup in Settings. Its words line up
            // with the text above; the button's own padding (and its 48dp target) reach out past them.
            val sheetContext = LocalContext.current
            TextButton(
                onClick = { IntentNavigationHelper.openWebPage(sheetContext, IntentNavigationHelper.PRIVACY_POLICY_URL) },
                modifier = Modifier.offset(x = -ButtonDefaults.TextButtonContentPadding.calculateLeftPadding(LayoutDirection.Ltr))
            ) {
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
private fun DisclosureSection(title: String, lines: List<DisclosureLine>) {
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
            Column {
                Text(stringResource(line.text), style = MaterialTheme.typography.bodyMedium)
                // Its own list, indented under it, with a hollow bullet.
                line.items.forEach { item ->
                    Row(Modifier.padding(top = 4.dp)) {
                        Text("◦", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(16.dp).clearAndSetSemantics { })
                        Text(stringResource(item), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(12.dp))
}
