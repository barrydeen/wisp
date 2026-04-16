package com.wisp.app.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wisp.app.R
import com.wisp.app.repo.ExtendedNetworkCache
import com.wisp.app.repo.SafetyPreferences
import kotlinx.coroutines.flow.StateFlow

@Composable
fun SafetyFiltersTab(
    safetyPrefs: SafetyPreferences,
    cachedNetwork: StateFlow<ExtendedNetworkCache?>,
    isNetworkReady: () -> Boolean,
    onNavigateToSocialGraph: () -> Unit
) {
    val spamEnabled by safetyPrefs.spamFilterEnabled.collectAsState()
    val wotEnabled by safetyPrefs.wotFilterEnabled.collectAsState()
    val network by cachedNetwork.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(16.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.safety_spam_filter_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Switch(
                checked = spamEnabled,
                onCheckedChange = { safetyPrefs.setSpamFilterEnabled(it) }
            )
        }
        Text(
            text = stringResource(R.string.safety_spam_filter_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )

        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.safety_wot_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Switch(
                checked = wotEnabled,
                onCheckedChange = { safetyPrefs.setWotFilterEnabled(it) }
            )
        }
        Text(
            text = stringResource(R.string.safety_wot_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )

        Spacer(Modifier.height(12.dp))

        val cache = network
        val ready = isNetworkReady()
        if (cache != null && ready) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Column {
                    Text(
                        text = stringResource(R.string.safety_wot_ready, cache.stats.qualifiedCount),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    val ageHours = (System.currentTimeMillis() / 1000 - cache.computedAtEpoch) / 3600
                    val ageText = if (ageHours < 1) "less than an hour"
                    else if (ageHours < 24) "$ageHours hours"
                    else "${ageHours / 24} days"
                    Text(
                        text = stringResource(R.string.safety_wot_last_computed, ageText),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onNavigateToSocialGraph) {
                Text(stringResource(R.string.safety_wot_recompute))
            }
        } else if (cache != null && !ready) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(
                    text = stringResource(R.string.safety_wot_stale),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (wotEnabled) {
                Text(
                    text = stringResource(R.string.safety_wot_inactive),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onNavigateToSocialGraph) {
                Text(stringResource(R.string.safety_wot_recompute))
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(
                    text = stringResource(R.string.safety_wot_not_computed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (wotEnabled) {
                Text(
                    text = stringResource(R.string.safety_wot_inactive),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onNavigateToSocialGraph) {
                Text(stringResource(R.string.safety_wot_compute_now))
            }
        }
    }
}
