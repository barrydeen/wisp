package com.wisp.app.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.wisp.app.repo.PowPreferences
import com.wisp.app.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PowSettingsScreen(
    powPrefs: PowPreferences,
    onBack: () -> Unit
) {
    var noteEnabled by remember { mutableStateOf(powPrefs.isNotePowEnabled()) }
    var noteDifficulty by remember { mutableIntStateOf(powPrefs.getNoteDifficulty()) }
    var reactionEnabled by remember { mutableStateOf(powPrefs.isReactionPowEnabled()) }
    var reactionDifficulty by remember { mutableIntStateOf(powPrefs.getReactionDifficulty()) }
    var dmEnabled by remember { mutableStateOf(powPrefs.isDmPowEnabled()) }
    var dmDifficulty by remember { mutableIntStateOf(powPrefs.getDmDifficulty()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.drawer_proof_of_work)) },
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(
                text = stringResource(R.string.pow_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(24.dp))

            // Notes section
            Text(
                text = stringResource(R.string.pow_notes),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.pow_enable_notes), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(R.string.pow_notes_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = noteEnabled,
                    onCheckedChange = {
                        noteEnabled = it
                        powPrefs.setNotePowEnabled(it)
                    }
                )
            }
            if (noteEnabled) {
                Spacer(Modifier.height(12.dp))
                DifficultySelector(
                    label = stringResource(R.string.pow_notes_difficulty),
                    value = noteDifficulty,
                    onValueChange = {
                        noteDifficulty = it
                        powPrefs.setNoteDifficulty(it)
                    }
                )
            }

            Spacer(Modifier.height(24.dp))

            // Reactions section
            Text(
                text = stringResource(R.string.pow_reactions),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.pow_enable_reactions), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(R.string.pow_reactions_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = reactionEnabled,
                    onCheckedChange = {
                        reactionEnabled = it
                        powPrefs.setReactionPowEnabled(it)
                    }
                )
            }
            if (reactionEnabled) {
                Spacer(Modifier.height(12.dp))
                DifficultySelector(
                    label = stringResource(R.string.pow_reactions_difficulty),
                    value = reactionDifficulty,
                    onValueChange = {
                        reactionDifficulty = it
                        powPrefs.setReactionDifficulty(it)
                    }
                )
            }

            Spacer(Modifier.height(24.dp))

            // DMs section
            Text(
                text = stringResource(R.string.pow_dms),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.pow_enable_dms), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(R.string.pow_dms_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = dmEnabled,
                    onCheckedChange = {
                        dmEnabled = it
                        powPrefs.setDmPowEnabled(it)
                    }
                )
            }
            if (dmEnabled) {
                Spacer(Modifier.height(12.dp))
                DifficultySelector(
                    label = stringResource(R.string.pow_dms_difficulty),
                    value = dmDifficulty,
                    onValueChange = {
                        dmDifficulty = it
                        powPrefs.setDmDifficulty(it)
                    }
                )
            }
        }
    }
}

@Composable
private fun DifficultySelector(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        IconButton(
            onClick = { onValueChange((value - 1).coerceIn(8, 32)) },
            enabled = value > 8,
            modifier = Modifier.size(36.dp)
        ) {
            Text("\u2212", style = MaterialTheme.typography.titleMedium)
        }
        Text(
            text = stringResource(R.string.pow_bits_format, value),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(56.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        IconButton(
            onClick = { onValueChange((value + 1).coerceIn(8, 32)) },
            enabled = value < 32,
            modifier = Modifier.size(36.dp)
        ) {
            Text("+", style = MaterialTheme.typography.titleMedium)
        }
    }
}
