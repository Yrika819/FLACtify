package com.flactify.viewmodel

import android.content.ContentResolver
import android.content.Context
import android.content.SharedPreferences
import android.content.UriPermission
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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

/**
 * Regression coverage for the Phase 1 cache-first startup restore.
 *
 * Dispatchers.Main is an UnconfinedTestDispatcher (separate from runTest's own scheduler) so the
 * ViewModel's withContext(Dispatchers.Main) state commits run eagerly and deterministically, while
 * a gated scan still suspends on its CompletableDeferred until the test releases it. This lets the
 * test pin the exact order in which startup restore, validation and folder switch complete without
 * relying on real timing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelStartupCacheRestoreTest {
    private val mainDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `valid cache with read permission restores the library before validation completes`() = runTest {
        val h = Harness()
        val gate = CompletableDeferred<Unit>()
        h.setCacheSnapshot(LibraryCacheSnapshot(h.a, trackList(h, "A", h.aUri())))
        h.grant(uri = h.a, read = true, write = true)
        h.scanner.gated(h.a, gate, success(h, "A", h.aUri()))

        launch { h.viewModel.performRestoreStartupLibrary(h.context, h.a) }
        advanceUntilIdle()

        // Provisional restore happened without any scan completing: the splash can end now.
        assertEquals(listOf("A"), h.viewModel.trackList.value)
        assertTrue(h.viewModel.isReady.value)
        assertTrue(h.viewModel.startupState.value is StartupState.CacheRestored)
        assertTrue(h.viewModel.scanFailures.value.isEmpty())

        // The validation result arrives and promotes the state to READY.
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(h.viewModel.startupState.value is StartupState.Ready)
        assertEquals(listOf("A"), h.viewModel.trackList.value)
    }

    @Test
    fun `no cache surfaces Validating and only commits after the scan succeeds`() = runTest {
        val h = Harness()
        val gate = CompletableDeferred<Unit>()
        h.setCacheSnapshot(null)
        h.grant(uri = h.a, read = true, write = false)
        h.scanner.gated(h.a, gate, success(h, "A", h.aUri()))

        launch { h.viewModel.performRestoreStartupLibrary(h.context, h.a) }
        advanceUntilIdle()

        // Nothing to show yet; the state is explicitly validating, library still empty.
        assertTrue(h.viewModel.startupState.value is StartupState.Validating)
        assertFalse(h.viewModel.isReady.value)
        assertTrue(h.viewModel.trackList.value.isEmpty())

        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(h.viewModel.startupState.value is StartupState.Ready)
        assertEquals(listOf("A"), h.viewModel.trackList.value)
        assertTrue(h.viewModel.isReady.value)
    }

    @Test
    fun `cache present but read permission revoked is not shown as a provisional library`() = runTest {
        val h = Harness()
        val gate = CompletableDeferred<Unit>()
        h.setCacheSnapshot(LibraryCacheSnapshot(h.a, trackList(h, "A", h.aUri())))
        // A persisted entry only for a *different* tree means our grant for A is gone.
        h.grant(uri = h.b, read = true, write = false)
        h.scanner.gated(h.a, gate, success(h, "A", h.aUri()))

        launch { h.viewModel.performRestoreStartupLibrary(h.context, h.a) }
        advanceUntilIdle()

        // We must not surface stale cache data for a folder we can no longer read.
        assertTrue(h.viewModel.startupState.value is StartupState.Validating)
        assertFalse(h.viewModel.isReady.value)
        assertTrue(h.viewModel.trackList.value.isEmpty())

        // Let the paused validation finish so nothing is left dangling.
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(h.viewModel.startupState.value is StartupState.Ready)
    }

    @Test
    fun `validation failure after a provisional restore keeps the shown library and reports the error`() = runTest {
        val h = Harness()
        h.setCacheSnapshot(LibraryCacheSnapshot(h.a, trackList(h, "A", h.aUri())))
        h.grant(uri = h.a, read = true, write = true)
        h.scanner.everyScan(h.a, scanFailed(SecurityException("permission denied")))

        launch { h.viewModel.performRestoreStartupLibrary(h.context, h.a) }
        advanceUntilIdle()

        assertEquals(listOf("A"), h.viewModel.trackList.value)
        assertTrue(h.viewModel.isReady.value)
        assertTrue(h.viewModel.scanFailures.value.isNotEmpty())
        val state = h.viewModel.startupState.value
        assertTrue("expected Error(keptProvisionalLibrary=true), was $state", state is StartupState.Error && state.keptProvisionalLibrary)
    }

    @Test
    fun `validation failure with no cache reports error and keeps an empty library`() = runTest {
        val h = Harness()
        h.setCacheSnapshot(null)
        h.grant(uri = h.a, read = true, write = false)
        h.scanner.everyScan(h.a, scanFailed(SecurityException("unavailable")))

        launch { h.viewModel.performRestoreStartupLibrary(h.context, h.a) }
        advanceUntilIdle()

        assertTrue(h.viewModel.trackList.value.isEmpty())
        assertTrue(h.viewModel.isReady.value)
        assertTrue(h.viewModel.scanFailures.value.isNotEmpty())
        val state = h.viewModel.startupState.value
        assertTrue(state is StartupState.Error && !state.keptProvisionalLibrary)
    }

    @Test
    fun `switching folders during startup validation discards the in-flight startup result`() = runTest {
        val h = Harness()
        val startupGate = CompletableDeferred<Unit>()
        h.setCacheSnapshot(LibraryCacheSnapshot(h.b, trackList(h, "B", h.bUri())))
        h.grant(uri = h.b, read = true, write = false)
        // Startup validation is restoring folder B and is paused inside its scan.
        h.scanner.gated(h.b, startupGate, success(h, "B", h.bUri()))

        launch { h.viewModel.performRestoreStartupLibrary(h.context, h.b) }
        advanceUntilIdle()
        // Provisional B is already on screen.
        assertEquals(listOf("B"), h.viewModel.trackList.value)

        // The user instead switches to folder A, which scans and commits deterministically.
        h.scanner.everyScan(h.a, success(h, "A", h.aUri()))
        launch { h.viewModel.performDirectoryLoad(h.context, h.a) }
        advanceUntilIdle()
        assertEquals(listOf("A"), h.viewModel.trackList.value)

        // The stale startup scan resolving afterwards must not resurrect B.
        startupGate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("A"), h.viewModel.trackList.value)
    }

    // ── Harness ──

    private inner class Harness {
        val a: Uri = uriFor("content://provider/tree/A")
        val b: Uri = uriFor("content://provider/tree/B")
        val viewModel = PlayerViewModel()
        val scanner = mockk<LibraryScanner>()
        val playbackController = mockk<PlaybackController>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        private val resolver = mockk<ContentResolver>(relaxed = true)
        private val preferences = mockk<SharedPreferences>(relaxed = true)
        private val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        var lastUri: String? = null

        fun aUri(): Uri = uriFor("content://provider/tree/A/A.flac")
        fun bUri(): Uri = uriFor("content://provider/tree/B/B.flac")

        init {
            every { context.applicationContext } returns context
            every { context.contentResolver } returns resolver
            every { context.getSharedPreferences(any(), any()) } returns preferences
            every { preferences.edit() } returns editor
            every { preferences.getString("last_uri", null) } answers { lastUri }
            every { preferences.getString("playback_uri", null) } returns null
            every { editor.putString("last_uri", any()) } answers { lastUri = secondArg(); editor }
            every { editor.putString("playback_uri", any()) } answers { editor }
            every { editor.apply() } returns Unit
            setField(viewModel, "sharedPreferences", preferences)
            setField(viewModel, "libraryScanner", scanner)
            setField(viewModel, "playbackController", playbackController)
        }

        fun grant(uri: Uri, read: Boolean, write: Boolean) {
            every { resolver.persistedUriPermissions } returns listOf(permission(uri, read, write))
        }

        fun setCacheSnapshot(snapshot: LibraryCacheSnapshot?) {
            @Suppress("UNCHECKED_CAST")
            val preloaded = getField(viewModel, "_preloadedCache") as CompletableDeferred<LibraryCacheSnapshot?>
            preloaded.complete(snapshot)
            setField(viewModel, "currentCacheSnapshot", snapshot)
        }
    }

    private fun LibraryScanner.gated(uri: Uri, gate: CompletableDeferred<Unit>, result: LibraryScanResult) {
        coEvery { scan(any(), uri, any(), any(), any()) } coAnswers {
            gate.await()
            result
        }
    }

    private fun LibraryScanner.everyScan(uri: Uri, result: LibraryScanResult) {
        coEvery { scan(any(), uri, any(), any(), any()) } returns result
    }

    private fun permission(uri: Uri, read: Boolean, write: Boolean): UriPermission {
        val p = mockk<UriPermission>(relaxed = true)
        every { p.uri } returns uri
        every { p.isReadPermission } returns read
        every { p.isWritePermission } returns write
        return p
    }

    private fun uriFor(value: String): Uri = mockk<Uri>(relaxed = true).also { every { it.toString() } returns value }

    private fun trackList(h: Harness, title: String, uri: Uri): List<TrackData> =
        listOf(TrackData(title, "Artist", album = "Album", originalIndex = 0, uri = uri))

    private fun success(h: Harness, title: String, uri: Uri) = LibraryScanResult(
        tracks = trackList(h, title, uri),
        files = listOf(mockk<DocumentFile>()),
        changed = true
    )

    private fun scanFailed(error: Exception) = LibraryScanResult(
        tracks = emptyList(), files = emptyList(), changed = false,
        scanSucceeded = false, failure = error
    )

    private fun setField(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true; set(target, value) }
    }

    private fun getField(target: Any, name: String): Any =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
            ?: error("Field $name was null")
}
