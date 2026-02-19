package com.wisp.app.ui.screen

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wisp.app.nostr.ProfileData
import com.wisp.app.ui.component.ProfilePicture

data class SuggestionUser(
    val pubkey: String,
    val profile: ProfileData?
)

@Composable
fun OnboardingSuggestionsScreen(
    suggestions: List<SuggestionUser>,
    onContinue: (selectedPubkeys: Set<String>) -> Unit,
    onSkip: () -> Unit
) {
    val selected = remember { mutableStateMapOf<String, Boolean>() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(48.dp))

        Text(
            text = "Find people to follow",
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "Here are some people on Nostr. You can always find more later.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(Modifier.height(16.dp))

        if (suggestions.isEmpty()) {
            Text(
                text = "No suggestions available",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f)
        ) {
            items(suggestions, key = { it.pubkey }) { user ->
                val isSelected = selected[user.pubkey] == true
                SuggestionRow(
                    user = user,
                    isSelected = isSelected,
                    onToggle = {
                        selected[user.pubkey] = !isSelected
                    }
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        val selectedCount = selected.count { it.value }
        Button(
            onClick = {
                onContinue(selected.filter { it.value }.keys)
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            Text(if (selectedCount > 0) "Follow $selectedCount & Continue" else "Continue")
        }

        TextButton(
            onClick = onSkip,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            Text("Skip")
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SuggestionRow(
    user: SuggestionUser,
    isSelected: Boolean,
    onToggle: () -> Unit
) {
    val profile = user.profile
    val displayName = profile?.displayString
        ?: "${user.pubkey.take(8)}...${user.pubkey.takeLast(4)}"

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        ProfilePicture(url = profile?.picture, size = 48)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = displayName,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!profile?.about.isNullOrBlank()) {
                Text(
                    text = profile!!.about!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Checkbox(
            checked = isSelected,
            onCheckedChange = { onToggle() }
        )
    }
}
