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
    fun `same timestamp with changed known size requires rescan`() {
        assertFalse(LibraryScanner.isCacheEntryFresh(123L, 123L, 100L, 101L))
    }

    @Test
    fun `untrustworthy timestamps can reuse equal known size`() {
        assertTrue(LibraryScanner.isCacheEntryFresh(0L, 0L, 100L, 100L))
        assertTrue(LibraryScanner.isCacheEntryFresh(123L, 0L, 100L, 100L))
    }

    @Test
    fun `untrustworthy timestamps without size require rescan`() {
        assertFalse(LibraryScanner.isCacheEntryFresh(0L, 0L, -1L, -1L))
    }

    @Test
    fun `cache ownership requires exact folder URI`() {
        val folder = "content://provider/tree/primary%3AMusic"
        assertTrue(LibraryScanner.ownsFolderUri(folder, folder))
        assertFalse(LibraryScanner.ownsFolderUri(folder, "content://provider/tree/primary%3AMusicExtra"))
        assertEquals(folder, "content://provider/tree/primary%3AMusic")
    }

}
