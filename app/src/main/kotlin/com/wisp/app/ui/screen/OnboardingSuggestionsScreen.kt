package com.wisp.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wisp.app.nostr.ProfileData
import com.wisp.app.ui.component.FollowToggleButton
import com.wisp.app.ui.component.ProfilePicture
import com.wisp.app.ui.component.StackedAvatars
import com.wisp.app.viewmodel.SectionType
import com.wisp.app.viewmodel.SuggestionSection

@Composable
fun OnboardingSuggestionsScreen(
    activeNow: SuggestionSection,
    creators: SuggestionSection,
    news: SuggestionSection,
    selectedPubkeys: Set<String>,
    onToggleFollowAll: (SectionType) -> Unit,
    onTogglePubkey: (String) -> Unit,
    totalSelected: Int,
    onContinue: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(48.dp))

            // Header
            Text(
                text = "Find people to follow",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Follow at least 5 accounts to build your feed",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(24.dp))

            // Section: Creators
            CreatorsSection(
                section = creators,
                selectedPubkeys = selectedPubkeys,
                onTogglePubkey = onTogglePubkey
            )

            Spacer(Modifier.height(24.dp))

            // Section: Active Right Now
            ActiveNowSection(
                section = activeNow,
                selectedPubkeys = selectedPubkeys,
                onToggleFollowAll = { onToggleFollowAll(SectionType.ACTIVE_NOW) },
                onTogglePubkey = onTogglePubkey
            )

            Spacer(Modifier.height(24.dp))

            // Section: News
            NewsSection(
                section = news,
                selectedPubkeys = selectedPubkeys,
                onTogglePubkey = onTogglePubkey
            )

            Spacer(Modifier.height(24.dp))
        }

        // Bottom pinned button — above system nav bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            val enabled = totalSelected >= 5
            Button(
                onClick = onContinue,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    if (enabled) "Follow $totalSelected accounts"
                    else "Select at least 5 ($totalSelected/5)"
                )
            }
        }
    }
}

@Composable
private fun CreatorsSection(
    section: SuggestionSection,
    selectedPubkeys: Set<String>,
    onTogglePubkey: (String) -> Unit
) {
    Text(
        text = "Meet the creators",
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.onSurface
    )
    Spacer(Modifier.height(8.dp))

    if (section.isLoading) {
        Box(modifier = Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        }
    } else if (section.profiles.isEmpty()) {
        // Show cards without profile data
        CreatorCards(profiles = emptyList(), selectedPubkeys = selectedPubkeys, onTogglePubkey = onTogglePubkey)
    } else {
        CreatorCards(profiles = section.profiles, selectedPubkeys = selectedPubkeys, onTogglePubkey = onTogglePubkey)
    }
}

@Composable
private fun CreatorCards(
    profiles: List<ProfileData>,
    selectedPubkeys: Set<String>,
    onTogglePubkey: (String) -> Unit
) {
    val creatorDescriptions = mapOf(
        "3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d" to "Creator of Nostr",
        "e2ccf7cf20403f3f2a4a55b328f0de3be38558a7d5f33632fdaaefc726c1c8eb" to "Creator of Wisp"
    )

    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        creatorDescriptions.forEach { (pubkey, role) ->
            val profile = profiles.find { it.pubkey == pubkey }
            val isSelected = pubkey in selectedPubkeys
            Card(
                modifier = Modifier.weight(1f),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    ProfilePicture(url = profile?.picture, size = 56)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = profile?.displayString ?: pubkey.take(8) + "...",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = role,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(8.dp))
                    FollowToggleButton(
                        isSelected = isSelected,
                        onClick = { onTogglePubkey(pubkey) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ActiveNowSection(
    section: SuggestionSection,
    selectedPubkeys: Set<String>,
    onToggleFollowAll: () -> Unit,
    onTogglePubkey: (String) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Active right now",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
            if (!section.isLoading && section.profiles.isNotEmpty()) {
                Text(
                    text = "${section.profiles.size} people posting right now",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (!section.isLoading && section.profiles.isNotEmpty()) {
            val allSelected = section.profiles.all { it.pubkey in selectedPubkeys }
            FilledTonalButton(
                onClick = onToggleFollowAll,
                shape = RoundedCornerShape(16.dp),
                colors = if (allSelected) ButtonDefaults.filledTonalButtonColors()
                else ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Text(
                    if (allSelected) "Unfollow All" else "Follow All",
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }

    Spacer(Modifier.height(8.dp))

    if (section.isLoading) {
        Box(modifier = Modifier.fillMaxWidth().height(60.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        }
    } else if (section.profiles.isEmpty()) {
        Text(
            text = "No active users found",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp)
        )
    } else {
        StackedAvatars(
            profiles = section.profiles,
            selectedPubkeys = selectedPubkeys,
            onTogglePubkey = onTogglePubkey
        )
    }
}

@Composable
private fun NewsSection(
    section: SuggestionSection,
    selectedPubkeys: Set<String>,
    onTogglePubkey: (String) -> Unit
) {
    Text(
        text = "News sources",
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.onSurface
    )
    Spacer(Modifier.height(4.dp))
    Text(
        text = "Pick the news sources you want to follow",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(8.dp))

    if (section.isLoading) {
        Box(modifier = Modifier.fillMaxWidth().height(60.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        }
    } else if (section.profiles.isEmpty()) {
        Text(
            text = "No news sources found",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp)
        )
    } else {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 4.dp)
        ) {
            items(section.profiles, key = { it.pubkey }) { profile ->
                NewsCard(
                    profile = profile,
                    isSelected = profile.pubkey in selectedPubkeys,
                    onToggle = { onTogglePubkey(profile.pubkey) }
                )
            }
        }
    }
}

@Composable
private fun NewsCard(
    profile: ProfileData,
    isSelected: Boolean,
    onToggle: () -> Unit
) {
    Card(
        modifier = Modifier.width(120.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            ProfilePicture(url = profile.picture, size = 56)
            Spacer(Modifier.height(8.dp))
            Text(
                text = profile.displayString,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            FollowToggleButton(
                isSelected = isSelected,
                onClick = onToggle
            )
        }
    }
}
