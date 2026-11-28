package com.flactify.ui

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream

class LyricsTest {
    @Before
    fun clearCache() {
        ExternalLyricsCache.clearForTests()
    }

    @Test
    fun `external URI stays within an authorized tree and uses sibling lrc name`() {
        val track = Uri.parse(
            "content://synthetic.documents/tree/primary%3AMusic/document/primary%3AMusic%2Ftrack.flac"
        )

        val external = externalLyricsUriFor(track)

        assertEquals(
            "content://synthetic.documents/tree/primary%3AMusic/document/primary%3AMusic%2Ftrack.lrc",
            external?.toString()
        )
    }

    @Test
    fun `plain document URI does not guess an unauthorized parent`() {
        val track = Uri.parse("content://synthetic.documents/document/primary%3AMusic%2Ftrack.flac")
        assertNull(externalLyricsUriFor(track))
    }

    @Test
    fun `selected tree grant resolves a child document sibling`() {
        val tree = Uri.parse("content://synthetic.documents/tree/primary%3AMusic")
        val track = Uri.parse("content://synthetic.documents/document/primary%3AMusic%2Ftrack.flac")

        assertEquals(
            "content://synthetic.documents/tree/primary%3AMusic/document/primary%3AMusic%2Ftrack.lrc",
            externalLyricsUriFor(track, tree)?.toString()
        )
    }

    @Test
    fun `external LRC is parsed and bounded`() {
        val parsed = readExternalLyrics(
            ByteArrayInputStream("[00:01.00]hello\nplain".toByteArray()),
            maxBytes = 64
        )
        assertEquals(listOf(1_000L to "hello", -1L to "plain"), parsed)

        assertNull(readExternalLyrics(ByteArrayInputStream(ByteArray(9)), maxBytes = 8))
    }

    @Test
    fun `cache stores misses and values by metadata key`() {
        val key = ExternalLyricsCacheKey("track", "title", "artist", "album")
        var loads = 0
        assertNull(ExternalLyricsCache.getOrLoad(key) { loads++; null })
        assertNull(ExternalLyricsCache.getOrLoad(key) { loads++; listOf(0L to "stale") })
        assertEquals(1, loads)

        val changed = key.copy(title = "new title")
        assertEquals(
            listOf(0L to "fresh"),
            ExternalLyricsCache.getOrLoad(changed) { loads++; listOf(0L to "fresh") }
        )
        assertEquals(2, loads)
    }

    @Test
    fun `manual offset and active line ignore untimed entries`() {
        val source = listOf(1_000L to "one", 2_000L to "two", -1L to "plain")
        val shifted = applyLyricsOffset(source, 500L)

        assertEquals(1_500L to "one", shifted[0])
        assertEquals(1, activeLyricsIndex(shifted, 1_900L))
        assertEquals(-1, activeLyricsIndex(shifted, 500L))
        assertTrue(shifted.last().first < 0L)
    }
}
