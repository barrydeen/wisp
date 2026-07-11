package com.wisp.app.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wisp.app.R
import com.wisp.app.nostr.ProfileData
import com.wisp.app.nostr.toNpub
import com.wisp.app.repo.AccountInfo
import com.wisp.app.repo.SigningMode

/**
 * Modal bottom sheet for switching between signed-in accounts or adding a new
 * one. Replaces the old inline expand/collapse panel in [WispDrawerContent] —
 * a bottom sheet is easier to reach, easier to dismiss, and gives the "add
 * account" action a full-width, unambiguous row instead of competing with a
 * cramped drawer header.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSwitcherSheet(
    accounts: List<AccountInfo>,
    activePubkey: String?,
    activeProfile: ProfileData?,
    onSwitchAccount: (String) -> Unit,
    onAddAccount: () -> Unit,
    onMoveAccount: (pubkeyHex: String, offset: Int) -> Unit = { _, _ -> },
    onDismiss: () -> Unit,
) {
    // skipPartiallyExpanded so the sheet opens fully expanded — guarantees
    // the pinned "Sign in with another account" row is visible above the
    // bottom nav/gesture chrome on first open, regardless of account count.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        // Cap the account list at ~45% of the screen so the "add account"
        // row stays pinned below it (and above the gesture/nav bar) even
        // with many accounts — the list scrolls within the cap instead of
        // growing the sheet past the viewport.
        val maxListHeight = LocalConfiguration.current.screenHeightDp.dp * 0.45f
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = stringResource(R.string.account_switcher_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))

            Column(
                modifier = Modifier
                    .heightIn(max = maxListHeight)
                    .verticalScroll(rememberScrollState())
            ) {
                accounts.forEachIndexed { index, account ->
                    val isActive = account.pubkeyHex == activePubkey
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !isActive) {
                                onSwitchAccount(account.pubkeyHex)
                                onDismiss()
                            }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // For the active account use the live profile picture; for others use cached AccountInfo
                        val pictureUrl = if (isActive) activeProfile?.picture else account.picture
                        ProfilePicture(url = pictureUrl, size = 40)
                        Spacer(Modifier.width(12.dp))
                        val displayText = if (isActive) {
                            activeProfile?.displayString ?: account.displayName ?: account.pubkeyHex.toNpub().let { it.take(16) + "..." }
                        } else {
                            account.displayName ?: account.pubkeyHex.toNpub().let { it.take(16) + "..." }
                        }
                        Text(
                            text = displayText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (account.signingMode == SigningMode.READ_ONLY) {
                            Icon(
                                Icons.Outlined.Visibility,
                                contentDescription = stringResource(R.string.cd_watch_only),
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                        if (isActive) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = stringResource(R.string.cd_active),
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                        if (accounts.size > 1) {
                            IconButton(
                                onClick = { onMoveAccount(account.pubkeyHex, -1) },
                                enabled = index > 0,
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    Icons.Filled.KeyboardArrowUp,
                                    contentDescription = stringResource(R.string.cd_move_account_up),
                                    modifier = Modifier.size(18.dp),
                                    tint = if (index > 0) MaterialTheme.colorScheme.onSurfaceVariant
                                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                                )
                            }
                            IconButton(
                                onClick = { onMoveAccount(account.pubkeyHex, 1) },
                                enabled = index < accounts.size - 1,
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    Icons.Filled.KeyboardArrowDown,
                                    contentDescription = stringResource(R.string.cd_move_account_down),
                                    modifier = Modifier.size(18.dp),
                                    tint = if (index < accounts.size - 1) MaterialTheme.colorScheme.onSurfaceVariant
                                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        onAddAccount()
                        onDismiss()
                    }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.account_switcher_add),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}
