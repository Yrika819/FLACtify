package com.flactify.viewmodel

import android.content.ContentResolver
import android.content.Context
import android.content.SharedPreferences
import android.net.TestUri
import android.net.Uri
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * How long the library generation lock may be held.
 *
 * `libraryCommitLock` exists to make a generation check and the library commit that follows it
 * atomic. That is easy to get right for state writes and easy to get wrong for anything that touches
 * the disk: the very same monitor is taken by the main thread to allocate the generation of the next
 * folder intention, so an expensive operation inside it parks the UI thread for as long as that
 * operation takes. A library cache write serialises every track and commits it through
 * `AtomicFile`, which is the most expensive library commit there is, and a large library pays it on
 * every folder switch.
 *
 * Both tests hold the expensive operation open with a latch, so they assert about blocking rather
 * than about how fast the machine is. The first one is the regression: a superseded generation must
 * not be able to keep the main thread waiting. The second one pins the guard itself, so that moving
 * the write out of the lock cannot quietly let a superseded generation write the cache.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelLibraryCommitLockTest {

    private val mainExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "commit-lock-main")
    }
    /** Never leaves a blocked worker behind when a test fails before releasing its latch. */
    private val openLatches = CopyOnWriteArrayList<CountDownLatch>()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainExecutor.asCoroutineDispatcher())
    }

    @After
    fun tearDown() {
        openLatches.forEach(CountDownLatch::countDown)
        mainExecutor.shutdownNow()
        Dispatchers.resetMain()
    }

    @Test
    fun `a folder switch never waits for an in-flight library cache write`() = runBlocking {
        val writeEntered = CountDownLatch(1)
        val releaseWrite = latch()
        val harness = Harness()
        harness.scanning(
            uri = harness.a,
            tracks = library("A", N),
            duringCacheWrite = {
                writeEntered.countDown()
                releaseWrite.await()
            }
        )
        harness.scanning(harness.b, library("B", N))

        harness.viewModel.loadDirectory(harness.context, harness.a)
        assertTrue("the scan never reached its cache write", writeEntered.await(30, TimeUnit.SECONDS))

        // The user switches folders while that write is still running. Allocating the generation for
        // the new intention must not have to wait for the disk to finish.
        val switched =
            mainExecutor.submit { harness.viewModel.loadDirectory(harness.context, harness.b) }
        switched.get(5, TimeUnit.SECONDS)

        releaseWrite.countDown()
        harness.awaitUntil { it.viewModel.trackList.value == library("B", N).map(TrackData::title) }

        // The newer generation still commits its own cache: the write is only moved out of the way
        // of the lock, not out of the app.
        assertEquals(library("B", N).map(TrackData::title), harness.viewModel.trackList.value)
        verify(exactly = 1) { harness.scanner.saveCache(harness.context, harness.b, any()) }
    }

    @Test
    fun `a superseded scan never writes the library cache after a folder switch`() = runBlocking {
        val scanEntered = CountDownLatch(1)
        val releaseScan = latch()
        val harness = Harness()
        harness.scanning(
            uri = harness.a,
            tracks = library("A", N),
            entered = scanEntered,
            gate = { releaseScan.await() }
        )
        harness.scanning(harness.b, library("B", 3))

        harness.viewModel.loadDirectory(harness.context, harness.a)
        val supersededScan: Job = harness.scanJob()
        assertTrue("the superseded scan never started", scanEntered.await(30, TimeUnit.SECONDS))

        // The newer intention wins while the older scan is still inside its scan.
        mainExecutor.submit { harness.viewModel.loadDirectory(harness.context, harness.b) }
            .get(5, TimeUnit.SECONDS)
        harness.awaitUntil { it.viewModel.trackList.value == library("B", 3).map(TrackData::title) }
        verify(exactly = 1) { harness.scanner.saveCache(harness.context, harness.b, any()) }

        // Now the superseded scan finishes. It must not persist a cache built from folder A, and it
        // must not resurrect the library that was on screen.
        releaseScan.countDown()
        withTimeout(30_000) { supersededScan.join() }
        verify(exactly = 0) { harness.scanner.saveCache(harness.context, harness.a, any()) }
        verify(exactly = 1) { harness.scanner.saveCache(harness.context, harness.b, any()) }
        assertEquals(library("B", 3).map(TrackData::title), harness.viewModel.trackList.value)
    }

    @Test
    fun `a failed folder switch preserves the displayed library and persisted folder`() = runBlocking {
        val writeEntered = CountDownLatch(1)
        val releaseWrite = latch()
        val harness = Harness()
        val tracksA = library("A", 3)
        harness.scanning(harness.a, tracksA, persistCache = false)
        harness.viewModel.loadDirectory(harness.context, harness.a)
        harness.awaitUntil { it.viewModel.trackList.value == tracksA.map(TrackData::title) }
        withTimeout(30_000) { harness.scanJob().join() }

        harness.scanning(
            uri = harness.a,
            tracks = tracksA,
            duringCacheWrite = {
                writeEntered.countDown()
                releaseWrite.await()
            }
        )
        harness.failing(harness.b)

        harness.viewModel.loadDirectory(harness.context, harness.a)
        assertTrue("the initial cache write did not start", writeEntered.await(30, TimeUnit.SECONDS))

        // The switch can be accepted while the old synchronous save is still in flight.
        val switched = mainExecutor.submit { harness.viewModel.loadDirectory(harness.context, harness.b) }
        switched.get(5, TimeUnit.SECONDS)
        releaseWrite.countDown()
        harness.awaitUntil { harness.scanJob().isCompleted }

        assertTrue("failed selection did not finish", harness.viewModel.isReady.value)
        assertEquals(tracksA.map(TrackData::title), harness.viewModel.trackList.value)
        verify(exactly = 1) { harness.editor.putString("last_uri", harness.a.toString()) }
        verify(exactly = 0) { harness.editor.putString("last_uri", harness.b.toString()) }
        assertTrue("scan indicator remained active", !harness.viewModel.isScanningArtists.value)
    }

    private fun latch(): CountDownLatch = CountDownLatch(1).also(openLatches::add)

    // ── Harness ──

    private inner class Harness {
        val a: Uri = TestUri("content://provider/tree/A")
        val b: Uri = TestUri("content://provider/tree/B")
        val viewModel = PlayerViewModel()
        val scanner = mockk<LibraryScanner>()
        private val playbackController = mockk<PlaybackController>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        private val resolver = mockk<ContentResolver>(relaxed = true)
        private val preferences = mockk<SharedPreferences>(relaxed = true)
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)

        init {
            every { context.applicationContext } returns context
            every { context.contentResolver } returns resolver
            every { context.getSharedPreferences(any(), any()) } returns preferences
            every { preferences.edit() } returns editor
            // The persisted folder is deliberately unknown: base `Uri.parse` returns null in a unit
            // test, so a known folder would only make the cache path fail for an unrelated reason.
            every { preferences.getString("last_uri", null) } returns null
            every { preferences.getString("playback_uri", null) } returns null
            every { editor.putString("last_uri", any()) } returns editor
            every { editor.apply() } returns Unit
            every { resolver.persistedUriPermissions } returns emptyList()
            every { scanner.saveCache(any(), any(), any()) } just Runs
            setField(viewModel, "sharedPreferences", preferences)
            setField(viewModel, "libraryScanner", scanner)
            setField(viewModel, "playbackController", playbackController)
            @Suppress("UNCHECKED_CAST")
            val preloaded = getField(viewModel, "_preloadedCache")
                as kotlinx.coroutines.CompletableDeferred<LibraryCacheSnapshot?>
            preloaded.complete(null)
            setField(viewModel, "currentCacheSnapshot", null)
        }

        fun scanJob(): Job = getField(viewModel, "scanJob") as Job

        fun failing(uri: Uri) {
            coEvery { scanner.scan(any(), uri, any(), any(), any(), any()) } coAnswers {
                withContext(Dispatchers.IO) {
                    LibraryScanResult(
                        tracks = emptyList(), files = emptyList(), changed = false,
                        scanSucceeded = false, failure = IllegalStateException("scan failed")
                    )
                }
            }
        }

        /**
         * A scan for [uri] that hands its result to the caller's `persistIfCurrent` hook, the way
         * the real scanner does. [entered] is counted down before [gate] runs, and [duringCacheWrite]
         * runs inside the cache write itself, so a test can hold the disk operation open.
         */
        fun scanning(
            uri: Uri,
            tracks: List<TrackData>,
            entered: CountDownLatch? = null,
            gate: suspend () -> Unit = {},
            duringCacheWrite: (() -> Unit)? = null,
            persistCache: Boolean = true
        ) {
            if (duringCacheWrite != null) {
                every { scanner.saveCache(context, uri, any()) } answers { duringCacheWrite() }
            }
            coEvery { scanner.scan(any(), uri, any(), any(), any(), any()) } coAnswers {
                entered?.countDown()
                val invocation = it.invocation
                // The real scanner runs on Dispatchers.IO, so the mock leaves the caller's
                // dispatcher too: otherwise the single main thread would stay parked inside the
                // scan and no folder switch could ever be observed.
                withContext(Dispatchers.IO) {
                    gate()
                    // For a suspend function MockK appends the continuation after the declared
                    // arguments, so the caller's persistIfCurrent hook is the second-to-last entry.
                    @Suppress("UNCHECKED_CAST")
                    val persist = invocation.args[invocation.args.size - 2]
                        as? (List<TrackData>) -> Unit
                    if (persistCache && persist != null) persist(tracks)
                    LibraryScanResult(tracks, emptyList(), changed = true)
                }
            }
        }

        suspend fun awaitUntil(settled: (Harness) -> Boolean) {
            withTimeout(30_000) {
                while (!settled(this@Harness)) delay(10)
            }
        }
    }

    private fun library(tag: String, count: Int): List<TrackData> =
        (0 until count).map { index ->
            TrackData(
                title = "Song $tag $index",
                artist = "Artist $tag",
                album = "Album $tag",
                originalIndex = index,
                uri = TestUri("content://provider/tree/$tag/track-$index.flac")
            )
        }

    private fun setField(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true; set(target, value) }
    }

    private fun getField(target: Any, name: String): Any =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
            ?: error("Field $name was null")

    private companion object {
        const val N = 4
    }
}
