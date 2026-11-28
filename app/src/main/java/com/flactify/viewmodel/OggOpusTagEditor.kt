package com.flactify.viewmodel

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Streaming metadata-only rewrite for a single RFC 7845 Ogg Opus logical stream.
 * Audio pages are copied byte-for-byte except for page sequence numbers and the CRC that covers
 * them. Unsupported/non-conforming layouts are rejected before the caller writes to the SAF URI.
 */
internal object OggOpusTagEditor {
    private const val OGG_HEADER_BYTES = 27
    private const val MAX_LACING_VALUES = 255
    private const val MAX_PAGE_BYTES = OGG_HEADER_BYTES + MAX_LACING_VALUES + MAX_LACING_VALUES * 255
    private const val OGG_CONTINUED = 0x01
    private const val OGG_BOS = 0x02
    private const val OGG_EOS = 0x04
    private const val SEQUENCE_MASK = 0xffff_ffffL
    private const val OGG_CRC_POLYNOMIAL = 0x04c11db7

    internal data class Changes(
        val fields: Map<String, String?>,
        val frontCoverJpeg: ByteArray? = null,
        val coverWidth: Int = 0,
        val coverHeight: Int = 0
    )

    internal data class RewriteResult(val frontCoverBytes: ByteArray?)

    fun rewrite(source: File, destination: File, changes: Changes): RewriteResult {
        DataInputStream(BufferedInputStream(FileInputStream(source))).use { input ->
            BufferedOutputStream(FileOutputStream(destination, false)).use { output ->
                val first = readPage(input) ?: throw IOException("Empty Ogg stream")
                val serial = first.serial
                var expectedSequence = first.sequence
                validatePage(first, serial, expectedSequence++)
                requirePage(first.flags and OGG_BOS != 0 && first.flags and OGG_EOS == 0, "Invalid Opus ID page flags")
                requirePage(first.granule == 0L, "Invalid Opus ID page granule")
                requirePage(first.flags and OGG_CONTINUED == 0, "Opus ID packet starts continued")
                val head = singleCompletedPacket(first, "OpusHead")
                requirePage(head.size >= 19 && head.startsWith("OpusHead"), "Invalid Opus ID packet")
                requirePage((head[8].toInt() and 0xf0) == 0, "Unsupported Opus header version")

                var originalTagPageCount = 0
                val tagsPacket = ByteArrayOutputStream()
                var commentPacketComplete = false
                var previousPageContinuesPacket = false
                while (!commentPacketComplete) {
                    val page = readPage(input) ?: throw IOException("Truncated OpusTags packet")
                    validatePage(page, serial, expectedSequence++)
                    requirePage(page.flags and OGG_BOS == 0 && page.flags and OGG_EOS == 0, "Invalid OpusTags page flags")
                    requirePage(page.granule == 0L, "Invalid OpusTags granule position")
                    requirePage(
                        (page.flags and OGG_CONTINUED != 0) == previousPageContinuesPacket,
                        "Invalid OpusTags continuation flag"
                    )
                    originalTagPageCount++
                    var bodyOffset = 0
                    page.lacing.forEachIndexed { index, lace ->
                        if (tagsPacket.size().toLong() + lace > MAX_TAG_PACKET_BYTES) {
                            throw IOException("OpusTags packet exceeds the safe size limit")
                        }
                        tagsPacket.write(page.body, bodyOffset, lace)
                        bodyOffset += lace
                        if (lace < 255) {
                            requirePage(index == page.lacing.lastIndex, "Data follows OpusTags on its completion page")
                            commentPacketComplete = true
                        }
                    }
                    requirePage(page.lacing.isNotEmpty(), "Empty OpusTags page")
                    previousPageContinuesPacket = page.lacing.last() == 255
                    requirePage(commentPacketComplete || previousPageContinuesPacket, "Malformed OpusTags lacing")
                }

                val originalTags = tagsPacket.toByteArray()
                requirePage(originalTags.startsWith("OpusTags"), "Invalid OpusTags signature")
                val replacementTags = OpusTags.updatePacket(
                    originalTags,
                    changes.fields,
                    changes.frontCoverJpeg?.let {
                        OpusTags.createFrontCoverPicture(it, changes.coverWidth, changes.coverHeight)
                    }
                )
                val replacementTagPages = packetPages(
                    packet = replacementTags,
                    serial = serial,
                    firstSequence = (first.sequence + 1L) and SEQUENCE_MASK
                )

                output.write(first.bytes)
                replacementTagPages.forEach(output::write)

                val sequenceDelta = replacementTagPages.size.toLong() - originalTagPageCount
                var audioPageCount = 0L
                var audioPageContinuesPacket = false
                var reachedEndOfStream = false
                while (true) {
                    val page = readPage(input) ?: break
                    requirePage(!reachedEndOfStream, "Ogg pages follow the end-of-stream page")
                    validatePage(page, serial, expectedSequence++)
                    requirePage(page.flags and OGG_BOS == 0, "Unexpected Ogg beginning-of-stream page")
                    requirePage(
                        (page.flags and OGG_CONTINUED != 0) == audioPageContinuesPacket,
                        "Invalid audio packet continuation flag"
                    )
                    requirePage(page.lacing.isNotEmpty(), "Empty Opus audio page")
                    audioPageContinuesPacket = page.lacing.last() == 255
                    reachedEndOfStream = page.flags and OGG_EOS != 0
                    val rewritten = page.bytes.copyOf()
                    writeUInt32Le(rewritten, 18, (page.sequence + sequenceDelta) and SEQUENCE_MASK)
                    rewritten.fill(0, 22, 26)
                    writeUInt32Le(rewritten, 22, oggCrc(rewritten).toLong() and SEQUENCE_MASK)
                    output.write(rewritten)
                    audioPageCount++
                }
                requirePage(audioPageCount > 0, "Opus stream has no audio pages")
                requirePage(reachedEndOfStream, "Truncated Opus stream without end-of-stream page")
                requirePage(!audioPageContinuesPacket, "Truncated final Opus audio packet")
                output.flush()
                FileOutputStreamSync.sync(destination)

                return RewriteResult(
                    frontCoverBytes = changes.frontCoverJpeg ?: readFrontCover(originalTags, replacementTags)
                )
            }
        }
    }

    private fun readFrontCover(original: ByteArray, updated: ByteArray): ByteArray? {
        val tags = OpusTags.read(updated.inputStream())
        return tags["METADATA_BLOCK_PICTURE"]?.let(OpusTags::decodePicture)
            ?: OpusTags.read(original.inputStream())["METADATA_BLOCK_PICTURE"]?.let(OpusTags::decodePicture)
    }

    private data class Page(
        val bytes: ByteArray,
        val flags: Int,
        val granule: Long,
        val serial: Long,
        val sequence: Long,
        val lacing: IntArray,
        val body: ByteArray
    )

    private fun readPage(input: DataInputStream): Page? {
        val header = ByteArray(OGG_HEADER_BYTES)
        val first = input.read()
        if (first < 0) return null
        header[0] = first.toByte()
        try {
            input.readFully(header, 1, OGG_HEADER_BYTES - 1)
        } catch (e: EOFException) {
            throw IOException("Truncated Ogg page header", e)
        }
        requirePage(header.startsWith("OggS"), "Invalid Ogg capture pattern")
        requirePage(header[4].toInt() == 0, "Unsupported Ogg page version")
        val segmentCount = header[26].toInt() and 0xff
        val lacingBytes = ByteArray(segmentCount)
        try {
            input.readFully(lacingBytes)
        } catch (e: EOFException) {
            throw IOException("Truncated Ogg lacing table", e)
        }
        val lacing = IntArray(segmentCount) { lacingBytes[it].toInt() and 0xff }
        val bodyLength = lacing.sum()
        val body = ByteArray(bodyLength)
        try {
            input.readFully(body)
        } catch (e: EOFException) {
            throw IOException("Truncated Ogg page body", e)
        }
        val bytes = ByteArray(header.size + lacingBytes.size + body.size)
        header.copyInto(bytes)
        lacingBytes.copyInto(bytes, header.size)
        body.copyInto(bytes, header.size + lacingBytes.size)
        requirePage(bytes.size <= MAX_PAGE_BYTES, "Ogg page exceeds the format limit")
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val flags = header[5].toInt() and 0xff
        requirePage(flags and 0xf8 == 0, "Reserved Ogg page flags are set")
        return Page(
            bytes = bytes,
            flags = flags,
            granule = buffer.getLong(6),
            serial = uint32Le(header, 14),
            sequence = uint32Le(header, 18),
            lacing = lacing,
            body = body
        )
    }

    private fun validatePage(page: Page, serial: Long, sequence: Long) {
        requirePage(page.serial == serial, "Chained or multiplexed Ogg streams are not supported for editing")
        requirePage(page.sequence == (sequence and SEQUENCE_MASK), "Ogg page sequence is discontinuous")
        val checksum = uint32Le(page.bytes, 22)
        val checked = page.bytes.copyOf().also { it.fill(0, 22, 26) }
        requirePage((oggCrc(checked).toLong() and SEQUENCE_MASK) == checksum, "Ogg page CRC check failed")
    }

    private fun singleCompletedPacket(page: Page, signature: String): ByteArray {
        requirePage(page.lacing.isNotEmpty(), "Missing $signature packet")
        val output = ByteArrayOutputStream()
        var offset = 0
        page.lacing.forEachIndexed { index, size ->
            output.write(page.body, offset, size)
            offset += size
            if (size < 255) requirePage(index == page.lacing.lastIndex, "Unexpected packet on $signature page")
        }
        requirePage(page.lacing.last() < 255, "Incomplete $signature packet")
        return output.toByteArray()
    }

    private fun packetPages(packet: ByteArray, serial: Long, firstSequence: Long): List<ByteArray> {
        requirePage(packet.isNotEmpty() && packet.size <= MAX_TAG_PACKET_BYTES, "Invalid OpusTags size")
        val lacing = ArrayList<Int>(packet.size / 255 + 1)
        var remaining = packet.size
        while (remaining >= 255) {
            lacing += 255
            remaining -= 255
        }
        lacing += remaining

        val pages = ArrayList<ByteArray>((lacing.size + MAX_LACING_VALUES - 1) / MAX_LACING_VALUES)
        var laceOffset = 0
        var bodyOffset = 0
        while (laceOffset < lacing.size) {
            val laceEnd = minOf(laceOffset + MAX_LACING_VALUES, lacing.size)
            val pageLacing = lacing.subList(laceOffset, laceEnd)
            val bodySize = pageLacing.sum()
            val flags = if (laceOffset == 0) 0 else OGG_CONTINUED
            pages += makePage(
                flags = flags,
                granule = 0L,
                serial = serial,
                sequence = (firstSequence + pages.size) and SEQUENCE_MASK,
                lacing = pageLacing,
                body = packet.copyOfRange(bodyOffset, bodyOffset + bodySize)
            )
            bodyOffset += bodySize
            laceOffset = laceEnd
        }
        return pages
    }

    private fun makePage(
        flags: Int,
        granule: Long,
        serial: Long,
        sequence: Long,
        lacing: List<Int>,
        body: ByteArray
    ): ByteArray {
        val header = ByteArray(OGG_HEADER_BYTES + lacing.size)
        "OggS".toByteArray(Charsets.US_ASCII).copyInto(header)
        header[4] = 0
        header[5] = flags.toByte()
        ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).putLong(6, granule)
        writeUInt32Le(header, 14, serial)
        writeUInt32Le(header, 18, sequence)
        header[26] = lacing.size.toByte()
        lacing.forEachIndexed { index, value -> header[OGG_HEADER_BYTES + index] = value.toByte() }
        val page = header + body
        writeUInt32Le(page, 22, oggCrc(page).toLong() and SEQUENCE_MASK)
        return page
    }

    private fun uint32Le(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xff) or
            ((bytes[offset + 1].toLong() and 0xff) shl 8) or
            ((bytes[offset + 2].toLong() and 0xff) shl 16) or
            ((bytes[offset + 3].toLong() and 0xff) shl 24)

    private fun writeUInt32Le(bytes: ByteArray, offset: Int, value: Long) {
        repeat(4) { index -> bytes[offset + index] = (value ushr (index * 8)).toByte() }
    }

    private fun ByteArray.startsWith(value: String): Boolean {
        val signature = value.toByteArray(Charsets.US_ASCII)
        return size >= signature.size && signature.indices.all { this[it] == signature[it] }
    }

    private fun requirePage(condition: Boolean, message: String) {
        if (!condition) throw IOException(message)
    }

    private fun oggCrc(bytes: ByteArray): Int {
        var crc = 0
        bytes.forEach { byte ->
            val index = ((crc ushr 24) xor (byte.toInt() and 0xff)) and 0xff
            crc = (crc shl 8) xor crcTable[index]
        }
        return crc
    }

    private val crcTable: IntArray by lazy {
        IntArray(256) { value ->
            var remainder = value shl 24
            repeat(8) {
                remainder = if (remainder < 0) (remainder shl 1) xor OGG_CRC_POLYNOMIAL
                else remainder shl 1
            }
            remainder
        }
    }

    private object FileOutputStreamSync {
        fun sync(file: File) {
            FileOutputStream(file, true).use { it.fd.sync() }
        }
    }

    private const val MAX_TAG_PACKET_BYTES = 16 * 1_048_576
}
