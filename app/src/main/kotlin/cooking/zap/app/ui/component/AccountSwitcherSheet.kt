package cooking.zap.app.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cooking.zap.app.R
import cooking.zap.app.nostr.ProfileData
import cooking.zap.app.nostr.toNpub
import cooking.zap.app.repo.AccountInfo
import cooking.zap.app.repo.SigningMode

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
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding(),
        ) {
            Text(
                text = stringResource(R.string.account_switcher_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))

            accounts.forEach { account ->
                val isActive = account.pubkeyHex == activePubkey
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !isActive) {
                            onSwitchAccount(account.pubkeyHex)
                            onDismiss()
                        }
                        .padding(vertical = 10.dp),
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
