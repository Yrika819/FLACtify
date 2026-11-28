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
    private const val MAX_PACKET_BYTES = 16 * 1_048_576
    private const val MAX_TAGS = 4096
    private const val MAX_ENCODED_PICTURE_CHARS = 22_369_622

    fun read(file: File): Map<String, String> = try {
        FileInputStream(file).use(::read)
    } catch (_: Exception) {
        emptyMap()
    }

    fun read(stream: InputStream): Map<String, String> = try {
        // The reader stops as soon as OpusTags (packet 2) is complete. Do not add a buffered
        // wrapper here: a provider-backed stream would otherwise read ahead into audio payload
        // bytes that metadata extraction never needs.
        DataInputStream(stream).use { input ->
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
        val pictureType = buffer.int
        require(pictureType >= 0)
        skipLengthPrefixed(buffer) // MIME type
        skipLengthPrefixed(buffer) // description
        val width = buffer.int
        val height = buffer.int
        val depth = buffer.int
        val colors = buffer.int
        require(width >= 0 && height >= 0 && depth >= 0 && colors >= 0)
        val length = buffer.int
        if (length < 0 || length != buffer.remaining()) null
        else if (pictureType != FRONT_COVER_PICTURE_TYPE) null
        else ByteArray(length).also(buffer::get)
    } catch (_: Exception) {
        null
    }

    /** Replaces only the fields edited in the dialog, keeping all other comments and suffix data. */
    fun updatePacket(
        packet: ByteArray,
        fields: Map<String, String?>,
        frontCoverJpeg: ByteArray?
    ): ByteArray {
        require(packet.size in 16..MAX_PACKET_BYTES) { "Invalid OpusTags packet size" }
        require(packet.copyOfRange(0, 8).contentEquals("OpusTags".toByteArray(Charsets.US_ASCII))) {
            "Invalid OpusTags signature"
        }

        val buffer = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(8)
        val vendorLength = readLength(buffer)
        require(vendorLength <= buffer.remaining()) { "Invalid OpusTags vendor length" }
        val vendor = ByteArray(vendorLength).also(buffer::get)
        val count = readLength(buffer)
        require(count <= MAX_TAGS) { "Too many OpusTags comments" }
        val comments = ArrayList<ByteArray>(count + fields.size + if (frontCoverJpeg != null) 1 else 0)
        repeat(count) {
            val length = readLength(buffer)
            require(length <= buffer.remaining()) { "Invalid OpusTags comment length" }
            comments += ByteArray(length).also(buffer::get)
        }
        val trailingData = ByteArray(buffer.remaining()).also(buffer::get)

        val normalizedFields = fields.mapKeys { it.key.uppercase() }
        comments.removeAll { raw -> raw.keyOrNull()?.let(normalizedFields::containsKey) == true }
        normalizedFields.forEach { (key, value) ->
            if (!value.isNullOrBlank()) {
                require(key.isNotBlank() && key.all { it.code in 0x20..0x7d && it != '=' })
                comments += "$key=$value".toByteArray(Charsets.UTF_8)
            }
        }

        if (frontCoverJpeg != null) {
            comments.removeAll { raw ->
                raw.keyOrNull() == "METADATA_BLOCK_PICTURE" && raw.isFrontCoverPictureComment()
            }
            comments += "METADATA_BLOCK_PICTURE=${encodePicture(frontCoverJpeg)}".toByteArray(Charsets.UTF_8)
        }
        require(comments.size <= MAX_TAGS) { "Too many OpusTags comments" }

        val output = ByteArrayOutputStream(minOf(MAX_PACKET_BYTES, packet.size + 1024))
        output.write("OpusTags".toByteArray(Charsets.US_ASCII))
        output.writeLittleEndianInt(vendor.size)
        output.write(vendor)
        output.writeLittleEndianInt(comments.size)
        comments.forEach { comment ->
            output.writeLittleEndianInt(comment.size)
            output.write(comment)
        }
        output.write(trailingData)
        return output.toByteArray().also {
            require(it.size <= MAX_PACKET_BYTES) { "OpusTags packet exceeds the safe size limit" }
        }
    }

    fun createFrontCoverPicture(jpeg: ByteArray, width: Int, height: Int): ByteArray {
        require(jpeg.isNotEmpty())
        val mime = "image/jpeg".toByteArray(Charsets.US_ASCII)
        val output = ByteArrayOutputStream(jpeg.size + 40)
        output.writeBigEndianInt(3) // front cover
        output.writeBigEndianInt(mime.size)
        output.write(mime)
        output.writeBigEndianInt(0) // empty description
        output.writeBigEndianInt(width)
        output.writeBigEndianInt(height)
        output.writeBigEndianInt(24) // JPEG truecolor
        output.writeBigEndianInt(0) // no indexed colors
        output.writeBigEndianInt(jpeg.size)
        output.write(jpeg)
        return output.toByteArray()
    }

    private fun encodePicture(picture: ByteArray): String =
        Base64.getEncoder().encodeToString(picture)

    private fun ByteArray.keyOrNull(): String? {
        val separator = indexOf('='.code.toByte())
        if (separator <= 0) return null
        return String(this, 0, separator, Charsets.US_ASCII).uppercase()
    }

    private fun ByteArray.isFrontCoverPictureComment(): Boolean = try {
        val separator = indexOf('='.code.toByte())
        val encoded = String(this, separator + 1, size - separator - 1, Charsets.US_ASCII)
        hasFrontCoverPictureType(encoded)
    } catch (_: Exception) {
        false
    }

    private fun hasFrontCoverPictureType(encoded: String): Boolean = try {
        if (encoded.length > MAX_ENCODED_PICTURE_CHARS) false else {
            val picture = Base64.getDecoder().decode(encoded)
            val buffer = ByteBuffer.wrap(picture).order(ByteOrder.BIG_ENDIAN)
            val type = buffer.int
            skipLengthPrefixed(buffer)
            skipLengthPrefixed(buffer)
            repeat(4) { require(buffer.int >= 0) }
            val length = buffer.int
            type == FRONT_COVER_PICTURE_TYPE && length >= 0 && length == buffer.remaining()
        }
    } catch (_: Exception) {
        false
    }

    private fun ByteArrayOutputStream.writeLittleEndianInt(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
        write((value ushr 16) and 0xff)
        write((value ushr 24) and 0xff)
    }

    private fun ByteArrayOutputStream.writeBigEndianInt(value: Int) {
        write((value ushr 24) and 0xff)
        write((value ushr 16) and 0xff)
        write((value ushr 8) and 0xff)
        write(value and 0xff)
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
                if (separator > 0) {
                    val key = text.substring(0, separator).uppercase()
                    val value = text.substring(separator + 1)
                    if (key != "METADATA_BLOCK_PICTURE" || hasFrontCoverPictureType(value)) {
                        result[key] = value
                    }
                }
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

    private const val FRONT_COVER_PICTURE_TYPE = 3

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
