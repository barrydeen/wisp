package com.wisp.app.ui.screen

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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.wisp.app.R
import com.wisp.app.repo.MuteRepository
import com.wisp.app.repo.ProfileRepository
import com.wisp.app.ui.component.ProfilePicture

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SafetyScreen(
    muteRepo: MuteRepository,
    profileRepo: ProfileRepository,
    onBack: () -> Unit,
    onChanged: () -> Unit = {}
) {
    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_safety)) },
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
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text(stringResource(R.string.tab_muted_words)) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text(stringResource(R.string.tab_muted_users)) }
                )
            }

            when (selectedTab) {
                0 -> MutedWordsTab(muteRepo, onChanged)
                1 -> MutedUsersTab(muteRepo, profileRepo, onChanged)
            }
        }
    }
}

@Composable
private fun MutedWordsTab(
    muteRepo: MuteRepository,
    onChanged: () -> Unit
) {
    val mutedWords by muteRepo.mutedWords.collectAsState()
    var newWord by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Text(
            text = stringResource(R.string.settings_muted_words_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 12.dp)
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = newWord,
                onValueChange = { newWord = it },
                placeholder = { Text(stringResource(R.string.placeholder_add_word_phrase)) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = {
                    val word = newWord.trim()
                    if (word.isNotEmpty()) {
                        muteRepo.addMutedWord(word)
                        newWord = ""
                        onChanged()
                    }
                }
            ) {
                Icon(Icons.Default.Add, stringResource(R.string.cd_add_word))
            }
        }

        Spacer(Modifier.height(16.dp))

        LazyColumn {
            items(items = mutedWords.toList().sorted()) { word ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                ) {
                    Text(
                        text = word,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = {
                            muteRepo.removeMutedWord(word)
                            onChanged()
                        }
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.btn_remove),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MutedUsersTab(
    muteRepo: MuteRepository,
    profileRepo: ProfileRepository,
    onChanged: () -> Unit
) {
    val blockedPubkeys by muteRepo.blockedPubkeys.collectAsState()
    val sorted = remember(blockedPubkeys) { blockedPubkeys.toList().sorted() }

    if (sorted.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(32.dp))
            Text(
                text = stringResource(R.string.settings_no_muted_users),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            items(items = sorted, key = { it }) { pubkey ->
                val profile = profileRepo.get(pubkey)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                ) {
                    ProfilePicture(url = profile?.picture, size = 40)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = profile?.displayString ?: (pubkey.take(16) + "..."),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (profile != null) {
                            Text(
                                text = pubkey.take(16) + "...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    IconButton(
                        onClick = {
                            muteRepo.unblockUser(pubkey)
                            onChanged()
                        }
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.btn_unblock),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}
