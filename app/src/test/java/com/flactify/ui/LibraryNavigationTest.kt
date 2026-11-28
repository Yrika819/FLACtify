package com.flactify.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryNavigationTest {
    @Test
    fun `playlist list and track detail are mutually exclusive content states`() {
        assertEquals(PlaylistTabContent.List, playlistTabContent(null))
        assertEquals(PlaylistTabContent.Tracks("playlist-id"), playlistTabContent("playlist-id"))
    }

    @Test
    fun `Back closes playlist content before leaving the library`() {
        assertEquals(
            LibraryBackAction.CLOSE_PLAYLIST,
            libraryBackAction("playlists", "Artist", "Album", "playlist-id")
        )
    }

    @Test
    fun `Back closes the selected artist or album before leaving its tab`() {
        assertEquals(
            LibraryBackAction.CLOSE_ARTIST,
            libraryBackAction("artists", "Artist", null, null)
        )
        assertEquals(
            LibraryBackAction.CLOSE_ALBUM,
            libraryBackAction("albums", null, "Album", null)
        )
    }

    @Test
    fun `Back leaves the library only at a tab root`() {
        assertEquals(
            LibraryBackAction.EXIT_LIBRARY,
            libraryBackAction("artists", null, null, null)
        )
        assertEquals(
            LibraryBackAction.EXIT_LIBRARY,
            libraryBackAction("songs", null, null, null)
        )
    }
}
