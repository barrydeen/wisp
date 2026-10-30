package com.wisp.app.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keep-newest selection for Drive nsec backups. Pure JVM — DriveBackupService
 * has no Android dependencies on this path.
 */
class DriveBackupPruneTest {
    private fun file(name: String, modified: String?) =
        DriveBackupService.BackupFile(fileId = "id-$name", name = name, modifiedTime = modified)

    @Test
    fun `keeps the newest and marks the rest superseded`() {
        val files = listOf(
            file("a", "2026-10-01T00:00:00Z"),
            file("c", "2026-10-03T00:00:00Z"),
            file("b", "2026-10-02T00:00:00Z")
        )
        // keep=2: c (newest) and b stay, a is superseded.
        assertEquals(listOf("a"), DriveBackupService.selectSuperseded(files, 2).map { it.name })
        // keep=1: only c stays.
        assertEquals(listOf("b", "a"), DriveBackupService.selectSuperseded(files, 1).map { it.name })
    }

    @Test
    fun `keep zero supersedes everything`() {
        val files = listOf(file("a", "2026-10-01T00:00:00Z"))
        assertEquals(files, DriveBackupService.selectSuperseded(files, 0))
    }

    @Test
    fun `negative keep supersedes nothing`() {
        val files = listOf(file("a", null))
        assertTrue(DriveBackupService.selectSuperseded(files, -1).isEmpty())
    }

    @Test
    fun `files without modified time sort oldest`() {
        val files = listOf(
            file("no-time", null),
            file("new", "2026-10-03T00:00:00Z")
        )
        val superseded = DriveBackupService.selectSuperseded(files, 1)
        assertEquals(listOf("no-time"), superseded.map { it.name })
    }

    @Test
    fun `fewer files than keep supersedes nothing`() {
        val files = listOf(file("a", "2026-10-01T00:00:00Z"), file("b", "2026-10-02T00:00:00Z"))
        assertTrue(DriveBackupService.selectSuperseded(files, 3).isEmpty())
    }
}
