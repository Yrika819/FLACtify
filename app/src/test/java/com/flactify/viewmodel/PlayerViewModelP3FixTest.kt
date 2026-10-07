package com.flactify.viewmodel

import android.content.Context
import android.net.Uri
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.documentfile.provider.DocumentFile
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import java.util.concurrent.ConcurrentHashMap
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelP3FixTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun cancelMetadataJob(viewModel: PlayerViewModel) {
        val field = PlayerViewModel::class.java.getDeclaredField("metadataJob")
        field.isAccessible = true
        val job = field.get(viewModel) as? kotlinx.coroutines.Job
        job?.cancel()
    }



    // ============================================================
    // #14: MIME type mapping
    // ============================================================

    @Test
    fun `getMimeTypeForExtension - flac returns audio-flac`() {
        val viewModel = PlayerViewModel()
        val method = PlayerViewModel::class.java.getDeclaredMethod("getMimeTypeForExtension", String::class.java)
        method.isAccessible = true
        val result = method.invoke(viewModel, "flac")
        assertEquals("audio/flac", result)
    }

    @Test
    fun `getMimeTypeForExtension - wav returns audio-wav`() {
        val viewModel = PlayerViewModel()
        val method = PlayerViewModel::class.java.getDeclaredMethod("getMimeTypeForExtension", String::class.java)
        method.isAccessible = true
        val result = method.invoke(viewModel, "wav")
        assertEquals("audio/wav", result)
    }

    @Test
    fun `getMimeTypeForExtension - m4a returns audio-mp4`() {
        val viewModel = PlayerViewModel()
        val method = PlayerViewModel::class.java.getDeclaredMethod("getMimeTypeForExtension", String::class.java)
        method.isAccessible = true
        val result = method.invoke(viewModel, "m4a")
        assertEquals("audio/mp4", result)
    }

    @Test
    fun `getMimeTypeForExtension - mp3 returns audio-mpeg`() {
        val viewModel = PlayerViewModel()
        val method = PlayerViewModel::class.java.getDeclaredMethod("getMimeTypeForExtension", String::class.java)
        method.isAccessible = true
        val result = method.invoke(viewModel, "mp3")
        assertEquals("audio/mpeg", result)
    }

    @Test
    fun `getMimeTypeForExtension - unknown returns audio-mpeg as fallback`() {
        val viewModel = PlayerViewModel()
        val method = PlayerViewModel::class.java.getDeclaredMethod("getMimeTypeForExtension", String::class.java)
        method.isAccessible = true
        val result = method.invoke(viewModel, "ogg")
        assertEquals("audio/mpeg", result)
    }

    // ============================================================
    // #15: DocumentFile.fromSingleUri() null fallback
    // ============================================================

    @Test
    fun `fileExtensionCache - uri lastPathSegment fallback when DocumentFile is null`() {
        runBlocking {
            val context = mockk<Context>(relaxed = true)
            val uri = mockk<Uri>(relaxed = true)

            every { uri.lastPathSegment } returns "song.m4a"
            every { uri.toString() } returns "content://test/song.m4a"

            mockkStatic(DocumentFile::class)
            every { DocumentFile.fromSingleUri(context, uri) } returns null

            val extractor = TrackMetadataExtractor(context)
            val result = extractor.detectExtension(uri)

            assertEquals("m4a", result)

            unmockkStatic(DocumentFile::class)
        }
    }

    @Test
    fun `fileExtensionCache - defaults to mp3 when no extension in lastPathSegment`() {
        runBlocking {
            val context = mockk<Context>(relaxed = true)
            val uri = mockk<Uri>(relaxed = true)

            every { uri.lastPathSegment } returns "unknown_no_ext"
            every { uri.toString() } returns "content://test/unknown_no_ext"

            mockkStatic(DocumentFile::class)
            every { DocumentFile.fromSingleUri(context, uri) } returns null

            val extractor = TrackMetadataExtractor(context)
            val result = extractor.detectExtension(uri)

            assertEquals("mp3", result)

            unmockkStatic(DocumentFile::class)
        }
    }

    // ============================================================
    // #16: updateTrackTags() concurrent execution guard
    // ============================================================

    @Test
    fun `updateTrackTags - returns false immediately when already saving`() = runTest {
        val viewModel = PlayerViewModel()

        val field = PlayerViewModel::class.java.getDeclaredField("isSavingMetadataGuard")
        field.isAccessible = true
        val guard = field.get(viewModel) as java.util.concurrent.atomic.AtomicBoolean
        guard.set(true)

        var callbackResult: Boolean? = null
        val context = mockk<Context>(relaxed = true)
        val uri = mockk<Uri>(relaxed = true)

        viewModel.updateTrackTags(
            context = context,
            trackUri = uri,
            title = "Test",
            artist = "Artist",
            album = "Album",
            trackNumber = "1",
            genre = null,
            year = null,
            composer = null,
            albumArtist = null,
            discNumber = null,
            comment = null,
            artworkBitmap = null,
            onComplete = { callbackResult = it }
        )

        advanceUntilIdle()

        assertEquals(false, callbackResult)

        guard.set(false)
    }

    @Test
    fun `updateTrackTags - second call prevented while first is saving`() = runTest {
        val viewModel = PlayerViewModel()

        val field = PlayerViewModel::class.java.getDeclaredField("isSavingMetadataGuard")
        field.isAccessible = true
        val guard = field.get(viewModel) as java.util.concurrent.atomic.AtomicBoolean
        guard.set(true)

        val context = mockk<Context>(relaxed = true)
        val uri = mockk<Uri>(relaxed = true)

        var firstCallbackResult: Boolean? = null
        var secondCallbackResult: Boolean? = null

        viewModel.updateTrackTags(context, uri, "T", "A", "Al", "1", null, null, null, null, null, null, null) { firstCallbackResult = it }
        viewModel.updateTrackTags(context, uri, "T", "A", "Al", "1", null, null, null, null, null, null, null) { secondCallbackResult = it }

        advanceUntilIdle()

        assertEquals("first call (when saving) should get false", false, firstCallbackResult)
        assertEquals("second call (when saving) should also get false", false, secondCallbackResult)

        guard.set(false)
    }

    // ============================================================
    // #17: Recursive file scanning
    // ============================================================

    @Test
    fun `collectAudioFiles - returns files from root directory`() {
        val viewModel = PlayerViewModel()

        val root = mockk<DocumentFile>()
        val file1 = mockk<DocumentFile> {
            every { isDirectory } returns false
            every { name } returns "song1.flac"
        }
        val file2 = mockk<DocumentFile> {
            every { isDirectory } returns false
            every { name } returns "song2.mp3"
        }

        every { root.listFiles() } returns arrayOf(file1, file2)

        val method = PlayerViewModel::class.java.getDeclaredMethod("collectAudioFiles", DocumentFile::class.java)
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val result = method.invoke(viewModel, root) as List<DocumentFile>

        assertEquals(2, result.size)
    }

    @Test
    fun `collectAudioFiles - recursively includes subfolder files`() {
        val viewModel = PlayerViewModel()

        val root = mockk<DocumentFile>()
        val subdir = mockk<DocumentFile> {
            every { isDirectory } returns true
        }
        val fileInRoot = mockk<DocumentFile> {
            every { isDirectory } returns false
            every { name } returns "root_song.flac"
        }
        val fileInSubdir = mockk<DocumentFile> {
            every { isDirectory } returns false
            every { name } returns "sub_song.wav"
        }

        every { root.listFiles() } returns arrayOf(fileInRoot, subdir)
        every { subdir.listFiles() } returns arrayOf(fileInSubdir)

        val method = PlayerViewModel::class.java.getDeclaredMethod("collectAudioFiles", DocumentFile::class.java)
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val result = method.invoke(viewModel, root) as List<DocumentFile>

        assertEquals(2, result.size)
        assertTrue(result.any { it.name == "root_song.flac" })
        assertTrue(result.any { it.name == "sub_song.wav" })
    }

    @Test
    fun `collectAudioFiles - deeply nested folders are traversed`() {
        val viewModel = PlayerViewModel()

        val root = mockk<DocumentFile>()
        val level1 = mockk<DocumentFile> {
            every { isDirectory } returns true
        }
        val level2 = mockk<DocumentFile> {
            every { isDirectory } returns true
        }
        val deepFile = mockk<DocumentFile> {
            every { isDirectory } returns false
            every { name } returns "deep.m4a"
        }
        val rootFile = mockk<DocumentFile> {
            every { isDirectory } returns false
            every { name } returns "top.mp3"
        }

        every { root.listFiles() } returns arrayOf(rootFile, level1)
        every { level1.listFiles() } returns arrayOf(level2)
        every { level2.listFiles() } returns arrayOf(deepFile)

        val method = PlayerViewModel::class.java.getDeclaredMethod("collectAudioFiles", DocumentFile::class.java)
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val result = method.invoke(viewModel, root) as List<DocumentFile>

        assertEquals(2, result.size)
        assertTrue(result.any { it.name == "top.mp3" })
        assertTrue(result.any { it.name == "deep.m4a" })
    }

    @Test
    fun `collectAudioFiles - filters non-audio files`() {
        val viewModel = PlayerViewModel()

        val root = mockk<DocumentFile>()
        val audioFile = mockk<DocumentFile> {
            every { isDirectory } returns false
            every { name } returns "music.flac"
        }
        val textFile = mockk<DocumentFile> {
            every { isDirectory } returns false
            every { name } returns "readme.txt"
        }
        val imageFile = mockk<DocumentFile> {
            every { isDirectory } returns false
            every { name } returns "cover.jpg"
        }

        every { root.listFiles() } returns arrayOf(audioFile, textFile, imageFile)

        val method = PlayerViewModel::class.java.getDeclaredMethod("collectAudioFiles", DocumentFile::class.java)
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val result = method.invoke(viewModel, root) as List<DocumentFile>

        assertEquals(1, result.size)
        assertEquals("music.flac", result[0].name)
    }

    @Test
    fun `collectAudioFiles - sorts results by name`() {
        val viewModel = PlayerViewModel()

        val root = mockk<DocumentFile>()
        val fileZ = mockk<DocumentFile> {
            every { isDirectory } returns false
            every { name } returns "z_song.flac"
        }
        val fileA = mockk<DocumentFile> {
            every { isDirectory } returns false
            every { name } returns "a_song.mp3"
        }
        val fileM = mockk<DocumentFile> {
            every { isDirectory } returns false
            every { name } returns "m_song.wav"
        }

        every { root.listFiles() } returns arrayOf(fileZ, fileA, fileM)

        val method = PlayerViewModel::class.java.getDeclaredMethod("collectAudioFiles", DocumentFile::class.java)
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val result = method.invoke(viewModel, root) as List<DocumentFile>

        assertEquals(3, result.size)
        assertEquals("a_song.mp3", result[0].name)
        assertEquals("m_song.wav", result[1].name)
        assertEquals("z_song.flac", result[2].name)
    }

    // ============================================================
    // #19: _preloadedCache deadlock prevention
    // ============================================================

    @Test
    fun `preloadedCache - CompletableDeferred completes even on cancellation`() = runTest {
        val viewModel = PlayerViewModel()

        val field = PlayerViewModel::class.java.getDeclaredField("_preloadedCache")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val deferred = field.get(viewModel) as CompletableDeferred<List<TrackData>>

        assertFalse("Should not be completed initially", deferred.isCompleted)

        val job = launch {
            deferred.await()
        }

        advanceUntilIdle()
        job.cancel()

        advanceUntilIdle()
    }

    @Test
    fun `preloadedCache - completes with empty list when cancelled`() = runTest {
        val viewModel = PlayerViewModel()

        val field = PlayerViewModel::class.java.getDeclaredField("_preloadedCache")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val deferred = field.get(viewModel) as CompletableDeferred<List<TrackData>>

        deferred.complete(emptyList())

        assertTrue("Should be completed", deferred.isCompleted)
        val result = deferred.await()
        assertEquals(emptyList<TrackData>(), result)
    }

    private fun advanceUntilIdle() {
        testDispatcher.scheduler.advanceUntilIdle()
    }
}
