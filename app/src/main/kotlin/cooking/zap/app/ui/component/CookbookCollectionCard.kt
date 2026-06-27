package cooking.zap.app.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import cooking.zap.app.R

/**
 * Cover card for a Cookbook collection (A14 PR 3b-i Saved sub-tab). Mirrors the
 * web `/cookbook` "Packs" grid: a cover image, title, and recipe count. The
 * default Saved list carries a small bookmark badge so it reads as the catch-all.
 *
 * [coverUrl] is resolved by [cooking.zap.app.repo.CookbookCovers]; null falls back
 * to a neutral placeholder.
 *
 * When [canManage] is true (owner with a signing key — PR 3b-iii) an overflow
 * menu surfaces the owner actions. Rename / delete are hidden for the default
 * Saved list (its title is locked and it can't be deleted), matching the web.
 */
@Composable
fun CookbookCollectionCard(
    title: String,
    coverUrl: String?,
    recipeCount: Int,
    isDefault: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    canManage: Boolean = false,
    onRename: () -> Unit = {},
    onEditDescription: () -> Unit = {},
    onChooseCover: () -> Unit = {},
    onDelete: () -> Unit = {},
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
            ) {
                if (coverUrl != null) {
                    AsyncImage(
                        model = coverUrl,
                        contentDescription = title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Restaurant,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }

                if (isDefault) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(10.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f))
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            Icons.Default.Bookmark,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(12.dp),
                        )
                        Text(
                            text = title,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }

                if (canManage) {
                    var menuOpen by remember { mutableStateOf(false) }
                    Box(modifier = Modifier.align(Alignment.TopEnd)) {
                        IconButton(onClick = { menuOpen = true }) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f))
                                    .padding(4.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Default.MoreVert,
                                    contentDescription = stringResource(R.string.cookbook_manage_menu),
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            // The default Saved list can't be renamed (title locked) or deleted.
                            if (!isDefault) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.cookbook_action_rename)) },
                                    onClick = { menuOpen = false; onRename() },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.cookbook_action_edit_description)) },
                                onClick = { menuOpen = false; onEditDescription() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.cookbook_action_choose_cover)) },
                                onClick = { menuOpen = false; onChooseCover() },
                            )
                            if (!isDefault) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(R.string.cookbook_action_delete),
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    },
                                    onClick = { menuOpen = false; onDelete() },
                                )
                            }
                        }
                    }
                }
            }

            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = pluralStringResource(
                        id = R.plurals.recipe_count_label,
                        count = recipeCount,
                        recipeCount,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
