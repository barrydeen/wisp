package cooking.zap.app.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cooking.zap.app.R
import cooking.zap.app.nostr.NofferData
import cooking.zap.app.nostr.ProfileData
import cooking.zap.app.repo.ZapPreferences
import kotlinx.coroutines.launch

/**
 * Merged "pay this person" entry point for a profile — combines the Zap and
 * CLINK offer flows behind one button, since they're conceptually the same
 * action (send this person money) and a profile's `lud16`/CLINK offer are
 * independently optional.
 *
 * When only one payment method is available, delegates straight to the
 * existing [ZapDialog] or [NofferPaySheet] (unchanged, including their own
 * guards/behavior). Only when BOTH are available does this render a new
 * tabbed sheet (Zap / CLINK) wrapping [ZapDialogContent] and
 * [NofferPaySheetContent] via a shared pager — no point building tab
 * machinery around a single option.
 */
enum class ProfileZapTab { ZAP, CLINK }

@Composable
fun ProfileZapSheet(
    hasZap: Boolean,
    noffer: NofferData?,
    initialTab: ProfileZapTab,
    onDismiss: () -> Unit,
    // Zap params (mirrors ZapDialog/ZapDialogContent)
    isWalletConnected: Boolean,
    onZap: (amountMsats: Long, message: String, isAnonymous: Boolean, isPrivate: Boolean) -> Unit,
    onGoToWallet: () -> Unit,
    zapPrefsRepo: ZapPreferences? = null,
    canPrivateZap: Boolean = false,
    recipientPubkey: String? = null,
    profileLookup: (String) -> ProfileData? = { null },
    recipientHasLud16: Boolean = true,
    // CLINK params (mirrors NofferPaySheet/NofferPaySheetContent)
    recipientProfile: ProfileData? = null,
    onPayInvoice: (suspend (String) -> Boolean)? = null,
) {
    when {
        hasZap && noffer != null -> ProfileZapTabbedSheet(
            noffer = noffer,
            initialTab = initialTab,
            onDismiss = onDismiss,
            isWalletConnected = isWalletConnected,
            onZap = onZap,
            onGoToWallet = onGoToWallet,
            zapPrefsRepo = zapPrefsRepo,
            canPrivateZap = canPrivateZap,
            recipientPubkey = recipientPubkey,
            profileLookup = profileLookup,
            recipientHasLud16 = recipientHasLud16,
            recipientProfile = recipientProfile,
            onPayInvoice = onPayInvoice,
        )
        hasZap -> ZapDialog(
            isWalletConnected = isWalletConnected,
            onDismiss = onDismiss,
            onZap = onZap,
            onGoToWallet = onGoToWallet,
            zapPrefsRepo = zapPrefsRepo,
            canPrivateZap = canPrivateZap,
            recipientPubkey = recipientPubkey,
            profileLookup = profileLookup,
            recipientHasLud16 = recipientHasLud16,
        )
        noffer != null -> NofferPaySheet(
            noffer = noffer,
            recipientProfile = recipientProfile,
            onPayInvoice = onPayInvoice,
            onDismiss = onDismiss,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileZapTabbedSheet(
    noffer: NofferData,
    initialTab: ProfileZapTab,
    onDismiss: () -> Unit,
    isWalletConnected: Boolean,
    onZap: (amountMsats: Long, message: String, isAnonymous: Boolean, isPrivate: Boolean) -> Unit,
    onGoToWallet: () -> Unit,
    zapPrefsRepo: ZapPreferences?,
    canPrivateZap: Boolean,
    recipientPubkey: String?,
    profileLookup: (String) -> ProfileData?,
    recipientHasLud16: Boolean,
    recipientProfile: ProfileData?,
    onPayInvoice: (suspend (String) -> Boolean)?,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(
        initialPage = if (initialTab == ProfileZapTab.CLINK) 1 else 0,
        pageCount = { 2 }
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier
            .fillMaxHeight()
            .statusBarsPadding()
    ) {
        TabRow(
            selectedTabIndex = pagerState.currentPage,
            modifier = Modifier.height(40.dp)
        ) {
            Tab(
                selected = pagerState.currentPage == 0,
                onClick = { scope.launch { pagerState.animateScrollToPage(0) } },
                modifier = Modifier.height(40.dp),
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(R.drawable.ic_bolt), null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Zap")
                    }
                }
            )
            Tab(
                selected = pagerState.currentPage == 1,
                onClick = { scope.launch { pagerState.animateScrollToPage(1) } },
                modifier = Modifier.height(40.dp),
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Sell, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("CLINK")
                    }
                }
            )
        }
        Spacer(Modifier.height(20.dp))
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth().fillMaxHeight()
        ) { page ->
            when (page) {
                0 -> if (isWalletConnected && recipientHasLud16) {
                    ZapDialogContent(
                        onZap = onZap,
                        closeSheet = onDismiss,
                        zapPrefsRepo = zapPrefsRepo,
                        canPrivateZap = canPrivateZap,
                        recipientPubkey = recipientPubkey,
                        profileLookup = profileLookup,
                    )
                } else {
                    ZapNotConnectedPrompt(
                        isWalletConnected = isWalletConnected,
                        onGoToWallet = onGoToWallet,
                    )
                }
                else -> NofferPaySheetContent(
                    noffer = noffer,
                    recipientProfile = recipientProfile,
                    onPayInvoice = onPayInvoice,
                    onDone = onDismiss,
                )
            }
        }
    }
}

/**
 * Inline stand-in for the Zap tab when there's no usable Zap path — mirrors
 * [ZapDialog]'s own not-connected guard, but as tab content rather than a
 * blocking dialog, so the CLINK tab alongside it stays fully usable.
 */
@Composable
private fun ZapNotConnectedPrompt(
    isWalletConnected: Boolean,
    onGoToWallet: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            if (isWalletConnected) "Can't Zap" else stringResource(R.string.zap_wallet_not_connected),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (isWalletConnected) {
            Text(
                "This profile has no lightning address to zap.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.zap_connect_wallet),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onGoToWallet) {
                Text(stringResource(R.string.btn_go_to_wallet))
            }
        }
    }
}
