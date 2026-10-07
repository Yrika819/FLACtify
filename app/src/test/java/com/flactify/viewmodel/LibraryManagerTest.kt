package com.flactify.viewmodel

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import io.mockk.every
import io.mockk.mockk
import android.content.Context
import java.io.File

class LibraryManagerTest {

    private lateinit var tempCacheDir: File

    @Before
    fun setUp() {
        tempCacheDir =
            File(System.getProperty("java.io.tmpdir"), "test_cache_${System.currentTimeMillis()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        tempCacheDir.deleteRecursively()
    }

    @Test
    fun `getCacheDirSize returns 0 for empty directory`() {
        val size = LibraryManager.calculateDirSize(tempCacheDir)
        assertEquals(0L, size)
    }

    @Test
    fun `getCacheDirSize returns correct size for flat files`() {
        File(tempCacheDir, "file1.txt").writeText("a".repeat(100))
        File(tempCacheDir, "file2.txt").writeText("b".repeat(200))
        val size = LibraryManager.calculateDirSize(tempCacheDir)
        assertEquals(300L, size)
    }

    @Test
    fun `getCacheDirSize includes subdirectory files recursively`() {
        File(tempCacheDir, "file1.txt").writeText("a".repeat(100))
        File(tempCacheDir, "subdir").mkdirs()
        File(tempCacheDir, "subdir/file2.txt").writeText("b".repeat(200))
        File(tempCacheDir, "nested/deep").mkdirs()
        File(tempCacheDir, "nested/deep/file3.txt").writeText("c".repeat(300))
        val size = LibraryManager.calculateDirSize(tempCacheDir)
        assertEquals(600L, size)
    }

    @Test
    fun `getCacheDirSize returns 0 for non-existent directory`() {
        val nonExistent = File(tempCacheDir, "does_not_exist")
        val size = LibraryManager.calculateDirSize(nonExistent)
        assertEquals(0L, size)
    }

    @Test
    fun `clearing managed library cache leaves sibling work files untouched`() {
        val context = mockk<Context>(relaxed = true)
        every { context.cacheDir } returns tempCacheDir
        val libraryCache = File(tempCacheDir, "flactify-library").apply { mkdirs() }
        File(libraryCache, "track_cache.json").writeText("rebuildable")
        val work = File(tempCacheDir, "flactify-work").apply { mkdirs() }
        val activeWorkFile = File(work, "edit_work.flac").apply { writeText("active-edit") }

        val manager = LibraryManager(context)
        assertEquals("rebuildable".length.toLong(), manager.getCacheDirSize())
        manager.clearCache()

        assertTrue(!libraryCache.exists())
        assertTrue(activeWorkFile.exists())
        assertEquals("active-edit", activeWorkFile.readText())
    }

    @Test
    fun `corrupt playlist JSON does not throw`() {
        assertTrue(LibraryManager.parsePlaylists("{truncated").isEmpty())
    }

    @Test
    fun `stats entry parser skips malformed siblings and clamps negative counters`() {
        val stats = LibraryManager.parseStatsEntries(
            listOf(
                "content://one" to PlayStatsValues(playCount = 3, skipCount = -2, lastPlayed = 9L),
                "broken" to null
            )
        )

        assertEquals(LibraryManager.PlayStats(playCount = 3, skipCount = 0, lastPlayed = 9L), stats["content://one"])
        assertTrue(!stats.containsKey("broken"))
    }

    @Test
    fun `stats entry parser returns empty for entirely malformed entries`() {
        assertTrue(LibraryManager.parseStatsEntries(listOf("broken" to null)).isEmpty())
    }
}
