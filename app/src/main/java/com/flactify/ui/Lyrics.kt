package com.flactify.ui

import android.net.Uri
import android.provider.DocumentsContract
import com.flactify.viewmodel.LrcParser
import java.io.InputStream
import java.nio.charset.StandardCharsets

/** Cache identity includes display metadata so a rewritten track can trigger a fresh lookup. */
internal data class ExternalLyricsCacheKey(
    val trackKey: String,
    val title: String?,
    val artist: String?,
    val album: String?
)

/** Small process-local cache; misses are cached too, so recomposition never rescans a sibling. */
internal object ExternalLyricsCache {
    private const val MAX_ENTRIES = 128
    internal const val MAX_CACHED_TEXT_BYTES = 8 * 1024 * 1024
    private data class Entry(
        val lyrics: List<Pair<Long, String>>?,
        val estimatedBytes: Int
    )

    private var cachedBytes = 0
    private val entries = LinkedHashMap<ExternalLyricsCacheKey, Entry>(MAX_ENTRIES, 0.75f, true)

    @Synchronized
    fun getOrLoad(
        key: ExternalLyricsCacheKey,
        loader: () -> List<Pair<Long, String>>?
    ): List<Pair<Long, String>>? {
        entries[key]?.let { return it.lyrics }
        if (entries.containsKey(key)) return null
        val value = loader()
        // UTF-16 character storage is conservatively estimated at two bytes per code unit;
        // the separate byte ceiling bounds retained lyric text across many large LRC files.
        val estimatedBytes = value?.fold(0L) { total, (_, line) ->
            (total + 8L + line.length.toLong() * 2L).coerceAtMost(Int.MAX_VALUE.toLong())
        }?.toInt() ?: 1
        if (estimatedBytes > MAX_CACHED_TEXT_BYTES) return value
        while (entries.size >= MAX_ENTRIES || cachedBytes.toLong() + estimatedBytes > MAX_CACHED_TEXT_BYTES) {
            val eldest = entries.entries.iterator().let { iterator ->
                if (iterator.hasNext()) iterator.next() else null
            } ?: break
            cachedBytes -= eldest.value.estimatedBytes
            entries.remove(eldest.key)
        }
        entries[key] = Entry(value, estimatedBytes)
        cachedBytes += estimatedBytes
        return value
    }

    @Synchronized
    fun clearForTests() {
        entries.clear()
        cachedBytes = 0
    }
}

/**
 * Returns the sibling LRC URI only when [trackUri] is itself a tree-backed DocumentsContract URI.
 * A plain provider document URI does not reliably expose its selected SAF parent, so returning a
 * guessed path there could escape the user's folder grant; such URIs intentionally return null.
 */
internal fun externalLyricsUriFor(trackUri: Uri): Uri? =
    externalLyricsUriFor(trackUri, trackUri.takeIf(DocumentsContract::isTreeUri))

internal fun externalLyricsUriFor(trackUri: Uri, selectedTreeUri: Uri?): Uri? = runCatching {
    val treeUri = selectedTreeUri?.takeIf(DocumentsContract::isTreeUri) ?: return@runCatching null
    val treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
    val documentId = DocumentsContract.getDocumentId(trackUri)
    if (treeDocumentId.isBlank() || documentId.isBlank() || !documentId.startsWith("$treeDocumentId/")) {
        return@runCatching null
    }
    val name = Uri.decode(documentId.substringAfterLast('/'))
    if (name.isBlank()) return@runCatching null
    val baseName = name.substringBeforeLast('.', name)
    val parent = documentId.substringBeforeLast('/', "")
    if (parent.isBlank()) return@runCatching null
    DocumentsContract.buildDocumentUriUsingTree(
        treeUri,
        "$parent/$baseName.lrc"
    )
}.getOrNull()

/** Reads at most 1 MiB of a sibling file and parses it as line-level LRC. */
internal fun readExternalLyrics(
    input: InputStream,
    maxBytes: Int = MAX_EXTERNAL_LYRICS_BYTES
): List<Pair<Long, String>>? {
    if (maxBytes <= 0) return null
    val bytes = ByteArray(DEFAULT_READ_BUFFER_BYTES)
    var total = 0
    val output = java.io.ByteArrayOutputStream()
    return try {
        while (true) {
            val count = input.read(bytes, 0, minOf(bytes.size, maxBytes - total))
            if (count < 0) break
            if (count == 0) {
                val single = input.read()
                if (single < 0) break
                if (total >= maxBytes) return null
                output.write(single)
                total++
            } else {
                total += count
                if (total > maxBytes) return null
                output.write(bytes, 0, count)
            }
            if (total == maxBytes) {
                // Probe one byte so an exactly-limit file is accepted while a larger file is rejected.
                if (input.read() >= 0) return null
                break
            }
        }
        LrcParser.parse(output.toString(StandardCharsets.UTF_8.name())).takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }
}

internal fun applyLyricsOffset(
    lyrics: List<Pair<Long, String>>,
    offsetMs: Long
): List<Pair<Long, String>> = lyrics.map { (timestamp, text) ->
    if (timestamp < 0L) timestamp to text
    else safeAdd(timestamp, offsetMs) to text
}

internal fun activeLyricsIndex(lyrics: List<Pair<Long, String>>, positionMs: Long): Int =
    lyrics.indices.lastOrNull { index ->
        val timestamp = lyrics[index].first
        timestamp >= 0L && timestamp <= positionMs
    } ?: -1

private fun safeAdd(first: Long, second: Long): Long = try {
    Math.addExact(first, second)
} catch (_: ArithmeticException) {
    if (second < 0L) Long.MIN_VALUE else Long.MAX_VALUE
}

private const val DEFAULT_READ_BUFFER_BYTES = 8 * 1024
private const val MAX_EXTERNAL_LYRICS_BYTES = 1 * 1024 * 1024
