package com.wisp.app.repo

import android.util.Log
import java.io.File
import java.io.IOException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** One atomic file per event, in the app's private, account-specific directory. */
class FileNotePublicationStore(
    private val directory: File,
    private val files: PublicationFileIO = AndroidPublicationFileIO()
) : NotePublicationStore {
    private val json = Json { ignoreUnknownKeys = true }
    private var receipts: Map<String, NotePublicationReceipt>? = null

    override fun loadReceipts(): List<NotePublicationReceipt> {
        if (receipts == null) {
            receipts = try {
                json.decodeFromString<List<NotePublicationReceipt>>(files.read(File(directory, "receipts.json")))
                    .takeLast(PUBLICATION_RECEIPT_LIMIT).associateBy { it.eventId }
            } catch (_: java.io.FileNotFoundException) {
                emptyMap()
            } catch (_: Exception) {
                Log.w("NotePublicationStore", "Could not restore publication receipts")
                emptyMap()
            }
        }
        return receipts.orEmpty().values.toList()
    }

    override fun load(): List<NotePublication> = directory.listFiles().orEmpty()
        .filter { it.name.matches(Regex("[0-9a-f]{64}\\.json(\\.bak)?")) }
        .map { it.name.removeSuffix(".bak") }.distinct()
        .mapNotNull { name ->
            try {
                val publication = json.decodeFromString<NotePublication>(files.read(File(directory, name)))
                if (publication.acceptedCount > 0 || loadReceipts().any { it.eventId == publication.event.id }) {
                    // Clean up a payload left behind by interruption between receipt save and deletion.
                    deletePayload(publication.event.id)
                    null
                } else publication
            } catch (_: Exception) {
                Log.w("NotePublicationStore", "Could not restore a saved publication")
                null
            }
        }

    override fun save(publication: NotePublication) {
        require(publication.event.id.matches(Regex("[0-9a-f]{64}"))) { "Invalid event ID" }
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Could not create publication storage")
        if (publication.acceptedCount > 0) {
            loadReceipts()
            val next = ((receipts.orEmpty() - publication.event.id) + (publication.event.id to publication.receipt()))
                .entries.toList().takeLast(PUBLICATION_RECEIPT_LIMIT).associate { it.toPair() }
            // Commit the small receipt first so a crash cannot make a confirmed note pending again.
            files.write(File(directory, "receipts.json"), json.encodeToString(next.values.toList()))
            receipts = next
            deletePayload(publication.event.id)
        } else {
            files.write(File(directory, "${publication.event.id}.json"), json.encodeToString(publication))
        }
    }

    private fun deletePayload(eventId: String) {
        val file = File(directory, "$eventId.json")
        files.delete(file)
        if (file.exists() || File(directory, "$eventId.json.bak").exists() || File(directory, "$eventId.json.new").exists()) {
            throw IOException("Could not remove confirmed publication payload")
        }
    }
}
