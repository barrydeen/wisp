package cooking.zap.app.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cooking.zap.app.R
import cooking.zap.app.nostr.ProfileData
import cooking.zap.app.ui.theme.WispThemeColors

/**
 * "Online Now" members sheet — in-network + global online counts with a grid
 * of tappable avatars routing to profiles. Lifted verbatim from the old Feed
 * top-bar online chip so the capability keeps a home after the chip moves into
 * the drawer's Advanced "Network" section.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun OnlineNowSheet(
    onlinePubkeys: List<String>,
    globalOnlineCount: Int?,
    profileProvider: (String) -> ProfileData?,
    onProfileClick: (String) -> Unit,
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
                stringResource(R.string.online_now),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            val greenColor = WispThemeColors.repostColor
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(modifier = Modifier.size(8.dp)) { drawCircle(color = greenColor) }
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.online_in_network, onlinePubkeys.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            if (globalOnlineCount != null) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Canvas(modifier = Modifier.size(8.dp)) { drawCircle(color = greenColor) }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.online_all_nostr, globalOnlineCount),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                onlinePubkeys.forEach { pubkey ->
                    val profile = profileProvider(pubkey)
                    ProfilePicture(
                        url = profile?.picture,
                        size = 44,
                        onClick = { onProfileClick(pubkey) },
                    )
                }
            }
        }
    }
}
