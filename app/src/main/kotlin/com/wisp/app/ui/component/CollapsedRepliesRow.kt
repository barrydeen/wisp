package com.wisp.app.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import com.wisp.app.R
import com.wisp.app.viewmodel.thread.ThreadItem

/**
 * Folded-subtree affordance: "Show N more replies". Tapping expands the subtree inline — the
 * caller's [onExpand] handles the VM toggle (and scroll anchoring, where used). The guide rail
 * is dashed at the top to signal it continues upward to the anchor note. Shared by ThreadScreen
 * and ArticleScreen so the two progressive-disclosure surfaces stay consistent.
 */
@Composable
fun CollapsedRepliesRow(
    item: ThreadItem.CollapsedReplies,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier
) {
    val indent = threadIndentDp(item.depth)
    val lineColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .threadConnector(show = true, indent = indent, lineColor = lineColor, dashedTop = true)
            .clickable(onClick = onExpand)
            .padding(start = indent, top = 8.dp, bottom = 8.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = pluralStringResource(R.plurals.thread_continue, item.hiddenCount, item.hiddenCount),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}
