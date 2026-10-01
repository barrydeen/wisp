package com.wisp.app.repo

import com.wisp.app.nostr.NostrEvent
import kotlinx.serialization.Serializable

@Serializable
enum class RelayPublicationStatus { PENDING, ACCEPTED, REJECTED, UNCONFIRMED, UNREACHABLE }

@Serializable
data class RelayPublication(
    val status: RelayPublicationStatus,
    val message: String = ""
)

@Serializable
data class NotePublication(
    val event: NostrEvent,
    val inboxPubkeys: List<String>,
    val relays: Map<String, RelayPublication>,
    val attempt: Int = 1,
    val inFlight: Boolean = true,
    val storageError: Boolean = false
) {
    val acceptedCount: Int get() = relays.values.count { it.status == RelayPublicationStatus.ACCEPTED }
    val rejectedCount: Int get() = relays.values.count { it.status == RelayPublicationStatus.REJECTED }
    val unconfirmedCount: Int get() = relays.values.count {
        it.status == RelayPublicationStatus.UNCONFIRMED || it.status == RelayPublicationStatus.UNREACHABLE
    }

    fun interrupted(): NotePublication = copy(
        inFlight = false,
        relays = relays.mapValues { (_, result) ->
            if (result.status == RelayPublicationStatus.PENDING) {
                RelayPublication(RelayPublicationStatus.UNCONFIRMED)
            } else result
        }
    )
}

/** Blocking storage operations. The publisher calls these on Dispatchers.IO. */
interface NotePublicationStore {
    fun load(): List<NotePublication>
    fun loadReceipts(): List<NotePublicationReceipt>
    fun save(publication: NotePublication)
}

/** Bounded delivery history, with no note content, tags or signature. */
@Serializable
data class NotePublicationReceipt(
    val eventId: String,
    val relays: Map<String, RelayPublication>,
    val attempt: Int
) {
    fun withEvent(event: NostrEvent): NotePublication =
        NotePublication(event, emptyList(), relays, attempt).interrupted()
}

fun NotePublication.receipt(): NotePublicationReceipt = NotePublicationReceipt(event.id, relays, attempt)

const val PUBLICATION_RECEIPT_LIMIT = 100
