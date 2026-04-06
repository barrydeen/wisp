package com.wisp.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.wisp.app.relay.LocalRelayConfig
import com.wisp.app.relay.LocalRelayWritePolicy
import com.wisp.app.relay.RelayConfig
import com.wisp.app.relay.RelayPool
import com.wisp.app.relay.RelaySetType
import com.wisp.app.R
import com.wisp.app.viewmodel.RelayViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelayScreen(
    viewModel: RelayViewModel,
    relayPool: RelayPool? = null,
    onBack: () -> Unit,
    signer: com.wisp.app.nostr.NostrSigner? = null
) {
    val context = LocalContext.current
    val selectedTab by viewModel.selectedTab.collectAsState()
    val relays by viewModel.relays.collectAsState()
    val dmRelays by viewModel.dmRelays.collectAsState()
    val searchRelays by viewModel.searchRelays.collectAsState()
    val blockedRelays by viewModel.blockedRelays.collectAsState()
    val newRelayUrl by viewModel.newRelayUrl.collectAsState()
    val localRelayConfig by viewModel.localRelay.collectAsState()

    val tabs = RelaySetType.entries

    Scaffold(
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_relays)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            ScrollableTabRow(
                selectedTabIndex = tabs.indexOf(selectedTab),
                edgePadding = 0.dp
            ) {
                tabs.forEach { tab ->
                    Tab(
                        selected = selectedTab == tab,
                        onClick = { viewModel.selectTab(tab) },
                        text = { Text(tab.displayName) }
                    )
                }
            }

            Column(modifier = Modifier.padding(16.dp)) {
                if (selectedTab == RelaySetType.LOCAL) {
                    // Local tab: show URL input only when no relay is configured
                    if (localRelayConfig == null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedTextField(
                                value = newRelayUrl,
                                onValueChange = { viewModel.updateNewRelayUrl(it) },
                                label = { Text(stringResource(R.string.local_relay_url_hint)) },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(8.dp))
                            IconButton(onClick = { viewModel.addRelay() }) {
                                Icon(Icons.Default.Add, stringResource(R.string.cd_add_relay))
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }

                    LocalRelayTab(localRelayConfig, viewModel)
                } else {
                    // Network relay tabs: URL input + broadcast
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = newRelayUrl,
                            onValueChange = { viewModel.updateNewRelayUrl(it) },
                            label = { Text(stringResource(R.string.placeholder_relay_url)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        IconButton(onClick = { viewModel.addRelay() }) {
                            Icon(Icons.Default.Add, stringResource(R.string.cd_add_relay))
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    if (relayPool != null) {
                        val buttonLabel = when (selectedTab) {
                            RelaySetType.GENERAL -> stringResource(R.string.broadcast_nip65)
                            RelaySetType.DM -> stringResource(R.string.broadcast_dm_relays)
                            RelaySetType.SEARCH -> stringResource(R.string.broadcast_search_relays)
                            RelaySetType.BLOCKED -> stringResource(R.string.broadcast_blocked_relays)
                            RelaySetType.LOCAL -> "" // unreachable
                        }
                        val successMsg = stringResource(R.string.error_relay_broadcast)
                        val failureMsg = stringResource(R.string.error_broadcast_failed)
                        Button(
                            onClick = {
                                val ok = viewModel.publishRelayList(relayPool, signer = signer)
                                val msg = if (ok) successMsg else failureMsg
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(buttonLabel)
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    when (selectedTab) {
                        RelaySetType.GENERAL -> GeneralRelayList(relays, viewModel)
                        RelaySetType.DM -> SimpleRelayList(dmRelays, viewModel)
                        RelaySetType.SEARCH -> SimpleRelayList(searchRelays, viewModel)
                        RelaySetType.BLOCKED -> SimpleRelayList(blockedRelays, viewModel)
                        RelaySetType.LOCAL -> {} // handled above
                    }
                }
            }
        }
    }
}

@Composable
private fun GeneralRelayList(relays: List<RelayConfig>, viewModel: RelayViewModel) {
    LazyColumn {
        items(items = relays.distinctBy { it.url }, key = { it.url }) { relay ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = relay.url,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = relay.read,
                            onClick = { viewModel.toggleRead(relay.url) },
                            label = { Text(stringResource(R.string.relay_read)) }
                        )
                        FilterChip(
                            selected = relay.write,
                            onClick = { viewModel.toggleWrite(relay.url) },
                            label = { Text(stringResource(R.string.relay_write)) }
                        )
                        FilterChip(
                            selected = relay.auth,
                            onClick = { viewModel.toggleAuth(relay.url) },
                            label = { Text(stringResource(R.string.relay_auth)) }
                        )
                    }
                }
                IconButton(onClick = { viewModel.removeRelay(relay.url) }) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.cd_remove_relay),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun LocalRelayTab(config: LocalRelayConfig?, viewModel: RelayViewModel) {
    if (config == null) return

    Column {
        // URL + enabled toggle
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = config.url,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Switch(
                checked = config.enabled,
                onCheckedChange = { viewModel.toggleLocalRelayEnabled() }
            )
        }

        Spacer(Modifier.height(12.dp))

        // Write policy selector
        Text(
            text = stringResource(R.string.local_relay_write_policy),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        LocalRelayWritePolicy.entries.forEach { policy ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = config.writePolicy == policy,
                    onClick = { viewModel.updateLocalRelayPolicy(policy) }
                )
                Text(
                    text = when (policy) {
                        LocalRelayWritePolicy.OWN_NOTES -> stringResource(R.string.local_relay_own_notes)
                        LocalRelayWritePolicy.TAGGED -> stringResource(R.string.local_relay_tagged)
                        LocalRelayWritePolicy.ALL_NOTES -> stringResource(R.string.local_relay_all_notes)
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // Kind chips
        Text(
            text = stringResource(R.string.local_relay_kinds),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = 1 in config.kinds,
                onClick = {
                    val updated = if (1 in config.kinds) config.kinds - 1 else config.kinds + 1
                    viewModel.updateLocalRelayKinds(updated)
                },
                label = { Text(stringResource(R.string.local_relay_kind_notes)) }
            )
            FilterChip(
                selected = 1059 in config.kinds,
                onClick = {
                    val updated = if (1059 in config.kinds) config.kinds - 1059 else config.kinds + 1059
                    viewModel.updateLocalRelayKinds(updated)
                },
                label = { Text(stringResource(R.string.local_relay_kind_dms)) }
            )
            FilterChip(
                selected = 9735 in config.kinds,
                onClick = {
                    val updated = if (9735 in config.kinds) config.kinds - 9735 else config.kinds + 9735
                    viewModel.updateLocalRelayKinds(updated)
                },
                label = { Text(stringResource(R.string.local_relay_kind_zaps)) }
            )
        }

        // Custom kind input
        var customKindInput by remember { mutableStateOf("") }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 4.dp)
        ) {
            OutlinedTextField(
                value = customKindInput,
                onValueChange = { customKindInput = it.filter { c -> c.isDigit() } },
                label = { Text(stringResource(R.string.local_relay_add_kind)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = {
                val kind = customKindInput.toIntOrNull()
                if (kind != null && kind !in config.kinds) {
                    viewModel.updateLocalRelayKinds(config.kinds + kind)
                    customKindInput = ""
                }
            }) {
                Icon(Icons.Default.Add, stringResource(R.string.cd_add_relay))
            }
        }

        // Show custom kinds as removable chips
        val customKinds = config.kinds - setOf(1, 1059, 9735)
        if (customKinds.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                customKinds.forEach { kind ->
                    FilterChip(
                        selected = true,
                        onClick = { viewModel.updateLocalRelayKinds(config.kinds - kind) },
                        label = { Text("Kind $kind") }
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        TextButton(
            onClick = { viewModel.removeRelay(config.url) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                stringResource(R.string.local_relay_remove),
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun SimpleRelayList(urls: List<String>, viewModel: RelayViewModel) {
    LazyColumn {
        items(items = urls.distinct(), key = { it }) { url ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                Text(
                    text = url,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { viewModel.removeRelay(url) }) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.cd_remove_relay),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}
