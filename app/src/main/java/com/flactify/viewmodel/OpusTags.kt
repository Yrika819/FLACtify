package com.flactify.viewmodel

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/** Bounded reader for the OpusTags packet (RFC 7845); malformed metadata is ignored. */
internal object OpusTags {
    private const val MAX_PACKET_BYTES = 1_048_576
    private const val MAX_TAGS = 4096
    private const val MAX_ENCODED_PICTURE_CHARS = 1_398_104

    fun read(file: File): Map<String, String> = try {
        FileInputStream(file).use(::read)
    } catch (_: Exception) {
        emptyMap()
    }

    fun read(stream: InputStream): Map<String, String> = try {
        DataInputStream(stream.buffered()).use { input ->
            val packet = ByteArrayOutputStream()
            var firstPacket = true
            var completedPackets = 0
            while (true) {
                val header = ByteArray(27)
                val firstByte = input.read()
                if (firstByte < 0) break
                header[0] = firstByte.toByte()
                input.readFully(header, 1, header.size - 1)
                if (String(header, 0, 4, Charsets.US_ASCII) != "OggS" || header[4].toInt() != 0) return emptyMap()
                val segments = header[26].toInt() and 0xff
                val lacing = ByteArray(segments)
                input.readFully(lacing)
                for (lengthByte in lacing) {
                    val length = lengthByte.toInt() and 0xff
                    if (packet.size() + length > MAX_PACKET_BYTES) return emptyMap()
                    val chunk = ByteArray(length)
                    input.readFully(chunk)
                    packet.write(chunk)
                    if (length < 255) {
                        val value = packet.toByteArray()
                        packet.reset()
                        completedPackets++
                        if (firstPacket) {
                            if (!value.startsWith("OpusHead")) return emptyMap()
                            firstPacket = false
                        } else if (completedPackets == 2) {
                            // RFC 7845 places OpusTags immediately after OpusHead. Stop
                            // here even for malformed files so a missing tag packet never
                            // makes metadata extraction walk the audio payload to EOF.
                            return if (value.startsWith("OpusTags")) parseComments(value) else emptyMap()
                        }
                    }
                }
            }
            emptyMap()
        }
    } catch (_: Exception) {
        emptyMap()
    }

    fun decodePicture(encoded: String): ByteArray? = try {
        require(encoded.length <= MAX_ENCODED_PICTURE_CHARS)
        val data = Base64.getDecoder().decode(encoded)
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        buffer.int // picture type
        skipLengthPrefixed(buffer) // MIME type
        skipLengthPrefixed(buffer) // description
        repeat(4) { buffer.int } // width, height, color depth, indexed colors
        val length = buffer.int
        if (length < 0 || length > buffer.remaining()) null else ByteArray(length).also(buffer::get)
    } catch (_: Exception) {
        null
    }

    private fun parseComments(packet: ByteArray): Map<String, String> {
        return try {
            val buffer = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
            buffer.position(8)
            val vendorLength = readLength(buffer)
            require(vendorLength <= buffer.remaining())
            buffer.position(buffer.position() + vendorLength)
            val count = readLength(buffer)
            require(count <= MAX_TAGS)
            val result = linkedMapOf<String, String>()
            repeat(count) {
                val length = readLength(buffer)
                require(length <= buffer.remaining())
                val bytes = ByteArray(length)
                buffer.get(bytes)
                val text = String(bytes, Charsets.UTF_8)
                val separator = text.indexOf('=')
                if (separator > 0) result[text.substring(0, separator).uppercase()] = text.substring(separator + 1)
            }
            result
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun readLength(buffer: ByteBuffer): Int {
        val length = buffer.int
        require(length >= 0)
        return length
    }

    private fun skipLengthPrefixed(buffer: ByteBuffer) {
        val size = buffer.int
        require(size >= 0 && size <= buffer.remaining())
        buffer.position(buffer.position() + size)
    }

    private fun ByteArray.startsWith(value: String): Boolean {
        val bytes = value.toByteArray(Charsets.US_ASCII)
        return size >= bytes.size && bytes.indices.all { this[it] == bytes[it] }
    }
}
