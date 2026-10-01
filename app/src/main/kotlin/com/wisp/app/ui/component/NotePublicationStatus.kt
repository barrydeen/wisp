package com.wisp.app.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wisp.app.R
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.repo.EventRepository
import com.wisp.app.repo.NotePublication
import com.wisp.app.repo.NotePublisher
import com.wisp.app.repo.RelayPublicationStatus
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

@Composable
private fun publicationFor(publisher: NotePublisher, event: NostrEvent): NotePublication? {
    val flow = remember(publisher, event) {
        combine(publisher.publications, publisher.receipts) { publications, receipts ->
            publications[event.id] ?: receipts[event.id]?.withEvent(event)
        }.distinctUntilChanged()
    }
    val publication by flow.collectAsState(initial = publisher.publicationFor(event))
    return publication
}

@Composable
fun NotePublicationMenuItem(event: NostrEvent, eventRepo: EventRepository?, onDismiss: () -> Unit) {
    val publisherState = eventRepo?.notePublisherState?.collectAsState() ?: return
    val publisher = publisherState.value ?: return
    if (!publisher.canPublish(event)) return
    val publication = publicationFor(publisher, event)
    DropdownMenuItem(
        text = { Text(stringResource(R.string.btn_rebroadcast)) },
        enabled = publication?.inFlight != true,
        onClick = {
            onDismiss()
            publisher.rebroadcast(event)
        }
    )
}

@Composable
fun publicationSummary(publication: NotePublication): String = when {
    publication.inFlight -> stringResource(R.string.publication_pending, publication.acceptedCount, publication.relays.size)
    publication.acceptedCount > 0 -> stringResource(R.string.publication_confirmed, publication.acceptedCount, publication.relays.size)
    publication.relays.isEmpty() -> stringResource(R.string.publication_no_relays)
    publication.rejectedCount == publication.relays.size -> stringResource(R.string.publication_rejected)
    else -> stringResource(R.string.publication_unconfirmed)
}

/** Kept visible on the post after the transient broadcast bar disappears. */
@Composable
fun NotePublicationStatus(event: NostrEvent, eventRepo: EventRepository?) {
    val publisherState = eventRepo?.notePublisherState?.collectAsState() ?: return
    val publisher = publisherState.value ?: return
    if (!publisher.canPublish(event)) return
    val publication = publicationFor(publisher, event)
    val errorFlow = remember(publisher, event.id) { publisher.actionErrors.map { event.id in it }.distinctUntilChanged() }
    val actionFailed by errorFlow.collectAsState(initial = false)
    var showDetails by remember(event.id) { mutableStateOf(false) }
    if (publication == null && !actionFailed) return

    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        if (publication != null) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { showDetails = true },
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (publication.inFlight) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    publicationSummary(publication),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (!publication.inFlight && publication.acceptedCount == 0) {
                        MaterialTheme.colorScheme.error
                    } else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (publication.storageError) {
                Text(stringResource(R.string.publication_status_save_failed), color = MaterialTheme.colorScheme.error)
            }
            if (!publication.inFlight && publication.acceptedCount == 0) {
                TextButton(onClick = { publisher.rebroadcast(event) }) { Text(stringResource(R.string.btn_rebroadcast)) }
            }
        }
        if (actionFailed) {
            Text(stringResource(R.string.publication_retry_failed), color = MaterialTheme.colorScheme.error)
        }
    }

    if (showDetails && publication != null) {
        AlertDialog(
            onDismissRequest = { showDetails = false },
            title = { Text(stringResource(R.string.publication_details)) },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    Text(publicationSummary(publication))
                    for ((url, result) in publication.relays) {
                        Spacer(Modifier.height(12.dp))
                        Text(url, style = MaterialTheme.typography.labelMedium)
                        Text(stringResource(when (result.status) {
                            RelayPublicationStatus.PENDING -> R.string.publication_relay_pending
                            RelayPublicationStatus.ACCEPTED -> R.string.publication_relay_accepted
                            RelayPublicationStatus.REJECTED -> R.string.publication_relay_rejected
                            RelayPublicationStatus.UNCONFIRMED -> R.string.publication_relay_unconfirmed
                            RelayPublicationStatus.UNREACHABLE -> R.string.publication_relay_unreachable
                        }))
                        if (result.message.isNotBlank()) Text(result.message)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !publication.inFlight,
                    onClick = {
                        showDetails = false
                        publisher.rebroadcast(event)
                    }
                ) { Text(stringResource(R.string.btn_rebroadcast)) }
            },
            dismissButton = {
                TextButton(onClick = { showDetails = false }) { Text(stringResource(R.string.btn_close)) }
            }
        )
    }
}
