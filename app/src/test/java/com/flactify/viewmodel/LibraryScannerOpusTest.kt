package com.flactify.viewmodel

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

class LibraryScannerOpusTest {
    private val uriCache = ConcurrentHashMap<String, Uri>()

    @Test
    fun `metadata copy refuses input beyond its temporary file budget`() {
        val output = ByteArrayOutputStream()
        assertThrows(IOException::class.java) {
            LibraryScanner.copyBoundedMetadata(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)), output, maxBytes = 3)
        }
        assertTrue(output.size() <= 3)
    }

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

    @Test
    fun `temporary format read failure retains cached track while unsupported content is excluded`() = runBlocking {
        val context = mockk<Context>(relaxed = true)
        val resolver = mockk<ContentResolver>(relaxed = true)
        val tempDir = kotlin.io.path.createTempDirectory("opus-scan-read-failure").toFile()
        every { context.contentResolver } returns resolver
        every { context.cacheDir } returns tempDir

        val folderUri = uriFor("read-failure-music")
        val root = mockk<DocumentFile>()
        every { root.exists() } returns true
        every { root.isDirectory } returns true
        val unavailable = document("temporarily-unavailable.opus", byteArrayOf(), 200L, resolver)
        val unsupported = document("renamed-to-audio.flac", "not an audio container".toByteArray(), 200L, resolver)
        every { resolver.openInputStream(unavailable.uri) } throws IOException("provider temporarily unavailable")
        every { root.listFiles() } returns arrayOf(unavailable, unsupported)
        mockkStatic(DocumentFile::class)
        every { DocumentFile.fromTreeUri(context, folderUri) } returns root

        val cachedUnavailable = TrackData(
            title = "Previously cached song",
            artist = "Synthetic artist",
            originalIndex = 0,
            uri = unavailable.uri,
            lastModified = 100L
        )
        val cachedUnsupported = TrackData(
            title = "No longer audio",
            artist = "Synthetic artist",
            originalIndex = 1,
            uri = unsupported.uri,
            lastModified = 100L
        )
        var persisted = false
        try {
            val result = LibraryScanner(TrackMetadataExtractor(context)).scan(
                context = context,
                folderUri = folderUri,
                cachedTracks = listOf(cachedUnavailable, cachedUnsupported),
                persistIfCurrent = { persisted = true }
            )

            assertFalse(result.scanSucceeded)
            assertEquals(listOf("temporarily-unavailable.opus"), result.failedFiles)
            assertEquals(listOf("Previously cached song"), result.tracks.map { it.title })
            assertEquals(unavailable.uri, result.tracks.single().uri)
            assertFalse(persisted)
        } finally {
            unmockkStatic(DocumentFile::class)
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `unchanged cached format does not reopen SAF provider`() = runBlocking {
        val context = mockk<Context>(relaxed = true)
        val resolver = mockk<ContentResolver>(relaxed = true)
        val folderUri = uriFor("cached-format-music")
        val cachedUri = uriFor("renamed-opus.bin")
        val root = mockk<DocumentFile>()
        val document = mockk<DocumentFile>()
        every { context.contentResolver } returns resolver
        every { root.exists() } returns true
        every { root.isDirectory } returns true
        every { root.listFiles() } returns arrayOf(document)
        every { document.isDirectory } returns false
        every { document.name } returns "renamed-opus.bin"
        every { document.uri } returns cachedUri
        every { document.lastModified() } returns 200L
        every { resolver.openInputStream(cachedUri) } throws IOException("must not read unchanged cache")
        mockkStatic(DocumentFile::class)
        every { DocumentFile.fromTreeUri(context, folderUri) } returns root

        val cached = TrackData(
            title = "Cached Opus",
            artist = "Synthetic artist",
            originalIndex = 0,
            uri = cachedUri,
            lastModified = 200L,
            formatExtension = "opus"
        )
        try {
            val result = LibraryScanner(null).scan(
                context = context,
                folderUri = folderUri,
                cachedTracks = listOf(cached),
                persistIfCurrent = {}
            )

            assertTrue(result.scanSucceeded)
            assertEquals(listOf("Cached Opus"), result.tracks.map { it.title })
            verify(exactly = 0) { resolver.openInputStream(cachedUri) }
        } finally {
            unmockkStatic(DocumentFile::class)
        }
    }

    @Test
    fun `same timestamp with changed provider size rescans cached entry`() = runBlocking {
        val context = mockk<Context>(relaxed = true)
        val resolver = mockk<ContentResolver>(relaxed = true)
        val tempDir = kotlin.io.path.createTempDirectory("same-mtime-replacement").toFile()
        val folderUri = uriFor("same-mtime-music")
        val replacementBytes = File("src/test/resources/audio/opus_untagged.opus").readBytes()
        every { context.contentResolver } returns resolver
        every { context.cacheDir } returns tempDir
        val root = mockk<DocumentFile>()
        val replacement = document("replacement.opus", replacementBytes, 100L, resolver)
        val replacementUri = uriFor("replacement.opus")
        every { root.exists() } returns true
        every { root.isDirectory } returns true
        every { root.listFiles() } returns arrayOf(replacement)
        mockkStatic(DocumentFile::class)
        every { DocumentFile.fromTreeUri(context, folderUri) } returns root

        val cached = TrackData(
            title = "Stale cached title",
            artist = "Synthetic artist",
            originalIndex = 0,
            uri = replacementUri,
            lastModified = 100L,
            formatExtension = "opus",
            fileSize = replacementBytes.size.toLong() - 1L
        )
        try {
            val result = LibraryScanner(null).scan(
                context = context,
                folderUri = folderUri,
                cachedTracks = listOf(cached),
                persistIfCurrent = {}
            )

            assertTrue(result.scanSucceeded)
            assertTrue(result.changed)
            assertEquals(1, result.tracks.size)
            assertFalse(result.tracks.single().title == "Stale cached title")
            verify(exactly = 2) { resolver.openInputStream(replacementUri) }
        } finally {
            unmockkStatic(DocumentFile::class)
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `full rescan ignores a valid cache entry for the same timestamp and size`() = runBlocking {
        val context = mockk<Context>(relaxed = true)
        val resolver = mockk<ContentResolver>(relaxed = true)
        val tempDir = kotlin.io.path.createTempDirectory("full-rescan").toFile()
        val folderUri = uriFor("full-rescan-music")
        val bytes = File("src/test/resources/audio/opus_untagged.opus").readBytes()
        every { context.contentResolver } returns resolver
        every { context.cacheDir } returns tempDir
        val root = mockk<DocumentFile>()
        val trackFile = document("rescan.opus", bytes, 100L, resolver)
        val trackUri = uriFor("rescan.opus")
        every { root.exists() } returns true
        every { root.isDirectory } returns true
        every { root.listFiles() } returns arrayOf(trackFile)
        mockkStatic(DocumentFile::class)
        every { DocumentFile.fromTreeUri(context, folderUri) } returns root
        try {
            val scanner = LibraryScanner(null)
            val initial = scanner.scan(context, folderUri, cachedTracks = emptyList(), persistIfCurrent = {})
            assertTrue(initial.scanSucceeded)
            verify(exactly = 2) { resolver.openInputStream(trackUri) }

            val refreshed = scanner.fullRescan(context, folderUri, persistIfCurrent = {})
            assertTrue(refreshed.scanSucceeded)
            assertEquals(listOf(trackUri), refreshed.tracks.map { it.uri })
            verify(exactly = 4) { resolver.openInputStream(trackUri) }
        } finally {
            unmockkStatic(DocumentFile::class)
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `legacy cache without size or format hint remains readable`() = runBlocking {
        val context = mockk<Context>(relaxed = true)
        val resolver = mockk<ContentResolver>(relaxed = true)
        val folderUri = uriFor("legacy-cache-music")
        val bytes = File("src/test/resources/audio/opus_untagged.opus").readBytes()
        val root = mockk<DocumentFile>()
        val document = document("legacy.opus", bytes, 100L, resolver)
        val documentUri = uriFor("legacy.opus")
        every { context.contentResolver } returns resolver
        every { root.exists() } returns true
        every { root.isDirectory } returns true
        every { root.listFiles() } returns arrayOf(document)
        mockkStatic(DocumentFile::class)
        every { DocumentFile.fromTreeUri(context, folderUri) } returns root

        val cached = TrackData(
            title = "Legacy cached title",
            artist = "Synthetic artist",
            originalIndex = 0,
            uri = documentUri,
            lastModified = 100L
        )
        try {
            val result = LibraryScanner(null).scan(
                context = context,
                folderUri = folderUri,
                cachedTracks = listOf(cached),
                persistIfCurrent = {}
            )

            assertTrue(result.scanSucceeded)
            assertEquals("Legacy cached title", result.tracks.single().title)
            assertEquals("opus", result.tracks.single().formatExtension)
            assertEquals(bytes.size.toLong(), result.tracks.single().fileSize)
            verify(exactly = 1) { resolver.openInputStream(documentUri) }
        } finally {
            unmockkStatic(DocumentFile::class)
        }
    }

    @Test
    fun `legacy cache json without provider fields still loads`() {
        val context = mockk<Context>(relaxed = true)
        val tempDir = kotlin.io.path.createTempDirectory("legacy-cache-json").toFile()
        every { context.cacheDir } returns tempDir
        every { context.filesDir } returns tempDir
        val cacheDir = File(tempDir, "flactify-library").apply { mkdirs() }
        val folderUri = uriFor("legacy-json-folder")
        val trackUri = uriFor("legacy-json-track")
        mockkStatic(Uri::class)
        every { Uri.parse("content://synthetic-library/folder") } returns folderUri
        every { Uri.parse("content://synthetic-library/song.opus") } returns trackUri
        File(cacheDir, LibraryScanner.CACHE_FILE_NAME).writeText(
            """
            {"version":1,"folderUri":"content://synthetic-library/folder","data":[
              {"t":"Old","ar":"Artist","al":"Album","u":"content://synthetic-library/song.opus","lm":100}
            ]}
            """.trimIndent()
        )

        try {
            val snapshot = LibraryScanner(null).loadCacheSnapshot(context)
            assertTrue(snapshot != null)
            val track = snapshot!!.tracks.single()
            assertNull(track.formatExtension)
            assertEquals(-1L, track.fileSize)
        } finally {
            unmockkStatic(Uri::class)
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
        every { file.length() } returns bytes.size.toLong()
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
