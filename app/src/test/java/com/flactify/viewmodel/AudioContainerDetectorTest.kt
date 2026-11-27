package com.flactify.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.ByteArrayOutputStream

class AudioContainerDetectorTest {
    @Test
    fun `sniffs opus independent of opus ogg and m4a suffixes`() {
        val prefix = oggOpusPrefix()
        assertEquals("opus", AudioContainerDetector.detect(prefix))
    }

    @Test
    fun `recognizes existing supported containers from their signatures`() {
        assertEquals("m4a", AudioContainerDetector.detect("....ftypM4A ".toByteArray()))
        assertEquals("flac", AudioContainerDetector.detect("fLaC".toByteArray()))
        assertEquals("wav", AudioContainerDetector.detect("RIFF....WAVE".toByteArray()))
        assertEquals("mp3", AudioContainerDetector.detect(byteArrayOf(0xff.toByte(), 0xfb.toByte())))
    }

    @Test
    fun `rejects unsupported corrupt data instead of inventing an audio type`() {
        assertNull(AudioContainerDetector.detect("not audio".toByteArray()))
    }

    @Test
    fun `reads opus comments from generated tagged fixture`() {
        val fixture = File("src/test/resources/audio/opus_tagged.opus")
        val tags = OpusTags.read(fixture)
        assertEquals("Synthetic Opus", tags["TITLE"])
        assertEquals("FLACtify Test", tags["ARTIST"])
        assertEquals("1", tags["TRACKNUMBER"])
        assertEquals("[00:00.00]Synthetic Opus lyric", tags["LYRICS"])
        val picture = requireNotNull(tags["METADATA_BLOCK_PICTURE"]?.let(OpusTags::decodePicture))
        assertArrayEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a), picture.copyOf(8))
    }

    @Test
    fun `fixture matrix detects containers regardless of suffix`() {
        assertFixture("opus_tagged.opus", "opus")
        assertFixture("opus_as_ogg.ogg", "opus")
        assertFixture("opus_as_m4a.m4a", "opus")
        assertFixture("aac.m4a", "m4a")
        assertFixture("flac_44100_16_stereo.flac", "flac")
        assertFixture("synthetic.mp3", "mp3")
        assertFixture("synthetic.wav", "wav")
        assertFixture("opus_untagged.opus", "opus")
        assertFixture("corrupt.opus", null)
    }

    @Test
    fun `untagged and corrupt Opus packets fall back without throwing`() {
        assertEquals(null, OpusTags.read(File("src/test/resources/audio/opus_untagged.opus"))["TITLE"])
        assertEquals(emptyMap<String, String>(), OpusTags.read(File("src/test/resources/audio/corrupt.opus")))
    }

    @Test
    fun `malformed Ogg without OpusTags is rejected before reading audio payload`() {
        val malformed = ByteArrayOutputStream().apply {
            write(oggPage("OpusHead".toByteArray() + ByteArray(11), headerType = 2))
            write(oggPage(byteArrayOf(1), headerType = 0))
            write(ByteArray(128 * 1024))
        }.toByteArray()
        val input = CountingInputStream(malformed)

        assertEquals(emptyMap<String, String>(), OpusTags.read(input))
        assertTrue("metadata parser must stop before the audio pages", input.bytesRead < malformed.size / 4)
    }

    private fun oggOpusPrefix(): ByteArray = ByteArray(64).also { bytes ->
        "OggS".toByteArray().copyInto(bytes)
        "OpusHead".toByteArray().copyInto(bytes, 28)
    }

    private fun assertFixture(name: String, expected: String?) {
        val fixture = File("src/test/resources/audio/$name")
        val actual = FileInputStream(fixture).use(AudioContainerDetector::sniff)
        assertEquals(name, expected, actual)
    }

    private fun oggPage(packet: ByteArray, headerType: Int): ByteArray = ByteArrayOutputStream().apply {
        write("OggS".toByteArray(Charsets.US_ASCII))
        write(0)
        write(headerType)
        write(ByteArray(8 + 4 + 4 + 4)) // granule position, serial, sequence, checksum
        write(1) // one lacing value
        write(packet.size)
        write(packet)
    }.toByteArray()

    private class CountingInputStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var bytesRead = 0
            private set

        override fun read(): Int = super.read().also { if (it >= 0) bytesRead++ }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            super.read(buffer, offset, length).also { if (it > 0) bytesRead += it }
    }
}
