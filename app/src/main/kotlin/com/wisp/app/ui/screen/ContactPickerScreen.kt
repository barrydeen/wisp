package com.wisp.app.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wisp.app.R
import com.wisp.app.repo.ContactRepository
import com.wisp.app.repo.EventRepository
import com.wisp.app.ui.component.ProfilePicture
import com.wisp.app.viewmodel.DmListViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactPickerScreen(
    viewModel: DmListViewModel,
    eventRepo: EventRepository,
    contactRepo: ContactRepository,
    onBack: () -> Unit,
    onConfirm: (conversationKey: String) -> Unit,
    myPubkey: String
) {
    val followList by contactRepo.followList.collectAsState()
    val selected by viewModel.selectedContacts.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }

    val filteredList = remember(followList, query) {
        if (query.isBlank()) followList
        else {
            val q = query.trim().lowercase()
            followList.filter { entry ->
                val profile = eventRepo.getProfileData(entry.pubkey)
                profile?.displayString?.lowercase()?.contains(q) == true ||
                profile?.nip05?.lowercase()?.contains(q) == true ||
                entry.pubkey.contains(q)
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.new_group_dm)) },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.clearContactSelection()
                        onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            if (selected.isNotEmpty()) {
                Button(
                    onClick = {
                        val convKey = viewModel.createGroupConversation(selected.toList(), myPubkey)
                        viewModel.clearContactSelection()
                        onConfirm(convKey)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text("Start Group DM (${selected.size} selected)")
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.nav_search)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(items = filteredList, key = { it.pubkey }) { entry ->
                val profile = remember(entry.pubkey) { eventRepo.getProfileData(entry.pubkey) }
                val isSelected = selected.contains(entry.pubkey)

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.toggleContactSelection(entry.pubkey) }
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { viewModel.toggleContactSelection(entry.pubkey) }
                    )
                    Spacer(Modifier.width(12.dp))
                    ProfilePicture(url = profile?.picture, size = 40)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = profile?.displayString
                                ?: entry.pubkey.take(8) + "..." + entry.pubkey.takeLast(4),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (profile?.nip05 != null) {
                            Text(
                                text = profile.nip05,
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
        } // end Column
    }
}
