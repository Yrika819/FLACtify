package com.flactify.viewmodel

import java.io.InputStream
import java.io.ByteArrayOutputStream

/** Identifies common audio containers from a short prefix; filename suffixes are hints only. */
internal object AudioContainerDetector {
    fun detect(prefix: ByteArray): String? {
        if (prefix.startsWithAscii("fLaC")) return "flac"
        if (prefix.startsWithAscii("RIFF", 0) && prefix.startsWithAscii("WAVE", 8)) return "wav"
        if (prefix.startsWithAscii("OggS")) {
            return if (containsAscii(prefix, "OpusHead")) "opus" else "ogg"
        }
        if (prefix.size >= 8 && prefix.startsWithAscii("ftyp", 4)) return "m4a"
        if (prefix.startsWithAscii("ID3") || hasMpegFrameSync(prefix)) return "mp3"

        // A suffix alone cannot establish that a file is audio; this also keeps corrupt
        // and unrelated files from entering the library during broad folder scans.
        return null
    }

    fun sniff(input: InputStream): String? {
        val prefix = ByteArrayOutputStream(PREFIX_BYTES)
        val buffer = ByteArray(PREFIX_BYTES)
        while (prefix.size() < PREFIX_BYTES) {
            val count = input.read(buffer, 0, minOf(buffer.size, PREFIX_BYTES - prefix.size()))
            if (count <= 0) break
            prefix.write(buffer, 0, count)
        }
        return detect(prefix.toByteArray())
    }

    private fun hasMpegFrameSync(bytes: ByteArray): Boolean =
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && (bytes[1].toInt() and 0xE0) == 0xE0

    private fun containsAscii(bytes: ByteArray, value: String): Boolean {
        val needle = value.toByteArray(Charsets.US_ASCII)
        return (0..bytes.size - needle.size).any { offset ->
            needle.indices.all { bytes[offset + it] == needle[it] }
        }
    }

    private fun ByteArray.startsWithAscii(value: String, offset: Int = 0): Boolean {
        val needle = value.toByteArray(Charsets.US_ASCII)
        return offset >= 0 && size >= offset + needle.size &&
            needle.indices.all { this[offset + it] == needle[it] }
    }

    private const val PREFIX_BYTES = 256
}
