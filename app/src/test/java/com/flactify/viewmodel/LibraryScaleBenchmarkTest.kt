package com.flactify.viewmodel

import android.net.TestUri
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.util.Locale
import kotlin.system.measureNanoTime

/**
 * JVM-only scale smoke test for the pure library stages and bounded JSON cache.
 *
 * This is deliberately not a device benchmark: SAF/provider latency, Media3 extraction, frame
 * time, and process memory are outside its scope. The 50k row case is useful for finding accidental
 * quadratic search/grouping or unbounded cache serialization before a provider is involved.
 */
class LibraryScaleBenchmarkTest {
    @Test
    fun `synthetic library scan search and cache remain measurable at requested scales`() {
        val rows = mutableListOf<String>()
        val tempDir = kotlin.io.path.createTempDirectory("library-scale-benchmark").toFile()
        val folderUri = TestUri("content://synthetic-library/scale")
        try {
            for (size in SIZES) {
                val tracks = buildTracks(size)
                var grouped: LibraryGrouping.Groups? = null
                val scanNanos = measureNanoTime { grouped = LibraryGrouping.group(tracks) }
                val searchNanos = measureNanoTime {
                    val hits = tracks.filter { LibrarySearch.matchesNormalized(it, "song ${size - 1}") }
                    assertEquals(1, hits.size)
                }
                val cacheFile = File(tempDir, "flactify-library/${LibraryScanner.CACHE_FILE_NAME}")
                cacheFile.parentFile?.mkdirs()
                val serializeNanos = measureNanoTime {
                    cacheFile.writeText(buildCacheJson(folderUri, tracks))
                }
                val storageNanos = measureNanoTime {
                    assertEquals(cacheFile.length().toInt(), cacheFile.readBytes().size)
                }
                rows += String.format(
                    Locale.ROOT,
                    "| %d | %.3f | %.3f | %.3f | %d |",
                    size,
                    scanNanos / 1_000_000.0,
                    searchNanos / 1_000_000.0,
                    (serializeNanos + storageNanos) / 1_000_000.0,
                    cacheFile.length()
                )
                check(grouped?.tracks?.size == size)
            }
            File("build/reports/phase8c-library-scale-benchmark.md").apply { parentFile?.mkdirs() }.writeText(
                buildString {
                    appendLine("# Synthetic library scale smoke benchmark")
                    appendLine()
                    appendLine("Single JVM; synthetic TrackData and local JSON cache only. Times are one cold pass per size.")
                    appendLine("SAF/provider, Media3, device jank, and PSS are not measured.")
                    appendLine()
                    appendLine("| tracks | grouping/scan ms | search ms | cache write/read ms | cache bytes |")
                    appendLine("|---:|---:|---:|---:|---:|")
                    rows.forEach(::appendLine)
                }
            )
            rows.forEach(::println)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun buildTracks(size: Int): List<TrackData> = (0 until size).map { index ->
        TrackData(
            title = "Song $index",
            artist = "Artist ${index % 100}",
            album = "Album ${index % 500}",
            originalIndex = index,
            uri = TestUri("content://synthetic-library/track/$index"),
            formatExtension = "flac",
            fileSize = 4096L
        )
    }

    private fun buildCacheJson(folderUri: TestUri, tracks: List<TrackData>): String = buildString {
        append("{\"version\":1,\"folderUri\":\"").append(folderUri).append("\",\"data\":[")
        tracks.forEachIndexed { index, track ->
            if (index > 0) append(',')
            append("{\"t\":\"").append(track.title)
                .append("\",\"ar\":\"").append(track.artist)
                .append("\",\"al\":\"").append(track.album)
                .append("\",\"u\":\"").append(track.uri)
                .append("\",\"lm\":0,\"fx\":\"flac\",\"fs\":4096}")
        }
        append("]}")
    }

    private companion object {
        val SIZES = listOf(1, 10, 50_000)
    }
}
