package com.flactify.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.documentfile.provider.DocumentFile
import com.flactify.audio.AudioRouteMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/** Compact embedded cover art before retaining it in every in-memory TrackData instance. */
internal fun compactArtworkBytes(data: ByteArray?): ByteArray? {
    if (data == null) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sampleSize = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize > 256) sampleSize *= 2
    val bitmap = BitmapFactory.decodeByteArray(
        data, 0, data.size,
        BitmapFactory.Options().apply { inSampleSize = sampleSize }
    ) ?: return null
    return try {
        java.io.ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 82, output)
            output.toByteArray()
        }
    } finally {
        bitmap.recycle()
    }
}

/**
 * What a file declares about its audio format. Nullable fields mean "not reliably known" and
 * are rendered as absent, never as a default.
 */
data class AudioSpec(
    val sampleRateHz: Int? = null,
    val bitDepthLabel: String? = null
)

/** Small access-ordered bitmap cache that evicts by decoded allocation size. */
internal class BoundedBitmapCache<K : Any>(private val maxBytes: Long) {
    private val entries = LinkedHashMap<K, Bitmap>(16, 0.75f, true)
    private var retainedBytes = 0L

    @Synchronized
    operator fun get(key: K): Bitmap? = entries[key]

    @Synchronized
    operator fun set(key: K, bitmap: Bitmap) {
        remove(key)
        val size = bitmap.allocationByteCount.toLong()
        if (size > maxBytes) return
        entries[key] = bitmap
        retainedBytes += size
        val iterator = entries.entries.iterator()
        while (retainedBytes > maxBytes && iterator.hasNext()) {
            val eldest = iterator.next()
            retainedBytes -= eldest.value.allocationByteCount.toLong()
            iterator.remove()
        }
    }

    @Synchronized
    fun remove(key: K): Bitmap? = entries.remove(key)?.also { bitmap ->
        retainedBytes -= bitmap.allocationByteCount.toLong()
    }

    @Synchronized
    fun containsKey(key: K): Boolean = entries.containsKey(key)

    @Synchronized
    fun retainedBytes(): Long = retainedBytes
}

class TrackMetadataExtractor(private val context: Context) {

    val lyricsCache = ConcurrentHashMap<Uri, List<Pair<Long, String>>>()
    internal val albumArtCache = BoundedBitmapCache<Uri>(16L * 1024L * 1024L)
    val audioSpecCache = ConcurrentHashMap<Uri, AudioSpec>()
    val fileExtensionCache = ConcurrentHashMap<Uri, String>()

    // The theme colour is derived from the album art by scaling it to 1x1, which is
    // comparatively expensive. It is immutable for a given artwork, so it is computed once
    // during extraction and read from here afterwards rather than re-scaling on every
    // cached metadata lookup.
    private val themeColorCache = ConcurrentHashMap<Uri, Int>()

    data class TrackMetadata(
        val albumArt: Bitmap?,
        val themeColor: Int,
        val lyrics: List<Pair<Long, String>>,
        val audioInfo: String,
        val bluetoothCodecInfo: String
    )

    suspend fun extractMetadata(uri: Uri): TrackMetadata {
        val retriever = MediaMetadataRetriever()
        try {
            // Unknown stays unknown. The previous defaults of 44100 / "16bit" fabricated a
            // spec for any file the extractor could not read, which is how a mislabelled
            // badge could be produced at all.
            var sampleRate: Int? = null
            var bitDepth: String? = null

            val ext = fileExtensionCache[uri] ?: run {
                val detectedExt = detectExtension(uri)
                fileExtensionCache[uri] = detectedExt
                detectedExt
            }

            var albumArt: Bitmap? = null
            var themeColor = android.graphics.Color.parseColor("#121212")

            context.contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
                retriever.setDataSource(fd.fileDescriptor)

                val art = retriever.embeddedPicture?.let(::decodeArtworkThumbnail)
                // Do not cache misses; absence is represented by the key simply not being present.
                if (art != null) {
                    albumArtCache[uri] = art
                } else {
                    albumArtCache.remove(uri)
                }
                albumArt = art

                art?.let { bitmap ->
                    themeColor = themeColorOf(bitmap)
                    themeColorCache[uri] = themeColor
                }

                try {
                    val srStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)
                    if (!srStr.isNullOrEmpty()) sampleRate = srStr.toIntOrNull()

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        val bitsStr = retriever.extractMetadata(39)
                        if (!bitsStr.isNullOrEmpty()) bitDepth = "${bitsStr}bit"
                    }
                } catch (e: Exception) {
                }
            }

            val parsedLyrics = extractLyrics(uri, ext, retriever)

            val formatName = when (ext) {
                "flac" -> "FLAC"
                "wav" -> "WAV"
                "m4a" -> "ALAC/AAC"
                else -> "MP3"
            }

            // Bit depth is only ever what the platform reported. The previous fallback
            // derived it from sample rate (`> 48 kHz -> 24 bit`), which is simply wrong:
            // 44.1 kHz/24-bit and 96 kHz/16-bit are both legal. Omit it when unknown.
            audioSpecCache[uri] = AudioSpec(sampleRateHz = sampleRate, bitDepthLabel = bitDepth)
            lyricsCache[uri] = parsedLyrics

            val ratePart = sampleRate?.let { "${it / 1000.0}kHz" }
            val depthPart = bitDepth?.takeIf { it.isNotEmpty() && formatName != "MP3" }
            val audioInfo = listOfNotNull(formatName, ratePart, depthPart).joinToString(" | ")

            val routeLabel = getOutputRouteLabel()

            return TrackMetadata(
                albumArt = albumArt,
                themeColor = themeColor,
                lyrics = parsedLyrics,
                audioInfo = audioInfo,
                bluetoothCodecInfo = routeLabel
            )
        } catch (ex: Exception) {
            ex.printStackTrace()
            return TrackMetadata(null, android.graphics.Color.parseColor("#121212"), emptyList(), "", "")
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
            }
        }
    }

    private suspend fun extractLyrics(
        uri: Uri,
        ext: String,
        retriever: MediaMetadataRetriever
    ): List<Pair<Long, String>> {
        var parsedLyrics = emptyList<Pair<Long, String>>()
        var lyricsLoaded = false

        if (ext == "flac") {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val rawLyrics = extractLyricsFromFlac(inputStream)
                if (!rawLyrics.isNullOrEmpty()) {
                    parsedLyrics = parseLyrics(rawLyrics)
                    lyricsLoaded = true
                }
            }
        }

        if (!lyricsLoaded) {
            val lyricsRetriever = MediaMetadataRetriever()
            try {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
                    lyricsRetriever.setDataSource(fd.fileDescriptor)
                    val rawLyrics = try {
                        lyricsRetriever.extractMetadata(1000)
                            ?: lyricsRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE + 14)
                    } catch (ex: Exception) {
                        null
                    }
                    parsedLyrics = parseLyrics(rawLyrics)
                }
            } finally {
                try {
                    lyricsRetriever.release()
                } catch (_: Exception) {
                }
            }
        }

        return parsedLyrics
    }

    fun getCachedMetadata(uri: Uri): TrackMetadata? {
        if (!lyricsCache.containsKey(uri) && !albumArtCache.containsKey(uri) && !audioSpecCache.containsKey(uri)) {
            return null
        }

        val cachedArt = albumArtCache[uri]
        val themeColor = themeColorCache[uri] ?: DEFAULT_THEME_COLOR

        val spec = audioSpecCache[uri] ?: AudioSpec()
        val ext = fileExtensionCache[uri] ?: run {
            val detectedExt = detectExtension(uri)
            fileExtensionCache[uri] = detectedExt
            detectedExt
        }
        val formatName =
            if (ext == "flac") "FLAC" else if (ext == "wav") "WAV" else if (ext == "m4a") "ALAC/AAC" else "MP3"
        val ratePart = spec.sampleRateHz?.let { "${it / 1000.0}kHz" }
        val depthPart = spec.bitDepthLabel?.takeIf { it.isNotEmpty() && formatName != "MP3" }
        val audioInfo = listOfNotNull(formatName, ratePart, depthPart).joinToString(" | ")

        return TrackMetadata(
            albumArt = cachedArt,
            themeColor = themeColor,
            lyrics = lyricsCache[uri] ?: emptyList(),
            audioInfo = audioInfo,
            bluetoothCodecInfo = getOutputRouteLabel()
        )
    }

    /**
     * Human-readable name of the current output route.
     *
     * Previously this returned the literal string `"ldac"` whenever a `TYPE_BLUETOOTH_A2DP`
     * device was present. That was wrong twice over: A2DP is a transport and says nothing
     * about the negotiated codec, and Android exposes no public API that reports whether LDAC,
     * aptX, AAC or SBC was chosen. The UI then compounded it by labelling the result
     * "LDAC Lossless", which is a stronger claim still.
     *
     * Route labelling now lives in [com.flactify.audio.AudioRouteMonitor]; this is a thin
     * delegate kept so existing call sites and tests do not change shape.
     */
    internal fun getBluetoothCodecInfo(): String = getOutputRouteLabel()

    private fun getOutputRouteLabel(): String {
        return try {
            AudioRouteMonitor(context).currentRoute()?.label ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    internal fun detectExtension(uri: Uri): String {
        val name = DocumentFile.fromSingleUri(context, uri)?.name?.lowercase()
            ?: uri.lastPathSegment?.lowercase()
            ?: ""
        return when {
            name.endsWith(".flac") -> "flac"
            name.endsWith(".wav") -> "wav"
            name.endsWith(".m4a") -> "m4a"
            else -> "mp3"
        }
    }

    /**
     * Drops everything cached for [uri].
     *
     * Required after a tag edit: the album art, lyrics and audio spec held here describe the
     * file as it was *before* the write, and a subsequent cache hit would show the old values.
     */
    fun invalidate(uri: Uri) {
        albumArtCache.remove(uri)
        lyricsCache.remove(uri)
        audioSpecCache.remove(uri)
        themeColorCache.remove(uri)
    }

    /**
     * Samples a single pixel from [bitmap] to use as the UI accent colour.
     *
     * Only called once per track, from [extractMetadata]; the result is memoised.
     */
    private fun themeColorOf(bitmap: Bitmap): Int = try {
        val scaled = Bitmap.createScaledBitmap(bitmap, 1, 1, false)
        val color = scaled.getPixel(0, 0)
        if (scaled !== bitmap) scaled.recycle()
        color
    } catch (e: Exception) {
        DEFAULT_THEME_COLOR
    }

    private fun decodeArtworkThumbnail(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val sampleSize = generateSequence(1) { it * 2 }
            .takeWhile { maxOf(bounds.outWidth, bounds.outHeight) / it > 1024 }
            .lastOrNull() ?: 1
        return BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sampleSize }
        )
    }

    private companion object {
        val DEFAULT_THEME_COLOR: Int = android.graphics.Color.parseColor("#121212")
    }

    private fun extractLyricsFromFlac(inputStream: InputStream): String? {
        try {
            val header = ByteArray(4)
            if (inputStream.read(header) != 4) return null

            if (String(header) != "fLaC") {
                if (header[0] == 'I'.code.toByte() && header[1] == 'D'.code.toByte() && header[2] == '3'.code.toByte()) {
                    val skipBytes = ByteArray(2)
                    if (inputStream.read(skipBytes) != 2) return null
                    val id3SizeBytes = ByteArray(4)
                    if (inputStream.read(id3SizeBytes) != 4) return null
                    val id3Size = ((id3SizeBytes[0].toInt() and 0x7F) shl 21) or
                            ((id3SizeBytes[1].toInt() and 0x7F) shl 14) or
                            ((id3SizeBytes[2].toInt() and 0x7F) shl 7) or
                            (id3SizeBytes[3].toInt() and 0x7F)
                    val skipTotal = id3Size.toLong()
                    var skipped = 0L
                    while (skipped < skipTotal) {
                        val result = inputStream.skip(skipTotal - skipped)
                        if (result <= 0) break
                        skipped += result
                    }
                    if (inputStream.read(header) != 4 || String(header) != "fLaC") return null
                } else {
                    return null
                }
            }

            var isLastBlock = false
            while (!isLastBlock) {
                val blockHeader = ByteArray(4)
                if (inputStream.read(blockHeader) != 4) break

                val headerInt = ByteBuffer.wrap(blockHeader).order(ByteOrder.BIG_ENDIAN).int
                isLastBlock = (headerInt shr 31 and 0x01) == 1
                val blockType = headerInt shr 24 and 0x7F
                val blockLength = headerInt and 0x00FFFFFF

                if (blockType == 4) {
                    val commentData = ByteArray(blockLength)
                    var readBytes = 0
                    while (readBytes < blockLength) {
                        val result = inputStream.read(commentData, readBytes, blockLength - readBytes)
                        if (result == -1) break
                        readBytes += result
                    }

                    val buffer = ByteBuffer.wrap(commentData).order(ByteOrder.LITTLE_ENDIAN)

                    val vendorLength = buffer.int
                    if (vendorLength < 0 || vendorLength > buffer.remaining()) return null
                    buffer.position(buffer.position() + vendorLength)

                    val userCommentListLength = buffer.int
                    for (i in 0 until userCommentListLength) {
                        if (buffer.remaining() < 4) break
                        val commentLength = buffer.int
                        if (commentLength < 0 || commentLength > buffer.remaining()) break

                        val commentBytes = ByteArray(commentLength)
                        buffer.get(commentBytes)
                        val comment = String(commentBytes, Charsets.UTF_8)

                        if (comment.startsWith("LYRICS=", ignoreCase = true) ||
                            comment.startsWith("UNSUCHEDLYRICS=", ignoreCase = true) ||
                            comment.startsWith("SUBTITLE=", ignoreCase = true)
                        ) {
                            return comment.substringAfter("=")
                        }
                    }
                    return null
                } else {
                    var skipped = 0L
                    while (skipped < blockLength) {
                        val result = inputStream.skip(blockLength.toLong() - skipped)
                        if (result <= 0) break
                        skipped += result
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    private fun parseLyrics(raw: String?): List<Pair<Long, String>> {
        if (raw == null) return emptyList()
        val timed = mutableListOf<Pair<Long, String>>()
        val untimed = mutableListOf<Pair<Long, String>>()
        val pattern = Pattern.compile("\\[(\\d+):(\\d+)[\\.:](\\d+)\\](.*)")
        raw.lines().forEach { line ->
            val matcher = pattern.matcher(line)
            if (matcher.find()) {
                val m = matcher.group(1)?.toLong() ?: 0L
                val s = matcher.group(2)?.toLong() ?: 0L
                val ms = matcher.group(3)?.toLong() ?: 0L
                val text = matcher.group(4)?.trim().orEmpty()
                if (text.isNotEmpty()) timed.add(((m * 60000) + (s * 1000) + ms) to text)
            } else {
                val text = line.trim()
                if (text.isNotEmpty() && !text.startsWith("[")) untimed.add(-1L to text)
            }
        }
        // Keep ordinary (non-LRC) lyric tags instead of silently dropping them.
        // Untimed lines are placed after timed lines and remain available in the
        // expanded lyrics view.
        return timed.sortedBy { it.first } + untimed
    }
}
