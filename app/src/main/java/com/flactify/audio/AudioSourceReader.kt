package com.flactify.audio

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.DataInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads what a file declares about itself, and only that.
 *
 * The previous implementation derived bit depth from sample rate
 * (`> 48 kHz -> 24 bit`, otherwise `16 bit`). That is not sound: 44.1 kHz/24-bit and
 * 96 kHz/16-bit are both legal, so the old rule produced confidently wrong badges. Bit depth
 * is now read from a real source or reported as unknown.
 */
class AudioSourceReader(private val context: Context) {

    /**
     * Reads source information without decoding audio.
     *
     * [container] is a hint, never a precondition. Callers originally supplied it from
     * `fileExtensionCache`, which the library scan populates asynchronously, so on the very
     * first read it could still be null and the FLAC header would be skipped. Detection is
     * therefore done from the file's own magic bytes; the hint only labels the result.
     *
     * Blocking; call from [kotlinx.coroutines.Dispatchers.IO].
     */
    fun read(uri: Uri, containerHint: String?): SourceAudioInfo {
        var sampleRateHz: Int? = null
        var bitDepth: Int? = null
        var channelCount: Int? = null
        var bitrateKbps: Int? = null

        val retriever = MediaMetadataRetriever()
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
                retriever.setDataSource(fd.fileDescriptor)
                sampleRateHz = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull()
                bitrateKbps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                    ?.let { it.toIntOrNull()?.div(1000) }
                // Public constant; not every extractor populates it, and some throw on it.
                try {
                    bitDepth =
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)?.toIntOrNull()
                } catch (e: Exception) {
                    bitDepth = null
                }
            }
        } catch (e: Exception) {
            // Fall through to the container-level reader below.
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
            }
        }

        // FLAC carries an authoritative bit depth in STREAMINFO that the platform extractor
        // does not expose, so parse the header directly. The magic check is self-validating:
        // if the file is not FLAC, readFlacStreamInfo returns null.
        val streamInfo = readFlacStreamInfo(uri)
        if (streamInfo != null) {
            if (bitDepth == null) bitDepth = streamInfo.bitDepth
            if (sampleRateHz == null) sampleRateHz = streamInfo.sampleRateHz
            if (channelCount == null) channelCount = streamInfo.channelCount
        }

        return SourceAudioInfo(
            container = when {
                streamInfo != null -> CONTAINER_FLAC
                !containerHint.isNullOrBlank() -> containerHint
                else -> null
            },
            sampleRateHz = sampleRateHz,
            bitDepth = bitDepth,
            channelCount = channelCount,
            bitrateKbps = bitrateKbps
        )
    }

    private data class FlacStreamInfo(
        val sampleRateHz: Int,
        val channelCount: Int,
        val bitDepth: Int
    )

    /**
     * Parses the FLAC STREAMINFO metadata block, which is always the first block in a FLAC
     * stream. Bounded to 64 KiB so a malformed file cannot cause a large read.
     */
    private fun readFlacStreamInfo(uri: Uri): FlacStreamInfo? = try {
        context.contentResolver.openInputStream(uri)?.use { raw ->
            DataInputStream(raw.buffered()).use { input -> readFlacStreamInfoOrNull(input) }
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Parses the STREAMINFO metadata block per RFC 9639 section 8.2.
     *
     * The 34-byte payload layout is fixed by the spec, in this order:
     *
     * ```
     *   0..1   minimum block size              u(16)
     *   2..3   maximum block size              u(16)
     *   4..6   minimum frame size              u(24)
     *   7..9   maximum frame size              u(24)
     *  10..17  sample rate u(20) | channels-1 u(3) | bps-1 u(5) | total samples u(36)
     *  18..33  MD5 of the unencoded audio      u(128)
     * ```
     *
     * So the packed 64-bit field starts at byte offset **10** and the MD5 comes **last**.
     * Sample rate is not byte-aligned: it spans the low 4 bits of byte 10 through byte 12.
     *
     * A STREAMINFO block is only required to be the *first* block; RFC 9639's own reference
     * file sets the last-metadata-block flag on it, so a STREAMINFO-only stream is legal and
     * must not be rejected on that basis.
     */
    private fun readFlacStreamInfoOrNull(input: InputStream): FlacStreamInfo? {
        return try {
            val magic = ByteArray(4)
            if (!readFully(input, magic)) return null
            if (String(magic, Charsets.US_ASCII) != "fLaC") return null

            val blockHeader = ByteArray(4)
            if (!readFully(input, blockHeader)) return null
            val header = ByteBuffer.wrap(blockHeader).order(ByteOrder.BIG_ENDIAN).int
            val blockType = header shr 24 and 0x7F
            val blockLength = header and 0x00FFFFFF

            // RFC 9639 section 8.2 fixes the STREAMINFO payload at exactly 34 bytes.
            if (blockType != STREAMINFO_BLOCK_TYPE || blockLength != STREAMINFO_PAYLOAD_BYTES) return null

            val payload = ByteArray(STREAMINFO_PAYLOAD_BYTES)
            if (!readFully(input, payload)) return null

            // Packed 64-bit field occupies payload bytes 10..17. Reading it as one big-endian
            // long keeps the byte offset explicit and avoids hand-rolled shift arithmetic.
            val packed = ByteBuffer.wrap(payload, 10, 8).order(ByteOrder.BIG_ENDIAN).long

            val sampleRateHz = ((packed ushr 44) and SAMPLE_RATE_MASK).toInt()
            val channelCount = (((packed ushr 41) and CHANNEL_COUNT_MASK) + 1L).toInt()
            val bitDepth = (((packed ushr 36) and BIT_DEPTH_MASK) + 1L).toInt()

            // Section 8.2: sample rate MUST NOT be 0 when the file contains audio, and FLAC
            // supports 4..32 bits per sample. Out-of-range values mean this is not a real
            // STREAMINFO header, so report nothing rather than a fabricated value.
            if (sampleRateHz <= 0 || bitDepth !in MIN_BIT_DEPTH..MAX_BIT_DEPTH) return null

            FlacStreamInfo(sampleRateHz, channelCount, bitDepth)
        } catch (e: Exception) {
            null
        }
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Boolean {
        var read = 0
        while (read < buffer.size) {
            val count = try {
                input.read(buffer, read, buffer.size - read)
            } catch (e: Exception) {
                return false
            }
            if (count <= 0) return false
            read += count
        }
        return true
    }

    private companion object {
        const val STREAMINFO_BLOCK_TYPE = 0

        const val CONTAINER_FLAC = "FLAC"

        /** RFC 9639 section 8.2: the STREAMINFO data payload is exactly 34 bytes. */
        const val STREAMINFO_PAYLOAD_BYTES = 34

        const val SAMPLE_RATE_MASK = 0xFFFFFL      // u(20)
        const val CHANNEL_COUNT_MASK = 0x7L        // u(3)
        const val BIT_DEPTH_MASK = 0x1FL           // u(5)

        /** RFC 9639 section 8.2: "FLAC supports from 4 to 32 bits per sample." */
        const val MIN_BIT_DEPTH = 4
        const val MAX_BIT_DEPTH = 32
    }
}
