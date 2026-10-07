package com.flactify.viewmodel

import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset

class ExtractLyricsFromFlacTest {

    @Test
    fun `parseLyrics preserves untimed lyric lines`() {
        val extractor = TrackMetadataExtractor(mockk(relaxed = true))
        val method = TrackMetadataExtractor::class.java.getDeclaredMethod("parseLyrics", String::class.java)
        method.isAccessible = true

        @Suppress("UNCHECKED_CAST")
        val result = method.invoke(extractor, "Verse one\nVerse two") as List<Pair<Long, String>>

        assertEquals(listOf(-1L to "Verse one", -1L to "Verse two"), result)
    }

    @Test
    fun `extractLyricsFromFlac returns null for non-flac non-id3 stream`() {
        val data = "NOT_A_FLAC_FILE".toByteArray()
        val stream = ByteArrayInputStream(data)
        val result = invokeExtractLyrics(stream)
        assertNull(result)
    }

    @Test
    fun `extractLyricsFromFlac returns lyrics from pure flac`() {
        val data = createMinimalFlacWithLyrics("[00:12.34]Hello World")
        val stream = ByteArrayInputStream(data)
        val result = invokeExtractLyrics(stream)
        assertNotNull(result)
        assertEquals("[00:12.34]Hello World", result)
    }

    @Test
    fun `extractLyricsFromFlac returns lyrics from id3-tagged flac`() {
        val id3TagSize = 64
        val flacData = createMinimalFlacWithLyrics("[01:23.45]Test Lyrics")
        val id3Header = createId3Header(id3TagSize)

        val padding = ByteArray(id3TagSize - 10)
        val fullData = ByteArray(id3Header.size + padding.size + flacData.size)
        System.arraycopy(id3Header, 0, fullData, 0, id3Header.size)
        System.arraycopy(padding, 0, fullData, id3Header.size, padding.size)
        System.arraycopy(flacData, 0, fullData, id3Header.size + padding.size, flacData.size)

        val stream = ByteArrayInputStream(fullData)
        val result = invokeExtractLyrics(stream)
        assertNotNull("Should extract lyrics from ID3-tagged FLAC", result)
        assertEquals("[01:23.45]Test Lyrics", result)
    }

    @Test
    fun `extractLyricsFromFlac returns null when id3 tag has no flac after`() {
        val id3TagSize = 10
        val id3Header = createId3Header(id3TagSize)
        val padding = ByteArray(id3TagSize - 10)
        val fullData = id3Header + padding + "GARBAGE".toByteArray()

        val stream = ByteArrayInputStream(fullData)
        val result = invokeExtractLyrics(stream)
        assertNull(result)
    }

    @Test
    fun `extractLyricsFromFlac returns null for empty stream`() {
        val stream = ByteArrayInputStream(ByteArray(0))
        val result = invokeExtractLyrics(stream)
        assertNull(result)
    }

    private fun invokeExtractLyrics(inputStream: ByteArrayInputStream): String? {
        val extractor = TrackMetadataExtractor(mockk(relaxed = true))
        val method = TrackMetadataExtractor::class.java.getDeclaredMethod(
            "extractLyricsFromFlac",
            java.io.InputStream::class.java
        )
        method.isAccessible = true
        return method.invoke(extractor, inputStream) as String?
    }

    private fun createMinimalFlacWithLyrics(lyrics: String): ByteArray {
        val output = mutableListOf<Byte>()

        output.addAll("fLaC".toByteArray().toList())

        val vendorString = "test"
        val comment = "LYRICS=$lyrics"
        val commentBytes = comment.toByteArray(Charset.forName("UTF-8"))

        val commentBlockSize = 4 + vendorString.length + 4 + 4 + commentBytes.size
        val blockHeader = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
        blockHeader.putInt((4 shl 24) or (commentBlockSize and 0x00FFFFFF))
        output.addAll(blockHeader.array().toList())

        val commentData = ByteBuffer.allocate(commentBlockSize).order(ByteOrder.LITTLE_ENDIAN)
        commentData.putInt(vendorString.length)
        commentData.put(vendorString.toByteArray(Charset.forName("UTF-8")))
        commentData.putInt(1)
        commentData.putInt(commentBytes.size)
        commentData.put(commentBytes)
        output.addAll(commentData.array().toList())

        val streamInfoHeader = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
        streamInfoHeader.putInt((0 shl 24) or (34 and 0x00FFFFFF) or (1 shl 31))
        output.addAll(streamInfoHeader.array().toList())

        val streamInfo = ByteArray(34)
        output.addAll(streamInfo.toList())

        return output.toByteArray()
    }

    private fun createId3Header(totalTagSize: Int): ByteArray {
        val syncsafeSize = totalTagSize - 10
        val header = ByteArray(10)
        header[0] = 'I'.code.toByte()
        header[1] = 'D'.code.toByte()
        header[2] = '3'.code.toByte()
        header[3] = 3
        header[4] = 0
        header[5] = 0

        header[6] = ((syncsafeSize shr 21) and 0x7F).toByte()
        header[7] = ((syncsafeSize shr 14) and 0x7F).toByte()
        header[8] = ((syncsafeSize shr 7) and 0x7F).toByte()
        header[9] = (syncsafeSize and 0x7F).toByte()

        return header
    }
}
