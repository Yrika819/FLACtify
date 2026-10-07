package com.flactify.viewmodel

import android.net.Uri
import android.graphics.Bitmap
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for the metadata cache lifecycle.
 *
 * A tag edit rewrites the file, but the extractor caches album art, lyrics and audio spec per
 * URI. Without explicit invalidation a later cache hit resurrects the pre-edit values.
 */
class TrackMetadataExtractorCacheTest {

    private val context = mockk<android.content.Context>(relaxed = true)

    private fun uri(name: String): Uri = mockk<Uri>(relaxed = true).also { mock ->
        every { mock.toString() } returns "content://media/audio/$name"
        every { mock.hashCode() } returns name.hashCode()
        every { mock.equals(any()) } answers { firstArg<Uri>() === mock }
    }

    @Test
    fun `invalidate removes cached tag-derived entries but keeps the file extension`() {
        val extractor = TrackMetadataExtractor(context)
        val target = uri("song.flac")

        extractor.fileExtensionCache[target] = "flac"
        extractor.lyricsCache[target] = listOf(0L to "old lyric")
        extractor.audioSpecCache[target] = AudioSpec(sampleRateHz = 96_000, bitDepthLabel = "24bit")
        // The bitmap cache stores only non-null values; artwork absence is represented by a missing key.
        extractor.albumArtCache.remove(target)

        extractor.invalidate(target)

        assertNull(extractor.lyricsCache[target])
        assertNull(extractor.audioSpecCache[target])
        assertTrue("a null artwork entry must also be dropped", !extractor.albumArtCache.containsKey(target))
        // The extension is a property of the file path, not of its tags, and the playlist
        // builder reads it to pick a MIME type, so it must survive a tag edit.
        assertEquals("flac", extractor.fileExtensionCache[target])
    }

    @Test
    fun `a track with no artwork does not poison the whole extraction`() {
        // Artwork misses must leave the cache empty without interfering with other metadata.
        val extractor = TrackMetadataExtractor(context)
        val target = uri("noart.flac")

        // Absence is now expressed by removing the key rather than storing null.
        extractor.albumArtCache.remove(target)
        assertNull(extractor.albumArtCache[target])

        // Other per-URI metadata caches remain usable when artwork is absent.
        extractor.lyricsCache[target] = listOf(0L to "lyric survives")
        assertEquals(1, extractor.lyricsCache[target]?.size)
    }

    @Test
    fun `invalidate leaves other tracks untouched`() {
        val extractor = TrackMetadataExtractor(context)
        val edited = uri("edited.flac")
        val other = uri("other.flac")

        extractor.audioSpecCache[edited] = AudioSpec(sampleRateHz = 44_100, bitDepthLabel = "16bit")
        extractor.audioSpecCache[other] = AudioSpec(sampleRateHz = 48_000, bitDepthLabel = "24bit")

        extractor.invalidate(edited)

        assertNull(extractor.audioSpecCache[edited])
        assertEquals(
            AudioSpec(sampleRateHz = 48_000, bitDepthLabel = "24bit"),
            extractor.audioSpecCache[other]
        )
    }

    @Test
    fun `an unreadable file yields no audio spec rather than a default one`() {
        // Regression: the extractor used to start from `var sampleRate = 44100` and to fall
        // back to `Pair(44100, "16bit")` on a cache miss, so a file the extractor could not
        // read was still displayed as 44.1 kHz / 16-bit.
        val extractor = TrackMetadataExtractor(context)
        val target = uri("unreadable.flac")

        assertEquals(AudioSpec(), AudioSpec())
        assertNull(extractor.audioSpecCache[target])
    }

    @Test
    fun `AudioSpec defaults to unknown on every field`() {
        val spec = AudioSpec()
        assertNull(spec.sampleRateHz)
        assertNull(spec.bitDepthLabel)
    }

    @Test
    fun `getCachedMetadata misses after invalidation`() {
        val extractor = TrackMetadataExtractor(context)
        val target = uri("song.flac")

        extractor.lyricsCache[target] = listOf(0L to "stale")
        extractor.audioSpecCache[target] = AudioSpec(sampleRateHz = 96_000, bitDepthLabel = "24bit")

        // A cache hit exists before the edit...
        assertNotNull(extractor.getCachedMetadata(target))

        extractor.invalidate(target)

        // ...and is gone afterwards, forcing a re-read.
        assertNull(extractor.getCachedMetadata(target))
    }

    @Test
    fun `bitmap cache evicts least recently used artwork within byte budget`() {
        val cache = BoundedBitmapCache<Uri>(10L)
        val first = uri("first.flac")
        val second = uri("second.flac")
        val third = uri("third.flac")
        val firstBitmap = bitmapBytes(5)
        val secondBitmap = bitmapBytes(5)
        val thirdBitmap = bitmapBytes(5)

        cache[first] = firstBitmap
        cache[second] = secondBitmap
        cache[first] // mark first as recently used
        cache[third] = thirdBitmap

        assertNotNull(cache[first])
        assertNull(cache[second])
        assertNotNull(cache[third])
        assertTrue(cache.retainedBytes() <= 10L)
    }

    @Test
    fun `album art invalidation removes stale bitmap after a tag edit`() {
        val extractor = TrackMetadataExtractor(context)
        val edited = uri("edited-art.flac")
        val staleArt = bitmapBytes(8)
        extractor.albumArtCache[edited] = staleArt

        extractor.invalidate(edited)

        assertNull(extractor.albumArtCache[edited])
        assertTrue(!extractor.albumArtCache.containsKey(edited))
    }

    private fun bitmapBytes(size: Int): Bitmap = mockk<Bitmap> {
        every { allocationByteCount } returns size
    }
}
