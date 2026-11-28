package com.flactify.viewmodel

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OggOpusTagEditorTest {
    private lateinit var directory: File

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("opus-tags-edit-").toFile()
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun `Japanese metadata and cover update preserve lyrics and every audio page payload`() {
        val source = File("src/test/resources/audio/opus_tagged.opus")
        val sourcePages = readPages(source.readBytes())
        val originalAudioPages = audioPagesAfterHeaders(sourcePages)
        val edited = File(directory, "edited.opus")
        val longComment = "日本語メモ".repeat(12_000)
        val cover = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 2, 3, 0xff.toByte(), 0xd9.toByte())

        OggOpusTagEditor.rewrite(
            source,
            edited,
            OggOpusTagEditor.Changes(
                fields = mapOf(
                    "TITLE" to "夜明けのテスト曲",
                    "ARTIST" to "試験アーティスト",
                    "ALBUM" to "合成アルバム",
                    "TRACKNUMBER" to "",
                    "DISCNUMBER" to "2",
                    "DATE" to "2026",
                    "COMMENT" to longComment
                ),
                frontCoverJpeg = cover,
                coverWidth = 2,
                coverHeight = 2
            )
        )

        val editedPages = readPages(edited.readBytes())
        val actualAudioPages = audioPagesAfterHeaders(editedPages)
        assertEquals(originalAudioPages.map { it.flags }, actualAudioPages.map { it.flags })
        assertEquals(originalAudioPages.map { it.granule }, actualAudioPages.map { it.granule })
        assertEquals(originalAudioPages.map { it.body.toList() }, actualAudioPages.map { it.body.toList() })
        assertTrue("growing tags may add pages before the audio stream", editedPages.size > sourcePages.size)
        assertTrue(editedPages.all(::hasValidCrc))

        val tags = OpusTags.read(edited)
        assertEquals("夜明けのテスト曲", tags["TITLE"])
        assertEquals("試験アーティスト", tags["ARTIST"])
        assertEquals("合成アルバム", tags["ALBUM"])
        assertEquals("2", tags["DISCNUMBER"])
        assertEquals("2026", tags["DATE"])
        assertEquals(longComment, tags["COMMENT"])
        assertEquals("[00:00.00]Synthetic Opus lyric", tags["LYRICS"])
        assertFalse("empty track number removes the edited field", tags.containsKey("TRACKNUMBER"))
        assertTrue(OpusTags.decodePicture(requireNotNull(tags["METADATA_BLOCK_PICTURE"]))!!.contentEquals(cover))
    }

    @Test
    fun `bad audio page CRC rejects rewrite without changing the input file`() {
        val original = File("src/test/resources/audio/opus_tagged.opus").readBytes()
        val pages = readPages(original)
        val audioStart = audioPageStartIndex(pages)
        val corrupted = original.copyOf().also { bytes ->
            val page = pages[audioStart]
            bytes[page.offset + page.bytes.lastIndex] = (bytes[page.offset + page.bytes.lastIndex].toInt() xor 1).toByte()
        }
        val source = File(directory, "corrupt.opus").apply { writeBytes(corrupted) }
        val before = source.readBytes()
        val edited = File(directory, "edited.opus")

        val failure = runCatching {
            OggOpusTagEditor.rewrite(source, edited, OggOpusTagEditor.Changes(mapOf("TITLE" to "別の曲")))
        }.exceptionOrNull()

        assertTrue(failure is java.io.IOException)
        assertTrue(before.contentEquals(source.readBytes()))
    }

    private data class TestPage(
        val offset: Int,
        val bytes: ByteArray,
        val flags: Int,
        val granule: Long,
        val sequence: Long,
        val lacing: IntArray,
        val body: ByteArray
    )

    private fun readPages(file: ByteArray): List<TestPage> {
        val result = mutableListOf<TestPage>()
        var offset = 0
        while (offset < file.size) {
            val segments = file[offset + 26].toInt() and 0xff
            val headerAndLacing = 27 + segments
            val bodyLength = (0 until segments).sumOf { file[offset + 27 + it].toInt() and 0xff }
            val pageBytes = file.copyOfRange(offset, offset + headerAndLacing + bodyLength)
            val lacing = IntArray(segments) { file[offset + 27 + it].toInt() and 0xff }
            val body = pageBytes.copyOfRange(headerAndLacing, pageBytes.size)
            result += TestPage(
                offset = offset,
                bytes = pageBytes,
                flags = pageBytes[5].toInt() and 0xff,
                granule = ByteBuffer.wrap(pageBytes).order(ByteOrder.LITTLE_ENDIAN).getLong(6),
                sequence = uint32(pageBytes, 18),
                lacing = lacing,
                body = body
            )
            offset += pageBytes.size
        }
        assertEquals("input should end at an Ogg page boundary", file.size, offset)
        return result
    }

    private fun audioPageStartIndex(pages: List<TestPage>): Int {
        var packetComplete = false
        var index = 1
        while (!packetComplete) {
            val page = pages[index]
            packetComplete = page.lacing.any { it < 255 }
            index++
        }
        return index
    }

    private fun audioPagesAfterHeaders(pages: List<TestPage>): List<TestPage> =
        pages.drop(audioPageStartIndex(pages))

    private fun hasValidCrc(page: TestPage): Boolean {
        val expected = uint32(page.bytes, 22)
        val input = page.bytes.copyOf().also { it.fill(0, 22, 26) }
        var crc = 0
        input.forEach { byte ->
            crc = crc xor ((byte.toInt() and 0xff) shl 24)
            repeat(8) {
                crc = if (crc < 0) (crc shl 1) xor 0x04c11db7 else crc shl 1
            }
        }
        return (crc.toLong() and 0xffff_ffffL) == expected
    }

    private fun uint32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xff) or
            ((bytes[offset + 1].toLong() and 0xff) shl 8) or
            ((bytes[offset + 2].toLong() and 0xff) shl 16) or
            ((bytes[offset + 3].toLong() and 0xff) shl 24)
}
