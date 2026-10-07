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

class TagEditor {
    suspend fun updateTrackTags(
        context: Context,
        trackUri: Uri,
        request: TagEditRequest,
        fileExtension: String
    ): Result<TagEditResult> = withContext(Dispatchers.IO) {
        var sourceTemp: File? = null
        var editedTemp: File? = null
        try {
            val sourceFile =
                File.createTempFile("edit_source_", ".$fileExtension", LibraryScanner.workDirectory(context))
            sourceTemp = sourceFile
            context.contentResolver.openInputStream(trackUri)?.use { input ->
                FileOutputStream(sourceFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return@withContext Result.failure(IllegalStateException("Unable to read track"))

            val editedFile = File.createTempFile("edit_work_", ".$fileExtension", LibraryScanner.workDirectory(context))
            editedTemp = editedFile
            sourceFile.copyTo(editedFile, overwrite = true)
            val audioFile = AudioFileIO.read(editedFile)
            val tag = audioFile.tag ?: audioFile.createDefaultTag()
            tag.setField(FieldKey.TITLE, request.title)
            tag.setField(FieldKey.ARTIST, request.artist)
            tag.setField(FieldKey.ALBUM, request.album)
            tag.setField(FieldKey.TRACK, request.trackNumber)
            updateOptionalField(tag, FieldKey.GENRE, request.genre)
            updateOptionalField(tag, FieldKey.YEAR, request.year)
            updateOptionalField(tag, FieldKey.COMPOSER, request.composer)
            updateOptionalField(tag, FieldKey.ALBUM_ARTIST, request.albumArtist)
            updateOptionalField(tag, FieldKey.DISC_NO, request.discNumber)
            updateOptionalField(tag, FieldKey.COMMENT, request.comment)

            request.artworkBitmap?.let { bitmap ->
                val stream = java.io.ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
                val artwork = AndroidArtwork()
                artwork.binaryData = stream.toByteArray()
                tag.deleteArtworkField()
                tag.setField(artwork)
            }

            audioFile.commit()
            val recoveryDirectory = TagRecoveryManager.recoveryDirectory(context).apply { mkdirs() }
            TagRecoveryManager.ensureCapacity(context, MAX_RECOVERY_COPIES)
            val recoveryFile = File.createTempFile(
                "recovery_", ".${fileExtension.takeIf { it.matches(Regex("[A-Za-z0-9]{1,10}")) } ?: "bin"}",
                recoveryDirectory
            )
            val recoveryMetadata = File(recoveryDirectory, "${recoveryFile.name}.json")
            val createdAt = System.currentTimeMillis()
            try {
                createVerifiedRecoveryCopy(sourceFile, recoveryFile)
            } catch (e: Exception) {
                recoveryFile.delete()
                if (e is CancellationException) throw e
                throw IOException("Tag edit cancelled because a complete recovery copy could not be created; original was not modified", e)
            }
            try {
                writeRecoveryMetadata(recoveryMetadata, trackUri, recoveryFile, createdAt, "Tag write pending")
            } catch (e: Exception) {
                recoveryFile.delete()
                if (e is CancellationException) throw e
                throw IOException("Tag edit cancelled because recovery details could not be saved; original was not modified", e)
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
                else writeRecoveryMetadata(recoveryMetadata, trackUri, recoveryFile, createdAt, "Edit succeeded; recovery copy retained")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                runCatching {
                    writeRecoveryMetadata(
                        recoveryMetadata, trackUri, recoveryFile, createdAt,
                        "Write or verification failed: ${e.javaClass.simpleName}"
                    )
                }
                throw IOException("Tag write failed. Recovery copy retained at ${recoveryFile.absolutePath}", e)
            }

            Result.success(TagEditResult(audioFile.tag?.firstArtwork?.binaryData))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            sourceTemp?.delete()
            editedTemp?.delete()
        }
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

    private companion object {
        const val MAX_RECOVERY_COPIES = 3
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
