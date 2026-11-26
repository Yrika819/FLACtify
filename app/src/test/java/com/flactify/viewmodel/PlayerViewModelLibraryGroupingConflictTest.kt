package com.flactify.viewmodel

import android.content.ContentResolver
import android.content.Context
import android.content.SharedPreferences
import android.content.UriPermission
import android.net.TestUri
import android.net.Uri
import io.mockk.coEvery
import io.mockk.every
import io.mockk.every
import io.mockk.coEvery
import io.mockk.just
import io.mockk.*
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
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
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Conflict coverage for the Phase 2A library grouping.
 *
 * The grouping now runs on `Dispatchers.Default` and is published on the main dispatcher, so the gap
 * between computing a result and committing it is real: a library intention that is superseded inside
 * that gap must never publish. These tests hold the gap open deterministically by occupying every
 * `Dispatchers.Default` worker, which lets the test flip the library generation before the grouping
 * can run at all.
 *
 * Every assertion is on published `StateFlow` state, and the expected groups are recomputed with
 * [LibraryGrouping], so a drift in the grouping rules cannot be masked by a stale expectation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelLibraryGroupingConflictTest {

    private val mainExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "grouping-conflict-main")
    }
    private val occupiedWorkers = CopyOnWriteArrayList<DefaultWorkerGate>()
    private lateinit var background: CoroutineScope
    private lateinit var harness: Harness

    @Before
    fun setUp() {
        Dispatchers.setMain(mainExecutor.asCoroutineDispatcher())
        background = CoroutineScope(Dispatchers.IO + SupervisorJob())
    }

    @After
    fun tearDown() {
        // Never leave blocked workers behind: the next test would starve on Dispatchers.Default.
        occupiedWorkers.forEach(DefaultWorkerGate::release)
        background.cancel()
        mainExecutor.shutdownNow()
        Dispatchers.resetMain()
    }

    @Test
    fun `provisional cache restore and the validated scan publish the shared grouping in order`() = runBlocking {
        val cached = library("cached", count = 12)
        val validated = library("validated", count = 9)
        val validationGate = CompletableDeferred<Unit>()
        harness = Harness()
        harness.cache(harness.a, cached)
        harness.grant(harness.a, read = true, write = false)
        harness.gateScan(harness.a, validationGate, validated)

        harness.background { it.viewModel.performRestoreStartupLibrary(it.context, harness.a) }
        harness.awaitUntil { it.viewModel.startupState.value is StartupState.CacheRestored }

        // The provisional restore shows the cache immediately, grouped by the shared rules.
        assertGroupsEqual(LibraryGrouping.group(cached).artistMap, harness.viewModel.artistMap.value)
        assertTrue(harness.viewModel.isReady.value)

        // The validated scan then promotes the state and republishes the validated grouping.
        validationGate.complete(Unit)
        harness.awaitUntil { it.viewModel.startupState.value is StartupState.Ready }
        assertGroupsEqual(LibraryGrouping.group(validated).artistMap, harness.viewModel.artistMap.value)
        assertEquals(
            LibraryGrouping.group(validated).tracks.map { it.title },
            harness.viewModel.trackList.value
        )
    }

    @Test
    fun `a provisional grouping superseded by a newer generation never publishes`() = runBlocking {
        harness = Harness()
        val groupingEntered = CountDownLatch(1)
        val releaseGrouping = CountDownLatch(1)
        val cached = library("cached", count = 9).mapIndexed { index, track ->
            if (index == 0) {
                track.copy(
                    uri = GatedUri("content://provider/tree/cached/gated.flac", groupingEntered, releaseGrouping)
                )
            } else track
        }
        val validated = library("validated", count = 6)
        harness.cache(harness.a, cached)
        harness.grant(harness.a, read = true, write = false)
        harness.scanResult(harness.a) { LibraryScanResult(validated, emptyList(), changed = true) }

        val startup = harness.background { it.viewModel.performRestoreStartupLibrary(it.context, harness.a) }
        assertTrue(
            "grouping did not start",
            groupingEntered.await(30, TimeUnit.SECONDS)
        )

        // Invalidate the startup generation while the grouping worker is held inside URI de-duplication.
        harness.viewModel.bumpLibraryGeneration()
        releaseGrouping.countDown()
        startup.join()

        assertTrue("the superseded grouping published artists", harness.viewModel.artistMap.value.isEmpty())
        assertTrue("the superseded grouping published albums", harness.viewModel.albumMap.value.isEmpty())
        assertTrue("the superseded grouping published titles", harness.viewModel.trackList.value.isEmpty())
        // The obsolete provisional restore must not advance StartupState or readiness.
        assertTrue(
            "stale restore changed startup state: ${harness.viewModel.startupState.value}",
            harness.viewModel.startupState.value is StartupState.Initializing
        )
        assertTrue("stale restore marked the library ready", !harness.viewModel.isReady.value)
    }

    @Test
    fun `a folder switch that supersedes an in-flight grouping publishes only the newer library`() = runBlocking {
        harness = Harness()
        val cached = library("stale", count = 11)
        val validated = library("fresh", count = 7)
        val next = library("next", count = 5)
        harness.cache(harness.a, cached)
        harness.grant(harness.a, read = true, write = true)
        harness.grant(harness.b, read = true, write = true)
        harness.scanResult(harness.a) { LibraryScanResult(validated, emptyList(), changed = true) }
        harness.scanResult(harness.b) { LibraryScanResult(next, emptyList(), changed = true) }

        val occupied = occupyDefaultWorkers()
        val reachedBranch = CountDownLatch(1)
        harness.onPermissionRead { reachedBranch.countDown() }

        val startup = harness.background { it.viewModel.performRestoreStartupLibrary(it.context, harness.a) }
        assertTrue(reachedBranch.await(30, TimeUnit.SECONDS))

        // The user switches folders while the provisional grouping is still in flight.
        val switch = harness.background { it.viewModel.performDirectoryLoad(it.context, harness.b) }
        occupied.release()

        startup.join()
        switch.join()

        val expected = LibraryGrouping.group(next)
        assertGroupsEqual(
            expected.artistMap,
            harness.viewModel.artistMap.value,
            "the superseded grouping must not survive the folder switch"
        )
        assertEquals(expected.tracks.map { it.title }, harness.viewModel.trackList.value)
        assertTrue("the folder switch never became ready", harness.viewModel.isReady.value)
        assertTrue(harness.viewModel.startupState.value is StartupState.Ready)
        assertTrue(harness.viewModel.artistMap.value.keys.none { it.contains("stale") || it.contains("fresh") })
    }

    @Test
    fun `an in-flight grouping that is cancelled with the ViewModel scope publishes nothing`() = runBlocking {
        harness = Harness()
        val cached = library("cached", count = 10)
        harness.cache(harness.a, cached)
        harness.grant(harness.a, read = true, write = false)
        harness.scanResult(harness.a) { LibraryScanResult(library("validated", 4), emptyList(), changed = true) }

        val occupied = occupyDefaultWorkers()
        val reachedBranch = CountDownLatch(1)
        harness.onPermissionRead { reachedBranch.countDown() }

        val viewModelScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val startup = viewModelScope.launch(Dispatchers.IO) {
            harness.viewModel.performRestoreStartupLibrary(harness.context, harness.a)
        }
        assertTrue(reachedBranch.await(30, TimeUnit.SECONDS))

        // The ViewModel is cleared while the grouping is queued: its coroutine is cancelled, so the
        // continuation that would publish the result must never run.
        viewModelScope.cancel()
        occupied.release()
        startup.join()

        assertTrue(startup.isCancelled)
        assertTrue(harness.viewModel.artistMap.value.isEmpty())
        assertTrue(harness.viewModel.trackList.value.isEmpty())
    }

    @Test
    fun `a scan that completes after a folder switch does not resurrect the previous library`() = runBlocking {
        harness = Harness()
        val first = library("first", count = 8)
        val second = library("second", count = 6)
        harness.grant(harness.a, read = true, write = true)
        harness.grant(harness.b, read = true, write = true)
        harness.scanResult(harness.a) { LibraryScanResult(first, emptyList(), changed = true) }

        val aJob = harness.background { it.viewModel.performDirectoryLoad(it.context, harness.a) }
        harness.awaitUntil { it.viewModel.artistMap.value.isNotEmpty() }

        harness.scanResult(harness.b) { LibraryScanResult(second, emptyList(), changed = true) }
        val bJob = harness.background { it.viewModel.performDirectoryLoad(it.context, harness.b) }
        harness.awaitUntil { it.viewModel.albumMap.value.keys.any { key -> key.contains("second") } }

        aJob.join()
        bJob.join()

        assertGroupsEqual(LibraryGrouping.group(second).artistMap, harness.viewModel.artistMap.value)
        assertTrue(harness.viewModel.artistMap.value.keys.none { it.contains("first") })
    }

    @Test
    fun `a tag edit that commits while a folder switch is grouping publishes only the tag edit result`() = runBlocking {
        harness = Harness()
        val original = library("original", count = 8)
        val pending = library("pending", count = 11)
        harness.grant(harness.a, read = true, write = true)
        harness.grant(harness.b, read = true, write = true)
        harness.scanResult(harness.a) { LibraryScanResult(original, emptyList(), changed = true) }

        harness.background { it.viewModel.performDirectoryLoad(it.context, harness.a) }
        harness.awaitUntil { it.viewModel.artistMap.value.isNotEmpty() }

        harness.scanResult(harness.b) { LibraryScanResult(pending, emptyList(), changed = true) }
        val beforeSwitch = harness.viewModel.readLibraryGeneration()

        // Both results are queued on Dispatchers.Default: the folder switch's grouping and the tag
        // edit's rebuild. The tag edit is the newer intention, so it is the one that publishes.
        val occupied = occupyDefaultWorkers()
        val switch = harness.background { it.viewModel.performDirectoryLoad(it.context, harness.b) }
        // Establish the operation order before starting the edit. Merely launching both IO
        // coroutines in order does not guarantee which one captures its library generation first.
        harness.awaitGeneration(beforeSwitch + 1)
        val edited = original[0]
        val edit = harness.background {
            it.viewModel.performUpdateTrackTags(
                appContext = harness.context,
                trackUri = edited.uri,
                title = edited.title,
                artist = "renamed artist",
                album = "renamed album",
                trackNumber = "1",
                genre = null,
                year = null,
                composer = null,
                albumArtist = null,
                discNumber = null,
                comment = null,
                artworkBitmap = null,
                onComplete = {}
            )
        }

        harness.awaitGeneration(beforeSwitch + 2)
        occupied.release()
        switch.join()
        edit.join()

        // The tag edit rebuilds whatever library is currently published, which is the first folder.
        val expected = LibraryGrouping.group(
            original.map { track ->
                if (track.uri == edited.uri) track.copy(artist = "renamed artist", album = "renamed album")
                else track
            }
        )
        assertGroupsEqual(expected.artistMap, harness.viewModel.artistMap.value)
        assertTrue(
            "the superseded folder switch must not have published",
            harness.viewModel.artistMap.value.keys.none { it.startsWith("Artist pending") }
        )
    }

    @Test
    fun `a tag edit rebuilds exactly the groups the scan path would`() = runBlocking {
        harness = Harness()
        val original = listOf(
            track("One", "alpha", "one"),
            track("Two", "alpha", "one"),
            track("Three", "beta", "two"),
            track("Four", "beta", "two"),
            track("Five", "beta", "two")
        )
        harness.grant(harness.a, read = true, write = true)
        harness.scanResult(harness.a) { LibraryScanResult(original, emptyList(), changed = true) }

        harness.background { it.viewModel.performDirectoryLoad(it.context, harness.a) }
        harness.awaitUntil { it.viewModel.albumMap.value.isNotEmpty() }

        // The tag-edit rebuild starts from the library that is already published, so the expectation
        // is built the same way rather than from the raw scan input.
        val published = harness.viewModel.artistMap.value.values.flatten()
        assertEquals(original.size, published.size)
        val edited = published[3]
        harness.viewModel.performUpdateTrackTags(
            appContext = harness.context,
            trackUri = edited.uri,
            title = edited.title,
            artist = "ALPHA",
            album = "ONE",
            trackNumber = "1",
            genre = null,
            year = null,
            composer = null,
            albumArtist = null,
            discNumber = null,
            comment = null,
            artworkBitmap = null,
            onComplete = {}
        )

        val expected = LibraryGrouping.group(
            published.map { track ->
                if (track.uri == edited.uri) track.copy(artist = "ALPHA", album = "ONE") else track
            }
        )
        assertGroupsEqual(expected.artistMap, harness.viewModel.artistMap.value)
        assertGroupsEqual(expected.albumMap, harness.viewModel.albumMap.value)
        assertEquals(expected.tracks.map { it.title }, harness.viewModel.trackList.value)
        val editedArtistTrack = harness.viewModel.artistMap.value.getValue("alpha")
            .single { it.uri == edited.uri }
        assertEquals("ALPHA", editedArtistTrack.artist)
        assertEquals("ONE", editedArtistTrack.album)
        assertEquals("1", editedArtistTrack.trackNumber)
        assertTrue("tag edit timestamp was not published", editedArtistTrack.lastModified > 0L)
        // The renamed track joins the existing case-equal group instead of creating a new one.
        assertEquals(2, harness.viewModel.artistMap.value.size)
        assertEquals(2, harness.viewModel.albumMap.value.size)
        assertTrue(harness.viewModel.artistMap.value.containsKey("alpha"))
        assertTrue(harness.viewModel.albumMap.value.containsKey("one"))
        assertEquals(
            listOf("One", "Two", "Four"),
            (harness.viewModel.artistMap.value["alpha"] ?: emptyList()).map { it.title }
        )
        assertEquals(
            listOf("One", "Two", "Four"),
            (harness.viewModel.albumMap.value["one"] ?: emptyList()).map { it.title }
        )
    }

    @Test
    fun `a library with a distinct artist per track still groups in the shown order`() = runBlocking {
        harness = Harness()
        val many = library("unique", count = 4_000)
        harness.grant(harness.a, read = true, write = true)
        harness.scanResult(harness.a) { LibraryScanResult(many, emptyList(), changed = true) }

        harness.background { it.viewModel.performDirectoryLoad(it.context, harness.a) }
        harness.awaitUntil { it.viewModel.artistMap.value.size == many.size }

        val expected = LibraryGrouping.group(many)
        assertEquals(many.size, harness.viewModel.artistMap.value.size)
        assertGroupsEqual(expected.artistMap, harness.viewModel.artistMap.value)
        assertEquals(expected.tracks.map { it.title }, harness.viewModel.trackList.value)
    }

    @Test
    fun `a fresh ViewModel reproduces the same grouping after Activity recreation`() = runBlocking {
        val shared = library("shared", count = 10)
        val first = Harness()
        first.grant(first.a, read = true, write = true)
        first.scanResult(first.a) { LibraryScanResult(shared, emptyList(), changed = true) }
        first.background { it.viewModel.performRestoreStartupLibrary(it.context, first.a) }
        first.awaitUntil { it.viewModel.startupState.value is StartupState.Ready }

        val second = Harness()
        second.cache(second.a, shared)
        second.grant(second.a, read = true, write = false)
        second.scanResult(second.a) { LibraryScanResult(shared, emptyList(), changed = true) }
        second.background { it.viewModel.performRestoreStartupLibrary(it.context, second.a) }
        second.awaitUntil { it.viewModel.startupState.value is StartupState.Ready }

        val expected = LibraryGrouping.group(shared)
        assertGroupsEqual(expected.artistMap, first.viewModel.artistMap.value)
        assertGroupsEqual(expected.artistMap, second.viewModel.artistMap.value)
        assertEquals(expected.tracks.map { it.title }, first.viewModel.trackList.value)
        assertEquals(expected.tracks.map { it.title }, second.viewModel.trackList.value)
    }

    // ── Harness ──

    private inner class Harness {
        val a: Uri = mockUri("content://provider/tree/A")
        val b: Uri = mockUri("content://provider/tree/B")
        val viewModel = PlayerViewModel()
        val scanner = mockk<LibraryScanner>(relaxed = true)
        private val playbackController = mockk<PlaybackController>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        private val resolver = mockk<ContentResolver>(relaxed = true)
        private val preferences = mockk<SharedPreferences>(relaxed = true)
        private val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        private val grants = linkedMapOf<String, Triple<Uri, Boolean, Boolean>>()
        private var onPermissionRead: (() -> Unit)? = null

        init {
            every { context.applicationContext } returns context
            every { context.contentResolver } returns resolver
            every { context.getSharedPreferences(any(), any()) } returns preferences
            every { preferences.edit() } returns editor
            // The persisted folder is unknown: base `Uri.parse` returns null in a unit test, and the
            // tag-edit path would then fail for a reason unrelated to what is being verified.
            every { preferences.getString("last_uri", null) } returns null
            every { editor.putString("last_uri", any()) } returns editor
            every { editor.apply() } returns Unit
            every { resolver.persistedUriPermissions } answers {
                onPermissionRead?.invoke()
                grants.values.map { (uri, read, write) -> permission(uri, read, write) }
            }
            setField(viewModel, "sharedPreferences", preferences)
            setField(viewModel, "libraryScanner", scanner)
            setField(viewModel, "playbackController", playbackController)
            setField(
                viewModel,
                "tagEditor",
                mockk<TagEditor>(relaxed = true).also { editor ->
                    coEvery { editor.updateTrackTags(any(), any(), any(), any()) } returns
                        kotlin.Result.success(TagEditResult(null))
                }
            )
            every { scanner.saveCache(any(), any(), any()) } just Runs
            @Suppress("UNCHECKED_CAST")
            val preloaded = getField(viewModel, "_preloadedCache") as CompletableDeferred<LibraryCacheSnapshot?>
            preloaded.complete(null)
            setField(viewModel, "currentCacheSnapshot", null)
        }

        fun grant(uri: Uri, read: Boolean, write: Boolean) {
            // The exact instance has to be stored: permission matching is by identity.
            grants[uri.toString()] = Triple(uri, read, write)
        }

        fun onPermissionRead(action: () -> Unit) {
            onPermissionRead = action
        }

        fun cache(uri: Uri, tracks: List<TrackData>) {
            @Suppress("UNCHECKED_CAST")
            val preloaded = getField(viewModel, "_preloadedCache") as CompletableDeferred<LibraryCacheSnapshot?>
            preloaded.complete(LibraryCacheSnapshot(uri, tracks))
            setField(viewModel, "currentCacheSnapshot", LibraryCacheSnapshot(uri, tracks))
        }

        fun scanResult(uri: Uri, tracks: List<TrackData>) {
            coEvery { scanner.scan(any(), uri, any(), any(), any(), any()) } returns
                LibraryScanResult(tracks, emptyList(), changed = true)
        }

        fun scanResult(uri: Uri, answer: () -> LibraryScanResult) {
            coEvery { scanner.scan(any(), uri, any(), any(), any(), any()) } coAnswers { answer() }
        }

        /**
         * Suspends the scan for [uri] behind [gate] so the test can observe an intermediate state
         * before the validated result commits.
         */
        fun gateScan(uri: Uri, gate: CompletableDeferred<Unit>, result: List<TrackData>) {
            coEvery { scanner.scan(any(), uri, any(), any(), any(), any()) } coAnswers {
                gate.await()
                LibraryScanResult(result, emptyList(), changed = true)
            }
        }

        fun background(body: suspend (Harness) -> Unit): Job =
            background.launch(Dispatchers.IO) { body(this@Harness) }

        /** Waits for the library generation to reach [expected], which is how a test observes intent. */
        suspend fun awaitGeneration(expected: Long) {
            withTimeout(120_000) {
                while (viewModel.readLibraryGeneration() < expected) {
                    delay(5)
                }
            }
        }

        suspend fun awaitUntil(settled: (Harness) -> Boolean) {
            withTimeout(120_000) {
                while (!settled(this@Harness)) {
                    delay(10)
                    val failure = viewModel.startupState.value as? StartupState.Error
                        ?: viewModel.scanFailures.value.takeIf { it.isNotEmpty() }?.let { "scan failures: $it" }
                    if (failure != null) error("unexpected library state: $failure")
                }
            }
        }
    }

    // ── Assertions ──

    /**
     * Checks grouping structure independently of metadata value equality. Tag-edit tests can update
     * fields such as track number and lastModified without changing group membership.
     */
    private fun assertGroupsEqual(
        expected: Map<String, List<TrackData>>,
        actual: Map<String, List<TrackData>>,
        message: String? = null
    ) {
        assertEquals(message ?: "group display order", expected.keys.toList(), actual.keys.toList())
        expected.forEach { (key, tracks) ->
            assertEquals(
                message ?: "track order inside '$key'",
                tracks.map { it.uri.toString() },
                actual.getValue(key).map { it.uri.toString() }
            )
        }
    }

    // ── Occupying Dispatchers.Default ──

    private fun occupyDefaultWorkers(): DefaultWorkerGate {
        val gate = DefaultWorkerGate()
        occupiedWorkers += gate
        gate.occupy()
        return gate
    }

    /**
     * Holds every `Dispatchers.Default` worker so the ViewModel's grouping cannot run until the test
     * releases it. That makes the gap between "grouping computed" and "state published" observable and
     * turns the generation check into a deterministic assertion instead of a race.
     */
    private class DefaultWorkerGate {
        private val latch = CountDownLatch(1)
        private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        fun occupy() {
            val workers = maxOf(4, Runtime.getRuntime().availableProcessors() * 4)
            repeat(workers) {
                scope.launch {
                    // A blocking suspension: the worker thread stays owned by this coroutine until
                    // the latch opens, so the scheduler cannot steal it for the grouping.
                    suspendCancellableCoroutine<Unit> { continuation ->
                        latch.await()
                        if (continuation.isActive) continuation.resume(Unit) { }
                    }
                }
            }
        }

        fun release() {
            latch.countDown()
        }
    }

    private class GatedUri(
        raw: String,
        private val entered: CountDownLatch,
        private val release: CountDownLatch
    ) : TestUri(raw) {
        private val blocked = AtomicBoolean(false)

        override fun toString(): String {
            if (blocked.compareAndSet(false, true)) {
                entered.countDown()
                check(release.await(30, TimeUnit.SECONDS)) { "timed out waiting to release URI de-duplication" }
            }
            return super.toString()
        }
    }

    private fun PlayerViewModel.bumpLibraryGeneration() {
        val field = PlayerViewModel::class.java.getDeclaredField("libraryGeneration").apply { isAccessible = true }
        (field.get(this) as AtomicLong).incrementAndGet()
    }

    private fun PlayerViewModel.readLibraryGeneration(): Long {
        val field = PlayerViewModel::class.java.getDeclaredField("libraryGeneration").apply { isAccessible = true }
        return (field.get(this) as AtomicLong).get()
    }

    // ── Fixtures ──

    /**
     * A library used for the timing-sensitive tests. Artist and album names are padded so the case
     * folding does real work, and every track owns a distinct URI so nothing is de-duplicated away.
     */
    private fun library(tag: String, count: Int): List<TrackData> =
        (0 until count).map { index ->
            TrackData(
                title = "Song $index",
                artist = "Artist $tag $index " + "x".repeat(48),
                album = "Album $tag $index " + "y".repeat(48),
                originalIndex = index,
                uri = TestUri("content://provider/tree/$tag/track-$index.flac")
            )
        }

    private fun track(title: String, artist: String, album: String) = TrackData(
        title = title,
        artist = artist,
        album = album,
        originalIndex = 0,
        uri = TestUri("content://provider/tree/$title.flac")
    )

    private fun mockUri(value: String): Uri = mockk<Uri>(relaxed = true).also { mock ->
        every { mock.toString() } returns value
        every { mock.hashCode() } returns value.hashCode()
        every { mock.equals(any()) } answers { firstArg<Uri>() === mock }
    }

    private fun permission(uri: Uri, read: Boolean, write: Boolean): UriPermission =
        mockk<UriPermission>(relaxed = true).also { permission ->
            every { permission.uri } returns uri
            every { permission.isReadPermission } returns read
            every { permission.isWritePermission } returns write
        }

    private fun setField(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true; set(target, value) }
    }

    private fun getField(target: Any, name: String): Any =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
            ?: error("Field $name was null")
}
