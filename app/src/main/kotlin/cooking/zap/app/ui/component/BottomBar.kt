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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.outlined.CurrencyBitcoin
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material3.HorizontalDivider
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
import cooking.zap.app.R
import cooking.zap.app.Routes

private val NavBarColor = Color(0xFF1F2937)
private val NAV_HEIGHT = 50.dp
private val BG_CIRCLE_SIZE = 74.dp  // background circle (perfect circle via requiredSize)
private val ICON_SIZE = 53.dp       // zc logo inside the bg circle
private val SIDE_ICON_SIZE = 21.dp  // the four flanking nav icons
// How far the circle's TOP edge rises above the nav bar top edge. The circle
// center then sits at (PROTRUSION below its top) - i.e. lower PROTRUSION = circle
// sits lower / more in line with the side icons.
private val PROTRUSION = 10.dp

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
    isZapAnimating: Boolean = false,
    isReplyAnimating: Boolean = false,
    notifSoundEnabled: Boolean = true,
    isReadOnly: Boolean = false,
    onTabSelected: (BottomTab) -> Unit
) {
    if (isReadOnly) {
        ReadOnlyBottomBar(
            currentRoute = currentRoute,
            hasUnreadHome = hasUnreadHome,
            hasUnreadNotifications = hasUnreadNotifications,
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

    Column {
        // Box is exactly NAV_HEIGHT tall — the circle overflows upward via offset,
        // so scrolling content is only padded by the bar rect, not the circle.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(NavigationBarDefaults.windowInsets)
                .height(NAV_HEIGHT)
                .background(NavBarColor)
        ) {
            // Four nav items flanking the center slot
            Row(
                modifier = Modifier
                    .fillMaxSize(),
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

            // requiredSize forces a true 82x82 circle (ignoring the parent's NAV_HEIGHT
            // constraint that would otherwise squash it to an oval). TopCenter puts the
            // circle's top at the bar's top edge; -PROTRUSION lifts it up so only that
            // much overflows above into the content area.
            Box(
                modifier = Modifier
                    .requiredSize(BG_CIRCLE_SIZE)
                    .align(Alignment.TopCenter)
                    .offset(y = -PROTRUSION)
                    .clip(CircleShape)
                    .background(NavBarColor)
                    .clickable { onTabSelected(BottomTab.WALLET) },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_zc_wallet),
                    contentDescription = stringResource(R.string.nav_wallet),
                    tint = Color.Unspecified,
                    modifier = Modifier.size(ICON_SIZE)
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
                        .background(color = Color(0xFFFF3B30), shape = CircleShape)
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
    isZapAnimating: Boolean,
    isReplyAnimating: Boolean,
    notifSoundEnabled: Boolean,
    onTabSelected: (BottomTab) -> Unit
) {
    val visibleTabs = listOf(BottomTab.FEED, BottomTab.RECIPES, BottomTab.NOTIFICATIONS)
    val useBolt = cooking.zap.app.ui.util.useBoltIcon()

    Column {
        NavigationBar(
            containerColor = NavBarColor,
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
                                        .background(color = Color(0xFFFF3B30), shape = CircleShape)
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
