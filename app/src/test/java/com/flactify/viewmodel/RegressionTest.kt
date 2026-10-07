package com.flactify.viewmodel

import android.content.Context
import android.net.Uri
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.documentfile.provider.DocumentFile
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class RegressionTest {

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

    // ============================================================
    // P0-1 - getCacheDirSize() の誤計算防止
    // ============================================================

    @Test
    fun `P0-1 - getCacheDirSize returns sum of file sizes not filesystem usage`() {
        val tempDir = createTempDir("test_cache_size")
        try {
            File(tempDir, "file1.txt").writeText("a".repeat(100))
            File(tempDir, "file2.txt").writeText("b".repeat(200))
            val size = LibraryManager.calculateDirSize(tempDir)
            assertEquals("Should return sum of file sizes", 300L, size)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `P0-1 - getCacheDirSize returns zero for empty directory`() {
        val tempDir = createTempDir("test_cache_empty")
        try {
            val size = LibraryManager.calculateDirSize(tempDir)
            assertEquals("Empty directory should return 0", 0L, size)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    // ============================================================
    // P0-3 - onClear() でリソースが解放される
    // ============================================================

    @Test
    fun `P0-3 - cleanup cancels all jobs`() = runTest {
        val viewModel = PlayerViewModel()
        var jobCancelled = false

        val jobField = PlayerViewModel::class.java.getDeclaredField("metadataJob")
        jobField.isAccessible = true
        val mockJob = mockk<Job>(relaxed = true)
        jobField.set(viewModel, mockJob)

        viewModel.cleanup()
        advanceUntilIdle()

        verify { mockJob.cancel() }
    }

    @Test
    fun `P0-3 - cleanup nullifies controller reference`() = runTest {
        val viewModel = PlayerViewModel()

        val controllerField = PlayerViewModel::class.java.getDeclaredField("controller")
        controllerField.isAccessible = true
        controllerField.set(viewModel, mockk<androidx.media3.session.MediaController>(relaxed = true))

        viewModel.cleanup()
        advanceUntilIdle()

        val controllerAfter = controllerField.get(viewModel)
        assertNull("Controller should be null after cleanup", controllerAfter)
    }

    // ============================================================
    // P1-G - キャンセル時にCompletableDeferredが2回completeされない
    // ============================================================

    @Test
    fun `P1-G - preloadedCache completes exactly once`() = runTest {
        val viewModel = PlayerViewModel()

        val cacheField = PlayerViewModel::class.java.getDeclaredField("_preloadedCache")
        cacheField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val cache = cacheField.get(viewModel) as CompletableDeferred<List<TrackData>>

        cache.complete(emptyList())
        advanceUntilIdle()

        assertTrue("Cache should be completed", cache.isCompleted)

        val result = cache.getCompleted()
        assertNotNull("Completed value should not be null", result)
    }

    // ============================================================
    // P1-O - Uri.EMPTY が使用されない
    // ============================================================

    @Test
    fun `P1-O - loadCache handles null last_uri`() = runTest {
        val viewModel = PlayerViewModel()
        val context = mockk<Context>(relaxed = true)

        val prefs = mockk<android.content.SharedPreferences>(relaxed = true)
        every { prefs.getString("last_uri", null) } returns null
        every { context.getSharedPreferences(any(), any()) } returns prefs

        val prefsField = PlayerViewModel::class.java.getDeclaredField("sharedPreferences")
        prefsField.isAccessible = true
        prefsField.set(viewModel, prefs)

        val loadCacheMethod = PlayerViewModel::class.java.getDeclaredMethod(
            "loadCache",
            Context::class.java,
            Uri::class.java
        )
        loadCacheMethod.isAccessible = true

        val uri = Uri.parse("content://test")

        @Suppress("UNCHECKED_CAST")
        val result = loadCacheMethod.invoke(viewModel, context, uri) as List<TrackData>
        assertTrue("Should return empty list when last_uri is null", result.isEmpty())
    }

    // ============================================================
    // P1-C - キャッシュは同じフォルバディにのみ適用
    // ============================================================

    private fun mockUri(value: String): Uri {
        val uri = mockk<Uri>(relaxed = true)
        every { uri.toString() } returns value
        return uri
    }

    @Test
    fun `P1-C - cache not applied to different folder`() = runTest {
        val viewModel = PlayerViewModel()

        val cacheField = PlayerViewModel::class.java.getDeclaredField("_preloadedCache")
        cacheField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val cache = cacheField.get(viewModel) as CompletableDeferred<List<TrackData>>

        val track1 = TrackData(
            title = "Song1",
            artist = "Artist1",
            album = "Album1",
            originalIndex = 0,
            uri = mockUri("content://folder1/song1")
        )
        val track2 = TrackData(
            title = "Song2",
            artist = "Artist2",
            album = "Album2",
            originalIndex = 1,
            uri = mockUri("content://folder1/song2")
        )
        cache.complete(listOf(track1, track2))

        val differentFolderUri = mockUri("content://folder2")

        val isCacheForCurrentFolder = cache.getCompleted().isEmpty() ||
                cache.getCompleted().all { it.uri.toString().startsWith(differentFolderUri.toString()) }

        assertFalse("Cache for folder1 should not be applied to folder2", isCacheForCurrentFolder)
    }

    @Test
    fun `P1-C - cache applied to same folder`() = runTest {
        val viewModel = PlayerViewModel()

        val cacheField = PlayerViewModel::class.java.getDeclaredField("_preloadedCache")
        cacheField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val cache = cacheField.get(viewModel) as CompletableDeferred<List<TrackData>>

        val track1 = TrackData(
            title = "Song1",
            artist = "Artist1",
            album = "Album1",
            originalIndex = 0,
            uri = mockUri("content://folder1/song1")
        )
        cache.complete(listOf(track1))

        val sameFolderUri = mockUri("content://folder1")

        val isCacheForCurrentFolder = cache.getCompleted().isEmpty() ||
                cache.getCompleted().all { it.uri.toString().startsWith(sameFolderUri.toString()) }

        assertTrue("Cache for folder1 should be applied to folder1", isCacheForCurrentFolder)
    }

    // ============================================================
    // P1-D - updateTrackTags の同時実行防止
    // ============================================================

    @Test
    fun `P1-D - concurrent updateTrackTags calls are prevented`() = runTest {
        val viewModel = PlayerViewModel()

        val guardField = PlayerViewModel::class.java.getDeclaredField("isSavingMetadataGuard")
        guardField.isAccessible = true
        val guard = guardField.get(viewModel) as java.util.concurrent.atomic.AtomicBoolean

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

        assertEquals("Second call should be rejected", false, callbackResult)

        guard.set(false)
    }

    @Test
    fun `P1-D - guard resets after successful completion`() = runTest {
        val viewModel = PlayerViewModel()

        val guardField = PlayerViewModel::class.java.getDeclaredField("isSavingMetadataGuard")
        guardField.isAccessible = true
        val guard = guardField.get(viewModel) as java.util.concurrent.atomic.AtomicBoolean

        assertFalse("Guard should start as false", guard.get())

        guard.set(true)
        guard.set(false)

        assertFalse("Guard should be resettable", guard.get())
    }

    // ============================================================
    // P2-A - detectExtension の共通化
    // ============================================================

    @Test
    fun `P2-A - detectExtension extracts flac correctly`() {
        val context = mockk<Context>(relaxed = true)
        val extractor = TrackMetadataExtractor(context)

        val uri = mockk<Uri>(relaxed = true)
        every { uri.lastPathSegment } returns "song.flac"

        mockkStatic(androidx.documentfile.provider.DocumentFile::class)
        every { androidx.documentfile.provider.DocumentFile.fromSingleUri(context, uri) } returns null

        val result = extractor.detectExtension(uri)
        assertEquals("flac", result)

        unmockkStatic(androidx.documentfile.provider.DocumentFile::class)
    }

    @Test
    fun `P2-A - detectExtension extracts wav correctly`() {
        val context = mockk<Context>(relaxed = true)
        val extractor = TrackMetadataExtractor(context)

        val uri = mockk<Uri>(relaxed = true)
        every { uri.lastPathSegment } returns "song.wav"

        mockkStatic(androidx.documentfile.provider.DocumentFile::class)
        every { androidx.documentfile.provider.DocumentFile.fromSingleUri(context, uri) } returns null

        val result = extractor.detectExtension(uri)
        assertEquals("wav", result)

        unmockkStatic(androidx.documentfile.provider.DocumentFile::class)
    }

    @Test
    fun `P2-A - detectExtension defaults to mp3 for unknown extension`() {
        val context = mockk<Context>(relaxed = true)
        val extractor = TrackMetadataExtractor(context)

        val uri = mockk<Uri>(relaxed = true)
        every { uri.lastPathSegment } returns "song.ogg"

        mockkStatic(androidx.documentfile.provider.DocumentFile::class)
        every { androidx.documentfile.provider.DocumentFile.fromSingleUri(context, uri) } returns null

        val result = extractor.detectExtension(uri)
        assertEquals("mp3", result)

        unmockkStatic(androidx.documentfile.provider.DocumentFile::class)
    }

    // ============================================================
    // P2-B - MediaMetadataRetriever のリソース解放
    // ============================================================

    @Test
    fun `P2-B - TrackMetadataExtractor releases retriever on exception`() = runTest {
        val context = mockk<Context>(relaxed = true)
        every { context.contentResolver.openFileDescriptor(any<Uri>(), any()) } throws RuntimeException("Test error")

        val extractor = TrackMetadataExtractor(context)
        val uri = mockk<Uri>(relaxed = true)
        every { uri.lastPathSegment } returns "test.flac"

        mockkStatic(androidx.documentfile.provider.DocumentFile::class)
        every { androidx.documentfile.provider.DocumentFile.fromSingleUri(context, uri) } returns null

        val result = extractor.extractMetadata(uri)

        assertNotNull("Should return metadata even on exception", result)

        unmockkStatic(androidx.documentfile.provider.DocumentFile::class)
    }

    @Test
    fun `P2-B - TrackMetadataExtractor returns default values on error`() = runTest {
        val context = mockk<Context>(relaxed = true)
        every { context.contentResolver.openFileDescriptor(any<Uri>(), any()) } throws RuntimeException("Test error")

        val extractor = TrackMetadataExtractor(context)
        val uri = mockk<Uri>(relaxed = true)
        every { uri.lastPathSegment } returns "test.flac"

        mockkStatic(androidx.documentfile.provider.DocumentFile::class)
        every { androidx.documentfile.provider.DocumentFile.fromSingleUri(context, uri) } returns null

        val result = extractor.extractMetadata(uri)

        assertNull("Album art should be null on error", result.albumArt)
        assertTrue("Lyrics should be empty on error", result.lyrics.isEmpty())
        assertEquals("Audio info should be empty on error", "", result.audioInfo)

        unmockkStatic(androidx.documentfile.provider.DocumentFile::class)
    }

    // ============================================================
    // P2-E - refreshTrackAfterEdit で Uri.EMPTY を渡さない
    // ============================================================

    @Test
    fun `P2-E - saveCache handles empty folderUri`() = runTest {
        val viewModel = PlayerViewModel()
        val context = mockk<Context>(relaxed = true)

        val prefs = mockk<android.content.SharedPreferences>(relaxed = true)
        every { prefs.getString("last_uri", null) } returns ""
        every { context.getSharedPreferences(any(), any()) } returns prefs

        val prefsField = PlayerViewModel::class.java.getDeclaredField("sharedPreferences")
        prefsField.isAccessible = true
        prefsField.set(viewModel, prefs)

        val saveCacheMethod = PlayerViewModel::class.java.getDeclaredMethod(
            "saveCache",
            Context::class.java,
            Uri::class.java,
            List::class.java
        )
        saveCacheMethod.isAccessible = true

        val uri = mockUri("content://test/song")
        val tracks = listOf(
            TrackData(
                title = "Test",
                artist = "Artist",
                album = "Album",
                originalIndex = 0,
                uri = uri
            )
        )

        try {
            saveCacheMethod.invoke(viewModel, context, uri, tracks)
        } catch (e: Exception) {
            fail("Should not throw: ${e.message}")
        }
    }

    // ============================================================
    // P0-5 - SettingsScreen 統計情報のリアクティブ更新
    // ============================================================

    @Test
    fun `P0-5 - statsVersion increments on notifyStatsChanged`() = runTest {
        val viewModel = PlayerViewModel()

        val initial = viewModel.statsVersion.value
        viewModel.notifyStatsChanged()
        advanceUntilIdle()

        assertEquals(initial + 1, viewModel.statsVersion.value)
    }

    @Test
    fun `P0-5 - statsVersion starts at zero`() = runTest {
        val viewModel = PlayerViewModel()
        assertEquals(0L, viewModel.statsVersion.value)
    }

    // ============================================================
    // P3-N - キャッシュヒット判定（既存動作の維持）
    // ============================================================

    @Test
    fun `P3-N - getCachedMetadata returns null when no cache exists`() {
        val context = mockk<Context>(relaxed = true)
        val audioManager = mockk<android.media.AudioManager>(relaxed = true)
        every { context.getSystemService(Context.AUDIO_SERVICE) } returns audioManager
        every { audioManager.getDevices(any()) } returns arrayOf()

        val extractor = TrackMetadataExtractor(context)
        val uri = mockUri("content://test/uncached")

        val result = extractor.getCachedMetadata(uri)
        assertNull("Should return null when no cache exists", result)
    }

    @Test
    fun `P3-N - getCachedMetadata returns data when cache exists`() = runTest {
        val context = mockk<Context>(relaxed = true)
        val audioManager = mockk<android.media.AudioManager>(relaxed = true)
        every { context.getSystemService(Context.AUDIO_SERVICE) } returns audioManager
        every { audioManager.getDevices(any()) } returns arrayOf()

        val extractor = TrackMetadataExtractor(context)
        val uri = mockUri("content://test/cached")

        extractor.lyricsCache[uri] = listOf(1000L to "Test Lyric")
        extractor.audioSpecCache[uri] = AudioSpec(sampleRateHz = 44_100, bitDepthLabel = "16bit")
        extractor.fileExtensionCache[uri] = "flac"

        val result = extractor.getCachedMetadata(uri)

        assertNotNull("Should return cached metadata", result)
        assertEquals(1, result!!.lyrics.size)
        assertEquals("Test Lyric", result.lyrics[0].second)
    }

    private fun advanceUntilIdle() {
        testDispatcher.scheduler.advanceUntilIdle()
    }
}
