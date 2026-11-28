package com.flactify.ui

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkStatic
import kotlinx.coroutines.CancellationException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LyricsTest {
    @Before
    fun clearCache() {
        ExternalLyricsCache.clearForTests()
    }

    @After
    fun clearAndroidStatics() {
        unmockkStatic(Uri::class)
        unmockkStatic(DocumentsContract::class)
    }

    @Test
    fun `successful provider sibling listing confirms NOT_FOUND without opening a guessed URI`() {
        val fixture = providerFixture(siblingRows = emptyList())

        val result = fixture.load()

        assertEquals(ExternalLyricsStatus.NOT_FOUND, result.status)
        assertTrue(result.lyrics.isEmpty())
        io.mockk.verify(exactly = 0) { fixture.resolver.openInputStream(any()) }
    }

    @Test
    fun `actual PlayerScreen loader contains FileNotFoundException from openInputStream`() {
        val fixture = providerFixture(open = { throw FileNotFoundException("missing after listing") })

        val result = fixture.load()

        assertEquals(ExternalLyricsStatus.ERROR, result.status)
    }

    @Test
    fun `Pixel provider IllegalArgumentException with FileNotFoundException text is recoverable ERROR`() {
        val providerFailure = IllegalArgumentException(
            "Failed to determine if synthetic LRC is child of authorized SAF tree: " +
                "java.io.FileNotFoundException: Missing file"
        )
        assertNull(providerFailure.cause)
        val fixture = providerFixture(open = {
            throw providerFailure
        })

        val result = fixture.load()

        assertEquals(ExternalLyricsStatus.ERROR, result.status)
        assertTrue(result.lyrics.isEmpty())
    }

    @Test
    fun `IllegalArgumentException without confirmed absence is never classified NOT_FOUND`() {
        val fixture = providerFixture(pathFailure = IllegalArgumentException("provider could not determine path"))

        val result = fixture.load()

        assertEquals(ExternalLyricsStatus.ERROR, result.status)
        assertFalse(result.status == ExternalLyricsStatus.NOT_FOUND)
    }

    @Test
    fun `IllegalArgumentException while listing siblings is ERROR rather than NOT_FOUND`() {
        val fixture = providerFixture(childrenQueryFailure = IllegalArgumentException("unclassified provider failure"))

        val result = fixture.load()

        assertEquals(ExternalLyricsStatus.ERROR, result.status)
        assertFalse(result.status == ExternalLyricsStatus.NOT_FOUND)
    }

    @Test
    fun `provider authority mismatch and unverifiable opaque path fail closed`() {
        val mismatch = providerFixture(trackAuthority = "other.documents")
        assertEquals(ExternalLyricsStatus.UNAVAILABLE, mismatch.load().status)
        io.mockk.verify(exactly = 0) { mismatch.resolver.query(any(), any(), any(), any(), any()) }

        val outside = providerFixture(pathRootId = "different opaque root")
        assertEquals(ExternalLyricsStatus.UNAVAILABLE, outside.load().status)
        io.mockk.verify(exactly = 0) { outside.resolver.query(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `SecurityException and unavailable provider cursor preserve player fallback`() {
        val denied = providerFixture(childrenQueryFailure = SecurityException("grant revoked"))
        assertEquals(ExternalLyricsStatus.UNAVAILABLE, denied.load().status)

        val noCursor = providerFixture(returnNullChildrenCursor = true)
        assertEquals(ExternalLyricsStatus.UNAVAILABLE, noCursor.load().status)
    }

    @Test
    fun `IOException and missing stream are recoverable read errors`() {
        val ioFailure = providerFixture(open = { throw IOException("provider temporarily unavailable") })
        assertEquals(ExternalLyricsStatus.ERROR, ioFailure.load().status)

        val nullStream = providerFixture(open = { null })
        assertEquals(ExternalLyricsStatus.ERROR, nullStream.load().status)
    }

    @Test
    fun `read failure in middle of LRC is ERROR and does not return partial lyrics`() {
        val fixture = providerFixture(open = { readThenFail() })

        val result = fixture.load()

        assertEquals(ExternalLyricsStatus.ERROR, result.status)
        assertTrue(result.lyrics.isEmpty())
    }

    @Test
    fun `CancellationException propagates from provider boundary and stream parser`() {
        val fixture = providerFixture(open = { throw CancellationException("track changed") })
        var propagated = false
        try {
            fixture.load()
        } catch (_: CancellationException) {
            propagated = true
        }
        assertTrue(propagated)

        propagated = false
        try {
            readExternalLyricsResult(object : InputStream() {
                override fun read(): Int = throw CancellationException("cancel parse")
            })
        } catch (_: CancellationException) {
            propagated = true
        }
        assertTrue(propagated)
    }

    @Test
    fun `normal Japanese external LRC is found using the provider returned document ID`() {
        val fixture = providerFixture(
            trackName = "長い日本語タイトル.opus",
            siblingName = "長い日本語タイトル.LRC",
            siblingDocumentId = "provider opaque id with %2F and 日本語",
            open = {
                ByteArrayInputStream("[00:01.25]こんにちは\n[00:02.00]世界".toByteArray(Charsets.UTF_8))
            }
        )

        val result = fixture.load()

        assertEquals(ExternalLyricsStatus.FOUND, result.status)
        assertEquals(listOf(1_250L to "こんにちは", 2_000L to "世界"), result.lyrics)
        io.mockk.verify(exactly = 1) {
            DocumentsContract.buildDocumentUriUsingTree(fixture.treeUri, "provider opaque id with %2F and 日本語")
        }
    }

    @Test
    fun `embedded lyrics remain visible when external lyrics are missing or unavailable`() {
        val embedded = listOf(0L to "embedded")
        val missing = ExternalLyricsLoadResult(ExternalLyricsStatus.NOT_FOUND)
        val unavailable = ExternalLyricsLoadResult(ExternalLyricsStatus.UNAVAILABLE)
        val found = ExternalLyricsLoadResult(ExternalLyricsStatus.FOUND, listOf(1_000L to "external"))

        assertEquals(embedded, lyricsForDisplay(embedded, missing, preferExternal = true))
        assertEquals(embedded, lyricsForDisplay(embedded, unavailable, preferExternal = true))
        assertEquals(found.lyrics, lyricsForDisplay(embedded, found, preferExternal = true))
        assertTrue(lyricsForDisplay(emptyList(), missing, preferExternal = false).isEmpty())
    }

    @Test
    fun `ambiguous same-name LRC entries are rejected rather than guessed`() {
        val fixture = providerFixture(siblingRows = listOf(
            listOf("id-one", "song.lrc", 1_000L, 10L),
            listOf("id-two", "song.LRC", 1_001L, 10L)
        ))

        assertEquals(ExternalLyricsStatus.INVALID, fixture.load().status)
        io.mockk.verify(exactly = 0) { fixture.resolver.openInputStream(any()) }
    }

    @Test
    fun `provider errors are not cached and a later retry can find lyrics`() {
        var opens = 0
        val fixture = providerFixture(open = {
            opens++
            if (opens == 1) throw IOException("temporary")
            ByteArrayInputStream("[00:01.00]recovered".toByteArray())
        })

        assertEquals(ExternalLyricsStatus.ERROR, fixture.load().status)
        assertEquals(ExternalLyricsStatus.FOUND, fixture.load().status)
        assertEquals(2, opens)
    }

    @Test
    fun `tree and complete rescan revision separate otherwise identical cache entries`() {
        val first = ExternalLyricsCacheKey("track", "tree-a", 1, "doc", 10, 20)
        val otherTree = first.copy(treeKey = "tree-b")
        val rescan = first.copy(libraryGeneration = 2)
        var loads = 0

        val load: () -> ExternalLyricsLoadResult = {
            loads++
            ExternalLyricsLoadResult(ExternalLyricsStatus.FOUND, listOf(loads.toLong() to "line"))
        }
        assertEquals(1L, ExternalLyricsCache.getOrLoad(first, nowMs = 1, loader = load).lyrics.single().first)
        assertEquals(2L, ExternalLyricsCache.getOrLoad(otherTree, nowMs = 1, loader = load).lyrics.single().first)
        assertEquals(3L, ExternalLyricsCache.getOrLoad(rescan, nowMs = 1, loader = load).lyrics.single().first)
        assertEquals(3, loads)
    }

    @Test
    fun `confirmed absence is not cached and an added LRC is found on the next lookup`() {
        val rows = mutableListOf<List<Any?>>()
        val fixture = providerFixture(siblingRows = rows)
        assertEquals(ExternalLyricsStatus.NOT_FOUND, fixture.load().status)

        rows += listOf("provider-child-id", "song.lrc", 100L, 32L)

        assertEquals(ExternalLyricsStatus.FOUND, fixture.load().status)
    }

    @Test
    fun `external LRC fingerprint refreshes changed lyrics and scan revision bypasses prior result`() {
        val rows = mutableListOf<List<Any?>>(listOf("provider-child-id", "song.lrc", 100L, 32L))
        var content = "[00:01.00]before"
        val fixture = providerFixture(siblingRows = rows, open = { ByteArrayInputStream(content.toByteArray()) })
        assertEquals("before", fixture.load().lyrics.single().second)

        rows[0] = listOf("provider-child-id", "song.lrc", 101L, 33L)
        content = "[00:01.00]after"
        assertEquals("after", fixture.load().lyrics.single().second)

        assertEquals(
            "after",
            loadExternalLyrics(
                fixture.resolver,
                "track-key",
                fixture.trackUri,
                fixture.treeUri,
                libraryGeneration = 1
            ).lyrics.single().second
        )
    }

    @Test
    fun `provider work for one cache key does not hold the cache monitor`() {
        val pool = Executors.newFixedThreadPool(2)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val slowKey = ExternalLyricsCacheKey("slow", "tree", 0, "slow-doc", 1, 1)
        val fastKey = ExternalLyricsCacheKey("fast", "tree", 0, "fast-doc", 1, 1)
        try {
            val slow = pool.submit<ExternalLyricsLoadResult> {
                ExternalLyricsCache.getOrLoad(slowKey) {
                    started.countDown()
                    check(release.await(2, TimeUnit.SECONDS))
                    ExternalLyricsLoadResult(ExternalLyricsStatus.FOUND, listOf(1L to "slow"))
                }
            }
            assertTrue(started.await(2, TimeUnit.SECONDS))

            val fast = pool.submit<ExternalLyricsLoadResult> {
                ExternalLyricsCache.getOrLoad(fastKey) {
                    ExternalLyricsLoadResult(ExternalLyricsStatus.FOUND, listOf(2L to "fast"))
                }
            }
            assertEquals(2L, fast.get(500, TimeUnit.MILLISECONDS).lyrics.single().first)
            release.countDown()
            assertEquals(1L, slow.get(2, TimeUnit.SECONDS).lyrics.single().first)
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `external LRC parser preserves 1 MiB limit and rejects invalid data`() {
        assertEquals(ExternalLyricsStatus.INVALID, readExternalLyricsResult(ByteArrayInputStream(byteArrayOf(1)), maxBytes = 0).status)
        assertEquals(ExternalLyricsStatus.INVALID, readExternalLyricsResult(ByteArrayInputStream(ByteArray(9)), maxBytes = 8).status)
        assertEquals(ExternalLyricsStatus.INVALID, readExternalLyricsResult(ByteArrayInputStream(byteArrayOf(0xff.toByte()))).status)
        assertEquals(
            ExternalLyricsStatus.INVALID,
            readExternalLyricsResult(ByteArrayInputStream(ByteArray(1024 * 1024 + 1))).status
        )
        val oneMiBLine = "[00:00.00]" + "x".repeat(1024 * 1024 - "[00:00.00]".length)
        assertEquals(
            ExternalLyricsStatus.FOUND,
            readExternalLyricsResult(ByteArrayInputStream(oneMiBLine.toByteArray())).status
        )
        assertEquals(
            ExternalLyricsStatus.FOUND,
            readExternalLyricsResult(ByteArrayInputStream("[00:00.00]ok".toByteArray())).status
        )
        assertEquals(ExternalLyricsCache.MAX_CACHED_TEXT_BYTES, 8 * 1024 * 1024)
    }

    @Test
    fun `null external result and empty track remain nonfatal player state`() {
        val result = ExternalLyricsLoadResult(ExternalLyricsStatus.UNAVAILABLE)
        assertNotNull(result)
        assertTrue(lyricsForDisplay(emptyList(), result, preferExternal = true).isEmpty())
        assertNull(readExternalLyrics(ByteArrayInputStream(byteArrayOf(0xff.toByte()))))
    }

    private data class ProviderFixture(
        val resolver: ContentResolver,
        val treeUri: Uri,
        val trackUri: Uri,
        private val trackKey: String = "track-key"
    ) {
        fun load() = loadExternalLyrics(resolver, trackKey, trackUri, treeUri)
    }

    private fun providerFixture(
        trackName: String = "song.opus",
        siblingName: String? = "song.lrc",
        siblingDocumentId: String = "provider-child-id",
        siblingRows: List<List<Any?>>? = null,
        trackAuthority: String = "synthetic.documents",
        pathRootId: String = "root opaque id",
        pathFailure: RuntimeException? = null,
        childrenQueryFailure: RuntimeException? = null,
        returnNullChildrenCursor: Boolean = false,
        open: () -> InputStream? = { ByteArrayInputStream("[00:01.00]line".toByteArray()) }
    ): ProviderFixture {
        mockkStatic(DocumentsContract::class)
        val resolver = mockk<ContentResolver>()
        val treeUri = uri("tree", "synthetic.documents")
        val trackUri = uri("track", trackAuthority)
        val childrenUri = uri("children", "synthetic.documents")
        val lrcUri = uri("lrc", "synthetic.documents")
        val path = mockk<DocumentsContract.Path>()
        every { DocumentsContract.isTreeUri(treeUri) } returns true
        every { DocumentsContract.getTreeDocumentId(treeUri) } returns "root opaque id"
        every { DocumentsContract.getDocumentId(trackUri) } returns "track opaque id"
        every { DocumentsContract.findDocumentPath(resolver, trackUri) } answers {
            pathFailure?.let { throw it }
            path
        }
        every { path.rootId } returns pathRootId
        every { path.path } returns listOf("root opaque id", "parent opaque id", "track opaque id")
        every { DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, "parent opaque id") } returns childrenUri
        every {
            DocumentsContract.buildDocumentUriUsingTree(treeUri, any())
        } answers {
            val id = secondArg<String>()
            if (id == siblingDocumentId) lrcUri else uri("unexpected:$id", "synthetic.documents")
        }

        val trackCursor = cursor(
            listOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            listOf(listOf(trackName))
        )
        every { resolver.query(trackUri, any(), any(), any(), any()) } returns trackCursor
        val rows = siblingRows ?: if (siblingName == null) emptyList() else listOf(
            listOf(siblingDocumentId, siblingName, 100L, 32L)
        )
        every { resolver.query(childrenUri, any(), any(), any(), any()) } answers {
            childrenQueryFailure?.let { throw it }
            if (returnNullChildrenCursor) null else cursor(
                listOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                    DocumentsContract.Document.COLUMN_SIZE
                ),
                rows
            )
        }
        every { resolver.openInputStream(lrcUri) } answers { open() }
        return ProviderFixture(resolver, treeUri, trackUri)
    }

    private fun cursor(columns: List<String>, rows: List<List<Any?>>): Cursor {
        val cursor = mockk<Cursor>()
        var position = -1
        every { cursor.moveToFirst() } answers {
            position = if (rows.isEmpty()) -1 else 0
            rows.isNotEmpty()
        }
        every { cursor.moveToNext() } answers {
            if (position + 1 < rows.size) {
                position++
                true
            } else {
                false
            }
        }
        every { cursor.getColumnIndex(any()) } answers { columns.indexOf(firstArg()) }
        every { cursor.getString(any()) } answers { rows[position][firstArg()] as? String }
        every { cursor.getLong(any()) } answers { (rows[position][firstArg()] as Number).toLong() }
        every { cursor.isNull(any()) } answers { rows[position][firstArg()] == null }
        every { cursor.close() } just runs
        return cursor
    }

    private fun uri(label: String, authority: String): Uri {
        val value = mockk<Uri>(relaxed = true)
        every { value.toString() } returns "content://$authority/$label"
        every { value.authority } returns authority
        return value
    }

    private fun readThenFail(): InputStream = object : InputStream() {
        private val prefix = "[00:01.00]partial".toByteArray()
        private var emitted = false

        override fun read(): Int = throw IOException("read failed")

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (emitted) throw IOException("read failed after partial data")
            emitted = true
            prefix.copyInto(buffer, offset)
            return prefix.size
        }
    }
}
