package com.wisp.app.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wisp.app.nostr.ProfileData
import com.wisp.app.repo.AccountInfo


@Composable
fun WispDrawerContent(
    profile: ProfileData?,
    pubkey: String?,
    isDarkTheme: Boolean = true,
    onToggleTheme: () -> Unit = {},
    isTorEnabled: Boolean = false,
    torStatus: TorStatus = TorStatus.DISABLED,
    onToggleTor: (Boolean) -> Unit = {},
    accounts: List<AccountInfo> = emptyList(),
    onSwitchAccount: (String) -> Unit = {},
    onAddAccount: () -> Unit = {},
    onProfile: () -> Unit,
    onFeed: () -> Unit,
    onSearch: () -> Unit,
    onMessages: () -> Unit,
    onWallet: () -> Unit,
    onLists: () -> Unit = {},
    onDrafts: () -> Unit = {},
    onMediaServers: () -> Unit,
    onKeys: () -> Unit = {},
    onSocialGraph: () -> Unit = {},
    onSafety: () -> Unit = {},
    onPowSettings: () -> Unit = {},
    onCustomEmojis: () -> Unit = {},
    onConsole: () -> Unit = {},
    onRelayHealth: () -> Unit = {},
    onRelaySettings: () -> Unit,
    onInterfaceSettings: () -> Unit = {},
    onLogout: () -> Unit,
    hasEmbeddedWallet: Boolean = false,
    userStatus: String? = null,
    onUpdateStatus: ((String) -> Unit)? = null,
    onScanResult: (String) -> Unit = {},
) {
    ModalDrawerSheet(
        drawerContainerColor = MaterialTheme.colorScheme.surface,
        windowInsets = androidx.compose.foundation.layout.WindowInsets(0)
    ) {
        val scrollState = rememberScrollState()
        val scope = rememberCoroutineScope()
        Column(modifier = Modifier
            .fillMaxHeight()
            .statusBarsPadding()
            .verticalScroll(scrollState)
        ) {
        var showProfileQr by remember { mutableStateOf(false) }
        var accountPickerExpanded by remember { mutableStateOf(false) }

        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                var crashTapCount by remember { mutableIntStateOf(0) }
                LaunchedEffect(crashTapCount) {
                    if (crashTapCount > 0) {
                        delay(2000)
                        crashTapCount = 0
                    }
                }
                Box(modifier = Modifier.clickable {
                    crashTapCount++
                    if (crashTapCount >= 7) {
                        throw RuntimeException("Test crash")
                    }
                    onProfile()
                }) {
                    ProfilePicture(url = profile?.picture, size = 64)
                }
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
                                contentDescription = stringResource(R.string.cd_toggle_tor),
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
                        contentDescription = stringResource(R.string.cd_toggle_theme),
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { showProfileQr = true }) {
                    Icon(
                        Icons.Outlined.QrCodeScanner,
                        contentDescription = stringResource(R.string.cd_show_qr_code),
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = accounts.size > 1 || accounts.isNotEmpty()) {
                        accountPickerExpanded = !accountPickerExpanded
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = profile?.displayString ?: "Anonymous",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (accounts.size > 1 || accounts.isNotEmpty()) {
                        Icon(
                            if (accountPickerExpanded) Icons.Outlined.KeyboardArrowDown
                            else Icons.Outlined.KeyboardArrowRight,
                            contentDescription = stringResource(R.string.cd_switch_account),
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
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

            // User status — tap to edit
            if (onUpdateStatus != null) {
                var showStatusDialog by remember { mutableStateOf(false) }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clickable { showStatusDialog = true }
                        .padding(top = 4.dp)
                ) {
                    if (userStatus.isNullOrBlank()) {
                        Icon(
                            Icons.Outlined.Edit,
                            contentDescription = "Set status",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "Set status...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                        )
                    } else {
                        Text(
                            text = userStatus,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            Icons.Outlined.Edit,
                            contentDescription = "Edit status",
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                }
                if (showStatusDialog) {
                    var statusText by remember { mutableStateOf(userStatus ?: "") }
                    AlertDialog(
                        onDismissRequest = { showStatusDialog = false },
                        title = { Text("Update Status") },
                        text = {
                            androidx.compose.material3.OutlinedTextField(
                                value = statusText,
                                onValueChange = { statusText = it },
                                label = { Text("What are you up to?") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                onUpdateStatus(statusText.trim())
                                showStatusDialog = false
                            }) {
                                Text(if (statusText.isBlank()) "Clear" else "Update")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showStatusDialog = false }) {
                                Text(stringResource(R.string.btn_cancel))
                            }
                        }
                    )
                }
            }

            // Account picker dropdown
            AnimatedVisibility(visible = accountPickerExpanded) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    accounts.forEach { account ->
                        val isActive = account.pubkeyHex == pubkey
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !isActive) {
                                    accountPickerExpanded = false
                                    onSwitchAccount(account.pubkeyHex)
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // For the active account use the live profile picture; for others use cached AccountInfo
                            val pictureUrl = if (isActive) profile?.picture else account.picture
                            ProfilePicture(url = pictureUrl, size = 36)
                            Spacer(modifier = Modifier.width(12.dp))
                            val displayText = if (isActive) {
                                profile?.displayString ?: account.displayName ?: account.pubkeyHex.take(16) + "..."
                            } else {
                                account.displayName ?: account.pubkeyHex.take(16) + "..."
                            }
                            Text(
                                text = displayText,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            if (isActive) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = stringResource(R.string.cd_active),
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                    // Add account row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                accountPickerExpanded = false
                                onAddAccount()
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier.size(36.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Filled.Add,
                                contentDescription = stringResource(R.string.cd_add_account),
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.cd_add_account),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        if (showProfileQr && pubkey != null) {
            ProfileQrSheet(
                pubkeyHex = pubkey,
                avatarUrl = profile?.picture,
                lud16 = profile?.lud16,
                onNavigate = { route ->
                    showProfileQr = false
                    onScanResult(route)
                },
                onDismiss = { showProfileQr = false }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        NavigationDrawerItem(
            icon = { Icon(Icons.Outlined.Person, contentDescription = null) },
            label = { Text(stringResource(R.string.drawer_my_profile)) },
            selected = false,
            onClick = onProfile,
            modifier = Modifier.padding(horizontal = 12.dp)
        )
        NavigationDrawerItem(
            icon = { Icon(Icons.Outlined.Home, contentDescription = null) },
            label = { Text(stringResource(R.string.drawer_feeds)) },
            selected = false,
            onClick = onFeed,
            modifier = Modifier.padding(horizontal = 12.dp)
        )
        NavigationDrawerItem(
            icon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            label = { Text(stringResource(R.string.title_search)) },
            selected = false,
            onClick = onSearch,
            modifier = Modifier.padding(horizontal = 12.dp)
        )
        NavigationDrawerItem(
            icon = { Icon(Icons.Outlined.Email, contentDescription = null) },
            label = { Text(stringResource(R.string.nav_messages)) },
            selected = false,
            onClick = onMessages,
            modifier = Modifier.padding(horizontal = 12.dp)
        )
        val useZapBolt = com.wisp.app.ui.util.useBoltIcon()
        NavigationDrawerItem(
            icon = {
                if (useZapBolt) {
                    Icon(
                        painter = painterResource(R.drawable.ic_bolt),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp)
                    )
                } else {
                    Icon(Icons.Outlined.CurrencyBitcoin, contentDescription = null)
                }
            },
            label = { Text(stringResource(R.string.nav_wallet)) },
            selected = false,
            onClick = onWallet,
            modifier = Modifier.padding(horizontal = 12.dp)
        )
        NavigationDrawerItem(
            icon = { Icon(Icons.Outlined.FormatListBulleted, contentDescription = null) },
            label = { Text(stringResource(R.string.drawer_lists)) },
            selected = false,
            onClick = onLists,
            modifier = Modifier.padding(horizontal = 12.dp)
        )
        NavigationDrawerItem(
            icon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
            label = { Text(stringResource(R.string.drawer_drafts)) },
            selected = false,
            onClick = onDrafts,
            modifier = Modifier.padding(horizontal = 12.dp)
        )
        var settingsExpanded by remember { mutableStateOf(false) }
        LaunchedEffect(settingsExpanded) {
            if (settingsExpanded) {
                delay(300) // wait for AnimatedVisibility expansion
                scrollState.animateScrollTo(scrollState.maxValue)
            }
        }
        NavigationDrawerItem(
            icon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
            label = { Text(stringResource(R.string.drawer_settings)) },
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
                    icon = { Icon(Icons.Outlined.Palette, contentDescription = null) },
                    label = { Text(stringResource(R.string.drawer_interface)) },
                    selected = false,
                    onClick = onInterfaceSettings,
                    modifier = Modifier.padding(start = 36.dp, end = 12.dp)
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                    label = { Text(stringResource(R.string.drawer_relays)) },
                    selected = false,
                    onClick = onRelaySettings,
                    modifier = Modifier.padding(start = 36.dp, end = 12.dp)
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Outlined.Cloud, contentDescription = null) },
                    label = { Text(stringResource(R.string.drawer_media_servers)) },
                    selected = false,
                    onClick = onMediaServers,
                    modifier = Modifier.padding(start = 36.dp, end = 12.dp)
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Outlined.Key, contentDescription = null) },
                    label = { Text(stringResource(R.string.drawer_keys)) },
                    selected = false,
                    onClick = onKeys,
                    modifier = Modifier.padding(start = 36.dp, end = 12.dp)
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Outlined.Block, contentDescription = null) },
                    label = { Text(stringResource(R.string.drawer_safety)) },
                    selected = false,
                    onClick = onSafety,
                    modifier = Modifier.padding(start = 36.dp, end = 12.dp)
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Outlined.Shield, contentDescription = null) },
                    label = { Text(stringResource(R.string.drawer_proof_of_work)) },
                    selected = false,
                    onClick = onPowSettings,
                    modifier = Modifier.padding(start = 36.dp, end = 12.dp)
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Outlined.Hub, contentDescription = null) },
                    label = { Text(stringResource(R.string.drawer_social_graph)) },
                    selected = false,
                    onClick = onSocialGraph,
                    modifier = Modifier.padding(start = 36.dp, end = 12.dp)
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Outlined.EmojiEmotions, contentDescription = null) },
                    label = { Text(stringResource(R.string.drawer_custom_emojis)) },
                    selected = false,
                    onClick = onCustomEmojis,
                    modifier = Modifier.padding(start = 36.dp, end = 12.dp)
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Outlined.FavoriteBorder, contentDescription = null) },
                    label = { Text(stringResource(R.string.drawer_relay_health)) },
                    selected = false,
                    onClick = onRelayHealth,
                    modifier = Modifier.padding(start = 36.dp, end = 12.dp)
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Outlined.BugReport, contentDescription = null) },
                    label = { Text(stringResource(R.string.drawer_console)) },
                    selected = false,
                    onClick = onConsole,
                    modifier = Modifier.padding(start = 36.dp, end = 12.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        var showLogoutDialog by remember { mutableStateOf(false) }

        NavigationDrawerItem(
            icon = {
                Icon(
                    Icons.AutoMirrored.Filled.ExitToApp,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            label = {
                Text(stringResource(R.string.btn_logout), color = MaterialTheme.colorScheme.error)
            },
            selected = false,
            onClick = { showLogoutDialog = true },
            modifier = Modifier.padding(horizontal = 12.dp)
        )

        if (showLogoutDialog) {
            AlertDialog(
                onDismissRequest = { showLogoutDialog = false },
                title = { Text(stringResource(R.string.btn_logout)) },
                text = {
                    Column {
                        Row(verticalAlignment = Alignment.Top) {
                            Icon(
                                Icons.Outlined.Key,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "Back up your private key before logging out. Without it, your Nostr account cannot be recovered.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        if (hasEmbeddedWallet) {
                            Spacer(Modifier.height(14.dp))
                            Row(verticalAlignment = Alignment.Top) {
                                Icon(
                                    Icons.Outlined.AccountBalanceWallet,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.error
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    "Back up your wallet recovery phrase. Without it, your funds cannot be recovered.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        showLogoutDialog = false
                        onLogout()
                    }) {
                        Text(stringResource(R.string.btn_logout), color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showLogoutDialog = false }) {
                        Text(stringResource(R.string.btn_cancel))
                    }
                }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Version info with wisp logo
        val versionContext = androidx.compose.ui.platform.LocalContext.current
        val versionName = remember {
            try {
                versionContext.packageManager.getPackageInfo(versionContext.packageName, 0).versionName ?: "?"
            } catch (_: Exception) { "?" }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_wisp_logo),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "wisp v$versionName",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
            )
        }
        }
    }
}
