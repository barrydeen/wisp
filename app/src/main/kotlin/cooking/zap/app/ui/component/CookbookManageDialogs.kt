package cooking.zap.app.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import cooking.zap.app.R
import cooking.zap.app.repo.CookbookMemberRecipe

/**
 * Cookbook collection-management UI (A14 PR 3b-iii) — all surfaced only to an
 * owner with a signing key. Mirrors the web `/cookbook` collection actions:
 * rename, edit description, choose cover (from member recipes), delete.
 */

/** Rename a collection. The submit is disabled while blank. */
@Composable
fun RenameCollectionDialog(
    initialTitle: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf(initialTitle) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.cookbook_action_rename)) },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.cookbook_rename_label)) },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(title.trim()) },
                enabled = title.trim().isNotEmpty(),
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** Edit (or clear) a collection's description. Empty is allowed — it clears it. */
@Composable
fun EditDescriptionDialog(
    initialSummary: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var summary by remember { mutableStateOf(initialSummary) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.cookbook_action_edit_description)) },
        text = {
            OutlinedTextField(
                value = summary,
                onValueChange = { summary = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.cookbook_description_label)) },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(summary.trim()) }) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** Confirm a destructive collection delete (a real NIP-09 kind-5 deletion). */
@Composable
fun DeleteCollectionDialog(
    title: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.cookbook_delete_title)) },
        text = { Text(stringResource(R.string.cookbook_delete_body, title)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    stringResource(R.string.cookbook_action_delete),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/**
 * Cover-picker — choose the collection cover from its **member** recipes (mirrors
 * the web cover-picker). [members] are resolved lazily; [loading] shows a spinner
 * until they arrive. The current cover (when set) is checkmarked.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChooseCoverSheet(
    members: List<CookbookMemberRecipe>,
    currentCoord: String?,
    loading: Boolean,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp)
                .navigationBarsPadding(),
        ) {
            Text(
                text = stringResource(R.string.cookbook_action_choose_cover),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))

            when {
                loading && members.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                }
                members.isEmpty() -> {
                    Text(
                        text = stringResource(R.string.cookbook_cover_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
                else -> {
                    LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                        items(members, key = { it.coord }) { member ->
                            CoverPickRow(
                                member = member,
                                selected = member.coord == currentCoord,
                                onClick = { onPick(member.coord) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CoverPickRow(
    member: CookbookMemberRecipe,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (member.image != null) {
                AsyncImage(
                    model = member.image,
                    contentDescription = member.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(56.dp),
                )
            } else {
                Icon(
                    Icons.Default.Restaurant,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Text(
            text = member.title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
