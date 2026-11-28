package com.flactify.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.images.AndroidArtwork
import org.json.JSONObject
import android.util.AtomicFile
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

data class TagEditRequest(
    val title: String,
    val artist: String,
    val album: String,
    val trackNumber: String,
    val genre: String?,
    val year: String?,
    val composer: String?,
    val albumArtist: String?,
    val discNumber: String?,
    val comment: String?,
    val artworkBitmap: Bitmap?
)

data class TagEditResult(
    val albumArtBytes: ByteArray?
)

enum class TagEditFailureCategory {
    UNSUPPORTED_FORMAT,
    READ_ACCESS,
    WRITE_ACCESS,
    RECOVERY_COPY,
    VERIFY_RESTORED,
    RECOVERY_REQUIRED,
    SAVE_FAILED
}

internal class TagEditFailure(
    val category: TagEditFailureCategory,
    message: String,
    cause: Throwable? = null
) : IOException(message, cause)

class TagEditor internal constructor(
    private val recoveryMetadataWriter: (File, Uri, File, Long, String) -> Unit = ::writeRecoveryMetadata
) {
    fun supportsFormat(format: String?): Boolean = format in SUPPORTED_FORMATS

    suspend fun updateTrackTags(
        context: Context,
        trackUri: Uri,
        request: TagEditRequest,
        fileExtension: String
    ): Result<TagEditResult> = withContext(Dispatchers.IO) {
        var sourceTemp: File? = null
        var editedTemp: File? = null
        try {
            val sourceStream = try {
                context.contentResolver.openInputStream(trackUri)
            } catch (e: SecurityException) {
                throw TagEditFailure(TagEditFailureCategory.READ_ACCESS, "Unable to read source", e)
            } ?: throw TagEditFailure(TagEditFailureCategory.READ_ACCESS, "Unable to open source")
            val sourceFormat = sourceStream.use(AudioContainerDetector::sniff)
                ?: throw TagEditFailure(TagEditFailureCategory.UNSUPPORTED_FORMAT, "Audio format was not recognized")
            if (!supportsFormat(sourceFormat)) {
                throw TagEditFailure(TagEditFailureCategory.UNSUPPORTED_FORMAT, "Unsupported audio format")
            }

            val safeExtension = fileExtension.takeIf { it.matches(Regex("[A-Za-z0-9]{1,10}")) } ?: sourceFormat
            val sourceFile = File.createTempFile("edit_source_", ".$safeExtension", LibraryScanner.workDirectory(context))
            sourceTemp = sourceFile
            context.contentResolver.openInputStream(trackUri)?.use { input ->
                FileOutputStream(sourceFile).use { output ->
                    input.copyTo(output)
                }
            } ?: throw TagEditFailure(TagEditFailureCategory.READ_ACCESS, "Unable to read source")

            val editedFile = File.createTempFile("edit_work_", ".$safeExtension", LibraryScanner.workDirectory(context))
            editedTemp = editedFile
            sourceFile.copyTo(editedFile, overwrite = true)
            val editedArtworkBytes = if (sourceFormat == "opus") {
                val cover = request.artworkBitmap?.let(::compressArtwork)
                val opusChanges = OggOpusTagEditor.Changes(
                    fields = opusFields(request),
                    frontCoverJpeg = cover,
                    coverWidth = request.artworkBitmap?.width ?: 0,
                    coverHeight = request.artworkBitmap?.height ?: 0
                )
                val rewrite = OggOpusTagEditor.rewrite(sourceFile, editedFile, opusChanges)
                verifyOpusTags(editedFile, request)
                rewrite.frontCoverBytes
            } else {
                val audioFile = AudioFileIO.read(editedFile)
                val tag = audioFile.tag ?: audioFile.createDefaultTag()
                tag.setField(FieldKey.TITLE, request.title)
                tag.setField(FieldKey.ARTIST, request.artist)
                tag.setField(FieldKey.ALBUM, request.album)
                updateOptionalField(tag, FieldKey.TRACK, request.trackNumber)
                updateOptionalField(tag, FieldKey.GENRE, request.genre)
                updateOptionalField(tag, FieldKey.YEAR, request.year)
                updateOptionalField(tag, FieldKey.COMPOSER, request.composer)
                updateOptionalField(tag, FieldKey.ALBUM_ARTIST, request.albumArtist)
                updateOptionalField(tag, FieldKey.DISC_NO, request.discNumber)
                updateOptionalField(tag, FieldKey.COMMENT, request.comment)

                request.artworkBitmap?.let { bitmap ->
                    val artwork = AndroidArtwork()
                    artwork.binaryData = compressArtwork(bitmap)
                    tag.deleteArtworkField()
                    tag.setField(artwork)
                }

                audioFile.commit()
                verifyStandardTags(editedFile, request)
                AudioFileIO.read(editedFile).tag?.firstArtwork?.binaryData
            }

            val recoveryDirectory = TagRecoveryManager.recoveryDirectory(context).apply { mkdirs() }
            TagRecoveryManager.ensureCapacity(context, MAX_RECOVERY_COPIES)
            val recoveryFile = File.createTempFile(
                "recovery_", ".$safeExtension",
                recoveryDirectory
            )
            val recoveryMetadata = File(recoveryDirectory, "${recoveryFile.name}.json")
            val createdAt = System.currentTimeMillis()
            try {
                createVerifiedRecoveryCopy(sourceFile, recoveryFile)
            } catch (e: Exception) {
                recoveryFile.delete()
                if (e is CancellationException) throw e
                throw TagEditFailure(TagEditFailureCategory.RECOVERY_COPY, "Recovery copy could not be verified", e)
            }
            try {
                recoveryMetadataWriter(recoveryMetadata, trackUri, recoveryFile, createdAt, "Tag write pending")
            } catch (e: Exception) {
                recoveryFile.delete()
                if (e is CancellationException) throw e
                throw TagEditFailure(TagEditFailureCategory.RECOVERY_COPY, "Recovery record could not be saved", e)
            }
            try {
                writeSafDocumentAndVerify(
                    original = sourceFile,
                    replacement = editedFile,
                    recoveryFile = recoveryFile,
                    openOutput = { context.contentResolver.openOutputStream(trackUri, "rwt") },
                    openInput = { context.contentResolver.openInputStream(trackUri) },
                    recoveryPrepared = true
                )
                if (!recoveryFile.exists() || recoveryFile.delete()) recoveryMetadata.delete()
                else recoveryMetadataWriter(recoveryMetadata, trackUri, recoveryFile, createdAt, "Edit succeeded; recovery copy retained")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                val restored = runCatching {
                    restoreSafDocumentAndVerify(
                        recoveryFile = recoveryFile,
                        openOutput = { context.contentResolver.openOutputStream(trackUri, "rwt") },
                        openInput = { context.contentResolver.openInputStream(trackUri) }
                    )
                }.isSuccess
                runCatching {
                    recoveryMetadataWriter(
                        recoveryMetadata, trackUri, recoveryFile, createdAt,
                        if (restored) "Write failed; original restored and verified"
                        else "Write failed; recovery copy retained"
                    )
                }
                throw TagEditFailure(
                    if (restored) TagEditFailureCategory.VERIFY_RESTORED else TagEditFailureCategory.RECOVERY_REQUIRED,
                    if (restored) "Tag write failed and original was restored" else "Tag write failed; recovery copy retained",
                    e
                )
            }

            Result.success(TagEditResult(editedArtworkBytes))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e.asTagEditFailure())
        } finally {
            sourceTemp?.delete()
            editedTemp?.delete()
        }
    }

    private fun opusFields(request: TagEditRequest): Map<String, String?> = mapOf(
        "TITLE" to request.title,
        "ARTIST" to request.artist,
        "ALBUM" to request.album,
        "TRACKNUMBER" to request.trackNumber,
        "GENRE" to request.genre,
        "DATE" to request.year,
        "COMPOSER" to request.composer,
        "ALBUMARTIST" to request.albumArtist,
        "DISCNUMBER" to request.discNumber,
        "COMMENT" to request.comment
    )

    private fun verifyOpusTags(file: File, request: TagEditRequest) {
        val tags = OpusTags.read(file)
        val expected = opusFields(request)
        expected.forEach { (key, value) ->
            if (value.isNullOrBlank()) {
                check(tags[key] == null) { "OpusTags field removal did not read back" }
            } else {
                check(tags[key] == value) { "OpusTags field did not read back" }
            }
        }
        if (request.artworkBitmap != null) {
            check(tags["METADATA_BLOCK_PICTURE"] != null) { "OpusTags artwork did not read back" }
        }
    }

    private fun verifyStandardTags(file: File, request: TagEditRequest) {
        val tag = AudioFileIO.read(file).tag ?: throw IOException("Edited tag could not be reopened")
        check(tag.getFirst(FieldKey.TITLE) == request.title) { "Title did not read back" }
        check(tag.getFirst(FieldKey.ARTIST) == request.artist) { "Artist did not read back" }
        check(tag.getFirst(FieldKey.ALBUM) == request.album) { "Album did not read back" }
        val expectedTrack = request.trackNumber.takeIf(String::isNotBlank).orEmpty()
        check(tag.getFirst(FieldKey.TRACK) == expectedTrack) { "Track number did not read back" }
        request.artworkBitmap?.let {
            check(tag.firstArtwork?.binaryData?.isNotEmpty() == true) { "Artwork did not read back" }
        }
    }

    private fun compressArtwork(bitmap: Bitmap): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)) { "Artwork encoding failed" }
        return output.toByteArray()
    }

    private fun Throwable.asTagEditFailure(): TagEditFailure = when (this) {
        is TagEditFailure -> this
        is SecurityException -> TagEditFailure(TagEditFailureCategory.WRITE_ACCESS, "SAF access denied", this)
        is IOException -> TagEditFailure(TagEditFailureCategory.SAVE_FAILED, "Tag edit failed", this)
        else -> TagEditFailure(TagEditFailureCategory.SAVE_FAILED, "Tag edit failed", this)
    }

    private fun updateOptionalField(
        tag: org.jaudiotagger.tag.Tag,
        fieldKey: FieldKey,
        value: String?
    ) {
        if (!value.isNullOrBlank()) {
            tag.setField(fieldKey, value)
        } else {
            try {
                tag.deleteField(fieldKey)
            } catch (_: Exception) {
                // Some tag formats do not support deleting every field.
            }
        }
    }


    companion object {
        const val MAX_RECOVERY_COPIES = 3
        val SUPPORTED_FORMATS = setOf("flac", "mp3", "m4a", "wav", "ogg", "opus")
    }
}

private fun writeRecoveryMetadata(
    destination: File,
    originalUri: Uri,
    recoveryFile: File,
    createdAt: Long,
    reason: String
) {
    val atomicFile = AtomicFile(destination)
    val output = atomicFile.startWrite()
    try {
        val json = JSONObject()
            .put("originalUri", originalUri.toString())
            .put("createdAt", createdAt)
            .put("reason", reason)
            .put("recoveryFile", recoveryFile.name)
        output.write(json.toString().toByteArray(Charsets.UTF_8))
        atomicFile.finishWrite(output)
    } catch (e: Exception) {
        atomicFile.failWrite(output)
        throw e
    }
}

private class RecoveryCopyException(cause: IOException) : IOException(cause)

/**
 * SAF does not offer a generic atomic replace operation. Keep an original recovery copy before
 * opening the destination in truncating mode, then require a complete write and matching readback
 * before reporting success. The caller retains the recovery copy if this function fails.
 */
internal fun writeSafDocumentAndVerify(
    original: File,
    replacement: File,
    recoveryFile: File,
    openOutput: () -> OutputStream?,
    openInput: () -> InputStream?,
    recoveryPrepared: Boolean = false
) {
    if (!recoveryPrepared) createVerifiedRecoveryCopy(original, recoveryFile)
    var written = 0L
    openOutput()?.use { output ->
        replacement.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
                written += count
            }
        }
        output.flush()
    } ?: throw IOException("Unable to open destination for writing")

    if (written != replacement.length()) {
        throw IOException("Short write: wrote $written of ${replacement.length()} bytes")
    }
    val expectedDigest = sha256(replacement.inputStream())
    val actualDigest = openInput()?.use(::sha256)
        ?: throw IOException("Unable to verify destination after writing")
    if (!expectedDigest.contentEquals(actualDigest)) {
        throw IOException("Destination verification did not match the edited file")
    }
    check(original.isFile) { "Original recovery source is missing" }
}

private fun createVerifiedRecoveryCopy(original: File, recoveryFile: File) {
    try {
        FileInputStream(original).use { input ->
            FileOutputStream(recoveryFile, false).use { output ->
                val copiedBytes = input.copyTo(output)
                output.fd.sync()
                if (copiedBytes != original.length() || recoveryFile.length() != original.length()) {
                    throw IOException("Recovery copy is incomplete")
                }
            }
        }
        if (!sha256(original.inputStream()).contentEquals(sha256(recoveryFile.inputStream()))) {
            throw IOException("Recovery copy verification failed")
        }
    } catch (e: Exception) {
        recoveryFile.delete()
        if (e is CancellationException) throw e
        throw RecoveryCopyException(if (e is IOException) e else IOException("Recovery copy failed", e))
    }
}

internal fun restoreSafDocumentAndVerify(
    recoveryFile: File,
    openOutput: () -> OutputStream?,
    openInput: () -> InputStream?
) {
    var written = 0L
    openOutput()?.use { output ->
        recoveryFile.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
                written += count
            }
        }
        output.flush()
    } ?: throw IOException("Unable to open destination for recovery")
    if (written != recoveryFile.length()) throw IOException("Incomplete recovery write")
    val expected = sha256(recoveryFile.inputStream())
    val actual = openInput()?.use(::sha256) ?: throw IOException("Unable to verify recovery write")
    if (!expected.contentEquals(actual)) throw IOException("Recovered source did not match")
}

private fun sha256(input: InputStream): ByteArray = input.use { stream ->
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val count = stream.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
    }
    digest.digest()
}
