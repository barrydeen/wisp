package com.wisp.app.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.CurrencyBitcoin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.wisp.app.R
import com.wisp.app.Routes

enum class BottomTab(
    val route: String,
    val labelResId: Int,
    val selectedIcon: ImageVector?,
    val unselectedIcon: ImageVector?,
    val selectedIconRes: Int? = null,
    val unselectedIconRes: Int? = null
) {
    HOME(Routes.FEED, R.string.nav_home, Icons.Filled.Home, Icons.Outlined.Home),
    WALLET(Routes.WALLET, R.string.nav_wallet, null, null, R.drawable.ic_wallet, R.drawable.ic_wallet_outlined),
    SEARCH(Routes.SEARCH, R.string.nav_search, Icons.Filled.Search, Icons.Outlined.Search),
    MESSAGES(Routes.DM_LIST, R.string.nav_messages, Icons.Filled.Forum, Icons.Outlined.Forum),
    NOTIFICATIONS(Routes.NOTIFICATIONS, R.string.nav_notifications, Icons.Filled.Notifications, Icons.Outlined.Notifications)
}

@Composable
fun WispBottomBar(
    currentRoute: String?,
    hasUnreadHome: Boolean,
    hasUnreadMessages: Boolean,
    hasUnreadNotifications: Boolean,
    isZapAnimating: Boolean = false,
    isReplyAnimating: Boolean = false,
    notifSoundEnabled: Boolean = true,
    isReadOnly: Boolean = false,
    onTabSelected: (BottomTab) -> Unit
) {
    val visibleTabs = if (isReadOnly)
        BottomTab.entries.filter { it != BottomTab.WALLET && it != BottomTab.MESSAGES }
    else
        BottomTab.entries

    Column {
        HorizontalDivider(
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )
        NavigationBar(
            // Match the body color (background) instead of Material's
            // default surface tone — iOS uses one near-black across body
            // + chrome and reserves the lighter "surface" for elevated
            // controls (pills, cards).
            containerColor = MaterialTheme.colorScheme.background,
            // Compact the chrome: reserve the gesture inset via
            // `windowInsetsPadding` (resolved at layout time, so no
            // first-frame snap) and clamp the content area to 56dp
            // (was Material's default 80dp). Pass `WindowInsets(0)` to
            // disable NavigationBar's internal inset handling so we
            // don't double-pad.
            modifier = Modifier
                .windowInsetsPadding(NavigationBarDefaults.windowInsets)
                .height(56.dp),
            windowInsets = WindowInsets(0)
        ) {
        visibleTabs.forEach { tab ->
            val selected = currentRoute == tab.route
            val hasUnread = when (tab) {
                BottomTab.HOME -> hasUnreadHome
                BottomTab.WALLET -> false
                BottomTab.SEARCH -> false
                BottomTab.MESSAGES -> hasUnreadMessages
                BottomTab.NOTIFICATIONS -> hasUnreadNotifications
            }

            NavigationBarItem(
                selected = selected,
                onClick = { onTabSelected(tab) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    // Drop the active-pill indicator — the orange tint on
                    // the selected icon is enough signal (matches iOS).
                    indicatorColor = Color.Transparent
                ),
                icon = {
                    val useBolt = com.wisp.app.ui.util.useBoltIcon()
                    Box(
                        modifier = Modifier.requiredSize(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        val zapTint = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        if (tab == BottomTab.NOTIFICATIONS && isZapAnimating && useBolt) {
                            Icon(
                                painter = painterResource(R.drawable.ic_bolt),
                                contentDescription = stringResource(tab.labelResId),
                                tint = zapTint
                            )
                        } else if (tab.selectedIconRes != null && tab.unselectedIconRes != null) {
                            // Drawable-backed tabs (wallet) — solid when selected,
                            // outlined when not.
                            val iconRes = if (selected) tab.selectedIconRes else tab.unselectedIconRes
                            Icon(
                                painter = painterResource(iconRes),
                                contentDescription = stringResource(tab.labelResId),
                                tint = zapTint
                            )
                        } else {
                            val icon = if (tab == BottomTab.NOTIFICATIONS && isZapAnimating)
                                Icons.Outlined.CurrencyBitcoin
                            else if (selected) tab.selectedIcon!!
                            else tab.unselectedIcon!!
                            Icon(
                                imageVector = icon,
                                contentDescription = stringResource(tab.labelResId),
                                tint = zapTint
                            )
                        }
                        if (hasUnread) {
                            // Notification dot uses iOS-standard badge red
                            // (#FF3B30) instead of the primary accent so it
                            // reads as "alert" rather than "branded highlight."
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .align(Alignment.TopEnd)
                                    .offset(x = 2.dp, y = (-2).dp)
                                    .background(
                                        color = Color(0xFFFF3B30),
                                        shape = CircleShape
                                    )
                            )
                        }
                        if (tab == BottomTab.NOTIFICATIONS) {
                            val zeroFootprintModifier = Modifier
                                .size(120.dp)
                                .layout { measurable, constraints ->
                                    val placeable = measurable.measure(
                                        constraints.copy(
                                            minWidth = 0,
                                            minHeight = 0
                                        )
                                    )
                                    layout(0, 0) {
                                        placeable.place(
                                            -placeable.width / 2,
                                            -placeable.height / 2
                                        )
                                    }
                                }
                            ZapBurstEffect(
                                isActive = isZapAnimating,
                                modifier = zeroFootprintModifier,
                                soundEnabled = notifSoundEnabled
                            )
                            IcqFlowerBurstEffect(
                                isActive = isReplyAnimating,
                                modifier = zeroFootprintModifier,
                                soundEnabled = notifSoundEnabled
                            )
                        }
                    }
                },
                label = null
            )
        }
    }
    }
}
