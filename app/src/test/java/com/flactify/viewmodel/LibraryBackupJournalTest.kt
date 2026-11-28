package com.flactify.viewmodel

import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class LibraryBackupJournalTest {
    private lateinit var directory: java.io.File

    @Before
    fun setUp() { directory = Files.createTempDirectory("backup-journal-").toFile() }

    @After
    fun tearDown() { directory.deleteRecursively() }

    @Test
    fun `staged restore survives a new journal instance and can be cleared`() {
        val target = java.io.File(directory, "restore.pending")
        LibraryBackupJournal(target).stage("{\"version\":1}")

        assertEquals("{\"version\":1}", LibraryBackupJournal(target).read())
        LibraryBackupJournal(target).clear()
        assertNull(LibraryBackupJournal(target).read())
    }
}
