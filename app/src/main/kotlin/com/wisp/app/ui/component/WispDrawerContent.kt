package com.wisp.app.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.painterResource
import com.wisp.app.R
import com.wisp.app.relay.TorStatus
import androidx.compose.material.icons.outlined.CurrencyBitcoin
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wisp.app.nostr.ProfileData


@Composable
fun WispDrawerContent(
    profile: ProfileData?,
    pubkey: String?,
    isDarkTheme: Boolean = true,
    onToggleTheme: () -> Unit = {},
    isTorEnabled: Boolean = false,
    torStatus: TorStatus = TorStatus.DISABLED,
    onToggleTor: (Boolean) -> Unit = {},
    onProfile: () -> Unit,
    onFeed: () -> Unit,
    onSearch: () -> Unit,
    onMessages: () -> Unit,
    onWallet: () -> Unit,
    onLists: () -> Unit = {},
    onDrafts: () -> Unit = {},
    onMediaServers: () -> Unit,
    onKeys: () -> Unit = {},
    onSafety: () -> Unit = {},
    onCustomEmojis: () -> Unit = {},
    onConsole: () -> Unit = {},
    onRelaySettings: () -> Unit,
    onLogout: () -> Unit
) {
    ModalDrawerSheet(
        drawerContainerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        var showQrDialog by remember { mutableStateOf(false) }
        var showLightningDialog by remember { mutableStateOf(false) }

        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                ProfilePicture(url = profile?.picture, size = 64)
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = { onToggleTor(!isTorEnabled) }) {
                    Box(contentAlignment = Alignment.Center) {
                        if (torStatus == TorStatus.STARTING) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                                strokeCap = StrokeCap.Round
                            )
                        } else {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_tor_onion),
                                contentDescription = "Toggle Tor",
                                modifier = Modifier.size(24.dp),
                                tint = when (torStatus) {
                                    TorStatus.CONNECTED -> MaterialTheme.colorScheme.primary
                                    TorStatus.ERROR -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }
                }
                IconButton(onClick = onToggleTheme) {
                    Icon(
                        if (isDarkTheme) Icons.Outlined.DarkMode else Icons.Outlined.LightMode,
                        contentDescription = "Toggle theme",
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { showQrDialog = true }) {
                    Icon(
                        Icons.Outlined.QrCode2,
                        contentDescription = "Show QR code",
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (profile?.lud16 != null) {
                    IconButton(onClick = { showLightningDialog = true }) {
                        Icon(
                            Icons.Outlined.CurrencyBitcoin,
                            contentDescription = "Lightning address",
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Text(
                text = profile?.displayString ?: "Anonymous",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (!profile?.nip05.isNullOrBlank()) {
                Text(
                    text = profile!!.nip05!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            } else if (pubkey != null) {
                Text(
                    text = pubkey.take(16) + "...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (showQrDialog && pubkey != null) {
            QrCodeDialog(pubkeyHex = pubkey, onDismiss = { showQrDialog = false })
        }
        if (showLightningDialog && profile?.lud16 != null) {
            LightningQrDialog(lud16 = profile.lud16, onDismiss = { showLightningDialog = false })
        }

        Spacer(modifier = Modifier.height(8.dp))

        NavigationDrawerItem(
            icon = { Icon(Icons.Outlined.Person, contentDescription = null) },
            label = { Text("My Profile") },
            selected = false,
            onClick = onProfile,
            modifier = Modifier.padding(horizontal = 12.dp)
        )
        NavigationDrawerItem(
            icon = { Icon(Icons.Outlined.Home, contentDescription = null) },
            label = { Text("Feeds") },
            selected = false,
            onClick = onFeed,
            modifier = Modifier.padding(horizontal = 12.dp)
        )
        NavigationDrawerItem(
            icon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            label = { Text("Search") },
            selected = false,
            onClick = onSearch,
            modifier = Modifier.padding(horizontal = 12.dp)
        )
        NavigationDrawerItem(
            icon = { Icon(Icons.Outlined.Email, contentDescription = null) },
            label = { Text("Messages") },
            selected = false,
            onClick = onMessages,
            modifier = Modifier.padding(horizontal = 12.dp)
        )
        NavigationDrawerItem(
            icon = { Icon(Icons.Outlined.CurrencyBitcoin, contentDescription = null) },
            label = { Text("Wallet") },
            selected = false,
            onClick = onWallet,
            modifier = Modifier.padding(horizontal = 12.dp)
        )
        NavigationDrawerItem(
            icon = { Icon(Icons.Outlined.FormatListBulleted, contentDescription = null) },
            label = { Text("Lists") },
            selected = false,
            onClick = onLists,
            modifier = Modifier.padding(horizontal = 12.dp)
        )
        NavigationDrawerItem(
            icon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
            label = { Text("Drafts") },
            selected = false,
            onClick = onDrafts,
            modifier = Modifier.padding(horizontal = 12.dp)
        )
        var settingsExpanded by remember { mutableStateOf(false) }
        NavigationDrawerItem(
            icon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
            label = { Text("Settings") },
            badge = {
                Icon(
                    if (settingsExpanded) Icons.Outlined.KeyboardArrowDown
                    else Icons.Outlined.KeyboardArrowRight,
                    contentDescription = null
                )
            },
            selected = false,
            onClick = { settingsExpanded = !settingsExpanded },
            modifier = Modifier.padding(horizontal = 12.dp)
        )
        AnimatedVisibility(visible = settingsExpanded) {
            Column {
                NavigationDrawerItem(
                    icon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                    label = { Text("Relays") },
                    selected = false,
                    onClick = onRelaySettings,
                    modifier = Modifier.padding(start = 36.dp, end = 12.dp)
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Outlined.Cloud, contentDescription = null) },
                    label = { Text("Media Servers") },
                    selected = false,
                    onClick = onMediaServers,
                    modifier = Modifier.padding(start = 36.dp, end = 12.dp)
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Outlined.Key, contentDescription = null) },
                    label = { Text("Keys") },
                    selected = false,
                    onClick = onKeys,
                    modifier = Modifier.padding(start = 36.dp, end = 12.dp)
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Outlined.Block, contentDescription = null) },
                    label = { Text("Safety") },
                    selected = false,
                    onClick = onSafety,
                    modifier = Modifier.padding(start = 36.dp, end = 12.dp)
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Outlined.EmojiEmotions, contentDescription = null) },
                    label = { Text("Custom Emojis") },
                    selected = false,
                    onClick = onCustomEmojis,
                    modifier = Modifier.padding(start = 36.dp, end = 12.dp)
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Outlined.BugReport, contentDescription = null) },
                    label = { Text("Console") },
                    selected = false,
                    onClick = onConsole,
                    modifier = Modifier.padding(start = 36.dp, end = 12.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        NavigationDrawerItem(
            icon = {
                Icon(
                    Icons.AutoMirrored.Filled.ExitToApp,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            label = {
                Text("Logout", color = MaterialTheme.colorScheme.error)
            },
            selected = false,
            onClick = onLogout,
            modifier = Modifier.padding(horizontal = 12.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
