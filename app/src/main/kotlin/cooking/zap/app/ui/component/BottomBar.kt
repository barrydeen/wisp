package cooking.zap.app.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CurrencyBitcoin
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import cooking.zap.app.R
import cooking.zap.app.Routes

private val NavBarDark = Color(0xFF1F2937)
// Unread badge — brand amber-400, lighter than the orange nav icons so it
// reads as a distinct "alert" dot rather than blending into the iconography.
private val UnreadDotColor = Color(0xFFFBBF24)
private val NAV_HEIGHT = 50.dp
private val BG_CIRCLE_SIZE = 74.dp  // background circle (perfect circle via requiredSize)
private val ICON_SIZE = 53.dp       // zc logo inside the bg circle
private val SIDE_ICON_SIZE = 21.dp  // the four flanking nav icons
// How far the circle's TOP edge rises above the nav bar top edge. The circle
// center then sits at (PROTRUSION below its top) - i.e. lower PROTRUSION = circle
// sits lower / more in line with the side icons.
// Must be >= BG_CIRCLE_SIZE - NAV_HEIGHT so the circle's bottom stays at/above the
// bar's bottom edge. Going lower pushes the circle into the system navigation inset,
// where the OS draws its nav bar over our app and crops the circle.
private val PROTRUSION = 24.dp

enum class BottomTab(
    val route: String,
    val labelResId: Int,
    val selectedIcon: ImageVector?,
    val unselectedIcon: ImageVector?,
    val selectedIconRes: Int? = null,
    val unselectedIconRes: Int? = null
) {
    FEED(Routes.FEED, R.string.nav_feed, null, null, R.drawable.ic_nav_home, R.drawable.ic_nav_home_outline),
    RECIPES(Routes.RECIPES, R.string.nav_recipes, null, null, R.drawable.ic_nav_recipes, R.drawable.ic_nav_recipes),
    WALLET(Routes.WALLET, R.string.nav_wallet, null, null, R.drawable.ic_zc_wallet, R.drawable.ic_zc_wallet),
    MESSAGES(Routes.DM_LIST, R.string.nav_messages, null, null, R.drawable.ic_nav_chat, R.drawable.ic_nav_chat_outline),
    NOTIFICATIONS(Routes.NOTIFICATIONS, R.string.nav_notifications, null, null, R.drawable.ic_nav_alert, R.drawable.ic_nav_alert_outline)
}

@Composable
fun WispBottomBar(
    currentRoute: String?,
    hasUnreadHome: Boolean,
    hasUnreadMessages: Boolean,
    hasUnreadNotifications: Boolean,
    isDarkTheme: Boolean = true,
    isZapAnimating: Boolean = false,
    isReplyAnimating: Boolean = false,
    notifSoundEnabled: Boolean = true,
    isReadOnly: Boolean = false,
    onTabSelected: (BottomTab) -> Unit
) {
    val navBarColor = if (isDarkTheme) NavBarDark else MaterialTheme.colorScheme.surface

    if (isReadOnly) {
        ReadOnlyBottomBar(
            currentRoute = currentRoute,
            hasUnreadHome = hasUnreadHome,
            hasUnreadNotifications = hasUnreadNotifications,
            isDarkTheme = isDarkTheme,
            isZapAnimating = isZapAnimating,
            isReplyAnimating = isReplyAnimating,
            notifSoundEnabled = notifSoundEnabled,
            onTabSelected = onTabSelected
        )
        return
    }

    val leftTabs = listOf(BottomTab.FEED, BottomTab.RECIPES)
    val rightTabs = listOf(BottomTab.MESSAGES, BottomTab.NOTIFICATIONS)
    val useBolt = cooking.zap.app.ui.util.useBoltIcon()

    // Overlay container: the bar + nav-inset spacer in a Column, with the elevated
    // circle drawn on TOP of all of it (so neither its top protrusion nor its
    // bottom overflow gets clipped by the bar rect or the gesture-inset region).
    Box(modifier = Modifier.fillMaxWidth()) {
        Column {
            // Nav bar rect — fixed height, side icons centered.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(NAV_HEIGHT)
                    .background(navBarColor)
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    leftTabs.forEach { tab ->
                        SideNavItem(
                            tab = tab,
                            selected = currentRoute == tab.route,
                            hasUnread = when (tab) {
                                BottomTab.FEED -> hasUnreadHome
                                else -> false
                            },
                            isZapAnimating = isZapAnimating,
                            isReplyAnimating = isReplyAnimating,
                            notifSoundEnabled = notifSoundEnabled,
                            useBolt = useBolt,
                            modifier = Modifier.weight(1f),
                            onTabSelected = onTabSelected
                        )
                    }
                    // Center placeholder — same weight as one tab
                    Spacer(Modifier.weight(1f))
                    rightTabs.forEach { tab ->
                        SideNavItem(
                            tab = tab,
                            selected = currentRoute == tab.route,
                            hasUnread = when (tab) {
                                BottomTab.MESSAGES -> hasUnreadMessages
                                BottomTab.NOTIFICATIONS -> hasUnreadNotifications
                                else -> false
                            },
                            isZapAnimating = isZapAnimating,
                            isReplyAnimating = isReplyAnimating,
                            notifSoundEnabled = notifSoundEnabled,
                            useBolt = useBolt,
                            modifier = Modifier.weight(1f),
                            onTabSelected = onTabSelected
                        )
                    }
                }
            }
            // Colored spacer reserving the system nav-bar inset. The elevated circle
            // overflows downward onto this matching background instead of being
            // clipped by the gesture-inset region.
            Spacer(
                Modifier
                    .fillMaxWidth()
                    .windowInsetsBottomHeight(WindowInsets.navigationBars)
                    .background(navBarColor)
            )
        }

        // requiredSize forces a true circle (ignoring the bar's NAV_HEIGHT constraint
        // that would otherwise squash it to an oval). Aligned to the bar's top edge
        // and lifted by PROTRUSION; drawn last so it sits above the bar + spacer.
        Box(
            modifier = Modifier
                .requiredSize(BG_CIRCLE_SIZE)
                .align(Alignment.TopCenter)
                .offset(y = -PROTRUSION)
                .zIndex(1f)
                .clip(CircleShape)
                .background(navBarColor)
                .clickable { onTabSelected(BottomTab.WALLET) },
            contentAlignment = Alignment.Center
        ) {
            // Three layers so each part can be tinted independently by state:
            //  Active   → gradient disc, white bolt, white ring (full brand color)
            //  Inactive → flat grey disc + grey ring (a flat tint overrides the
            //             gradient), with the bolt as the bar background (cutout) —
            //             a monochrome grey mark matching the other inactive icons.
            val walletSelected = currentRoute == BottomTab.WALLET.route
            val inactiveGrey = MaterialTheme.colorScheme.onSurfaceVariant
            Box(
                modifier = Modifier.size(ICON_SIZE),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_zc_wallet_circle),
                    contentDescription = stringResource(R.string.nav_wallet),
                    tint = if (walletSelected) Color.Unspecified else inactiveGrey,
                    modifier = Modifier.fillMaxSize()
                )
                Icon(
                    painter = painterResource(R.drawable.ic_zc_wallet_bolt),
                    contentDescription = null,
                    tint = if (walletSelected) Color.White else navBarColor,
                    modifier = Modifier.fillMaxSize()
                )
                Icon(
                    painter = painterResource(R.drawable.ic_zc_wallet_ring),
                    contentDescription = null,
                    tint = if (walletSelected) {
                        if (isDarkTheme) Color.White else inactiveGrey
                    } else inactiveGrey,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@Composable
private fun SideNavItem(
    tab: BottomTab,
    selected: Boolean,
    hasUnread: Boolean,
    isZapAnimating: Boolean,
    isReplyAnimating: Boolean,
    notifSoundEnabled: Boolean,
    useBolt: Boolean,
    modifier: Modifier = Modifier,
    onTabSelected: (BottomTab) -> Unit
) {
    val zapTint = if (selected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = modifier
            .height(NAV_HEIGHT)
            .clickable { onTabSelected(tab) },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier.requiredSize(SIDE_ICON_SIZE),
            contentAlignment = Alignment.Center
        ) {
            if (tab == BottomTab.NOTIFICATIONS && isZapAnimating) {
                // Zap animation overrides the bell — bolt or bitcoin symbol.
                if (useBolt) {
                    Icon(
                        painter = painterResource(R.drawable.ic_bolt),
                        contentDescription = stringResource(tab.labelResId),
                        tint = zapTint
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.CurrencyBitcoin,
                        contentDescription = stringResource(tab.labelResId),
                        tint = zapTint
                    )
                }
            } else if (tab.selectedIconRes != null) {
                Icon(
                    painter = painterResource(if (selected) tab.selectedIconRes else tab.unselectedIconRes!!),
                    contentDescription = stringResource(tab.labelResId),
                    tint = zapTint
                )
            } else {
                Icon(
                    imageVector = if (selected) tab.selectedIcon!! else tab.unselectedIcon!!,
                    contentDescription = stringResource(tab.labelResId),
                    tint = zapTint
                )
            }

            if (hasUnread) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .align(Alignment.TopEnd)
                        .offset(x = 2.dp, y = (-2).dp)
                        .background(color = UnreadDotColor, shape = CircleShape)
                )
            }

            if (tab == BottomTab.NOTIFICATIONS) {
                val zeroFootprintModifier = Modifier
                    .size(120.dp)
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(
                            constraints.copy(minWidth = 0, minHeight = 0)
                        )
                        layout(0, 0) {
                            placeable.place(-placeable.width / 2, -placeable.height / 2)
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
    }
}

// Read-only layout: 3 items (FEED, RECIPES, NOTIFICATIONS), no WALLET or MESSAGES
@Composable
private fun ReadOnlyBottomBar(
    currentRoute: String?,
    hasUnreadHome: Boolean,
    hasUnreadNotifications: Boolean,
    isDarkTheme: Boolean = true,
    isZapAnimating: Boolean,
    isReplyAnimating: Boolean,
    notifSoundEnabled: Boolean,
    onTabSelected: (BottomTab) -> Unit
) {
    val navBarColor = if (isDarkTheme) NavBarDark else MaterialTheme.colorScheme.surface
    val visibleTabs = listOf(BottomTab.FEED, BottomTab.RECIPES, BottomTab.NOTIFICATIONS)
    val useBolt = cooking.zap.app.ui.util.useBoltIcon()

    Column {
        NavigationBar(
            containerColor = navBarColor,
            modifier = Modifier
                .windowInsetsPadding(NavigationBarDefaults.windowInsets)
                .height(NAV_HEIGHT),
            windowInsets = WindowInsets(0)
        ) {
            visibleTabs.forEach { tab ->
                val selected = currentRoute == tab.route
                val hasUnread = when (tab) {
                    BottomTab.FEED -> hasUnreadHome
                    BottomTab.NOTIFICATIONS -> hasUnreadNotifications
                    else -> false
                }
                NavigationBarItem(
                    selected = selected,
                    onClick = { onTabSelected(tab) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        indicatorColor = Color.Transparent
                    ),
                    icon = {
                        Box(
                            modifier = Modifier.requiredSize(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            val zapTint = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                            if (tab == BottomTab.NOTIFICATIONS && isZapAnimating) {
                                if (useBolt) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_bolt),
                                        contentDescription = stringResource(tab.labelResId),
                                        tint = zapTint
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Outlined.CurrencyBitcoin,
                                        contentDescription = stringResource(tab.labelResId),
                                        tint = zapTint
                                    )
                                }
                            } else if (tab.selectedIconRes != null) {
                                Icon(
                                    painter = painterResource(if (selected) tab.selectedIconRes else tab.unselectedIconRes!!),
                                    contentDescription = stringResource(tab.labelResId),
                                    tint = zapTint
                                )
                            } else {
                                Icon(
                                    imageVector = if (selected) tab.selectedIcon!! else tab.unselectedIcon!!,
                                    contentDescription = stringResource(tab.labelResId),
                                    tint = zapTint
                                )
                            }
                            if (hasUnread) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .align(Alignment.TopEnd)
                                        .offset(x = 2.dp, y = (-2).dp)
                                        .background(color = UnreadDotColor, shape = CircleShape)
                                )
                            }
                            if (tab == BottomTab.NOTIFICATIONS) {
                                val zeroFootprintModifier = Modifier
                                    .size(120.dp)
                                    .layout { measurable, constraints ->
                                        val placeable = measurable.measure(
                                            constraints.copy(minWidth = 0, minHeight = 0)
                                        )
                                        layout(0, 0) {
                                            placeable.place(-placeable.width / 2, -placeable.height / 2)
                                        }
                                    }
                                ZapBurstEffect(isActive = isZapAnimating, modifier = zeroFootprintModifier, soundEnabled = notifSoundEnabled)
                                IcqFlowerBurstEffect(isActive = isReplyAnimating, modifier = zeroFootprintModifier, soundEnabled = notifSoundEnabled)
                            }
                        }
                    },
                    label = null
                )
            }
        }
    }
}
