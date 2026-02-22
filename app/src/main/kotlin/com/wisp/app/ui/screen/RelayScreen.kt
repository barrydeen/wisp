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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wisp.app.relay.RelayConfig
import com.wisp.app.relay.RelayPool
import com.wisp.app.relay.RelaySetType
import com.wisp.app.viewmodel.RelayViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelayScreen(
    viewModel: RelayViewModel,
    relayPool: RelayPool? = null,
    onBack: () -> Unit,
    signer: com.wisp.app.nostr.NostrSigner? = null
) {
    val selectedTab by viewModel.selectedTab.collectAsState()
    val relays by viewModel.relays.collectAsState()
    val dmRelays by viewModel.dmRelays.collectAsState()
    val searchRelays by viewModel.searchRelays.collectAsState()
    val blockedRelays by viewModel.blockedRelays.collectAsState()
    val newRelayUrl by viewModel.newRelayUrl.collectAsState()

    val tabs = RelaySetType.entries

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Relays") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
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
            TabRow(selectedTabIndex = tabs.indexOf(selectedTab)) {
                tabs.forEach { tab ->
                    Tab(
                        selected = selectedTab == tab,
                        onClick = { viewModel.selectTab(tab) },
                        text = { Text(tab.displayName) }
                    )
                }
            }

            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = newRelayUrl,
                        onValueChange = { viewModel.updateNewRelayUrl(it) },
                        label = { Text("wss://...") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = { viewModel.addRelay() }) {
                        Icon(Icons.Default.Add, "Add relay")
                    }
                }

                Spacer(Modifier.height(8.dp))

                if (relayPool != null) {
                    val buttonLabel = when (selectedTab) {
                        RelaySetType.GENERAL -> "Broadcast Relay List (NIP-65)"
                        RelaySetType.DM -> "Broadcast DM Relays"
                        RelaySetType.SEARCH -> "Broadcast Search Relays"
                        RelaySetType.BLOCKED -> "Broadcast Blocked Relays"
                    }
                    Button(
                        onClick = { viewModel.publishRelayList(relayPool, signer = signer) },
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
                }
            }
        }
    }
}

@Composable
private fun GeneralRelayList(relays: List<RelayConfig>, viewModel: RelayViewModel) {
    LazyColumn {
        items(items = relays, key = { it.url }) { relay ->
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
                            label = { Text("read") }
                        )
                        FilterChip(
                            selected = relay.write,
                            onClick = { viewModel.toggleWrite(relay.url) },
                            label = { Text("write") }
                        )
                    }
                }
                IconButton(onClick = { viewModel.removeRelay(relay.url) }) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Remove",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun SimpleRelayList(urls: List<String>, viewModel: RelayViewModel) {
    LazyColumn {
        items(items = urls, key = { it }) { url ->
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
                        contentDescription = "Remove",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}
