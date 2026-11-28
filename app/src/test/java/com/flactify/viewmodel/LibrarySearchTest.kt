package com.flactify.viewmodel

import android.net.Uri
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibrarySearchTest {
    @Test
    fun `normalization matches full width Latin and case differences`() {
        assertEquals("flactify", LibrarySearch.normalize("ＦＬＡＣｔｉｆｙ"))
        assertTrue(LibrarySearch.matches(track(title = "FLACtify Player"), "ｆｌａｃ"))
    }

    @Test
    fun `normalization matches half width Japanese Katakana`() {
        val track = track(title = "カタカナの歌")

        assertTrue(LibrarySearch.matches(track, "ｶﾀｶﾅ"))
    }

    @Test
    fun `normalization matches composed and combining accent spellings`() {
        val track = track(title = "Café del Mar")

        assertTrue(LibrarySearch.matches(track, "cafe\u0301"))
    }

    @Test
    fun `matching searches title artist and album but rejects unrelated metadata`() {
        val track = track(title = "夜明け", artist = "青葉市子", album = "アダンの風")

        assertTrue(LibrarySearch.matches(track, "市子"))
        assertTrue(LibrarySearch.matches(track, "アダン"))
        assertFalse(LibrarySearch.matches(track, "夜更け"))
    }

    @Test
    fun `normalized query can be reused across tracks`() {
        val query = LibrarySearch.normalize("ｶﾀ")

        assertTrue(LibrarySearch.matchesNormalized(track(title = "カタカナ"), query))
        assertFalse(LibrarySearch.matchesNormalized(track(title = "さかな"), query))
    }

    private fun track(
        title: String,
        artist: String = "Artist",
        album: String = "Album"
    ): TrackData = TrackData(
        title = title,
        artist = artist,
        album = album,
        originalIndex = 0,
        uri = mockk()
    )
}
