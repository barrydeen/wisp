package com.wisp.app.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.wisp.app.nostr.ProfileData

@Composable
fun StackedAvatars(
    profiles: List<ProfileData>,
    selectedPubkeys: Set<String>,
    onTogglePubkey: (String) -> Unit,
    maxVisible: Int = 8,
    avatarSize: Int = 44,
    overlapFraction: Float = 0.35f
) {
    var expanded by remember { mutableStateOf(false) }
    val visibleProfiles = profiles.take(maxVisible)
    val extraCount = (profiles.size - maxVisible).coerceAtLeast(0)

    Column {
        // Collapsed: overlapping avatars row with expand arrow
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 8.dp)
        ) {
            Box(modifier = Modifier.weight(1f)) {
                visibleProfiles.forEachIndexed { index, profile ->
                    val offsetX = (index * avatarSize * (1f - overlapFraction)).dp
                    Box(
                        modifier = Modifier
                            .offset(x = offsetX)
                            .zIndex((visibleProfiles.size - index).toFloat())
                            .size(avatarSize.dp)
                            .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
                            .clip(CircleShape)
                    ) {
                        ProfilePicture(url = profile.picture, size = avatarSize)
                    }
                }
                if (extraCount > 0) {
                    val offsetX = (visibleProfiles.size * avatarSize * (1f - overlapFraction)).dp
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .offset(x = offsetX)
                            .zIndex(0f)
                            .size(avatarSize.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                            .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
                    ) {
                        Text(
                            text = "+$extraCount",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
        }

        // Expanded: individual profile list
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Column(modifier = Modifier.padding(top = 4.dp)) {
                profiles.forEach { profile ->
                    val isSelected = profile.pubkey in selectedPubkeys
                    ExpandedProfileRow(
                        profile = profile,
                        isSelected = isSelected,
                        onToggle = { onTogglePubkey(profile.pubkey) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ExpandedProfileRow(
    profile: ProfileData,
    isSelected: Boolean,
    onToggle: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 6.dp)
    ) {
        ProfilePicture(url = profile.picture, size = 40)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = profile.displayString,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!profile.about.isNullOrBlank()) {
                Text(
                    text = profile.about,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        FollowToggleButton(isSelected = isSelected, onClick = onToggle)
    }
}

@Composable
fun FollowToggleButton(
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (isSelected) {
        FilledTonalButton(
            onClick = onClick,
            modifier = modifier.height(32.dp),
            shape = RoundedCornerShape(16.dp),
            contentPadding = ButtonDefaults.ContentPadding
        ) {
            Text("Following", style = MaterialTheme.typography.labelMedium)
        }
    } else {
        FilledTonalButton(
            onClick = onClick,
            modifier = modifier.height(32.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ),
            contentPadding = ButtonDefaults.ContentPadding
        ) {
            Text("Follow", style = MaterialTheme.typography.labelMedium)
        }
    }
}
