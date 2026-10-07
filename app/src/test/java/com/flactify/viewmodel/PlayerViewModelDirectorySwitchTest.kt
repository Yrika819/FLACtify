package com.flactify.viewmodel

import android.content.ContentResolver
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelDirectorySwitchTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `successful B scan activates B and persists B`() = runTest {
        val harness = Harness()
        harness.scanner.coEveryScan(harness.b, success("B"))
        harness.viewModel.loadDirectory(harness.context, harness.b)
        advanceUntilIdle()

        assertEquals(listOf("B"), harness.viewModel.trackList.value)
        assertEquals(harness.b.toString(), harness.lastUri)
        assertTrue(harness.viewModel.scanFailures.value.isEmpty())
    }

    @Test
    fun `failed B permission scan keeps A library queue URI and edit permission`() = runTest {
        val harness = Harness()
        harness.activateA(this)
        harness.permissionState(true)
        clearMocks(harness.playbackController, answers = false, recordedCalls = true, childMocks = false)
        harness.scanner.coEveryScan(harness.b, failed(SecurityException("permission denied")))

        harness.viewModel.loadDirectory(harness.context, harness.b)
        advanceUntilIdle()

        assertEquals(listOf("A"), harness.viewModel.trackList.value)
        assertEquals(harness.a.toString(), harness.lastUri)
        assertTrue(harness.viewModel.canEditMetadata.value)
        assertTrue(harness.viewModel.scanFailures.value.isNotEmpty())
        verify(exactly = 0) {
            harness.playbackController.setPlaylist(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
            )
        }
    }

    @Test
    fun `failed B provider scan keeps A library queue URI and edit permission`() = runTest {
        val harness = Harness()
        harness.activateA(this)
        harness.permissionState(true)
        clearMocks(harness.playbackController, answers = false, recordedCalls = true, childMocks = false)
        harness.scanner.coEveryScan(harness.b, failed(IllegalStateException("provider failure")))

        harness.viewModel.loadDirectory(harness.context, harness.b)
        advanceUntilIdle()

        assertEquals(listOf("A"), harness.viewModel.trackList.value)
        assertEquals(harness.a.toString(), harness.lastUri)
        assertTrue(harness.viewModel.canEditMetadata.value)
        assertTrue(harness.viewModel.scanFailures.value.isNotEmpty())
        verify(exactly = 0) {
            harness.playbackController.setPlaylist(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
            )
        }
    }

    @Test
    fun `inaccessible persisted folder at startup reports failure without committing empty library`() = runTest {
        val harness = Harness()
        harness.lastUri = harness.a.toString()
        harness.scanner.coEveryScan(harness.a, failed(SecurityException("unavailable")))

        harness.viewModel.loadDirectory(harness.context, harness.a)
        advanceUntilIdle()

        assertTrue(harness.viewModel.trackList.value.isEmpty())
        assertEquals(harness.a.toString(), harness.lastUri)
        assertTrue(harness.viewModel.scanFailures.value.isNotEmpty())
        assertTrue(harness.viewModel.isReady.value)
        assertFalse(harness.viewModel.isScanningArtists.value)
    }

    private inner class Harness {
        val a: Uri = mockUri("content://provider/tree/A")
        val b: Uri = mockUri("content://provider/tree/B")
        val viewModel = PlayerViewModel()
        val scanner = mockk<LibraryScanner>()
        val playbackController = mockk<PlaybackController>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        private val resolver = mockk<ContentResolver>(relaxed = true)
        private val preferences = mockk<SharedPreferences>(relaxed = true)
        private val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        var lastUri: String? = null

        init {
            every { context.applicationContext } returns context
            every { context.contentResolver } returns resolver
            every { context.getSharedPreferences(any(), any()) } returns preferences
            every { preferences.edit() } returns editor
            every { preferences.getString("last_uri", null) } answers { lastUri }
            every { editor.putString("last_uri", any()) } answers {
                lastUri = secondArg()
                editor
            }
            every { editor.apply() } returns Unit
            setField(viewModel, "sharedPreferences", preferences)
            setField(viewModel, "libraryScanner", scanner)
            setField(viewModel, "playbackController", playbackController)
            @Suppress("UNCHECKED_CAST")
            val preloaded = getField(viewModel, "_preloadedCache") as CompletableDeferred<LibraryCacheSnapshot?>
            preloaded.complete(null)
        }

        fun permissionState(value: Boolean) {
            val state = getField(viewModel, "_canEditMetadata") as kotlinx.coroutines.flow.MutableStateFlow<Boolean>
            state.value = value
        }

        fun activateA(scope: TestScope) {
            scanner.coEveryScan(a, success("A"))
            viewModel.loadDirectory(context, a)
            scope.advanceUntilIdle()
            assertEquals(listOf("A"), viewModel.trackList.value)
            assertEquals(a.toString(), lastUri)
        }
    }

    private fun LibraryScanner.coEveryScan(uri: Uri, result: LibraryScanResult) {
        coEvery { scan(any(), uri, any(), any()) } returns result
    }

    private fun mockUri(@Suppress("UNUSED_PARAMETER") value: String): Uri = mockk()

    private fun success(title: String) = LibraryScanResult(
        tracks = listOf(TrackData(title, "Artist", originalIndex = 0, uri = mockUri("content://track/$title"))),
        files = listOf(mockk<DocumentFile>()),
        changed = true
    )

    private fun failed(error: Exception) = LibraryScanResult(
        tracks = emptyList(), files = emptyList(), changed = false,
        scanSucceeded = false, failure = error
    )

    private fun setField(target: Any, name: String, value: Any) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true; set(target, value) }
    }

    private fun getField(target: Any, name: String): Any =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
            ?: error("Field $name was null")
}
