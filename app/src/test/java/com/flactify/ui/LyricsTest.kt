package com.flactify.ui

import android.net.Uri
import android.provider.DocumentsContract
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
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

    @org.junit.After
    fun clearAndroidStatics() {
        unmockkStatic(Uri::class)
        unmockkStatic(DocumentsContract::class)
    }

    @Test
    fun `external URI stays within an authorized tree and uses sibling lrc name`() {
        mockkStatic(Uri::class)
        mockkStatic(DocumentsContract::class)
        val track = uri("track")
        every { DocumentsContract.isTreeUri(track) } returns true
        every { DocumentsContract.getTreeDocumentId(track) } returns "primary:Music"
        every { DocumentsContract.getDocumentId(track) } returns "primary:Music/track.flac"
        every { Uri.decode("track.flac") } returns "track.flac"
        val expected = uri("expected-lrc")
        every { DocumentsContract.buildDocumentUriUsingTree(track, "primary:Music/track.lrc") } returns expected

        val external = externalLyricsUriFor(track)

        assertEquals(expected, external)
    }

    @Test
    fun `plain document URI does not guess an unauthorized parent`() {
        mockkStatic(DocumentsContract::class)
        val track = uri("plain-document")
        every { DocumentsContract.isTreeUri(track) } returns false
        assertNull(externalLyricsUriFor(track))
    }

    @Test
    fun `selected tree grant resolves a child document sibling`() {
        mockkStatic(Uri::class)
        mockkStatic(DocumentsContract::class)
        val tree = uri("tree")
        val track = uri("child-document")
        every { DocumentsContract.isTreeUri(tree) } returns true
        every { DocumentsContract.getTreeDocumentId(tree) } returns "primary:Music"
        every { DocumentsContract.getDocumentId(track) } returns "primary:Music/track.flac"
        every { Uri.decode("track.flac") } returns "track.flac"
        val expected = uri("tree-sibling-lrc")
        every { DocumentsContract.buildDocumentUriUsingTree(tree, "primary:Music/track.lrc") } returns expected

        assertEquals(expected, externalLyricsUriFor(track, tree))
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
    fun `cache does not retain a lyric value larger than its aggregate budget`() {
        val key = ExternalLyricsCacheKey("large", null, null, null)
        val largeLine = "x".repeat(ExternalLyricsCache.MAX_CACHED_TEXT_BYTES / 2)
        var loads = 0
        val loader: () -> List<Pair<Long, String>> = {
            loads++
            listOf(0L to largeLine)
        }

        assertEquals(largeLine, ExternalLyricsCache.getOrLoad(key, loader)?.single()?.second)
        assertEquals(largeLine, ExternalLyricsCache.getOrLoad(key, loader)?.single()?.second)
        assertEquals(2, loads)
    }

    @Test
    fun `manual offset and active line ignore untimed entries`() {
        val source = listOf(1_000L to "one", 2_000L to "two", -1L to "plain")
        val shifted = applyLyricsOffset(source, 500L)

        assertEquals(1_500L to "one", shifted[0])
        assertEquals(0, activeLyricsIndex(shifted, 1_900L))
        assertEquals(-1, activeLyricsIndex(shifted, 500L))
        assertTrue(shifted.last().first < 0L)
    }

    private fun uri(label: String): Uri {
        val value = mockk<Uri>(relaxed = true)
        every { value.toString() } returns "content://synthetic.documents/$label"
        every { value.hashCode() } returns label.hashCode()
        every { value.equals(any()) } answers { firstArg<Any>() === value }
        return value
    }
}
