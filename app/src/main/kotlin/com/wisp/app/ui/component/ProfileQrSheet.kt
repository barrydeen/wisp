package com.wisp.app.ui.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.outlined.CurrencyBitcoin
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.wisp.app.R
import com.wisp.app.nostr.Nip19
import com.wisp.app.nostr.Noffer
import com.wisp.app.nostr.hexToByteArray
import com.wisp.app.toRoute
import com.wisp.app.ui.theme.WispThemeColors
import kotlinx.coroutines.launch

/**
 * Consolidated QR sheet showing Nostr identity, Lightning address, and a scan
 * tab in a tabbed bottom sheet with swipeable pages.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ProfileQrSheet(
    pubkeyHex: String,
    avatarUrl: String? = null,
    lud16: String? = null,
    clinkOffer: String? = null,
    onNavigate: (String) -> Unit = {},
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val useZapBolt = com.wisp.app.ui.util.useBoltIcon()

    val npub = remember(pubkeyHex) { Nip19.npubEncode(pubkeyHex.hexToByteArray()) }
    val npubQr = remember(npub) { generateQrBitmap(npub) }
    val lightningQr = remember(lud16) { lud16?.let { generateQrBitmap(it) } }
    // QR payload must be exactly the bech32 string per the CLINK spec.
    val bareOffer = remember(clinkOffer) {
        clinkOffer?.takeIf { Noffer.isNofferString(it) }?.let { Noffer.stripNostrPrefix(it) }
    }
    val offerQr = remember(bareOffer) { bareOffer?.let { generateQrBitmap(it) } }

    val hasLightning = lud16 != null
    val hasOffer = bareOffer != null
    // The Lightning pane shows the address QR and/or the CLINK offer QR.
    val hasPayment = hasLightning || hasOffer
    // True = the offer QR is showing instead of the address QR. Offer-only
    // profiles always show the offer.
    var showOffer by remember { mutableStateOf(!hasLightning) }
    // Page indices: 0 = Nostr, 1 = Lightning (if present), last = Scan.
    val lightningPage = if (hasPayment) 1 else -1
    val scanPage = if (hasPayment) 2 else 1
    val pageCount = if (hasPayment) 3 else 2
    val pagerState = rememberPagerState(pageCount = { pageCount })
    var scanError by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
        ) {
            TabRow(
                selectedTabIndex = pagerState.currentPage,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary,
                divider = {}
            ) {
                Tab(
                    selected = pagerState.currentPage == 0,
                    onClick = { scope.launch { pagerState.animateScrollToPage(0) } },
                    text = { Text("Nostr") },
                    icon = { Icon(Icons.Outlined.Person, null, modifier = Modifier.size(18.dp)) }
                )
                if (hasPayment) {
                    Tab(
                        selected = pagerState.currentPage == lightningPage,
                        onClick = { scope.launch { pagerState.animateScrollToPage(lightningPage) } },
                        text = { Text("Lightning") },
                        icon = {
                            if (useZapBolt) {
                                Icon(painterResource(R.drawable.ic_bolt), null, modifier = Modifier.size(18.dp))
                            } else {
                                Icon(Icons.Outlined.CurrencyBitcoin, null, modifier = Modifier.size(18.dp))
                            }
                        }
                    )
                }
                Tab(
                    selected = pagerState.currentPage == scanPage,
                    onClick = { scope.launch { pagerState.animateScrollToPage(scanPage) } },
                    text = { Text("Scan") },
                    icon = { Icon(Icons.Outlined.QrCodeScanner, null, modifier = Modifier.size(18.dp)) }
                )
            }

            Spacer(Modifier.height(16.dp))

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxWidth()
            ) { page ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                ) {
                    when (page) {
                        0 -> {
                            // Nostr QR
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(240.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(androidx.compose.ui.graphics.Color.White)
                                    .padding(8.dp)
                            ) {
                                Image(
                                    bitmap = npubQr.asImageBitmap(),
                                    contentDescription = stringResource(R.string.cd_qr_code),
                                    modifier = Modifier.matchParentSize()
                                )
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(CircleShape)
                                        .background(androidx.compose.ui.graphics.Color.White)
                                        .padding(3.dp)
                                ) {
                                    if (avatarUrl != null) {
                                        AsyncImage(
                                            model = avatarUrl,
                                            contentDescription = "Avatar",
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.matchParentSize().clip(CircleShape)
                                        )
                                    } else {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_wisp_logo),
                                            contentDescription = "Wisp",
                                            tint = androidx.compose.ui.graphics.Color.Unspecified,
                                            modifier = Modifier.matchParentSize()
                                        )
                                    }
                                }
                            }

                            Spacer(Modifier.height(16.dp))

                            Text(
                                "npub",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(4.dp))
                            CopyableRow(text = npub, onCopy = {
                                clipboardManager.setText(AnnotatedString(npub))
                            })
                        }
                        lightningPage -> {
                            val payQr = if (showOffer) offerQr else lightningQr
                            val payValue = if (showOffer) bareOffer else lud16
                            if (payQr != null && payValue != null) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(240.dp)
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(androidx.compose.ui.graphics.Color.White)
                                        .padding(8.dp)
                                ) {
                                    Image(
                                        bitmap = payQr.asImageBitmap(),
                                        contentDescription = if (showOffer) "CLINK offer QR Code" else "Lightning QR Code",
                                        modifier = Modifier.matchParentSize()
                                    )
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .size(44.dp)
                                            .clip(CircleShape)
                                            .background(androidx.compose.ui.graphics.Color.White)
                                            .padding(4.dp)
                                    ) {
                                        if (useZapBolt) {
                                            Icon(
                                                painter = painterResource(R.drawable.ic_bolt),
                                                contentDescription = "Lightning",
                                                tint = WispThemeColors.zapColor,
                                                modifier = Modifier.size(28.dp)
                                            )
                                        } else {
                                            Icon(
                                                Icons.Outlined.CurrencyBitcoin,
                                                contentDescription = "Bitcoin",
                                                tint = WispThemeColors.zapColor,
                                                modifier = Modifier.size(28.dp)
                                            )
                                        }
                                    }
                                }

                                Spacer(Modifier.height(16.dp))

                                Text(
                                    if (showOffer) "CLINK Offer" else "Lightning Address",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(4.dp))
                                CopyableRow(text = payValue, onCopy = {
                                    clipboardManager.setText(AnnotatedString(payValue))
                                })

                                // Only when the profile has both artifacts; a
                                // single artifact needs no switch.
                                if (hasLightning && hasOffer) {
                                    Spacer(Modifier.height(8.dp))
                                    androidx.compose.material3.TextButton(onClick = { showOffer = !showOffer }) {
                                        Icon(
                                            Icons.Default.SwapHoriz,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(if (showOffer) "Switch to Lightning address" else "Switch to CLINK Offer")
                                    }
                                }
                            }
                        }
                        scanPage -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(360.dp)
                            ) {
                                QrScanner(
                                    onResult = { raw ->
                                        val route = Nip19.decodeNostrQr(raw)?.toRoute()
                                        if (route != null) {
                                            scanError = null
                                            onNavigate(route)
                                            onDismiss()
                                        } else {
                                            scanError = "Not a Nostr QR code"
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    promptText = scanError
                                        ?: "Point at an npub, note, profile, event, or address QR"
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CopyableRow(text: String, onCopy: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(4.dp))
        IconButton(onClick = onCopy) {
            Icon(
                Icons.Default.ContentCopy,
                contentDescription = "Copy",
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
