package com.wisp.app.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.wisp.app.R
import com.wisp.app.ui.component.ProfilePicture
import com.wisp.app.ui.component.RelayIcon
import com.wisp.app.ui.theme.WispThemeColors
import com.wisp.app.viewmodel.HealthFilter
import com.wisp.app.viewmodel.HealthSortMode
import com.wisp.app.viewmodel.RelayHealthSummary
import com.wisp.app.viewmodel.RelayHealthViewModel
import com.wisp.app.viewmodel.RelayType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelayHealthScreen(
    viewModel: RelayHealthViewModel,
    onBack: () -> Unit,
    onRelayDetail: (String) -> Unit
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_relay_health)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Summary row
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SummaryCard(
                        label = stringResource(R.string.tab_connected),
                        value = "${state.totalConnected}/${state.totalRelays}",
                        modifier = Modifier.weight(1f)
                    )
                    SummaryCard(
                        label = stringResource(R.string.tab_bad),
                        value = state.totalBad.toString(),
                        modifier = Modifier.weight(1f),
                        valueColor = if (state.totalBad > 0) Color(0xFFFF5252) else null
                    )
                }
            }

            // Sort/filter chips
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = state.sortMode == HealthSortMode.STATUS,
                        onClick = { viewModel.setSortMode(HealthSortMode.STATUS) },
                        label = { Text(stringResource(R.string.tab_status), style = MaterialTheme.typography.labelSmall) }
                    )
                    FilterChip(
                        selected = state.sortMode == HealthSortMode.FAILURES,
                        onClick = { viewModel.setSortMode(HealthSortMode.FAILURES) },
                        label = { Text(stringResource(R.string.tab_failures), style = MaterialTheme.typography.labelSmall) }
                    )
                    FilterChip(
                        selected = state.sortMode == HealthSortMode.EVENTS,
                        onClick = { viewModel.setSortMode(HealthSortMode.EVENTS) },
                        label = { Text(stringResource(R.string.tab_events), style = MaterialTheme.typography.labelSmall) }
                    )
                    FilterChip(
                        selected = state.sortMode == HealthSortMode.NAME,
                        onClick = { viewModel.setSortMode(HealthSortMode.NAME) },
                        label = { Text(stringResource(R.string.tab_name), style = MaterialTheme.typography.labelSmall) }
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = state.filter == HealthFilter.ALL,
                        onClick = { viewModel.setFilter(HealthFilter.ALL) },
                        label = { Text(stringResource(R.string.tab_all), style = MaterialTheme.typography.labelSmall) }
                    )
                    FilterChip(
                        selected = state.filter == HealthFilter.BAD_ONLY,
                        onClick = { viewModel.setFilter(HealthFilter.BAD_ONLY) },
                        label = { Text(stringResource(R.string.tab_bad_only), style = MaterialTheme.typography.labelSmall) }
                    )
                    FilterChip(
                        selected = state.filter == HealthFilter.ERRORS_ONLY,
                        onClick = { viewModel.setFilter(HealthFilter.ERRORS_ONLY) },
                        label = { Text(stringResource(R.string.tab_errors), style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }

            // Relay list
            items(state.relays, key = { it.url }) { relay ->
                RelayHealthItem(
                    relay = relay,
                    onClick = { onRelayDetail(relay.url) },
                    onClearBad = { viewModel.clearBadRelay(relay.url) }
                )
            }

            // Empty state
            if (state.relays.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (state.filter != HealthFilter.ALL) "No relays match this filter"
                            else "No relay data yet",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun SummaryCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color? = null
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = valueColor ?: MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun RelayHealthItem(
    relay: RelayHealthSummary,
    onClick: () -> Unit,
    onClearBad: () -> Unit
) {
    val domain = remember(relay.url) {
        relay.url.removePrefix("wss://").removePrefix("ws://").removeSuffix("/")
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            // Relay icon with connection status overlay
            Box {
                RelayIcon(
                    iconUrl = relay.iconUrl,
                    relayUrl = relay.url,
                    size = 40.dp
                )
                // Connection dot overlaid on bottom-right of icon
                Surface(
                    shape = CircleShape,
                    color = when {
                        relay.isBad -> Color(0xFFFF5252)
                        relay.cooldownRemaining > 0 -> WispThemeColors.paidColor
                        relay.isConnected -> WispThemeColors.repostColor
                        else -> MaterialTheme.colorScheme.outline
                    },
                    modifier = Modifier
                        .size(12.dp)
                        .align(Alignment.BottomEnd)
                ) {}
            }

            Spacer(Modifier.width(12.dp))

            // Info column
            Column(modifier = Modifier.weight(1f)) {
                // Relay name + domain
                Text(
                    text = relay.relayName ?: domain,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (relay.relayName != null) {
                    Text(
                        text = domain,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(Modifier.height(4.dp))

                // Badges row: type + bad/cooldown
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (relay.relayType != RelayType.PERSISTENT) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = when (relay.relayType) {
                                RelayType.DM -> Color(0xFF90CAF9).copy(alpha = 0.2f)
                                RelayType.EPHEMERAL -> MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
                                else -> Color.Transparent
                            }
                        ) {
                            Text(
                                text = if (relay.relayType == RelayType.DM) "DM" else "Ephemeral",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    if (relay.isBad) {
                        Surface(
                            onClick = onClearBad,
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) {
                                Text(
                                    "BAD — Tap to clear",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                                if (relay.badReason != null) {
                                    Text(
                                        relay.badReason,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }

                // Cooldown with reason
                if (!relay.isBad && relay.cooldownRemaining > 0) {
                    Spacer(Modifier.height(2.dp))
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = WispThemeColors.paidColor.copy(alpha = 0.15f)
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) {
                            Text(
                                "Cooldown ${relay.cooldownRemaining}s",
                                style = MaterialTheme.typography.labelSmall,
                                color = WispThemeColors.paidColor
                            )
                            if (relay.cooldownReason != null) {
                                Text(
                                    text = relay.cooldownReason,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))

                // Stats row
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (relay.stats != null) {
                        Text(
                            text = "${formatNumber(relay.stats.totalEventsReceived)} events",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (relay.stats.totalFailures > 0) {
                            Text(
                                text = "${relay.stats.totalFailures} failures",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFFF5252)
                            )
                        }
                        if (relay.stats.totalRateLimits > 0) {
                            Text(
                                text = "${relay.stats.totalRateLimits} rate limits",
                                style = MaterialTheme.typography.labelSmall,
                                color = WispThemeColors.paidColor
                            )
                        }
                    }
                }

                // Session health
                if (relay.sessionHistory.isNotEmpty()) {
                    val okSessions = relay.sessionHistory.count { !it.hadMidSessionFailure }
                    val total = relay.sessionHistory.size
                    val healthColor = when {
                        okSessions.toFloat() / total < 0.5f -> Color(0xFFFF5252)
                        okSessions.toFloat() / total < 0.8f -> WispThemeColors.paidColor
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Text(
                        text = "$okSessions/$total sessions OK",
                        style = MaterialTheme.typography.labelSmall,
                        color = healthColor
                    )
                }

                // Inbox authors — overlapping profile pictures
                if (relay.inboxAuthorCount > 0) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box {
                            relay.inboxAuthors.forEachIndexed { index, author ->
                                Box(
                                    modifier = Modifier
                                        .offset(x = (index * 16).dp)
                                        .zIndex((relay.inboxAuthors.size - index).toFloat())
                                ) {
                                    ProfilePicture(url = author.picture, size = 22)
                                }
                            }
                        }
                        // Offset text past the stacked avatars
                        Spacer(Modifier.width((relay.inboxAuthors.size * 16 + 8).dp))
                        Text(
                            text = if (relay.inboxAuthorCount > relay.inboxAuthors.size)
                                "covers ${relay.inboxAuthorCount} (${relay.inboxAuthorCount - relay.inboxAuthors.size} more)"
                            else
                                "covers ${relay.inboxAuthorCount}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Operator row
                if (relay.operatorName != null || relay.operatorPubkey != null) {
                    Spacer(Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ProfilePicture(url = relay.operatorPicture, size = 18)
                        Text(
                            text = "Op: ${relay.operatorName
                                ?: relay.operatorPubkey?.take(12)?.plus("...")}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
