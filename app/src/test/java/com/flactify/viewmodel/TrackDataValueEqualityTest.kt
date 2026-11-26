package com.flactify.viewmodel

import android.net.Uri
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TrackDataValueEqualityTest {
    @Test
    fun `metadata changes on the same URI publish updated album group values`() {
        val uri = mockk<Uri>()
        val original = track(uri, title = "Old title", artist = "Old artist")
        val edited = original.copy(title = "New title", artist = "New artist")
        val state = MutableStateFlow(LibraryGrouping.group(listOf(original)).albumMap)

        state.value = LibraryGrouping.group(listOf(edited)).albumMap

        val publishedTrack = state.value.values.single().single()
        assertEquals("New title", publishedTrack.title)
        assertEquals("New artist", publishedTrack.artist)
    }

    @Test
    fun `track value equality includes metadata and compares artwork by contents`() {
        val uri = mockk<Uri>()
        val original = track(uri, title = "Title", artwork = byteArrayOf(1, 2, 3))

        assertNotEquals(original, original.copy(title = "Edited title"))
        assertNotEquals(original, original.copy(albumArtBytes = byteArrayOf(1, 2, 4)))

        val equivalentArtwork = original.copy(albumArtBytes = byteArrayOf(1, 2, 3))
        assertEquals(original, equivalentArtwork)
        assertEquals(original.hashCode(), equivalentArtwork.hashCode())
    }

    private fun track(
        uri: Uri,
        title: String,
        artist: String = "Artist",
        artwork: ByteArray? = null
    ) = TrackData(
        title = title,
        artist = artist,
        album = "Album",
        originalIndex = 0,
        uri = uri,
        albumArtBytes = artwork
    )
}
