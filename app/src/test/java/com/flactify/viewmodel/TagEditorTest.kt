package com.flactify.viewmodel

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.nio.file.Files
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TagEditorTest {
    private lateinit var tempCacheDir: File

    @Before
    fun setUp() {
        tempCacheDir = Files.createTempDirectory("tag-editor-test-").toFile()
    }

    @After
    fun tearDown() {
        tempCacheDir.deleteRecursively()
    }

    @Test
    fun `updateTrackTags returns failure when source URI cannot be read`() = runTest {
        val context = mockk<Context>()
        val resolver = mockk<ContentResolver>()
        val uri = mockk<Uri>()
        every { context.cacheDir } returns tempCacheDir
        every { context.contentResolver } returns resolver
        every { resolver.openInputStream(uri) } returns null

        val result = TagEditor().updateTrackTags(
            context = context,
            trackUri = uri,
            request = TagEditRequest(
                title = "Title",
                artist = "Artist",
                album = "Album",
                trackNumber = "1",
                genre = null,
                year = null,
                composer = null,
                albumArtist = null,
                discNumber = null,
                comment = null,
                artworkBitmap = null
            ),
            fileExtension = "flac"
        )

        assertFalse(result.isSuccess)
        assertTrue(File(tempCacheDir, "flactify-work").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `SAF replacement succeeds only after exact content can be read back`() {
        val original = File(tempCacheDir, "original.flac").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val replacement = File(tempCacheDir, "replacement.flac").apply { writeBytes(byteArrayOf(4, 5, 6, 7)) }
        val destination = ByteArrayOutputStream()

        writeSafDocumentAndVerify(
            original, replacement, File(tempCacheDir, "recovery.flac"),
            openOutput = { destination },
            openInput = { ByteArrayInputStream(destination.toByteArray()) }
        )

        assertTrue(destination.toByteArray().contentEquals(replacement.readBytes()))
        assertTrue("the caller can remove the recovery copy only after verified success", File(tempCacheDir, "recovery.flac").exists())
    }

    @Test
    fun `SAF replacement fails if destination cannot be opened`() {
        val original = File(tempCacheDir, "original.flac").apply { writeBytes(byteArrayOf(1)) }
        val replacement = File(tempCacheDir, "replacement.flac").apply { writeBytes(byteArrayOf(2)) }

        val error = runCatching {
            writeSafDocumentAndVerify(original, replacement, File(tempCacheDir, "recovery.flac"), openOutput = { null }, openInput = { null })
        }.exceptionOrNull()

        assertTrue(error is IOException)
        assertTrue("an unopened destination still leaves a recovery copy", File(tempCacheDir, "recovery.flac").exists())
    }

    @Test
    fun `SAF replacement propagates a destination failure during write`() {
        val original = File(tempCacheDir, "original.flac").apply { writeBytes(byteArrayOf(1, 1, 1, 1)) }
        val replacement = File(tempCacheDir, "replacement.flac").apply { writeBytes(ByteArray(16) { 2 }) }
        val destination = ByteArrayOutputStream()
        val failingOutput = object : OutputStream() {
            override fun write(value: Int) {
                if (destination.size() >= 2) throw IOException("provider interrupted write")
                destination.write(value)
            }

            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                for (index in offset until offset + length) write(buffer[index].toInt())
            }
        }

        val error = runCatching {
            writeSafDocumentAndVerify(original, replacement, File(tempCacheDir, "recovery.flac"), { failingOutput }, { null })
        }.exceptionOrNull()

        assertTrue(error is IOException)
        assertTrue("the original working copy remains available", original.readBytes().contentEquals(byteArrayOf(1, 1, 1, 1)))
        assertTrue("a recovery file is retained after a partial write", File(tempCacheDir, "recovery.flac").exists())
        assertTrue("the retained recovery copy is the complete original", File(tempCacheDir, "recovery.flac").readBytes().contentEquals(original.readBytes()))
        assertTrue("a partial destination must not count as success", !destination.toByteArray().contentEquals(replacement.readBytes()))
    }

    @Test
    fun `SAF destination is not opened when recovery copy cannot be created`() {
        val original = File(tempCacheDir, "original.flac").apply { writeBytes(byteArrayOf(8, 7, 6)) }
        val replacement = File(tempCacheDir, "replacement.flac").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val impossibleRecovery = File(tempCacheDir, "missing-directory/recovery.flac")
        var destinationOpened = false

        val error = runCatching {
            writeSafDocumentAndVerify(
                original, replacement, impossibleRecovery,
                openOutput = { destinationOpened = true; ByteArrayOutputStream() },
                openInput = { ByteArrayInputStream(replacement.readBytes()) }
            )
        }.exceptionOrNull()

        assertTrue(error is IOException)
        assertFalse(destinationOpened)
        assertTrue(original.readBytes().contentEquals(byteArrayOf(8, 7, 6)))
        assertFalse(impossibleRecovery.exists())
    }

    @Test
    fun `SAF replacement rejects mismatched readback`() {
        val original = File(tempCacheDir, "original.flac").apply { writeBytes(byteArrayOf(1)) }
        val replacement = File(tempCacheDir, "replacement.flac").apply { writeBytes(byteArrayOf(2, 3)) }
        val destination = ByteArrayOutputStream()

        val error = runCatching {
            writeSafDocumentAndVerify(
                original, replacement, File(tempCacheDir, "recovery.flac"),
                openOutput = { destination },
                openInput = { ByteArrayInputStream(byteArrayOf(9, 9)) }
            )
        }.exceptionOrNull()

        assertTrue(error is IOException)
    }
}
