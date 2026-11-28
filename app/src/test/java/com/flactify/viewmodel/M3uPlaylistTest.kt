package com.flactify.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class M3uPlaylistTest {
    @Test
    fun `round trip preserves UTF-8 metadata and URI locations`() {
        val playlist = M3uPlaylist(
            listOf(
                M3uEntry("content://synthetic/one", "夜明け", 123),
                M3uEntry("https://example.test/two.flac")
            )
        )

        val parsed = M3uPlaylistCodec.parse(M3uPlaylistCodec.serialize(playlist))

        assertEquals(playlist, parsed)
        assertTrue(parsed.warnings.isEmpty())
    }

    @Test
    fun `malformed directives are ignored without turning comments into locations`() {
        val input = """
            ﻿#EXTM3U
            #EXTINF:not-a-duration,Bad metadata
            content://synthetic/one
            #EXT-X-VERSION:3
            # this is a comment
            content://synthetic/two
        """.trimIndent()

        val parsed = M3uPlaylistCodec.parse(input)

        assertEquals(listOf("content://synthetic/one", "content://synthetic/two"), parsed.entries.map { it.location })
        assertEquals(1, parsed.warnings.size)
    }

    @Test
    fun `relative paths resolve inside base and traversal is rejected`() {
        val parsed = M3uPlaylistCodec.parse(
            "#EXTM3U\n../outside.flac\nalbum/track.flac\nC:\\Music\\explicit.flac\n",
            baseDirectory = "/synthetic/music"
        )

        assertEquals(
            listOf("/synthetic/music/album/track.flac", "C:\\Music\\explicit.flac"),
            parsed.entries.map { it.location }
        )
        assertTrue(parsed.warnings.any { it.contains("unsafe path") })
    }

    @Test
    fun `serializer rejects line injection in location and title`() {
        val badLocation = runCatching {
            M3uPlaylistCodec.serialize(M3uPlaylist(listOf(M3uEntry("one\ntwo"))))
        }
        val badTitle = runCatching {
            M3uPlaylistCodec.serialize(M3uPlaylist(listOf(M3uEntry("one", "bad\rtitle"))))
        }

        assertTrue(badLocation.isFailure)
        assertTrue(badTitle.isFailure)
    }
}
