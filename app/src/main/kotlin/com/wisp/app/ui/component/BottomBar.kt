package com.wisp.app.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.CurrencyBitcoin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.wisp.app.R
import com.wisp.app.Routes

enum class BottomTab(
    val route: String,
    val labelResId: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    HOME(Routes.FEED, R.string.nav_home, Icons.Filled.Home, Icons.Outlined.Home),
    WALLET(Routes.WALLET, R.string.nav_wallet, Icons.Filled.AccountBalanceWallet, Icons.Outlined.AccountBalanceWallet),
    SEARCH(Routes.SEARCH, R.string.nav_search, Icons.Filled.Search, Icons.Outlined.Search),
    MESSAGES(Routes.DM_LIST, R.string.nav_messages, Icons.Filled.Email, Icons.Outlined.Email),
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
    onTabSelected: (BottomTab) -> Unit
) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        BottomTab.entries.forEach { tab ->
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
                    indicatorColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                icon = {
                    Box(
                        modifier = Modifier.requiredSize(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        val icon = if (tab == BottomTab.NOTIFICATIONS && isZapAnimating)
                            Icons.Outlined.CurrencyBitcoin
                        else if (selected) tab.selectedIcon
                        else tab.unselectedIcon
                        Icon(
                            imageVector = icon,
                            contentDescription = stringResource(tab.labelResId),
                            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (hasUnread) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .align(Alignment.TopEnd)
                                    .offset(x = 2.dp, y = (-2).dp)
                                    .background(
                                        color = MaterialTheme.colorScheme.primary,
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
