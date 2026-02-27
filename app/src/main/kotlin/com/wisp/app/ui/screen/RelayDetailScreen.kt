package com.wisp.app.ui.screen

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wisp.app.nostr.RelayInfo
import com.wisp.app.relay.ConsoleLogEntry
import com.wisp.app.relay.ConsoleLogType
import com.wisp.app.relay.RelayHealthTracker
import com.wisp.app.repo.RelayInfoRepository
import com.wisp.app.nostr.ProfileData
import com.wisp.app.nostr.RelaySet
import com.wisp.app.ui.component.ProfilePicture
import com.wisp.app.ui.component.RelayIcon
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RelayDetailScreen(
    relayUrl: String,
    relayInfoRepo: RelayInfoRepository,
    healthTracker: RelayHealthTracker,
    consoleEntries: List<ConsoleLogEntry>,
    operatorProfile: ProfileData?,
    isFavorite: Boolean = false,
    relaySets: List<RelaySet> = emptyList(),
    onBack: () -> Unit,
    onOperatorClick: ((String) -> Unit)? = null,
    onToggleFavorite: (() -> Unit)? = null,
    onAddToRelaySet: ((String) -> Unit)? = null,
    onCreateRelaySet: ((String) -> Unit)? = null
) {
    var relayInfo by remember { mutableStateOf(relayInfoRepo.getInfo(relayUrl)) }
    val iconUrl = remember(relayUrl) { relayInfoRepo.getIconUrl(relayUrl) }
    val stats = remember(relayUrl) { healthTracker.getStats(relayUrl) }
    val isBad = remember(relayUrl) { healthTracker.isBad(relayUrl) }

    // Fetch relay info if not cached
    LaunchedEffect(relayUrl) {
        if (relayInfo == null || relayInfo?.name == null) {
            relayInfo = relayInfoRepo.fetchInfo(relayUrl)
        }
    }

    val domain = remember(relayUrl) {
        relayUrl.removePrefix("wss://").removePrefix("ws://").removeSuffix("/")
    }

    val relayConsoleEntries = remember(consoleEntries, relayUrl) {
        consoleEntries.filter { it.relayUrl == relayUrl }.takeLast(50).reversed()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        domain,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
            // -- Header: Icon + Name + Description --
            item {
                RelayHeader(
                    relayUrl = relayUrl,
                    iconUrl = iconUrl,
                    info = relayInfo,
                    domain = domain,
                    isBad = isBad,
                    operatorProfile = operatorProfile,
                    isFavorite = isFavorite,
                    relaySets = relaySets,
                    onOperatorClick = onOperatorClick,
                    onClearBad = { healthTracker.clearBadRelay(relayUrl) },
                    onToggleFavorite = onToggleFavorite,
                    onAddToRelaySet = onAddToRelaySet,
                    onCreateRelaySet = onCreateRelaySet
                )
            }

            // -- Stats Section --
            if (stats != null) {
                item {
                    SectionHeader("Statistics")
                }
                item {
                    StatsGrid(stats)
                }
            }

            // -- NIP-11 Details --
            if (relayInfo != null && hasDetails(relayInfo!!)) {
                item {
                    SectionHeader("Relay Information")
                }
                item {
                    RelayInfoDetails(relayInfo!!)
                }
            }

            // -- Supported NIPs --
            if (relayInfo != null && relayInfo!!.supportedNips.isNotEmpty()) {
                item {
                    SectionHeader("Supported NIPs")
                }
                item {
                    NipChips(relayInfo!!.supportedNips)
                }
            }

            // -- Limitations --
            if (relayInfo != null && hasLimitations(relayInfo!!)) {
                item {
                    SectionHeader("Limitations")
                }
                item {
                    LimitationsSection(relayInfo!!)
                }
            }

            // -- Console Log --
            if (relayConsoleEntries.isNotEmpty()) {
                item {
                    SectionHeader("Recent Log (${relayConsoleEntries.size})")
                }
                items(relayConsoleEntries) { entry ->
                    ConsoleEntryItem(entry)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                }
            }

            // Bottom spacer
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun RelayHeader(
    relayUrl: String,
    iconUrl: String?,
    info: RelayInfo?,
    domain: String,
    isBad: Boolean,
    operatorProfile: ProfileData?,
    isFavorite: Boolean = false,
    relaySets: List<RelaySet> = emptyList(),
    onOperatorClick: ((String) -> Unit)?,
    onClearBad: () -> Unit,
    onToggleFavorite: (() -> Unit)? = null,
    onAddToRelaySet: ((String) -> Unit)? = null,
    onCreateRelaySet: ((String) -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Large relay icon
        RelayIcon(
            iconUrl = iconUrl,
            relayUrl = relayUrl,
            size = 72.dp
        )

        Spacer(Modifier.height(12.dp))

        // Relay name
        Text(
            text = info?.name ?: domain,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        // URL (if name is different from domain)
        if (info?.name != null) {
            Text(
                text = domain,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace
            )
        }

        Spacer(Modifier.height(8.dp))

        // Status badges row
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isBad) {
                Surface(
                    onClick = onClearBad,
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
                ) {
                    Text(
                        "Marked Bad — Tap to Clear",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            if (info?.paymentRequired == true) {
                StatusBadge("Paid", Color(0xFFFFD54F))
            }
            if (info?.authRequired == true) {
                StatusBadge("Auth Required", Color(0xFF90CAF9))
            }
            if (info?.restrictedWrites == true) {
                StatusBadge("Restricted Writes", Color(0xFFCE93D8))
            }
            if (info != null && info.isOpenPublicRelay()) {
                StatusBadge("Open", Color(0xFF81C784))
            }
        }

        // Action buttons: Favorite + Add to Set
        if (onToggleFavorite != null || onAddToRelaySet != null) {
            Spacer(Modifier.height(12.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (onToggleFavorite != null) {
                    Surface(
                        onClick = onToggleFavorite,
                        shape = RoundedCornerShape(12.dp),
                        color = if (isFavorite) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                               else MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (isFavorite) "\u2605" else "\u2606",
                                style = MaterialTheme.typography.titleMedium,
                                color = if (isFavorite) MaterialTheme.colorScheme.primary
                                       else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                if (isFavorite) "Favorited" else "Favorite",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (isFavorite) MaterialTheme.colorScheme.primary
                                       else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                if (onAddToRelaySet != null) {
                    var showSetPicker by remember { mutableStateOf(false) }
                    var newSetName by remember { mutableStateOf("") }

                    Surface(
                        onClick = { showSetPicker = true },
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            "Add to Set",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }

                    if (showSetPicker) {
                        androidx.compose.material3.AlertDialog(
                            onDismissRequest = { showSetPicker = false },
                            title = { Text("Add to Relay Set") },
                            text = {
                                Column {
                                    if (relaySets.isNotEmpty()) {
                                        for (set in relaySets) {
                                            val contains = relayUrl in set.relays
                                            Surface(
                                                onClick = {
                                                    onAddToRelaySet(set.dTag)
                                                    showSetPicker = false
                                                },
                                                shape = RoundedCornerShape(8.dp),
                                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        set.name,
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        modifier = Modifier.weight(1f)
                                                    )
                                                    if (contains) {
                                                        Text(
                                                            "\u2713",
                                                            style = MaterialTheme.typography.bodyMedium,
                                                            color = MaterialTheme.colorScheme.primary
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                        HorizontalDivider(
                                            modifier = Modifier.padding(vertical = 8.dp),
                                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                                        )
                                    }
                                    // Create new set
                                    androidx.compose.material3.OutlinedTextField(
                                        value = newSetName,
                                        onValueChange = { newSetName = it },
                                        placeholder = { Text("New set name") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                        trailingIcon = {
                                            if (newSetName.isNotBlank()) {
                                                IconButton(onClick = {
                                                    onCreateRelaySet?.invoke(newSetName.trim())
                                                    newSetName = ""
                                                    showSetPicker = false
                                                }) {
                                                    Text("Create", style = MaterialTheme.typography.labelSmall)
                                                }
                                            }
                                        }
                                    )
                                }
                            },
                            confirmButton = {},
                            dismissButton = {
                                TextButton(onClick = { showSetPicker = false }) { Text("Cancel") }
                            }
                        )
                    }
                }
            }
        }

        // Description
        if (!info?.description.isNullOrBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = info!!.description!!,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Operator
        if (info?.pubkey != null || info?.contact != null || operatorProfile != null) {
            Spacer(Modifier.height(12.dp))
            OperatorRow(info, operatorProfile, onOperatorClick)
        }
    }
}

@Composable
private fun StatusBadge(label: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = color.copy(alpha = 0.15f)
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

@Composable
private fun OperatorRow(
    info: RelayInfo?,
    operatorProfile: ProfileData?,
    onOperatorClick: ((String) -> Unit)?
) {
    val clickable = info?.pubkey != null && onOperatorClick != null
    Surface(
        onClick = { if (clickable) onOperatorClick!!(info!!.pubkey!!) },
        enabled = clickable,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ProfilePicture(url = operatorProfile?.picture, size = 40)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Operator",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    operatorProfile?.displayString
                        ?: info?.contact
                        ?: info?.pubkey?.take(16)?.plus("...")
                        ?: "Unknown",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (info?.contact != null && operatorProfile != null) {
                    Text(
                        info.contact,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp)
    )
}

@Composable
private fun StatsGrid(stats: RelayHealthTracker.RelayStats) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatCard("Events Received", formatNumber(stats.totalEventsReceived), Modifier.weight(1f))
            StatCard("Events Sent", formatNumber(stats.totalEventsSent), Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatCard("Data Received", formatBytes(stats.bytesReceived), Modifier.weight(1f))
            StatCard("Data Sent", formatBytes(stats.bytesSent), Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatCard("Connections", stats.totalConnections.toString(), Modifier.weight(1f))
            StatCard("Uptime", formatDuration(stats.totalConnectedMs), Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatCard(
                "Failures", stats.totalFailures.toString(), Modifier.weight(1f),
                valueColor = if (stats.totalFailures > 0) Color(0xFFFF5252) else null
            )
            StatCard(
                "Rate Limits", stats.totalRateLimits.toString(), Modifier.weight(1f),
                valueColor = if (stats.totalRateLimits > 0) Color(0xFFFFD54F) else null
            )
        }

        // Timeline
        if (stats.firstSeenAt > 0) {
            Spacer(Modifier.height(8.dp))
            val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatCard("First Seen", dateFormat.format(Date(stats.firstSeenAt)), Modifier.weight(1f))
                StatCard(
                    "Last Connected",
                    if (stats.lastConnectedAt > 0) dateFormat.format(Date(stats.lastConnectedAt)) else "—",
                    Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun StatCard(
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
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
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

private fun hasDetails(info: RelayInfo): Boolean =
    info.software != null || info.version != null

private fun hasLimitations(info: RelayInfo): Boolean =
    info.maxMessageLength != null || info.maxSubscriptions != null ||
            info.maxContentLength != null

@Composable
private fun RelayInfoDetails(info: RelayInfo) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                if (info.software != null) {
                    DetailRow("Software", info.software.removePrefix("git+"))
                }
                if (info.version != null) {
                    DetailRow("Version", info.version)
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(200.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.End
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NipChips(nips: List<Int>) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        for (nip in nips.sorted()) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.border(
                    0.5.dp,
                    MaterialTheme.colorScheme.outline,
                    RoundedCornerShape(8.dp)
                )
            ) {
                Text(
                    text = nip.toString().padStart(2, '0'),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun LimitationsSection(info: RelayInfo) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            if (info.maxMessageLength != null) {
                DetailRow("Max Message", formatBytes(info.maxMessageLength.toLong()))
            }
            if (info.maxContentLength != null) {
                DetailRow("Max Content", formatBytes(info.maxContentLength.toLong()))
            }
            if (info.maxSubscriptions != null) {
                DetailRow("Max Subscriptions", info.maxSubscriptions.toString())
            }
        }
    }
}

@Composable
private fun ConsoleEntryItem(entry: ConsoleLogEntry) {
    val typeLabel = when (entry.type) {
        ConsoleLogType.OK_REJECTED -> "REJECTED"
        ConsoleLogType.NOTICE -> "NOTICE"
        ConsoleLogType.CONN_FAILURE -> "FAILURE"
        ConsoleLogType.CONN_CLOSED -> "CLOSED"
    }
    val typeColor = when (entry.type) {
        ConsoleLogType.OK_REJECTED, ConsoleLogType.CONN_FAILURE -> MaterialTheme.colorScheme.error
        ConsoleLogType.NOTICE -> MaterialTheme.colorScheme.tertiary
        ConsoleLogType.CONN_CLOSED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = typeLabel,
            style = MaterialTheme.typography.labelSmall,
            color = typeColor,
            modifier = Modifier.width(64.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = timeFormat.format(Date(entry.timestamp)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// -- Formatting Helpers --

private fun formatNumber(n: Long): String = when {
    n >= 1_000_000 -> String.format("%.1fM", n / 1_000_000.0)
    n >= 1_000 -> String.format("%.1fK", n / 1_000.0)
    else -> n.toString()
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824 -> String.format("%.1f GB", bytes / 1_073_741_824.0)
    bytes >= 1_048_576 -> String.format("%.1f MB", bytes / 1_048_576.0)
    bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    return when {
        hours > 24 -> "${hours / 24}d ${hours % 24}h"
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m"
        else -> "${totalSeconds}s"
    }
}
