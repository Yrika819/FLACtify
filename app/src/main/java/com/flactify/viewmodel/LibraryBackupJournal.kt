package com.flactify.viewmodel

import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Small durable intent journal used to retry a restore interrupted around preference commit. */
internal class LibraryBackupJournal(private val file: File) {
    fun stage(json: String) {
        require(json.length <= MAX_JSON_CHARS) { "Backup journal entry is too large" }
        file.parentFile?.mkdirs()
        val temporary = File(file.path + ".tmp")
        try {
            FileOutputStream(temporary, false).use { output ->
                output.write(json.toByteArray(StandardCharsets.UTF_8))
                output.fd.sync()
            }
            runCatching {
                Files.move(
                    temporary.toPath(), file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            }.getOrElse {
                if (!temporary.renameTo(file)) error("Unable to commit backup journal")
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    fun read(): String? = file.takeIf { it.isFile && it.length() <= MAX_JSON_BYTES }
        ?.readText(StandardCharsets.UTF_8)

    fun clear() {
        file.delete()
        File(file.path + ".tmp").delete()
    }

    private companion object {
        const val MAX_JSON_CHARS = 32 * 1024 * 1024
        const val MAX_JSON_BYTES = 32L * 1024L * 1024L
    }
}
