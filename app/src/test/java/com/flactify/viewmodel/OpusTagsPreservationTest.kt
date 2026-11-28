package com.flactify.viewmodel

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpusTagsPreservationTest {
    @Test
    fun `multi-value updates preserve lyrics replaygain artwork bytes and packet suffix`() {
        val original = packet(
            listOf(
                "TITLE=old",
                "LYRICS=[00:00.00]one",
                "LYRICS=[00:01.00]two",
                "REPLAYGAIN_TRACK_GAIN=-8.00 dB",
                "METADATA_BLOCK_PICTURE=opaque-picture"
            ),
            suffix = byteArrayOf(0x55, 0x66, 0x77)
        )

        val updated = OpusTags.updatePacketValues(
            original,
            mapOf("TITLE" to listOf("日本語", "English")),
            frontCoverJpeg = null
        )
        val values = OpusTags.readPacketValues(updated)

        assertEquals(listOf("日本語", "English"), values["TITLE"])
        assertEquals(listOf("[00:00.00]one", "[00:01.00]two"), values["LYRICS"])
        assertEquals(listOf("-8.00 dB"), values["REPLAYGAIN_TRACK_GAIN"])
        assertTrue(updated.copyOfRange(updated.size - 3, updated.size).contentEquals(byteArrayOf(0x55, 0x66, 0x77)))
        assertTrue(updated.toString(Charsets.UTF_8).contains("METADATA_BLOCK_PICTURE=opaque-picture"))
    }

    private fun packet(comments: List<String>, suffix: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        output.write("OpusTags".toByteArray(Charsets.US_ASCII))
        output.writeLittleEndian(0)
        output.writeLittleEndian(comments.size)
        comments.forEach {
            val bytes = it.toByteArray(Charsets.UTF_8)
            output.writeLittleEndian(bytes.size)
            output.write(bytes)
        }
        output.write(suffix)
        return output.toByteArray()
    }

    private fun ByteArrayOutputStream.writeLittleEndian(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
        write((value ushr 16) and 0xff)
        write((value ushr 24) and 0xff)
    }
}
