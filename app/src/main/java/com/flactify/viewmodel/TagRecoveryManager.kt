package com.flactify.viewmodel

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** Owns user-manageable tag-edit recovery copies in private, non-backed-up storage. */
class TagRecoveryManager(
    private val context: Context,
    private val migrationCopy: (File, File) -> Unit = { source, destination ->
        source.copyTo(
            destination,
            overwrite = true
        )
    }
) {
    data class Entry(
        val id: String,
        val originalIdentifier: String,
        val createdAt: Long?,
        val failureReason: String,
        val audioFile: File?,
        val metadataFile: File?,
        val metadataValid: Boolean
    ) {
        val sizeBytes: Long get() = audioFile?.takeIf(File::isFile)?.length() ?: 0L
    }

    fun entries(): List<Entry> {
        migrateLegacyEntries()
        return recoveryDirectories(context).flatMap { directory ->
            val files = directory.listFiles()?.toList().orEmpty()
            val audioFiles = files.filter { it.isFile && it.name.startsWith("recovery_") && !it.name.endsWith(".json") }
            val metadataFiles =
                files.filter { it.isFile && it.name.startsWith("recovery_") && it.name.endsWith(".json") }
            val ids = (audioFiles.map { it.name } + metadataFiles.map { it.name.removeSuffix(".json") }).toSet()
            ids.map { id ->
                val audio = audioFiles.firstOrNull { it.name == id }
                val metadata = metadataFiles.firstOrNull { it.name == "$id.json" }
                val parsed = metadata?.let { parseMetadata(it, id, audio) }
                Entry(
                    id = "${directory.absolutePath}/$id",
                    originalIdentifier = parsed?.first ?: "Unknown document",
                    createdAt = parsed?.second,
                    failureReason = parsed?.third
                        ?: if (audio == null) "Recovery audio is missing" else "Recovery metadata is missing or invalid",
                    audioFile = audio,
                    metadataFile = metadata,
                    metadataValid = parsed != null
                )
            }
        }.sortedByDescending { it.createdAt ?: 0L }
    }

    private fun migrateLegacyEntries() {
        val legacyDirectory = File(context.filesDir, "tag-recovery")
        val destinationDirectory = recoveryDirectory(context)
        val legacyFiles = legacyDirectory.listFiles()?.toList().orEmpty()
        val audioFiles =
            legacyFiles.filter { it.isFile && it.name.startsWith("recovery_") && !it.name.endsWith(".json") }
        for (sourceAudio in audioFiles) {
            val id = sourceAudio.name
            val sourceMetadata = File(legacyDirectory, "$id.json")
            val targetAudio = File(destinationDirectory, id)
            val targetMetadata = File(destinationDirectory, "$id.json")
            if (!sourceMetadata.isFile || parseMetadata(sourceMetadata, id, sourceAudio) == null) continue
            if (!destinationDirectory.exists() && !destinationDirectory.mkdirs()) continue
            try {
                if (targetAudio.exists() && !targetAudio.readBytes().contentEquals(sourceAudio.readBytes())) continue
                if (targetMetadata.exists() && !targetMetadata.readBytes()
                        .contentEquals(sourceMetadata.readBytes())
                ) continue
                if (!targetAudio.exists()) {
                    val stagedAudio = File.createTempFile("migration_", ".audio", destinationDirectory)
                    try {
                        migrationCopy(sourceAudio, stagedAudio)
                        if (!stagedAudio.readBytes()
                                .contentEquals(sourceAudio.readBytes())
                        ) throw IOException("Recovery audio migration did not verify")
                        if (!stagedAudio.renameTo(targetAudio)) throw IOException("Unable to commit recovery audio migration")
                    } finally {
                        stagedAudio.delete()
                    }
                }
                if (!targetMetadata.exists()) {
                    val stagedMetadata = File.createTempFile("migration_", ".metadata", destinationDirectory)
                    try {
                        migrationCopy(sourceMetadata, stagedMetadata)
                        if (!stagedMetadata.readBytes().contentEquals(sourceMetadata.readBytes()) || parseMetadata(
                                stagedMetadata,
                                id,
                                targetAudio
                            ) == null
                        ) {
                            throw IOException("Recovery metadata migration did not verify")
                        }
                        if (!stagedMetadata.renameTo(targetMetadata)) throw IOException("Unable to commit recovery metadata migration")
                    } finally {
                        stagedMetadata.delete()
                    }
                }
                if (parseMetadata(targetMetadata, id, targetAudio) != null &&
                    targetAudio.readBytes().contentEquals(sourceAudio.readBytes()) &&
                    targetMetadata.readBytes().contentEquals(sourceMetadata.readBytes())
                ) {
                    sourceAudio.delete()
                    sourceMetadata.delete()
                }
            } catch (_: Exception) {
                // Keep legacy material visible and retry migration on the next enumeration.
            }
        }
    }

    fun delete(entry: Entry): Boolean {
        val audioDeleted = entry.audioFile?.let { !it.exists() || it.delete() } ?: true
        val metadataDeleted = entry.metadataFile?.let { !it.exists() || it.delete() } ?: true
        return audioDeleted && metadataDeleted
    }

    /** Copies the full private recovery file; success is reported only after output closes. */
    fun export(entry: Entry, destination: Uri) {
        export(entry) { context.contentResolver.openOutputStream(destination, "w") }
    }

    internal fun export(entry: Entry, openDestination: () -> java.io.OutputStream?) {
        val source = entry.audioFile?.takeIf(File::isFile)
            ?: throw IOException("Recovery audio is unavailable")
        val output = openDestination() ?: throw IOException("Unable to open selected export destination")
        output.use { destinationStream ->
            source.inputStream().use { input ->
                val copied = input.copyTo(destinationStream)
                destinationStream.flush()
                if (copied != source.length()) throw IOException("Recovery export was incomplete")
            }
        }
    }

    private fun parseMetadata(file: File, id: String, audio: File?): Triple<String, Long, String>? {
        return try {
            val json = JSONObject(file.readText())
            val recoveryName = json.optString("recoveryFile")
            val original = json.optString("originalUri").takeIf { it.isNotBlank() }
            val created = json.optLong("createdAt", 0L).takeIf { it > 0L }
            val reason = json.optString("reason").takeIf { it.isNotBlank() }
            if (recoveryName != id || audio == null || original == null || created == null || reason == null) null
            else Triple(original, created, reason)
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        fun recoveryDirectory(context: Context): File = File(context.noBackupFilesDir, "tag-recovery")

        internal fun recoveryDirectories(context: Context): List<File> = listOf(
            recoveryDirectory(context),
            File(context.filesDir, "tag-recovery")
        ).distinctBy { it.absolutePath }

        internal fun ensureCapacity(context: Context, maximum: Int) {
            val directory = recoveryDirectory(context)
            if (!directory.exists() && !directory.mkdirs()) {
                throw IOException("Unable to create tag recovery directory")
            }
            val audioFiles = recoveryDirectories(context).flatMap { it.listFiles()?.toList().orEmpty() }
                .count { it.isFile && it.name.startsWith("recovery_") && !it.name.endsWith(".json") }
            if (audioFiles >= maximum) {
                throw IOException("Tag recovery limit reached. Export or delete recovery entries in Settings before editing another file.")
            }
        }
    }
}
