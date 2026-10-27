package com.wisp.app.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wisp.app.R

/**
 * The composer's secondary panel: every option that is not core to writing a
 * note (NSFW, Proof of Work, scheduling, private reply, save draft) lives
 * here instead of crowding the attach row. Core actions (media, poll) stay
 * one tap away in the composer itself; this sheet is the "…" overflow.
 *
 * Rows reflect current state via switches and fire the same ViewModel calls
 * the old inline icon buttons did, so behavior is unchanged — only exposure.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposerMoreSheet(
    explicit: Boolean,
    onToggleExplicit: () -> Unit,
    powEnabled: Boolean,
    powAvailable: Boolean,
    onTogglePow: () -> Unit,
    scheduleEnabled: Boolean,
    scheduleSummary: String?,
    onToggleSchedule: () -> Unit,
    privateReplyVisible: Boolean,
    privateReplyEnabled: Boolean,
    privateReplyLocked: Boolean,
    onTogglePrivateReply: () -> Unit,
    saveDraftVisible: Boolean,
    onSaveDraft: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(bottom = 16.dp)
        ) {
            Text(
                text = "More options",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )

            ComposerMoreRow(
                icon = Icons.Outlined.Warning,
                title = "Mark as NSFW",
                subtitle = if (explicit) stringResource(R.string.content_marked_nsfw) else null,
                checked = explicit,
                onToggle = onToggleExplicit
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

            ComposerMoreRow(
                icon = Icons.Outlined.Shield,
                title = "Proof of Work",
                subtitle = if (powEnabled) "On" else null,
                checked = powEnabled,
                enabled = powAvailable,
                onToggle = onTogglePow
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

            ComposerMoreRow(
                icon = Icons.Outlined.Schedule,
                title = "Schedule post",
                subtitle = if (scheduleEnabled) scheduleSummary else null,
                checked = scheduleEnabled,
                onToggle = onToggleSchedule
            )

            if (privateReplyVisible) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                ComposerMoreRow(
                    icon = Icons.Outlined.VisibilityOff,
                    title = "Private reply",
                    subtitle = if (privateReplyEnabled) "On" else null,
                    checked = privateReplyEnabled,
                    enabled = !privateReplyLocked,
                    onToggle = onTogglePrivateReply
                )
            }

            if (saveDraftVisible) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                ComposerMoreRow(
                    icon = Icons.Outlined.Save,
                    title = stringResource(R.string.btn_save_draft),
                    subtitle = null,
                    checked = null,
                    onToggle = onSaveDraft
                )
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * One sheet row: leading icon, label, optional status subtitle, trailing
 * switch. Rows without a boolean state (save draft) pass [checked] = null and
 * render a plain row that fires [onToggle] once.
 */
@Composable
private fun ComposerMoreRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    checked: Boolean?,
    enabled: Boolean = true,
    onToggle: () -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled) { onToggle() }
                .padding(horizontal = 20.dp, vertical = 14.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                       else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface
                           else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (checked != null) {
                Switch(
                    checked = checked,
                    enabled = enabled,
                    onCheckedChange = { if (it != checked) onToggle() }
                )
            }
        }
    }
}
