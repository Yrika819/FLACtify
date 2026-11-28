package com.flactify.ui

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import com.flactify.viewmodel.LrcParser
import kotlinx.coroutines.CancellationException
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.charset.CodingErrorAction
import java.nio.charset.CharacterCodingException

/** Cache identity includes tree, resolved document fingerprint, and successful full-scan revision. */
internal data class ExternalLyricsCacheKey(
    val trackKey: String,
    val treeKey: String,
    val libraryGeneration: Long,
    val documentUri: String,
    val lastModified: Long,
    val sizeBytes: Long
)

internal enum class ExternalLyricsStatus { FOUND, NOT_FOUND, UNAVAILABLE, INVALID, ERROR }

internal data class ExternalLyricsLoadResult(
    val status: ExternalLyricsStatus,
    val lyrics: List<Pair<Long, String>> = emptyList()
)

internal fun lyricsForDisplay(
    embedded: List<Pair<Long, String>>,
    external: ExternalLyricsLoadResult,
    preferExternal: Boolean
): List<Pair<Long, String>> = when {
    preferExternal && external.status == ExternalLyricsStatus.FOUND -> external.lyrics
    embedded.isNotEmpty() -> embedded
    external.status == ExternalLyricsStatus.FOUND -> external.lyrics
    else -> emptyList()
}

private data class ExternalLyricsDocument(
    val uri: Uri,
    val lastModified: Long,
    val sizeBytes: Long
)

/** Bounded process-local cache; provider I/O is always performed outside its monitor. */
internal object ExternalLyricsCache {
    private const val MAX_ENTRIES = 128
    internal const val MAX_CACHED_TEXT_BYTES = 8 * 1024 * 1024
    private const val FOUND_TTL_MS = 60_000L
    private const val INVALID_TTL_MS = 5_000L
    private data class Entry(
        val result: ExternalLyricsLoadResult,
        val estimatedBytes: Int,
        val expiresAtMs: Long
    )

    private var cachedBytes = 0
    private val entries = LinkedHashMap<ExternalLyricsCacheKey, Entry>(MAX_ENTRIES, 0.75f, true)

    fun getOrLoad(
        key: ExternalLyricsCacheKey,
        nowMs: Long = System.nanoTime() / 1_000_000L,
        loader: () -> ExternalLyricsLoadResult
    ): ExternalLyricsLoadResult {
        synchronized(this) {
            entries[key]?.let { entry ->
                if (nowMs < entry.expiresAtMs) return entry.result
                cachedBytes -= entry.estimatedBytes
                entries.remove(key)
            }
        }
        val value = loader()
        val ttlMs = when (value.status) {
            ExternalLyricsStatus.FOUND -> FOUND_TTL_MS
            ExternalLyricsStatus.INVALID -> INVALID_TTL_MS
            ExternalLyricsStatus.NOT_FOUND, ExternalLyricsStatus.UNAVAILABLE, ExternalLyricsStatus.ERROR -> 0L
        }
        if (ttlMs == 0L) return value
        // UTF-16 character storage is conservatively estimated at two bytes per code unit;
        // the separate byte ceiling bounds retained lyric text across many large LRC files.
        val estimatedBytes = value.lyrics.fold(0L) { total, (_, line) ->
            (total + 8L + line.length.toLong() * 2L).coerceAtMost(Int.MAX_VALUE.toLong())
        }.toInt().coerceAtLeast(1)
        if (estimatedBytes > MAX_CACHED_TEXT_BYTES) return value
        synchronized(this) {
            // Another caller may have completed the same load while this caller was in provider I/O.
            entries.remove(key)?.let { cachedBytes -= it.estimatedBytes }
            while (entries.size >= MAX_ENTRIES || cachedBytes.toLong() + estimatedBytes > MAX_CACHED_TEXT_BYTES) {
                val eldest = entries.entries.iterator().let { iterator ->
                    if (iterator.hasNext()) iterator.next() else null
                } ?: break
                cachedBytes -= eldest.value.estimatedBytes
                entries.remove(eldest.key)
            }
            entries[key] = Entry(value, estimatedBytes, nowMs + ttlMs)
            cachedBytes += estimatedBytes
        }
        return value
    }

    @Synchronized
    fun clearForTests() {
        entries.clear()
        cachedBytes = 0
    }
}

/**
 * Resolves an existing sibling by asking the selected provider for the track's document path and
 * listing only that parent directory. Document IDs remain opaque: no path is inferred from them.
 */
internal fun loadExternalLyrics(
    resolver: ContentResolver,
    trackKey: String,
    trackUri: Uri,
    selectedTreeUri: Uri?,
    libraryGeneration: Long = 0L
): ExternalLyricsLoadResult {
    val treeUri = selectedTreeUri?.takeIf { DocumentsContract.isTreeUri(it) }
        ?: return ExternalLyricsLoadResult(ExternalLyricsStatus.UNAVAILABLE)
    if (treeUri.authority.isNullOrBlank() || treeUri.authority != trackUri.authority) {
        return ExternalLyricsLoadResult(ExternalLyricsStatus.UNAVAILABLE)
    }
    val treeId = try {
        DocumentsContract.getTreeDocumentId(treeUri)
    } catch (_: IllegalArgumentException) {
        return ExternalLyricsLoadResult(ExternalLyricsStatus.UNAVAILABLE)
    }
    val trackId = try {
        DocumentsContract.getDocumentId(trackUri)
    } catch (_: IllegalArgumentException) {
        return ExternalLyricsLoadResult(ExternalLyricsStatus.UNAVAILABLE)
    }
    if (treeId.isBlank() || trackId.isBlank()) {
        return ExternalLyricsLoadResult(ExternalLyricsStatus.UNAVAILABLE)
    }

    val path = try {
        DocumentsContract.findDocumentPath(resolver, trackUri)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: SecurityException) {
        return ExternalLyricsLoadResult(ExternalLyricsStatus.UNAVAILABLE)
    } catch (_: FileNotFoundException) {
        return ExternalLyricsLoadResult(ExternalLyricsStatus.UNAVAILABLE)
    } catch (_: IOException) {
        return ExternalLyricsLoadResult(ExternalLyricsStatus.ERROR)
    } catch (_: UnsupportedOperationException) {
        return ExternalLyricsLoadResult(ExternalLyricsStatus.UNAVAILABLE)
    } catch (_: IllegalArgumentException) {
        return ExternalLyricsLoadResult(ExternalLyricsStatus.ERROR)
    } catch (_: RuntimeException) {
        return ExternalLyricsLoadResult(ExternalLyricsStatus.ERROR)
    } ?: return ExternalLyricsLoadResult(ExternalLyricsStatus.UNAVAILABLE)

    val pathIds = path.path
    val treeIndex = pathIds.indexOf(treeId)
    if (path.rootId != pathIds.firstOrNull() ||
        pathIds.lastOrNull() != trackId ||
        pathIds.size < 2 ||
        treeIndex < 0 || treeIndex >= pathIds.lastIndex
    ) {
        return ExternalLyricsLoadResult(ExternalLyricsStatus.UNAVAILABLE)
    }
    val trackName = try {
        queryDisplayName(resolver, trackUri)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: SecurityException) {
        return ExternalLyricsLoadResult(ExternalLyricsStatus.UNAVAILABLE)
    } catch (_: FileNotFoundException) {
        return ExternalLyricsLoadResult(ExternalLyricsStatus.UNAVAILABLE)
    } catch (_: IOException) {
        return ExternalLyricsLoadResult(ExternalLyricsStatus.ERROR)
    } catch (_: UnsupportedOperationException) {
        return ExternalLyricsLoadResult(ExternalLyricsStatus.UNAVAILABLE)
    } catch (_: IllegalArgumentException) {
        return ExternalLyricsLoadResult(ExternalLyricsStatus.ERROR)
    } catch (_: RuntimeException) {
        return ExternalLyricsLoadResult(ExternalLyricsStatus.ERROR)
    } ?: return ExternalLyricsLoadResult(ExternalLyricsStatus.UNAVAILABLE)
    val dot = trackName.lastIndexOf('.')
    val baseName = if (dot > 0) trackName.substring(0, dot) else trackName
    if (baseName.isBlank()) return ExternalLyricsLoadResult(ExternalLyricsStatus.INVALID)

    val parentId = pathIds[pathIds.lastIndex - 1]
    val childrenUri = try {
        DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: RuntimeException) {
        return ExternalLyricsLoadResult(ExternalLyricsStatus.UNAVAILABLE)
    }
    val candidates = try {
        querySiblingLyrics(resolver, treeUri, childrenUri, baseName)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: SecurityException) {
        return ExternalLyricsLoadResult(ExternalLyricsStatus.UNAVAILABLE)
    } catch (_: FileNotFoundException) {
        return ExternalLyricsLoadResult(ExternalLyricsStatus.UNAVAILABLE)
    } catch (_: IOException) {
        return ExternalLyricsLoadResult(ExternalLyricsStatus.ERROR)
    } catch (_: UnsupportedOperationException) {
        return ExternalLyricsLoadResult(ExternalLyricsStatus.UNAVAILABLE)
    } catch (_: IllegalArgumentException) {
        return ExternalLyricsLoadResult(ExternalLyricsStatus.ERROR)
    } catch (_: RuntimeException) {
        return ExternalLyricsLoadResult(ExternalLyricsStatus.ERROR)
    } ?: return ExternalLyricsLoadResult(ExternalLyricsStatus.UNAVAILABLE)
    if (candidates.isEmpty()) return ExternalLyricsLoadResult(ExternalLyricsStatus.NOT_FOUND)
    if (candidates.size != 1) return ExternalLyricsLoadResult(ExternalLyricsStatus.INVALID)

    val document = candidates.single()
    if (document.sizeBytes > MAX_EXTERNAL_LYRICS_BYTES) {
        return ExternalLyricsLoadResult(ExternalLyricsStatus.INVALID)
    }
    val key = ExternalLyricsCacheKey(
        trackKey = trackKey,
        treeKey = treeUri.toString(),
        libraryGeneration = libraryGeneration,
        documentUri = document.uri.toString(),
        lastModified = document.lastModified,
        sizeBytes = document.sizeBytes
    )
    return ExternalLyricsCache.getOrLoad(key) {
        readResolvedExternalLyrics(resolver, document.uri)
    }
}

private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? {
    val cursor = resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
        ?: return null
    return cursor.use {
        if (!it.moveToFirst()) return null
        val column = it.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        if (column < 0) null else it.getString(column)?.takeIf(String::isNotBlank)
    }
}

private fun querySiblingLyrics(
    resolver: ContentResolver,
    treeUri: Uri,
    childrenUri: Uri,
    baseName: String
): List<ExternalLyricsDocument>? {
    val cursor = resolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_SIZE
            ),
            null,
            null,
            null
        ) ?: return null
    return cursor.use {
            val idColumn = it.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = it.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val modifiedColumn = it.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            val sizeColumn = it.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            if (idColumn < 0 || nameColumn < 0) return null
            val expected = "$baseName.lrc"
            val found = mutableListOf<ExternalLyricsDocument>()
            while (it.moveToNext()) {
                val name = it.getString(nameColumn) ?: continue
                if (!name.equals(expected, ignoreCase = true)) continue
                val documentId = it.getString(idColumn)?.takeIf(String::isNotBlank) ?: continue
                val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                val modified = if (modifiedColumn >= 0 && !it.isNull(modifiedColumn)) it.getLong(modifiedColumn) else -1L
                val size = if (sizeColumn >= 0 && !it.isNull(sizeColumn)) it.getLong(sizeColumn) else -1L
                found += ExternalLyricsDocument(documentUri, modified, size)
            }
            found
    }
}

private fun readResolvedExternalLyrics(
    resolver: ContentResolver,
    uri: Uri
): ExternalLyricsLoadResult {
    return try {
        val stream = resolver.openInputStream(uri)
            ?: return ExternalLyricsLoadResult(ExternalLyricsStatus.ERROR)
        stream.use { readExternalLyricsResult(it) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: SecurityException) {
        ExternalLyricsLoadResult(ExternalLyricsStatus.UNAVAILABLE)
    } catch (_: FileNotFoundException) {
        // A listed document that disappears before open is a provider race, not confirmed absence.
        ExternalLyricsLoadResult(ExternalLyricsStatus.ERROR)
    } catch (_: IOException) {
        ExternalLyricsLoadResult(ExternalLyricsStatus.ERROR)
    } catch (_: IllegalArgumentException) {
        // SAF providers can throw this while opening a missing document. Do not infer NOT_FOUND from text.
        ExternalLyricsLoadResult(ExternalLyricsStatus.ERROR)
    } catch (_: RuntimeException) {
        ExternalLyricsLoadResult(ExternalLyricsStatus.ERROR)
    }
}

/** Reads at most 1 MiB of a sibling file and parses it as line-level LRC. */
internal fun readExternalLyrics(
    input: InputStream,
    maxBytes: Int = MAX_EXTERNAL_LYRICS_BYTES
): List<Pair<Long, String>>? {
    val result = readExternalLyricsResult(input, maxBytes)
    return result.lyrics.takeIf { result.status == ExternalLyricsStatus.FOUND }
}

internal fun readExternalLyricsResult(
    input: InputStream,
    maxBytes: Int = MAX_EXTERNAL_LYRICS_BYTES
): ExternalLyricsLoadResult {
    if (maxBytes <= 0) return ExternalLyricsLoadResult(ExternalLyricsStatus.INVALID)
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
                if (total >= maxBytes) return ExternalLyricsLoadResult(ExternalLyricsStatus.INVALID)
                output.write(single)
                total++
            } else {
                total += count
                if (total > maxBytes) return ExternalLyricsLoadResult(ExternalLyricsStatus.INVALID)
                output.write(bytes, 0, count)
            }
            if (total == maxBytes) {
                // Probe one byte so an exactly-limit file is accepted while a larger file is rejected.
                if (input.read() >= 0) return ExternalLyricsLoadResult(ExternalLyricsStatus.INVALID)
                break
            }
        }
        val decoded = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(output.toByteArray()))
            .toString()
        val parsed = LrcParser.parse(decoded)
        if (parsed.isEmpty()) ExternalLyricsLoadResult(ExternalLyricsStatus.INVALID)
        else ExternalLyricsLoadResult(ExternalLyricsStatus.FOUND, parsed)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: CharacterCodingException) {
        ExternalLyricsLoadResult(ExternalLyricsStatus.INVALID)
    } catch (_: IOException) {
        ExternalLyricsLoadResult(ExternalLyricsStatus.ERROR)
    } catch (_: IllegalArgumentException) {
        ExternalLyricsLoadResult(ExternalLyricsStatus.INVALID)
    } catch (_: Exception) {
        ExternalLyricsLoadResult(ExternalLyricsStatus.ERROR)
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
