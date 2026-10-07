package com.flactify.viewmodel


import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryScannerOwnershipTest {
    @Test
    fun `matching nonzero timestamp permits cache reuse`() {
        assertTrue(LibraryScanner.isTimestampFresh(123L, 123L))
    }

    @Test
    fun `changed nonzero timestamp requires rescan`() {
        assertFalse(LibraryScanner.isTimestampFresh(123L, 124L))
    }

    @Test
    fun `zero current timestamp requires rescan`() {
        assertFalse(LibraryScanner.isTimestampFresh(123L, 0L))
    }

    @Test
    fun `zero cached timestamp requires rescan`() {
        assertFalse(LibraryScanner.isTimestampFresh(0L, 123L))
    }

    @Test
    fun `both zero timestamps require rescan`() {
        assertFalse(LibraryScanner.isTimestampFresh(0L, 0L))
    }

    @Test
    fun `cache ownership requires exact folder URI`() {
        val folder = "content://provider/tree/primary%3AMusic"
        assertTrue(LibraryScanner.ownsFolderUri(folder, folder))
        assertFalse(LibraryScanner.ownsFolderUri(folder, "content://provider/tree/primary%3AMusicExtra"))
        assertEquals(folder, "content://provider/tree/primary%3AMusic")
    }

}
