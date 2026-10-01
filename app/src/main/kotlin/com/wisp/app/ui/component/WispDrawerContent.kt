package com.wisp.app.ui.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.res.painterResource
import com.wisp.app.R
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
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wisp.app.nostr.Nip05
import com.wisp.app.nostr.ProfileData
import com.wisp.app.nostr.toNpub
import com.wisp.app.repo.AccountInfo
import com.wisp.app.repo.FiatPreferences
import com.wisp.app.repo.WalletBalanceDisplayMode
import com.wisp.app.ui.util.AmountFormatter
import com.wisp.app.ui.util.LocalCanSign


@Composable
fun WispDrawerContent(
    profile: ProfileData?,
    pubkey: String?,
    isDarkTheme: Boolean = true,
    onToggleTheme: () -> Unit = {},
    accounts: List<AccountInfo> = emptyList(),
    onSwitchAccount: (String) -> Unit = {},
    onAddAccount: () -> Unit = {},
    onMoveAccount: (pubkeyHex: String, offset: Int) -> Unit = { _, _ -> },
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
    // Mini-wallet widget (wisp-ios #474 port). `walletConfigured` is the
    // active wallet's mode != NONE; `walletBalanceMsats` is null whenever
    // the balance is unknown (never fetched / connecting / errored).
    walletConfigured: Boolean = false,
    walletBalanceMsats: Long? = null,
    isWatchOnly: Boolean = false,
) {
    ModalDrawerSheet(
        drawerContainerColor = MaterialTheme.colorScheme.surface,
        windowInsets = androidx.compose.foundation.layout.WindowInsets(0)
    ) {
        val scrollState = rememberScrollState()
        val scope = rememberCoroutineScope()
        val canSign = LocalCanSign.current
        Column(modifier = Modifier
            .fillMaxHeight()
            .statusBarsPadding()
            .verticalScroll(scrollState)
        ) {
        var showProfileQr by remember { mutableStateOf(false) }
        var showAccountSwitcher by remember { mutableStateOf(false) }

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
                Spacer(modifier = Modifier.width(8.dp))
                // Icon-only affordance that opens the account switcher modal.
                // Same action (switch or add an account) regardless of how many
                // accounts are signed in; shows a "+N" badge counting the other
                // accounts when more than one is signed in.
                val otherAccountCount = (accounts.size - 1).coerceAtLeast(0)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { showAccountSwitcher = true }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Icon(
                        Icons.Outlined.People,
                        contentDescription = stringResource(R.string.cd_switch_account),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (otherAccountCount > 0) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "+$otherAccountCount",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
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
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = profile?.displayString ?: "Anonymous",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }
            Spacer(Modifier.height(2.dp))
            if (!profile?.nip05.isNullOrBlank()) {
                Text(
                    text = Nip05.formatForDisplay(profile!!.nip05!!),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            } else if (pubkey != null) {
                Text(
                    text = pubkey.toNpub().let { it.take(16) + "..." },
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
                                onValueChange = { new -> if (!com.wisp.app.ui.component.NsecPasteGuard.blockIfNsec(statusText, new)) statusText = new },
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

        }

        if (showAccountSwitcher) {
            AccountSwitcherSheet(
                accounts = accounts,
                activePubkey = pubkey,
                activeProfile = profile,
                onSwitchAccount = onSwitchAccount,
                onAddAccount = onAddAccount,
                onMoveAccount = onMoveAccount,
                onDismiss = { showAccountSwitcher = false }
            )
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

        // Mini-wallet stripe — replaces the plain Wallet menu row with a
        // live-balance card (hide/show toggle sharing the dashboard's
        // per-pubkey hidden state, or a "Set up wallet" CTA). Skipped for
        // watch-only accounts, mirroring wisp-ios #474.
        if (!isWatchOnly) {
            DrawerMiniWalletRow(
                pubkey = pubkey,
                walletConfigured = walletConfigured,
                balanceMsats = walletBalanceMsats,
                onOpenWallet = onWallet
            )
        }

        NavigationDrawerItem(
            icon = { Icon(Icons.Outlined.Person, contentDescription = null) },
            label = { Text(stringResource(R.string.drawer_my_profile)) },
            selected = false,
            onClick = onProfile,
            modifier = Modifier.height(48.dp).padding(horizontal = 12.dp)
        )
        NavigationDrawerItem(
            icon = { Icon(Icons.Outlined.Home, contentDescription = null) },
            label = { Text(stringResource(R.string.drawer_feeds)) },
            selected = false,
            onClick = onFeed,
            modifier = Modifier.height(48.dp).padding(horizontal = 12.dp)
        )
        NavigationDrawerItem(
            icon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            label = { Text(stringResource(R.string.title_search)) },
            selected = false,
            onClick = onSearch,
            modifier = Modifier.height(48.dp).padding(horizontal = 12.dp)
        )
        if (canSign) {
            NavigationDrawerItem(
                icon = { Icon(Icons.Outlined.Email, contentDescription = null) },
                label = { Text(stringResource(R.string.nav_messages)) },
                selected = false,
                onClick = onMessages,
                modifier = Modifier.height(48.dp).padding(horizontal = 12.dp)
            )
        }
        // Wallet lives in the mini-wallet widget near the top of the
        // drawer now.
        NavigationDrawerItem(
            icon = { Icon(Icons.Outlined.FormatListBulleted, contentDescription = null) },
            label = { Text(stringResource(R.string.drawer_lists)) },
            selected = false,
            onClick = onLists,
            modifier = Modifier.height(48.dp).padding(horizontal = 12.dp)
        )
        NavigationDrawerItem(
            icon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
            label = { Text(stringResource(R.string.drawer_drafts)) },
            selected = false,
            onClick = onDrafts,
            modifier = Modifier.height(48.dp).padding(horizontal = 12.dp)
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
            modifier = Modifier.height(48.dp).padding(horizontal = 12.dp)
        )
        AnimatedVisibility(visible = settingsExpanded) {
            Column {
                NavigationDrawerItem(
                    icon = { Icon(Icons.Outlined.Palette, contentDescription = null) },
                    label = { Text(stringResource(R.string.drawer_interface)) },
                    selected = false,
                    onClick = onInterfaceSettings,
                    modifier = Modifier.height(48.dp).padding(start = 36.dp, end = 12.dp)
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                    label = { Text(stringResource(R.string.drawer_relays)) },
                    selected = false,
                    onClick = onRelaySettings,
                    modifier = Modifier.height(48.dp).padding(start = 36.dp, end = 12.dp)
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Outlined.Cloud, contentDescription = null) },
                    label = { Text(stringResource(R.string.drawer_media_servers)) },
                    selected = false,
                    onClick = onMediaServers,
                    modifier = Modifier.height(48.dp).padding(start = 36.dp, end = 12.dp)
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Outlined.Key, contentDescription = null) },
                    label = { Text(stringResource(R.string.drawer_keys)) },
                    selected = false,
                    onClick = onKeys,
                    modifier = Modifier.height(48.dp).padding(start = 36.dp, end = 12.dp)
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Outlined.Block, contentDescription = null) },
                    label = { Text(stringResource(R.string.drawer_safety)) },
                    selected = false,
                    onClick = onSafety,
                    modifier = Modifier.height(48.dp).padding(start = 36.dp, end = 12.dp)
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Outlined.Shield, contentDescription = null) },
                    label = { Text(stringResource(R.string.drawer_proof_of_work)) },
                    selected = false,
                    onClick = onPowSettings,
                    modifier = Modifier.height(48.dp).padding(start = 36.dp, end = 12.dp)
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Outlined.Hub, contentDescription = null) },
                    label = { Text(stringResource(R.string.drawer_social_graph)) },
                    selected = false,
                    onClick = onSocialGraph,
                    modifier = Modifier.height(48.dp).padding(start = 36.dp, end = 12.dp)
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Outlined.EmojiEmotions, contentDescription = null) },
                    label = { Text(stringResource(R.string.drawer_custom_emojis)) },
                    selected = false,
                    onClick = onCustomEmojis,
                    modifier = Modifier.height(48.dp).padding(start = 36.dp, end = 12.dp)
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Outlined.FavoriteBorder, contentDescription = null) },
                    label = { Text(stringResource(R.string.drawer_relay_health)) },
                    selected = false,
                    onClick = onRelayHealth,
                    modifier = Modifier.height(48.dp).padding(start = 36.dp, end = 12.dp)
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Outlined.BugReport, contentDescription = null) },
                    label = { Text(stringResource(R.string.drawer_console)) },
                    selected = false,
                    onClick = onConsole,
                    modifier = Modifier.height(48.dp).padding(start = 36.dp, end = 12.dp)
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
            modifier = Modifier.height(48.dp).padding(horizontal = 12.dp)
        )

        if (showLogoutDialog) {
            AlertDialog(
                onDismissRequest = { showLogoutDialog = false },
                title = { Text(stringResource(R.string.btn_logout)) },
                text = {
                    Column {
                        Row(verticalAlignment = Alignment.Top) {
                            Icon(
                                if (canSign) Icons.Outlined.Key else Icons.Outlined.Visibility,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = if (canSign) MaterialTheme.colorScheme.error
                                       else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                if (canSign)
                                    "Back up your private key before logging out. Without it, your Nostr account cannot be recovered."
                                else
                                    "Sign back in with your npub anytime.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        if (canSign && hasEmbeddedWallet) {
                            Spacer(Modifier.height(14.dp))
                            Row(verticalAlignment = Alignment.Top) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_wallet_outlined),
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

// ── Mini wallet widget ─────────────────────────────────────────────────────

/**
 * Compact live-balance stripe replacing the Wallet row in the drawer menu.
 * Shows the active wallet's balance (compacted to "1.2M"-style once the
 * grouped number gets long), a hide/show toggle that shares the wallet
 * dashboard's per-pubkey hidden state, and a "Set up wallet" call-to-action
 * when no wallet is configured. Tapping the stripe opens the wallet tab.
 * Port of wisp-ios #474 (SidebarMiniWalletView).
 */
@Composable
private fun DrawerMiniWalletRow(
    pubkey: String?,
    walletConfigured: Boolean,
    balanceMsats: Long?,
    onOpenWallet: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember(pubkey) {
        context.getSharedPreferences("wisp_settings", android.content.Context.MODE_PRIVATE)
    }
    // Same key the wallet dashboard's balance display uses, so hiding here
    // hides there and vice versa.
    var displayMode by remember(pubkey) {
        mutableStateOf(WalletBalanceDisplayMode.read(prefs, pubkey))
    }
    val fiatPrefs = remember { FiatPreferences.get(context) }
    val fiatMode by fiatPrefs.fiatMode.collectAsState()
    val fiatCurrency by fiatPrefs.currency.collectAsState()

    // Hiding captures the current mode under the restore key; unhiding puts
    // it back (SATS when nothing was captured), so a FIAT dashboard isn't
    // reset by the drawer toggle.
    fun toggleHidden() {
        if (displayMode == WalletBalanceDisplayMode.HIDDEN) {
            val saved = pubkey?.let { prefs.getString(WalletBalanceDisplayMode.restoreStorageKey(it), null) }
            val next = WalletBalanceDisplayMode.values()
                .firstOrNull { it.name.equals(saved, ignoreCase = true) }
                ?.takeIf { it != WalletBalanceDisplayMode.HIDDEN }
                ?: WalletBalanceDisplayMode.SATS
            displayMode = next
            WalletBalanceDisplayMode.write(prefs, pubkey, next)
        } else {
            pubkey?.let {
                prefs.edit()
                    .putString(WalletBalanceDisplayMode.restoreStorageKey(it), displayMode.name.lowercase())
                    .apply()
            }
            displayMode = WalletBalanceDisplayMode.HIDDEN
            WalletBalanceDisplayMode.write(prefs, pubkey, WalletBalanceDisplayMode.HIDDEN)
        }
    }

    val balanceText = when {
        !walletConfigured -> stringResource(R.string.drawer_set_up_wallet)
        displayMode == WalletBalanceDisplayMode.HIDDEN -> "* * * * *"
        balanceMsats == null ->
            // Never render an unknown balance as "0" — see the matching
            // comment in WalletScreen's balance card.
            "\u2026"
        else -> {
            val sats = balanceMsats / 1000
            // Fiat renders when the wallet dashboard's display mode is FIAT
            // or the app-wide fiat mode is on (same precedence as the
            // dashboard's balance card), falling back to the sats display
            // when no exchange rate is cached.
            val fiat = if (fiatMode || displayMode == WalletBalanceDisplayMode.FIAT) {
                AmountFormatter.formatFiat(sats, fiatCurrency)
            } else null
            if (fiat != null) {
                fiat
            } else {
                val number = if (sats >= 1_000_000) AmountFormatter.formatSatsShort(sats)
                else AmountFormatter.formatSatsOnly(sats)
                stringResource(R.string.amount_sats_format, number)
            }
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .clickable(onClick = onOpenWallet)
            // Same leading inset NavigationDrawerItem content gets (12dp row
            // padding + 16dp internal start padding) so the icon and label
            // line up with the rest of the menu; the edge-to-edge background
            // stripe is what sets the widget apart.
            .padding(start = 28.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_wallet_outlined),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.width(12.dp))
        // One Text so TalkBack reads the figure as a unit.
        AnimatedContent(
            targetState = balanceText,
            transitionSpec = {
                (slideInVertically(tween(250)) { it / 4 } + fadeIn(tween(250))) togetherWith
                    (slideOutVertically(tween(250)) { -it / 4 } + fadeOut(tween(250)))
            },
            label = "miniWalletBalance"
        ) { text ->
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.weight(1f))
        if (walletConfigured) {
            // The eye's own clickable consumes the tap so it doesn't fall
            // through to the stripe's open-wallet action.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable { toggleHidden() }
            ) {
                Icon(
                    imageVector = if (displayMode == WalletBalanceDisplayMode.HIDDEN) Icons.Outlined.Visibility
                    else Icons.Outlined.VisibilityOff,
                    contentDescription = stringResource(
                        if (displayMode == WalletBalanceDisplayMode.HIDDEN) R.string.cd_show_balance
                        else R.string.cd_hide_balance
                    ),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        } else {
            Icon(
                Icons.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
