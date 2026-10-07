package com.flactify.viewmodel

import android.net.Uri
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class UserLibraryControllerTest {
    private lateinit var libraryManager: LibraryManager

    @Before
    fun setUp() {
        libraryManager = mockk()
    }

    @Test
    fun `getPlaylistTracks preserves persisted URI order`() {
        val oneUri = mockUri("content://one")
        val twoUri = mockUri("content://two")
        every { libraryManager.getPlaylists() } returns listOf(
            Playlist(
                id = "p1",
                name = "Road trip",
                trackUris = listOf("content://two", "content://one")
            )
        )

        val tracks = listOf(
            track("One", oneUri),
            track("Two", twoUri)
        )

        val result = UserLibraryController(libraryManager).getPlaylistTracks("p1", tracks)

        assertEquals(listOf("Two", "One"), result.map { it.title })
        verify(exactly = 1) { libraryManager.getPlaylists() }
    }

    @Test
    fun `getStatsFor delegates persisted statistics`() {
        every { libraryManager.getStatsFor("content://one") } returns
            LibraryManager.PlayStats(playCount = 3, skipCount = 1, lastPlayed = 1234L)

        val result = UserLibraryController(libraryManager).getStatsFor("content://one")

        assertEquals(3, result.playCount)
        assertEquals(1, result.skipCount)
        assertEquals(1234L, result.lastPlayed)
        verify(exactly = 1) { libraryManager.getStatsFor("content://one") }
    }

    @Test
    fun `getCacheDirSize delegates cache directory measurement`() {
        every { libraryManager.getCacheDirSize() } returns 30L

        val result = UserLibraryController(libraryManager).getCacheDirSize()

        assertEquals(30L, result)
        verify(exactly = 1) { libraryManager.getCacheDirSize() }
    }

    private fun mockUri(value: String): Uri = mockk<Uri>(relaxed = true).also {
        every { it.toString() } returns value
    }

    private fun track(title: String, uri: Uri) = TrackData(
        title = title,
        artist = "Artist",
        album = "Album",
        originalIndex = 0,
        uri = uri
    )
}
