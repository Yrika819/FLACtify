package com.flactify.viewmodel

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class LibraryScannerOpusTest {
    private val uriCache = ConcurrentHashMap<String, Uri>()

    @Test
    fun `initial scan and incremental scan recognize Opus by content and ignore corrupt mixture`() = runBlocking {
        val context = mockk<Context>(relaxed = true)
        val resolver = mockk<ContentResolver>(relaxed = true)
        val tempDir = kotlin.io.path.createTempDirectory("opus-library-scan").toFile()
        every { context.contentResolver } returns resolver
        every { context.cacheDir } returns tempDir

        val folderUri = uriFor("music")
        val root = mockk<DocumentFile>()
        every { root.exists() } returns true
        every { root.isDirectory } returns true
        val fixtures = linkedMapOf(
            "tagged.opus" to File("src/test/resources/audio/opus_tagged.opus").readBytes(),
            "opus-as-ogg.ogg" to File("src/test/resources/audio/opus_untagged.opus").readBytes(),
            "opus-as-m4a.m4a" to File("src/test/resources/audio/opus_untagged.opus").readBytes(),
            "aac.m4a" to File("src/test/resources/audio/aac.m4a").readBytes(),
            "flac.flac" to File("src/test/resources/audio/flac_44100_16_stereo.flac").readBytes(),
            "mp3.mp3" to File("src/test/resources/audio/synthetic.mp3").readBytes(),
            "wav.wav" to File("src/test/resources/audio/synthetic.wav").readBytes(),
            "broken.opus" to File("src/test/resources/audio/corrupt.opus").readBytes()
        )
        val documents = fixtures.map { (name, bytes) -> document(name, bytes, 100L, resolver) }
        every { root.listFiles() } returns documents.toTypedArray()
        mockkStatic(DocumentFile::class)
        every { DocumentFile.fromTreeUri(context, folderUri) } returns root

        val extractor = TrackMetadataExtractor(context)
        val scanner = LibraryScanner(extractor)
        try {
            val initial = scanner.scan(
                context = context,
                folderUri = folderUri,
                cachedTracks = emptyList(),
                persistIfCurrent = {}
            )
            assertTrue(initial.scanSucceeded)
            assertEquals(7, initial.tracks.size)
            assertFalse(initial.tracks.any { it.title == "broken" })
            assertEquals("Synthetic Opus", initial.tracks.first { it.uri == uriFor("tagged.opus") }.title)
            assertEquals("opus", extractor.fileExtensionCache[uriFor("opus-as-m4a.m4a")])
            assertEquals("opus", extractor.fileExtensionCache[uriFor("opus-as-ogg.ogg")])
            assertEquals("m4a", extractor.fileExtensionCache[uriFor("aac.m4a")])

            val addedBytes = File("src/test/resources/audio/opus_untagged.opus").readBytes()
            val added = document("added-without-audio-suffix.bin", addedBytes, 200L, resolver)
            every { root.listFiles() } returns (documents + added).toTypedArray()
            val incremental = scanner.scan(
                context = context,
                folderUri = folderUri,
                cachedTracks = initial.tracks,
                persistIfCurrent = {}
            )
            assertTrue(incremental.scanSucceeded)
            assertTrue(incremental.changed)
            assertEquals(8, incremental.tracks.size)
            assertTrue(incremental.tracks.any { it.uri == uriFor("added-without-audio-suffix.bin") })
            assertEquals("opus", extractor.fileExtensionCache[uriFor("added-without-audio-suffix.bin")])
        } finally {
            extractor.fileExtensionCache.clear()
            unmockkStatic(DocumentFile::class)
            tempDir.deleteRecursively()
        }
    }

    private fun document(
        name: String,
        bytes: ByteArray,
        modified: Long,
        resolver: ContentResolver
    ): DocumentFile {
        val uri = uriFor(name)
        val file = mockk<DocumentFile>()
        every { file.isDirectory } returns false
        every { file.name } returns name
        every { file.uri } returns uri
        every { file.lastModified() } returns modified
        every { resolver.openInputStream(uri) } answers { ByteArrayInputStream(bytes) }
        return file
    }

    private fun uriFor(name: String): Uri = uriCache.getOrPut(name) {
        val value = mockk<Uri>(relaxed = true)
        val uriString = "content://synthetic-library/$name"
        every { value.toString() } returns uriString
        every { value.equals(any()) } answers { firstArg<Any>() === value }
        every { value.hashCode() } returns uriString.hashCode()
        value
    }
}
