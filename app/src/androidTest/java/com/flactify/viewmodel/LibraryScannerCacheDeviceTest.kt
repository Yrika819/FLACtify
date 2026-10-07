package com.flactify.viewmodel

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class LibraryScannerCacheDeviceTest {
    private lateinit var root: File
    private lateinit var context: Context

    @Before
    fun setUp() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(app.cacheDir, "library-cache-test-${System.nanoTime()}").apply { mkdirs() }
        context = IsolatedContext(app, root)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun legacyCacheMigratesToRebuildableCacheAndRetainsExactFolderOwnership() {
        val folder = Uri.parse("content://provider/tree/music")
        val track = TrackData(
            title = "Song", artist = "Artist", originalIndex = 0,
            uri = Uri.parse("content://provider/document/music%3Asong.flac"), lastModified = 123L
        )
        val scanner = LibraryScanner(null)
        scanner.saveCache(context, folder, listOf(track))
        val managed = File(context.cacheDir, "flactify-library/track_cache.json")
        val legacy = File(context.filesDir, "track_cache.json").apply {
            parentFile?.mkdirs()
            writeBytes(managed.readBytes())
        }
        managed.delete()

        val snapshot = scanner.loadCacheSnapshot(context)
        assertEquals(folder, snapshot?.folderUri)
        assertEquals(track.uri, snapshot?.tracks?.single()?.uri)
        assertFalse(legacy.exists())
        assertTrue(managed.isFile)
        assertTrue(scanner.loadCache(context, Uri.parse("content://provider/tree/music-extra")).isEmpty())
    }

    @Test
    fun invalidLegacyCacheIsPreservedWhenMigrationCannotReadIt() {
        val legacy = File(context.filesDir, "track_cache.json").apply {
            parentFile?.mkdirs()
            writeText("not-json")
        }
        assertEquals(null, LibraryScanner(null).loadCacheSnapshot(context))
        assertTrue(legacy.exists())
        assertEquals("not-json", legacy.readText())
    }

    @Test
    fun failedStagedWritePreservesLegacyAndCanRetry() {
        val folder = Uri.parse("content://provider/tree/retry")
        val valid = LibraryScanner(null)
        valid.saveCache(context, folder, emptyList())
        val legacy = File(context.filesDir, "track_cache.json").apply {
            parentFile?.mkdirs()
            writeBytes(File(context.cacheDir, "flactify-library/track_cache.json").readBytes())
        }
        File(context.cacheDir, "flactify-library/track_cache.json").delete()

        val failing = LibraryScanner(null, migrationWrite = { _, _ -> throw IOException("write failed") })
        assertEquals(null, failing.loadCacheSnapshot(context))
        assertTrue(legacy.isFile)
        assertFalse(File(context.cacheDir, "flactify-library/track_cache.json").exists())
        assertEquals(folder, valid.loadCacheSnapshot(context)?.folderUri)
        assertFalse(legacy.exists())
    }

    @Test
    fun failedStagedVerificationPreservesLegacyAndCanRetry() {
        val folder = Uri.parse("content://provider/tree/verify-retry")
        val valid = LibraryScanner(null)
        valid.saveCache(context, folder, emptyList())
        val legacy = File(context.filesDir, "track_cache.json").apply {
            parentFile?.mkdirs()
            writeBytes(File(context.cacheDir, "flactify-library/track_cache.json").readBytes())
        }
        File(context.cacheDir, "flactify-library/track_cache.json").delete()

        var verificationCount = 0
        val failing = LibraryScanner(null, migrationVerify = { _, _ ->
            verificationCount++
            verificationCount == 1
        })
        assertEquals(null, failing.loadCacheSnapshot(context))
        assertTrue(legacy.isFile)
        assertFalse(File(context.cacheDir, "flactify-library/track_cache.json").exists())
        assertEquals(folder, valid.loadCacheSnapshot(context)?.folderUri)
        assertFalse(legacy.exists())
    }

    @Test
    fun corruptDestinationDoesNotHideValidLegacyCache() {
        val folder = Uri.parse("content://provider/tree/legacy-wins")
        val scanner = LibraryScanner(null)
        scanner.saveCache(context, folder, emptyList())
        val managed = File(context.cacheDir, "flactify-library/track_cache.json")
        val legacy = File(context.filesDir, "track_cache.json").apply {
            parentFile?.mkdirs()
            writeBytes(managed.readBytes())
        }
        managed.writeText("corrupt destination")

        assertEquals(folder, scanner.loadCacheSnapshot(context)?.folderUri)
        assertFalse(legacy.exists())
    }

    private class IsolatedContext(base: Context, private val root: File) : ContextWrapper(base) {
        override fun getNoBackupFilesDir(): File = File(root, "no-backup")
        override fun getFilesDir(): File = File(root, "files")
        override fun getCacheDir(): File = File(root, "cache")
    }
}
