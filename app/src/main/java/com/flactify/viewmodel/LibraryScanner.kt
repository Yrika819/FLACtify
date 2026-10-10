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

class LibraryScanner(
    private val metadataExtractor: TrackMetadataExtractor?,
    private val migrationWrite: (File, ByteArray) -> Unit = { file, bytes -> file.writeBytes(bytes) },
    private val migrationVerify: (File, ByteArray) -> Boolean = { file, bytes -> file.readBytes().contentEquals(bytes) }
) {
    suspend fun scan(
        context: Context,
        folderUri: Uri,
        cachedTracks: List<TrackData>,
        onProgress: (LibraryScanProgress) -> Unit = {},
        shouldPersist: () -> Boolean = { true }
    ): LibraryScanResult = withContext(Dispatchers.IO) {
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
            collectAudioFiles(root)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext LibraryScanResult(emptyList(), emptyList(), false, scanSucceeded = false, failure = e)
        }
        if (files.isEmpty()) {
            val changed = cachedTracks.isNotEmpty()
            if (shouldPersist()) saveCache(context, folderUri, emptyList())
            return@withContext LibraryScanResult(emptyList(), files, changed)
        }

        val newParsedTracks = mutableListOf<TrackData>()
        val failedFiles = mutableListOf<String>()
        val cachedMap = cachedTracks.associateBy { it.uri.toString() }
        var hasChanges = false

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
            val fileLastMod = try {
                file.lastModified()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failedFiles.add(fileName.ifBlank { fileUriStr })
                onProgress(LibraryScanProgress(files.size, index + 1, fileName, failedFiles.toList()))
                continue
            }
            onProgress(LibraryScanProgress(files.size, index, fileName, failedFiles.toList()))
            val cached = cachedMap[fileUriStr]

            if (cached != null && isTimestampFresh(cached.lastModified, fileLastMod)) {
                newParsedTracks.add(cached.copy(originalIndex = index))
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

                val fileExt = fileName.substringAfterLast(".", "flac").lowercase().ifBlank { "flac" }
                metadataExtractor?.fileExtensionCache?.set(file.uri, fileExt)

                var tempFile: File? = null
                var sourceCopied = false
                try {
                    val scanFile = File.createTempFile("scan_", ".$fileExt", workDirectory(context))
                    tempFile = scanFile
                    val source = context.contentResolver.openInputStream(file.uri)
                        ?: throw java.io.IOException("Unable to open audio document")
                    source.use { input ->
                        FileOutputStream(scanFile).use { output -> input.copyTo(output) }
                    }
                    sourceCopied = true

                    val audioFile = AudioFileIO.read(scanFile)
                    val tag = audioFile.tag
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
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("FLACtify", "Error reading tags", e)
                } finally {
                    tempFile?.delete()
                }

                if (!sourceCopied) {
                    failedFiles.add(fileName.ifBlank { fileUriStr })
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
                        lastModified = fileLastMod
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
        val libraryChanged = hasChanges || cachedTracks.size != newParsedTracks.size || cachedTracks.isEmpty()
        if (libraryChanged && shouldPersist()) saveCache(context, folderUri, newParsedTracks)
        LibraryScanResult(newParsedTracks, files, libraryChanged)
    }

    fun saveCache(context: Context, folderUri: Uri, tracks: List<TrackData>) {
        try {
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
                })
            }
            root.put("data", array)

            val atomicFile = AtomicFile(cacheFile(context))
            val output = atomicFile.startWrite()
            try {
                output.write(root.toString().toByteArray(Charsets.UTF_8))
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
                destination.takeIf { it.isFile }?.let { runCatching { parseCacheSnapshot(it.readText()) }.getOrNull() }
            if (existing != null) {
                if (legacy.isFile && runCatching {
                        legacy.readBytes().contentEquals(destination.readBytes())
                    }.getOrDefault(false)) {
                    legacy.delete()
                }
                return existing
            }
            if (destination.exists() || File(destination.path + ".bak").exists()) AtomicFile(destination).delete()
            if (legacy.isFile) migrateLegacyCache(legacy, destination)
            if (!destination.isFile) return null
            parseCacheSnapshot(destination.readText())
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
        val root = JSONObject(json)
        if (root.optInt("version", -1) != 1) return null
        val folderUri = root.optString("folderUri", "").takeIf { it.isNotBlank() }?.let(Uri::parse) ?: return null
        val array = root.optJSONArray("data") ?: return null
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
                    originalIndex = i
                )
            )
        }
        return LibraryCacheSnapshot(folderUri, list)
    }

    private fun migrateLegacyCache(legacy: File, destination: File) {
        val source = legacy.readBytes()
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

    companion object {
        internal fun isTimestampFresh(cached: Long, current: Long): Boolean =
            cached > 0L && current > 0L && cached == current

        internal fun ownsFolder(snapshot: LibraryCacheSnapshot?, folderUri: Uri): Boolean =
            ownsFolderUri(snapshot?.folderUri?.toString(), folderUri.toString())

        internal fun ownsFolderUri(cachedFolderUri: String?, selectedFolderUri: String): Boolean =
            cachedFolderUri == selectedFolderUri

        const val CACHE_FILE_NAME = "track_cache.json"
        const val LEGACY_CACHE_FILE_NAME = "track_cache.json"
        fun libraryCacheDirectory(context: Context): File =
            File(context.cacheDir, "flactify-library").apply { mkdirs() }

        fun workDirectory(context: Context): File = File(context.cacheDir, "flactify-work").apply { mkdirs() }
    }

    fun collectAudioFiles(dir: DocumentFile): List<DocumentFile> {
        val result = mutableListOf<DocumentFile>()
        dir.listFiles().forEach { file ->
            if (file.isDirectory) {
                result.addAll(collectAudioFiles(file))
            } else {
                val name = file.name?.lowercase() ?: ""
                if (name.endsWith(".mp3") || name.endsWith(".flac") || name.endsWith(".m4a") || name.endsWith(".wav")) {
                    result.add(file)
                }
            }
        }
        return result.sortedBy { it.name }
    }
}
