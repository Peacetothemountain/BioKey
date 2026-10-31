package com.android.BioKey.feature.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.android.BioKey.R
import com.android.BioKey.ui.navigation.IntentNavigationHelper
import com.android.BioKey.ui.security.ProtectDialogWindow
import com.android.BioKey.ui.security.rejectObscuredTouches
import com.android.BioKey.ui.theme.Group
import com.android.BioKey.ui.theme.GroupRow
import com.android.BioKey.ui.theme.SectionHeader

/**
 * What locking [app] does ([LockExplainer]). [justLocked]: shown as the owner switched its lock on
 * (titled "<app> is locked", with "Don't show again"); otherwise opened from its options, to read
 * again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LockExplainSheet(
    app: AppItem,
    explanation: LockExplainer.Explanation,
    justLocked: Boolean,
    onDontShowAgain: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        ProtectDialogWindow()
        LockExplainContent(app, explanation, justLocked, onDontShowAgain, onDismiss)
    }
}

/** The sheet's contents, on their own for tests and screenshots. */
@Composable
internal fun LockExplainContent(
    app: AppItem,
    explanation: LockExplainer.Explanation,
    justLocked: Boolean,
    onDontShowAgain: () -> Unit,
    onDone: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .rejectObscuredTouches()
            .padding(bottom = 24.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            AppIcon(app, 56.dp)
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(if (justLocked) R.string.explain_title_locked else R.string.explain_title, app.label),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() }
            )
            Spacer(Modifier.height(4.dp))
            Text(
                explanation.howItOpens,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
        SectionHeader(stringResource(R.string.explain_still_section))
        // What a lock doesn't cover, each on its own row: read at a glance, nothing to tap.
        val rows = buildList<@Composable (Int, Int) -> Unit> {
            add { i, n ->
                // A lock doesn't hide them: Android's own per-app switch does, a tap away.
                val context = LocalContext.current
                GroupRow(
                    index = i,
                    count = n,
                    title = stringResource(R.string.explain_notifications_title),
                    subtitle = stringResource(R.string.explain_notifications_shown),
                    icon = Icons.Default.Notifications,
                    onClick = { IntentNavigationHelper.openAppNotificationSettings(context, app.packageName) },
                    trailing = {
                        Icon(
                            Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                )
            }
            if (explanation.hasWidgets) add { i, n ->
                GroupRow(
                    index = i,
                    count = n,
                    title = stringResource(R.string.explain_widgets_title),
                    subtitle = stringResource(R.string.explain_widgets_text),
                    icon = Icons.Default.Widgets
                )
            }
            add { i, n ->
                GroupRow(
                    index = i,
                    count = n,
                    title = stringResource(R.string.explain_access_title),
                    subtitle = stringResource(R.string.explain_access_text, app.label),
                    icon = Icons.Default.AutoAwesome
                )
            }
        }
        Group(count = rows.size) { i -> rows[i](i, rows.size) }

        val kind = explanation.dataKind
        if (kind != null && explanation.otherApps.isNotEmpty()) {
            SectionHeader(
                stringResource(
                    when (kind) {
                        LockExplainer.DataKind.PHOTOS -> R.string.explain_photos_title
                        LockExplainer.DataKind.FILES -> R.string.explain_files_title
                        LockExplainer.DataKind.MESSAGES -> R.string.explain_messages_title
                    }
                )
            )
            Text(
                stringResource(
                    when (kind) {
                        LockExplainer.DataKind.PHOTOS -> R.string.explain_photos_text
                        LockExplainer.DataKind.FILES -> R.string.explain_files_text
                        LockExplainer.DataKind.MESSAGES -> R.string.explain_messages_text
                    }
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 32.dp).padding(bottom = 12.dp)
            )
            var showAll by rememberSaveable(explanation.packageName) { mutableStateOf(false) }
            val shown = if (showAll) explanation.otherApps else explanation.otherApps.take(LockExplainer.SHOWN_APPS)
            Group(count = shown.size) { i ->
                GroupRow(index = i, count = shown.size, title = shown[i].label, leading = { AppIcon(shown[i]) })
            }
            val hidden = explanation.otherApps.size - shown.size
            if (hidden > 0) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), contentAlignment = Alignment.CenterEnd) {
                    TextButton(onClick = { showAll = true }) {
                        Text(pluralStringResource(R.plurals.explain_view_all, explanation.otherApps.size, explanation.otherApps.size))
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            itemVerticalAlignment = Alignment.CenterVertically
        ) {
            if (justLocked) TextButton(onClick = onDontShowAgain) { Text(stringResource(R.string.explain_dont_show_again)) }
            Button(onClick = onDone) { Text(stringResource(R.string.explain_done)) }
        }
    }
}
