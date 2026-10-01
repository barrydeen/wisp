package com.wisp.app.repo

import com.wisp.app.nostr.NostrEvent
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileNotePublicationStoreTest {
    @get:Rule val folder = TemporaryFolder()

    /** Real files and atomic moves. Android's AtomicFile adapter still requires device validation. */
    private class JvmFileIO : PublicationFileIO {
        var failDelete = false
        override fun read(path: File): String = path.readText()
        override fun write(path: File, content: String) {
            val staging = File(path.parentFile, "${path.name}.new")
            staging.outputStream().use { it.write(content.toByteArray()); it.fd.sync() }
            Files.move(staging.toPath(), path.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        }
        override fun delete(path: File) {
            if (failDelete) throw IOException("Deletion failed")
            Files.deleteIfExists(path.toPath())
            Files.deleteIfExists(File(path.parentFile, "${path.name}.bak").toPath())
            Files.deleteIfExists(File(path.parentFile, "${path.name}.new").toPath())
        }
    }

    private val note = NostrEvent("1".repeat(64), "a".repeat(64), 1, 1,
        listOf(listOf("p", "b".repeat(64))), "Content that must not remain in receipt history", "c".repeat(128))
    private val relay = "wss://a.example"
    private fun pending(event: NostrEvent = note) =
        NotePublication(event, emptyList(), mapOf(relay to RelayPublication(RelayPublicationStatus.UNCONFIRMED)), inFlight = false)
    private fun accepted(event: NostrEvent = note) =
        pending(event).copy(relays = mapOf(relay to RelayPublication(RelayPublicationStatus.ACCEPTED)))

    @Test
    fun `unconfirmed signed event survives a fresh store instance`() {
        val directory = folder.newFolder("account")
        FileNotePublicationStore(directory, JvmFileIO()).save(pending())
        val restored = FileNotePublicationStore(directory, JvmFileIO()).load().single()
        assertEquals(note.toJson(), restored.event.toJson())
        assertEquals(pending(), restored)
    }

    @Test
    fun `acceptance removes full payload and keeps only a compact receipt`() {
        val directory = folder.newFolder("account")
        val store = FileNotePublicationStore(directory, JvmFileIO())
        store.save(pending())
        assertTrue(File(directory, "${note.id}.json").exists())
        store.save(accepted())
        assertFalse(File(directory, "${note.id}.json").exists())
        assertTrue(store.load().isEmpty())
        val history = File(directory, "receipts.json").readText()
        assertFalse(history.contains(note.content))
        assertFalse(history.contains(note.sig))
        assertFalse(history.contains("created_at"))
        assertFalse(history.contains("tags"))
        assertEquals(accepted().receipt(), FileNotePublicationStore(directory, JvmFileIO()).loadReceipts().single())
    }

    @Test
    fun `already confirmed event is never written as a recovery payload`() {
        val directory = folder.newFolder("account")
        val store = FileNotePublicationStore(directory, JvmFileIO())
        store.save(accepted())
        assertEquals(listOf("receipts.json"), directory.listFiles()!!.map { it.name })
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun `receipt history is bounded without pruning unconfirmed notes`() {
        val directory = folder.newFolder("account")
        val store = FileNotePublicationStore(directory, JvmFileIO())
        store.save(pending())
        for (index in 2..PUBLICATION_RECEIPT_LIMIT + 21) {
            store.save(accepted(note.copy(id = index.toString(16).padStart(64, '0'))))
        }
        val reopened = FileNotePublicationStore(directory, JvmFileIO())
        assertEquals(PUBLICATION_RECEIPT_LIMIT, reopened.loadReceipts().size)
        assertEquals(note, reopened.load().single().event)
        assertEquals(2, directory.listFiles()!!.size)
    }

    @Test
    fun `receipt committed before interrupted payload deletion prevents false recovery`() {
        val directory = folder.newFolder("account")
        val io = JvmFileIO()
        val store = FileNotePublicationStore(directory, io)
        store.save(pending())
        io.failDelete = true
        try {
            store.save(accepted())
            fail("Expected deletion failure")
        } catch (_: IOException) { }
        assertTrue(File(directory, "${note.id}.json").exists())
        val reopened = FileNotePublicationStore(directory, JvmFileIO())
        assertTrue(reopened.load().isEmpty())
        assertFalse(File(directory, "${note.id}.json").exists())
        assertEquals(1, reopened.loadReceipts().size)
    }

    @Test
    fun `account directories isolate pending events and receipts`() {
        val first = FileNotePublicationStore(folder.newFolder("first-account"), JvmFileIO())
        val second = FileNotePublicationStore(folder.newFolder("second-account"), JvmFileIO())
        first.save(pending())
        second.save(accepted(note.copy(id = "2".repeat(64), pubkey = "d".repeat(64))))
        assertEquals(note, first.load().single().event)
        assertTrue(first.loadReceipts().isEmpty())
        assertTrue(second.load().isEmpty())
        assertEquals("2".repeat(64), second.loadReceipts().single().eventId)
    }
}
