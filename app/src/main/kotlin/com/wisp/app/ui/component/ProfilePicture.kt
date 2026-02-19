package com.wisp.app.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

@Composable
fun ProfilePicture(
    url: String?,
    modifier: Modifier = Modifier,
    size: Int = 40,
    showFollowBadge: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    Box(modifier = modifier) {
        AsyncImage(
            model = url,
            contentDescription = "Profile picture",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(size.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        )
        if (showFollowBadge) {
            val badgeSize = (size * 0.3f).coerceIn(10f, 16f)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 1.dp, y = 1.dp)
                    .size(badgeSize.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                    .border(1.dp, MaterialTheme.colorScheme.surface, CircleShape)
            ) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "Following",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size((badgeSize * 0.65f).dp)
                )
            }
        }
    }
}
