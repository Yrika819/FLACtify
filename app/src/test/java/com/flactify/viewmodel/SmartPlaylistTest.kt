package com.flactify.viewmodel

import android.net.Uri
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartPlaylistTest {
    @Test
    fun `all configured conditions must match with Unicode normalization`() {
        val query = SmartPlaylistQuery(
            artistContains = "ｱｰﾃｨｽﾄ",
            genreContains = "ＪＡＺＺ",
            yearContains = "2024"
        )
        val match = track(
            title = "夜明け",
            artist = "アーティストＡ",
            genre = "Jazz",
            year = "2024"
        )
        val wrongGenre = match.copy(genre = "Rock")
        val wrongYear = match.copy(year = "2023")

        assertTrue(query.matches(match))
        assertFalse(query.matches(wrongGenre))
        assertFalse(query.matches(wrongYear))
    }

    @Test
    fun `filter preserves library order and ignores blank conditions`() {
        val tracks = listOf(
            track("A", artist = "The Band"),
            track("B", artist = "Solo"),
            track("C", artist = "The Band")
        )
        val query = SmartPlaylistQuery(artistContains = "  the  ")

        assertEquals(listOf("A", "C"), query.filter(tracks).map { it.title })
        assertEquals(tracks, SmartPlaylistQuery(artistContains = " ").filter(tracks))
    }

    @Test
    fun `empty query selects every track`() {
        val tracks = listOf(track("A"), track("B"))

        assertEquals(tracks, SmartPlaylistQuery().filter(tracks))
    }

    @Test
    fun `presets use explicit stats and favorites without inventing date added`() {
        val first = track("First")
        val second = track("Second")
        val context = SmartPlaylistContext(
            favorites = setOf(first.uri.toString()),
            playCounts = mapOf(first.uri.toString() to 4, second.uri.toString() to 0),
            lastPlayed = mapOf(first.uri.toString() to 100L, second.uri.toString() to 200L)
        )

        assertEquals(listOf("First"), SmartPlaylistQuery(preset = SmartPlaylistPreset.FAVORITES).filter(listOf(first, second), context).map { it.title })
        assertEquals(listOf("Second", "First"), SmartPlaylistQuery(preset = SmartPlaylistPreset.RECENT).filter(listOf(first, second), context).map { it.title })
        assertEquals(listOf("Second"), SmartPlaylistQuery(preset = SmartPlaylistPreset.LOW_PLAY_COUNT).filter(listOf(first, second), context).map { it.title })
    }

    @Test
    fun `order and limit apply after conditions`() {
        val tracks = listOf(track("C"), track("A"), track("B"))

        val query = SmartPlaylistQuery(order = SmartPlaylistOrder.TITLE, limit = 2)

        assertEquals(listOf("A", "B"), query.filter(tracks).map { it.title })
    }

    private fun track(
        title: String,
        artist: String = "Artist",
        album: String = "Album",
        genre: String? = null,
        year: String? = null
    ): TrackData = TrackData(
        title = title,
        artist = artist,
        album = album,
        genre = genre,
        year = year,
        originalIndex = 0,
        uri = mockk<Uri>()
    )
}
