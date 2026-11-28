package com.flactify.viewmodel

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import android.media.MediaMetadataRetriever
import android.util.AtomicFile

data class LibraryCacheSnapshot(val folderUri: Uri, val tracks: List<TrackData>)

data class LibraryScanResult(
    val tracks: List<TrackData>,
    val files: List<DocumentFile>,
    val changed: Boolean,
    val failedFiles: List<String> = emptyList(),
    val scanSucceeded: Boolean = true,
    val failure: Exception? = null
)

data class LibraryScanProgress(
    val total: Int,
    val completed: Int,
    val currentFile: String,
    val failedFiles: List<String>
)

/** Whether a scan may reuse entries from the persisted library snapshot. */
enum class LibraryScanMode {
    INCREMENTAL,
    COMPLETE
}

class LibraryScanner(
    private val metadataExtractor: TrackMetadataExtractor?,
    private val migrationWrite: (File, ByteArray) -> Unit = { file, bytes -> file.writeBytes(bytes) },
    private val migrationVerify: (File, ByteArray) -> Boolean = { file, bytes -> file.readBytes().contentEquals(bytes) }
) {
    /** Differential validation used by normal startup and folder-switch scans. */
    suspend fun scan(
        context: Context,
        folderUri: Uri,
        cachedTracks: List<TrackData>,
        onProgress: (LibraryScanProgress) -> Unit = {},
        shouldPersist: () -> Boolean = { true },
        persistIfCurrent: ((List<TrackData>) -> Unit)? = null
    ): LibraryScanResult = scanInternal(
        context, folderUri, cachedTracks, onProgress, shouldPersist, persistIfCurrent,
        LibraryScanMode.INCREMENTAL
    )

    /**
     * Explicit recovery path for a user-facing “rescan all” action. It deliberately discards
     * cache entries for this invocation, refreshing metadata and format hints for every document.
     * The caller owns any UI action that invokes it; normal scans remain incremental. This is
     * required when provider mtime and size metadata are insufficient to identify replacements.
     */
    suspend fun fullRescan(
        context: Context,
        folderUri: Uri,
        onProgress: (LibraryScanProgress) -> Unit = {},
        shouldPersist: () -> Boolean = { true },
        persistIfCurrent: ((List<TrackData>) -> Unit)? = null
    ): LibraryScanResult = scanInternal(
        context, folderUri, emptyList(), onProgress, shouldPersist, persistIfCurrent,
        LibraryScanMode.COMPLETE
    )

    private suspend fun scanInternal(
        context: Context,
        folderUri: Uri,
        cachedTracks: List<TrackData>,
        onProgress: (LibraryScanProgress) -> Unit,
        shouldPersist: () -> Boolean,
        persistIfCurrent: ((List<TrackData>) -> Unit)?,
        mode: LibraryScanMode
    ): LibraryScanResult = withContext(Dispatchers.IO) {
        val cacheForScan = if (mode == LibraryScanMode.COMPLETE) emptyList() else cachedTracks
        val root = try {
            DocumentFile.fromTreeUri(context, folderUri)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext LibraryScanResult(emptyList(), emptyList(), false, scanSucceeded = false, failure = e)
        } ?: return@withContext LibraryScanResult(
            emptyList(), emptyList(), false, scanSucceeded = false,
            failure = SecurityException("Selected folder is no longer accessible")
        )
        try {
            if (!root.exists() || !root.isDirectory) {
                return@withContext LibraryScanResult(
                    emptyList(), emptyList(), false, scanSucceeded = false,
                    failure = SecurityException("Selected folder is unavailable")
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext LibraryScanResult(emptyList(), emptyList(), false, scanSucceeded = false, failure = e)
        }
        val files = try {
            collectCandidateFiles(root)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext LibraryScanResult(emptyList(), emptyList(), false, scanSucceeded = false, failure = e)
        }
        if (files.isEmpty()) {
            val changed = cachedTracks.isNotEmpty()
            if (persistIfCurrent != null) {
                persistIfCurrent(emptyList())
            } else if (shouldPersist()) {
                saveCache(context, folderUri, emptyList())
            }
            return@withContext LibraryScanResult(emptyList(), files, changed)
        }

        val newParsedTracks = mutableListOf<TrackData>()
        val failedFiles = mutableListOf<String>()
        val cachedMap = cacheForScan.associateBy { it.uri.toString() }
        var hasChanges = false
        var cacheNeedsWrite = false

        for ((index, file) in files.withIndex()) {
            ensureActive()
            val fileName = try {
                file.name ?: ""
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failedFiles.add("Unknown file: ${e.javaClass.simpleName}")
                onProgress(LibraryScanProgress(files.size, index + 1, "", failedFiles.toList()))
                continue
            }
            val fileUriStr = try {
                file.uri.toString()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failedFiles.add(fileName.ifBlank { "Unknown file" })
                onProgress(LibraryScanProgress(files.size, index + 1, fileName, failedFiles.toList()))
                continue
            }
            val cached = cachedMap[fileUriStr]
            val fileLastMod = try {
                file.lastModified()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failedFiles.add(fileName.ifBlank { fileUriStr })
                cached?.let { newParsedTracks.add(it.copy(originalIndex = index)) }
                onProgress(LibraryScanProgress(files.size, index + 1, fileName, failedFiles.toList()))
                continue
            }
            val fileSize = try {
                file.length().takeIf { it > 0L } ?: -1L
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                -1L
            }
            onProgress(LibraryScanProgress(files.size, index, fileName, failedFiles.toList()))

            // The detected container is persisted with each cached track. On a validation scan,
            // an unchanged document can therefore be reused without reopening the provider just
            // to sniff its first bytes. A missing hint is deliberately a cache miss: older cache
            // files and hand-built callers still receive the content-based detection path below.
            if (cached != null && isCacheEntryFresh(cached.lastModified, fileLastMod, cached.fileSize, fileSize) &&
                cached.formatExtension?.let(::isSupportedFormatExtension) == true
            ) {
                metadataExtractor?.fileExtensionCache?.set(file.uri, cached.formatExtension)
                newParsedTracks.add(cached.copy(originalIndex = index))
                continue
            }

            // Sniff even cache hits: a renamed container must get the same Media3 MIME hint
            // and audio-quality label as a correctly suffixed file. This is also the migration
            // path for cache entries written before formatExtension was persisted.
            val detectedExt = try {
                val stream = context.contentResolver.openInputStream(file.uri)
                    ?: throw java.io.IOException("Unable to open audio document for format detection")
                stream.use { AudioContainerDetector.sniff(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A provider read failure is not evidence that the file is unsupported. Keep
                // the last good library entry and make the incomplete scan visible to callers.
                failedFiles.add(fileName.ifBlank { fileUriStr })
                cached?.let { newParsedTracks.add(it.copy(originalIndex = index)) }
                onProgress(LibraryScanProgress(files.size, index + 1, fileName, failedFiles.toList()))
                continue
            }
            if (detectedExt == null) {
                // A successful read with an unknown signature is an unsupported/non-audio file.
                onProgress(LibraryScanProgress(files.size, index + 1, fileName, failedFiles.toList()))
                continue
            }
            metadataExtractor?.fileExtensionCache?.set(file.uri, detectedExt)

            if (cached != null && isCacheEntryFresh(cached.lastModified, fileLastMod, cached.fileSize, fileSize)) {
                val withFormat = cached.copy(
                    originalIndex = index,
                    formatExtension = detectedExt,
                    fileSize = fileSize.takeIf { it > 0L } ?: cached.fileSize
                )
                newParsedTracks.add(withFormat)
                cacheNeedsWrite = cacheNeedsWrite || cached.formatExtension != detectedExt ||
                    (cached.fileSize <= 0L && fileSize > 0L)
                continue
            }

            hasChanges = true
            try {
                Log.d("FLACtify", "Scanning new or changed file")
                var titleName = fileName.substringBeforeLast(".").ifBlank { "不明" }
                var artistName = ""
                var albumName = ""
                var trackNum: String? = null
                var genre: String? = null
                var year: String? = null
                var composer: String? = null
                var albumArtist: String? = null
                var discNumber: String? = null
                var comment: String? = null
                var artBytes: ByteArray? = null
                var replayGain = com.flactify.audio.ReplayGainTags.UNKNOWN

                val fileExt = detectedExt
                metadataExtractor?.fileExtensionCache?.set(file.uri, fileExt)

                var tempFile: File? = null
                var sourceCopied = false
                try {
                    val scanFile = File.createTempFile("scan_", ".$fileExt", workDirectory(context))
                    tempFile = scanFile
                    val source = context.contentResolver.openInputStream(file.uri)
                        ?: throw java.io.IOException("Unable to open audio document")
                    source.use { input ->
                        FileOutputStream(scanFile).use { output -> copyBoundedMetadata(input, output) }
                    }
                    sourceCopied = true

                    try {
                        val tag = AudioFileIO.read(scanFile).tag
                        replayGain = ReplayGainTagReader.fromTag(tag)
                        if (tag != null) {
                            tag.getFirst(FieldKey.TITLE).takeIf { it.isNotBlank() }?.let { titleName = it.trim() }
                            tag.getFirst(FieldKey.ARTIST).takeIf { it.isNotBlank() }?.let { artistName = it.trim() }
                            tag.getFirst(FieldKey.ALBUM).takeIf { it.isNotBlank() }?.let { albumName = it.trim() }
                            tag.getFirst(FieldKey.TRACK).takeIf { it.isNotBlank() }?.let { trackNum = it.trim() }
                            tag.getFirst(FieldKey.GENRE).takeIf { it.isNotBlank() }?.let { genre = it.trim() }
                            tag.getFirst(FieldKey.YEAR).takeIf { it.isNotBlank() }?.let { year = it.trim() }
                            tag.getFirst(FieldKey.COMPOSER).takeIf { it.isNotBlank() }?.let { composer = it.trim() }
                            tag.getFirst(FieldKey.ALBUM_ARTIST).takeIf { it.isNotBlank() }?.let { albumArtist = it.trim() }
                            tag.getFirst(FieldKey.DISC_NO).takeIf { it.isNotBlank() }?.let { discNumber = it.trim() }
                            tag.getFirst(FieldKey.COMMENT).takeIf { it.isNotBlank() }?.let { comment = it.trim() }
                            tag.firstArtwork?.let { artBytes = compactArtworkBytes(it.binaryData) }
                        }
                    } catch (e: Exception) {
                        // Opus tags are read below because jaudiotagger 3.0.1 has no Opus reader.
                        Log.d("FLACtify", "Primary tag reader did not recognize container")
                    }
                    if (fileExt == "opus") {
                        OpusTags.read(scanFile)?.let { tags ->
                            replayGain = ReplayGainTagReader.fromComments(tags)
                            tags["TITLE"]?.takeIf { it.isNotBlank() }?.let { titleName = it }
                            tags["ARTIST"]?.takeIf { it.isNotBlank() }?.let { artistName = it }
                            tags["ALBUM"]?.takeIf { it.isNotBlank() }?.let { albumName = it }
                            tags["TRACKNUMBER"]?.takeIf { it.isNotBlank() }?.let { trackNum = it }
                            tags["GENRE"]?.takeIf { it.isNotBlank() }?.let { genre = it }
                            tags["DATE"]?.takeIf { it.isNotBlank() }?.let { year = it }
                            tags["COMPOSER"]?.takeIf { it.isNotBlank() }?.let { composer = it }
                            tags["ALBUMARTIST"]?.takeIf { it.isNotBlank() }?.let { albumArtist = it }
                            tags["DISCNUMBER"]?.takeIf { it.isNotBlank() }?.let { discNumber = it }
                            tags["COMMENT"]?.takeIf { it.isNotBlank() }?.let { comment = it }
                            tags["METADATA_BLOCK_PICTURE"]?.let(OpusTags::decodePicture)?.let(::compactArtworkBytes)?.let { artBytes = it }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("FLACtify", "Error reading tags", e)
                } finally {
                    tempFile?.delete()
                }

                if (!sourceCopied) {
                    failedFiles.add(fileName.ifBlank { fileUriStr })
                    cached?.let { newParsedTracks.add(it.copy(originalIndex = index)) }
                    onProgress(LibraryScanProgress(files.size, index + 1, fileName, failedFiles.toList()))
                    continue
                }

                if (artistName.isBlank() || albumName.isBlank()) {
                    val retriever = MediaMetadataRetriever()
                    try {
                        context.contentResolver.openFileDescriptor(file.uri, "r")?.use { fd ->
                            retriever.setDataSource(fd.fileDescriptor)
                            if (artistName.isBlank()) artistName =
                                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.trim() ?: ""
                            if (albumName.isBlank()) albumName =
                                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.trim() ?: ""
                            if (titleName == "不明") titleName =
                                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.trim() ?: "不明"
                            if (trackNum == null) trackNum =
                                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)?.trim()
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                    } finally {
                        try {
                            retriever.release()
                        } catch (_: Exception) {
                        }
                    }
                }

                if (artistName.isBlank()) {
                    val rawName = file.name?.substringBeforeLast(".") ?: ""
                    if (rawName.contains(" - ")) {
                        val parts = rawName.split(" - ", limit = 2)
                        artistName = parts[0].trim()
                        if (titleName == "不明" || titleName == rawName) titleName = parts[1].trim()
                    }
                }

                newParsedTracks.add(
                    TrackData(
                        title = titleName,
                        artist = artistName.ifBlank { "不明なアーティスト" },
                        album = albumName.ifBlank { "不明なアルバム" },
                        trackNumber = trackNum,
                        genre = genre,
                        year = year,
                        composer = composer,
                        albumArtist = albumArtist,
                        discNumber = discNumber,
                        comment = comment,
                        originalIndex = index,
                        uri = file.uri,
                        albumArtBytes = artBytes,
                        lastModified = fileLastMod,
                        formatExtension = fileExt,
                        fileSize = fileSize,
                        replayGainTags = replayGain
                    )
                )
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                if (t !is Exception) throw t
                Log.e("FLACtify", "Unable to read an audio document", t)
                failedFiles.add(fileName.ifBlank { fileUriStr })
            }
            onProgress(LibraryScanProgress(files.size, index + 1, fileName, failedFiles.toList()))
        }

        if (failedFiles.isNotEmpty()) {
            return@withContext LibraryScanResult(
                newParsedTracks, files, false, failedFiles.toList(), scanSucceeded = false,
                failure = IllegalStateException("One or more files could not be read")
            )
        }
        val libraryChanged = hasChanges || cacheForScan.size != newParsedTracks.size || cacheForScan.isEmpty()
        if (libraryChanged || cacheNeedsWrite) {
            if (persistIfCurrent != null) {
                persistIfCurrent(newParsedTracks)
            } else if (shouldPersist()) {
                saveCache(context, folderUri, newParsedTracks)
            }
        }
        LibraryScanResult(newParsedTracks, files, libraryChanged)
    }

    fun saveCache(context: Context, folderUri: Uri, tracks: List<TrackData>) {
        try {
            require(tracks.size <= MAX_CACHED_TRACKS) {
                "Library cache exceeds the $MAX_CACHED_TRACKS track limit"
            }
            val root = JSONObject().apply {
                put("version", 1)
                put("folderUri", folderUri.toString())
            }
            val array = JSONArray()
            tracks.forEach { track ->
                array.put(JSONObject().apply {
                    put("t", track.title)
                    put("ar", track.artist)
                    put("al", track.album)
                    put("tn", track.trackNumber ?: "")
                    put("g", track.genre ?: "")
                    put("y", track.year ?: "")
                    put("c", track.composer ?: "")
                    put("aa", track.albumArtist ?: "")
                    put("dn", track.discNumber ?: "")
                    put("cm", track.comment ?: "")
                    put("u", track.uri.toString())
                    put("lm", track.lastModified)
                    track.formatExtension?.let { put("fx", it) }
                    if (track.fileSize > 0L) put("fs", track.fileSize)
                    track.replayGainTags.trackGainDb?.let { put("rgtg", it) }
                    track.replayGainTags.albumGainDb?.let { put("rgag", it) }
                    track.replayGainTags.trackPeak?.let { put("rgtp", it) }
                    track.replayGainTags.albumPeak?.let { put("rgap", it) }
                    track.replayGainTags.referenceLoudnessDb?.let { put("rgrl", it) }
                    track.replayGainTags.r128TrackGainDb?.let { put("r128", it) }
                })
            }
            root.put("data", array)
            val bytes = root.toString().toByteArray(Charsets.UTF_8)
            require(bytes.size <= MAX_CACHE_BYTES) {
                "Library cache exceeds the $MAX_CACHE_BYTES byte limit"
            }

            val atomicFile = AtomicFile(cacheFile(context))
            val output = atomicFile.startWrite()
            try {
                output.write(bytes)
                atomicFile.finishWrite(output)
            } catch (e: Exception) {
                atomicFile.failWrite(output)
                throw e
            }
        } catch (e: Exception) {
            Log.e("FLACtify", "Unable to save library cache", e)
        }
    }

    fun loadCacheSnapshot(context: Context): LibraryCacheSnapshot? {
        return try {
            val destination = cacheFile(context)
            val legacy = File(context.filesDir, LEGACY_CACHE_FILE_NAME)
            val existing =
                destination.takeIf { it.isFile }?.let { runCatching { readCacheText(it)?.let(::parseCacheSnapshot) }.getOrNull() }
            if (existing != null) {
                if (legacy.isFile && legacy.length() <= MAX_CACHE_BYTES && runCatching {
                        legacy.readBytes().contentEquals(destination.readBytes())
                    }.getOrDefault(false)) {
                    legacy.delete()
                }
                return existing
            }
            if (destination.exists() || File(destination.path + ".bak").exists()) AtomicFile(destination).delete()
            if (legacy.isFile) migrateLegacyCache(legacy, destination)
            if (!destination.isFile) return null
            readCacheText(destination)?.let(::parseCacheSnapshot)
        } catch (e: Exception) {
            Log.e("FLACtify", "Unable to read library cache", e)
            null
        }
    }

    fun loadCache(context: Context, folderUri: Uri?): List<TrackData> {
        if (folderUri == null) return emptyList()
        val snapshot = loadCacheSnapshot(context) ?: return emptyList()
        return if (snapshot.folderUri.toString() == folderUri.toString()) snapshot.tracks else emptyList()
    }

    private fun parseCacheSnapshot(json: String): LibraryCacheSnapshot? {
        if (json.toByteArray(Charsets.UTF_8).size > MAX_CACHE_BYTES) return null
        val root = JSONObject(json)
        if (root.optInt("version", -1) != 1) return null
        val folderUri = root.optString("folderUri", "").takeIf { it.isNotBlank() }?.let(Uri::parse) ?: return null
        val array = root.optJSONArray("data") ?: return null
        if (array.length() > MAX_CACHED_TRACKS) return null
        val list = mutableListOf<TrackData>()

        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            list.add(
                TrackData(
                    title = obj.optString("t", "不明"),
                    artist = obj.optString("ar", "不明なアーティスト"),
                    album = obj.optString("al", "不明なアルバム"),
                    trackNumber = obj.optString("tn").takeIf { it.isNotEmpty() },
                    genre = obj.optString("g").takeIf { it.isNotEmpty() },
                    year = obj.optString("y").takeIf { it.isNotEmpty() },
                    composer = obj.optString("c").takeIf { it.isNotEmpty() },
                    albumArtist = obj.optString("aa").takeIf { it.isNotEmpty() },
                    discNumber = obj.optString("dn").takeIf { it.isNotEmpty() },
                    comment = obj.optString("cm").takeIf { it.isNotEmpty() },
                    uri = Uri.parse(obj.getString("u")),
                    lastModified = obj.optLong("lm", 0L),
                    formatExtension = obj.optString("fx")
                        .takeIf(::isSupportedFormatExtension),
                    fileSize = obj.optLong("fs", -1L).takeIf { it > 0L } ?: -1L,
                    replayGainTags = com.flactify.audio.ReplayGainTags(
                        trackGainDb = obj.optDouble("rgtg").takeUnless { it.isNaN() },
                        albumGainDb = obj.optDouble("rgag").takeUnless { it.isNaN() },
                        trackPeak = obj.optDouble("rgtp").takeUnless { it.isNaN() },
                        albumPeak = obj.optDouble("rgap").takeUnless { it.isNaN() },
                        referenceLoudnessDb = obj.optDouble("rgrl").takeUnless { it.isNaN() },
                        r128TrackGainDb = obj.optDouble("r128").takeUnless { it.isNaN() }
                    ),
                    originalIndex = i
                )
            )
        }
        return LibraryCacheSnapshot(folderUri, list)
    }

    private fun migrateLegacyCache(legacy: File, destination: File) {
        if (legacy.length() > MAX_CACHE_BYTES) return
        val source = legacy.readBytes()
        if (source.size > MAX_CACHE_BYTES) return
        if (parseCacheSnapshot(source.toString(Charsets.UTF_8)) == null) return
        val staged = File.createTempFile("${CACHE_FILE_NAME}.", ".migration", destination.parentFile)
        try {
            migrationWrite(staged, source)
            if (!migrationVerify(staged, source) || parseCacheSnapshot(staged.readText()) == null) {
                throw java.io.IOException("Staged library cache did not verify")
            }
            if (destination.exists() || File(destination.path + ".bak").exists()) AtomicFile(destination).delete()
            if (!staged.renameTo(destination)) throw java.io.IOException("Unable to commit migrated library cache")
            if (!migrationVerify(destination, source) || parseCacheSnapshot(destination.readText()) == null) {
                AtomicFile(destination).delete()
                throw java.io.IOException("Migrated library cache did not verify")
            }
            if (!legacy.delete() && legacy.exists()) {
                Log.w("FLACtify", "Migrated cache verified but legacy cache could not be removed")
            }
        } finally {
            staged.delete()
        }
    }

    private fun cacheFile(context: Context): File = File(libraryCacheDirectory(context), CACHE_FILE_NAME)

    private fun readCacheText(file: File): String? {
        if (!file.isFile || file.length() > MAX_CACHE_BYTES) return null
        return file.readText()
    }

    companion object {
        private fun isSupportedFormatExtension(extension: String): Boolean =
            extension in setOf("flac", "wav", "ogg", "opus", "m4a", "mp3")

        internal fun isTimestampFresh(cached: Long, current: Long): Boolean =
            cached > 0L && current > 0L && cached == current

        /**
         * Validates provider metadata for an incremental reuse. A non-positive timestamp is
         * untrustworthy, so equal size alone is never accepted: the caller must reopen and parse
         * the document. Size and mtime are metadata hints, not a content identity guarantee.
         */
        internal fun isCacheEntryFresh(
            cachedTimestamp: Long,
            currentTimestamp: Long,
            cachedSize: Long,
            currentSize: Long
        ): Boolean {
            val sizesKnown = cachedSize > 0L && currentSize > 0L
            if (sizesKnown && cachedSize != currentSize) return false
            if (cachedTimestamp <= 0L || currentTimestamp <= 0L) return false
            return cachedTimestamp == currentTimestamp
        }

        internal fun ownsFolder(snapshot: LibraryCacheSnapshot?, folderUri: Uri): Boolean =
            ownsFolderUri(snapshot?.folderUri?.toString(), folderUri.toString())

        internal fun ownsFolderUri(cachedFolderUri: String?, selectedFolderUri: String): Boolean =
            cachedFolderUri == selectedFolderUri

        const val CACHE_FILE_NAME = "track_cache.json"
        const val LEGACY_CACHE_FILE_NAME = "track_cache.json"
        /** Bounds temporary storage and I/O for a single SAF item during full metadata parsing. */
        const val MAX_TRACK_METADATA_COPY_BYTES = 1L shl 30

        internal fun copyBoundedMetadata(
            input: InputStream,
            output: OutputStream,
            maxBytes: Long = MAX_TRACK_METADATA_COPY_BYTES
        ): Long {
            require(maxBytes >= 0L)
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var copied = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) return copied
                if (copied + count > maxBytes) {
                    throw java.io.IOException("Audio document exceeds the metadata scan size limit")
                }
                output.write(buffer, 0, count)
                copied += count
            }
        }
        fun libraryCacheDirectory(context: Context): File =
            File(context.cacheDir, "flactify-library").apply { mkdirs() }

        fun workDirectory(context: Context): File = File(context.cacheDir, "flactify-work").apply { mkdirs() }

        private const val MAX_SCAN_DEPTH = 32
        private const val MAX_SCAN_FILES = 25_000
        private const val MAX_CACHED_TRACKS = MAX_SCAN_FILES
        private const val MAX_CACHE_BYTES = 16 * 1024 * 1024
    }

    fun collectAudioFiles(dir: DocumentFile): List<DocumentFile> {
        return collectCandidateFiles(dir).filter { file ->
            val name = file.name?.lowercase() ?: ""
            listOf(".mp3", ".flac", ".m4a", ".wav", ".ogg", ".opus").any(name::endsWith)
        }
    }

    private fun collectCandidateFiles(dir: DocumentFile): List<DocumentFile> =
        collectCandidateFiles(dir, depth = 0, result = mutableListOf())

    private fun collectCandidateFiles(
        dir: DocumentFile,
        depth: Int,
        result: MutableList<DocumentFile>
    ): List<DocumentFile> {
        if (depth > MAX_SCAN_DEPTH) {
            throw IllegalStateException("Library folder nesting exceeds the $MAX_SCAN_DEPTH level limit")
        }
        if (result.size >= MAX_SCAN_FILES) {
            throw IllegalStateException("Library contains more than the $MAX_SCAN_FILES file limit")
        }
        dir.listFiles().forEach { file ->
            if (file.isDirectory) {
                collectCandidateFiles(file, depth + 1, result)
            } else {
                if (result.size >= MAX_SCAN_FILES) {
                    throw IllegalStateException("Library contains more than the $MAX_SCAN_FILES file limit")
                }
                result.add(file)
            }
        }
        return result.sortedBy { it.name }
    }
}
