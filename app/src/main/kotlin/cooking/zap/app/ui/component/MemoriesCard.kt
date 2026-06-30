package cooking.zap.app.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cooking.zap.app.repo.MemoriesRepository
import cooking.zap.app.repo.MemoryGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Date

/**
 * Dismissible "Memories" teaser banner (mirrors the web's MemoriesCard). Shows
 * only when the signed-in user has memories for today and hasn't dismissed it.
 * Tapping opens the full Memories screen ([onOpen]); the X dismisses for the day
 * with a 5s Undo. Self-contained and read-only — loads via [MemoriesRepository]'s
 * per-day cache, so it doesn't re-query on every feed open.
 */
@Composable
fun MemoriesCard(
    repo: MemoriesRepository,
    userPubkey: String?,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (userPubkey == null) return

    val scope = rememberCoroutineScope()
    var dismissed by remember(userPubkey) { mutableStateOf(repo.isMemoriesCardDismissed(userPubkey)) }
    var undoVisible by remember(userPubkey) { mutableStateOf(false) }
    var groups by remember(userPubkey) { mutableStateOf<List<MemoryGroup>?>(null) }

    LaunchedEffect(userPubkey) {
        if (repo.isMemoriesCardDismissed(userPubkey)) {
            dismissed = true
            return@LaunchedEffect
        }
        groups = try {
            withContext(Dispatchers.IO) { repo.getMemoriesCached(userPubkey) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    val nonEmpty = groups?.filter { it.events.isNotEmpty() }?.sortedBy { it.yearsAgo } ?: emptyList()

    if (!dismissed && nonEmpty.isNotEmpty()) {
        val totalCount = nonEmpty.sumOf { it.events.size }
        val years = nonEmpty.joinToString(", ") { yearOf(it.dateSec) }
        val summary = "$totalCount ${if (totalCount == 1) "note" else "notes"} · $years"

        Surface(
            modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    modifier = Modifier.weight(1f).clickable { onOpen() }.padding(start = 12.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.DateRange,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                        Text(
                            "Memories",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "A look back at notes from this day",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = {
                    repo.dismissMemoriesCard(userPubkey)
                    dismissed = true
                    undoVisible = true
                    scope.launch {
                        delay(5000)
                        undoVisible = false
                    }
                }) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Hide memories for today",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    } else if (dismissed && undoVisible) {
        Surface(
            modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Memories hidden",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = {
                    repo.undismissMemoriesCard(userPubkey)
                    undoVisible = false
                    dismissed = false
                }) {
                    Text("Undo")
                }
            }
        }
    }
}

private fun yearOf(dateSec: Long): String {
    val cal = Calendar.getInstance().apply { time = Date(dateSec * 1000) }
    return cal.get(Calendar.YEAR).toString()
}
