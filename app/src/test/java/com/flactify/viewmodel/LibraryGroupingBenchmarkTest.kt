package com.flactify.viewmodel

import android.net.TestUri
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * Size/behaviour benchmark for the library grouping.
 *
 * The purpose of this harness is a repeatable record of how the Phase 2A grouping compares with the
 * algorithm it replaced, at library sizes where the old linear key scan was visible. It measures
 * wall-clock cost per grouping pass (median of repeated runs after warm-up, so JIT effects are the
 * same for both implementations) and a rough heap delta for the retained grouping result.
 *
 * The previous implementation is measured after warm-up and with repeated samples at every scale.
 * At 10 000 tracks with a distinct artist per track, even one pass costs seconds, so fewer repeats
 * are used at larger sizes while still providing a median for every requested scale.
 *
 * Deliberately *no* wall-clock threshold is asserted: CI machines span a wide performance range, so
 * a millisecond bound would fail for reasons unrelated to the change. What is asserted is only what
 * holds on any machine: the new implementation must never be slower than the one it replaced at
 * worst-case key cardinality, and it must not scale worse than linearly as the library grows.
 */
class LibraryGroupingBenchmarkTest {

    @Test
    fun `library grouping scales with track count instead of distinct artist count`() {
        val rows = ArrayList<Row>()

        try {
            for (size in TRACK_COUNTS) {
                // A pass over 10 000 tracks with a distinct artist per track leaves a large amount of
                // short-lived map and iterator garbage behind; a collection before the next scenario
                // keeps the small-scale numbers comparable.
                System.gc()
                rows += measure("few distinct keys", size)
                System.gc()
                rows += measure("every track its own key", size)
            }
        } finally {
            writeReport(rows)
        }

        rows.forEach { row -> println(row.summary()) }
        assertBenchmarkInvariants(rows)
    }

    // ── Measurement ──

    private class Row(
        val scenario: String,
        val trackCount: Int,
        val distinctArtists: Int,
        val distinctAlbums: Int,
        val legacyNanos: Long,
        val groupedNanos: Long,
        val legacyHeapBytes: Long?,
        val groupedHeapBytes: Long
    ) {
        val improvementFactor: Double = if (groupedNanos == 0L) 0.0 else legacyNanos.toDouble() / groupedNanos
        val savingPercent: Double = if (legacyNanos == 0L) 0.0 else (1.0 - groupedNanos.toDouble() / legacyNanos) * 100.0

        fun summary(): String = String.format(
            Locale.ROOT,
            "%-22s tracks=%-6d artists=%-6d albums=%-6d previous=%10.3f ms  new=%8.3f ms  x%-9.2f  %.1f%% faster",
            scenario,
            trackCount,
            distinctArtists,
            distinctAlbums,
            legacyNanos / 1_000_000.0,
            groupedNanos / 1_000_000.0,
            improvementFactor,
            savingPercent
        )
    }

    private fun measure(scenario: String, trackCount: Int): Row {
        val tracks = buildTracks(scenario, trackCount)

        val legacy = previousImplementationCost(tracks, trackCount)
        val grouped = newImplementationCost(tracks, trackCount)
        val counted = LibraryGrouping.group(tracks)

        return Row(
            scenario = scenario,
            trackCount = trackCount,
            distinctArtists = counted.artistMap.size,
            distinctAlbums = counted.albumMap.size,
            legacyNanos = legacy.nanos,
            groupedNanos = grouped.nanos,
            legacyHeapBytes = legacy.heapBytes,
            groupedHeapBytes = grouped.heapBytes ?: roughRetainedBytes { LibraryGrouping.group(tracks) }
        )
    }

    private class Sample(val nanos: Long, val heapBytes: Long? = null)

    /**
     * The previous implementation costs O(tracks x distinct keys), so the repeat count decreases at
     * larger scales. The heap figure for smaller cases is measured separately from the timing runs.
     */
    private fun previousImplementationCost(tracks: List<TrackData>, trackCount: Int): Sample {
        val (warmUp, runs) = when {
            trackCount >= 10_000 -> 1 to 5
            trackCount >= 5_000 -> 2 to 7
            trackCount >= 1_000 -> 6 to 20
            else -> 12 to 40
        }

        repeat(warmUp) { legacyGroup(tracks) }
        val samples = LongArray(runs) { nanosOf { legacyGroup(tracks) } }
        samples.sort()

        val heapBytes = if (trackCount <= 1_000) {
            System.gc()
            val before = usedHeapBytes()
            legacyGroup(tracks)
            usedHeapBytes() - before
        } else {
            null
        }
        return Sample(samples[samples.size / 2], heapBytes)
    }

    private fun newImplementationCost(tracks: List<TrackData>, trackCount: Int): Sample {
        val (warmUp, runs) = when {
            trackCount >= 10_000 -> 3 to 9
            trackCount >= 5_000 -> 4 to 11
            trackCount >= 1_000 -> 8 to 25
            else -> 12 to 40
        }

        repeat(warmUp) { LibraryGrouping.group(tracks) }
        val samples = LongArray(runs) { nanosOf { LibraryGrouping.group(tracks) } }
        samples.sort()

        System.gc()
        val before = usedHeapBytes()
        LibraryGrouping.group(tracks)
        return Sample(samples[samples.size / 2], usedHeapBytes() - before)
    }

    private fun nanosOf(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return System.nanoTime() - start
    }

    private fun roughRetainedBytes(block: () -> Unit): Long {
        System.gc()
        val before = usedHeapBytes()
        block()
        return usedHeapBytes() - before
    }

    private fun usedHeapBytes(): Long =
        Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()

    private fun buildTracks(scenario: String, trackCount: Int): List<TrackData> =
        (0 until trackCount).map { index ->
            val artist: String
            val album: String
            when (scenario) {
                "few distinct keys" -> {
                    artist = "Artist ${index % 10}"
                    album = "Album ${index % 20}"
                }
                else -> {
                    artist = "Artist $index"
                    album = "Album $index"
                }
            }
            TrackData(
                title = "Song $index",
                artist = artist,
                album = album,
                originalIndex = index,
                uri = TestUri("content://benchmark/track/$index")
            )
        }

    // ── Reporting ──

    private fun writeReport(rows: List<Row>) {
        runCatching {
            val report = File(REPORT_DIRECTORY, REPORT_NAME).apply { parentFile?.mkdirs() }
            report.writeText(
                buildString {
                    appendLine("# FLACtify library grouping benchmark (Phase 2A)")
                    appendLine()
                    appendLine("Generated by `LibraryGroupingBenchmarkTest`. Wall clock, single JVM. The new")
                    appendLine("and previous implementations are medians of repeated runs after warm-up.")
                    appendLine("The previous implementation is O(tracks x distinct key count), so at")
                    appendLine("10 000 tracks with a distinct artist per track even one pass costs seconds;")
                    appendLine("fewer repeats are used at the larger sizes.")
                    appendLine()
                    appendLine("| scenario | tracks | artists | albums | previous (ms) | new (ms) | factor | saving | previous heap (KB) | new heap (KB) |")
                    appendLine("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
                    rows.forEach { row ->
                        val heap = row.legacyHeapBytes?.let { "${it / 1024}" }
                        appendLine(
                            "| ${row.scenario} | ${row.trackCount} | ${row.distinctArtists} | ${row.distinctAlbums} " +
                                "| ${"%.3f".format(Locale.ROOT, row.legacyNanos / 1_000_000.0)} " +
                                "| ${"%.3f".format(Locale.ROOT, row.groupedNanos / 1_000_000.0)} " +
                                "| ${"%.2f".format(Locale.ROOT, row.improvementFactor)} " +
                                "| ${"%.1f%%".format(Locale.ROOT, row.savingPercent)} " +
                                "| ${heap ?: "n/a"} | ${row.groupedHeapBytes / 1024} |"
                        )
                    }
                }
            )
        }
    }

    // ── Invariants that hold on any machine ──

    private fun assertBenchmarkInvariants(rows: List<Row>) {
        assertTrue("no benchmark rows were produced", rows.isNotEmpty())
        rows.forEach { row ->
            assertTrue("new grouping time was not measured for ${row.summary()}", row.groupedNanos > 0L)
            assertTrue("previous grouping time was not measured for ${row.summary()}", row.legacyNanos > 0L)
        }

        // The old implementation rescanned the whole key set per track, so at worst-case key
        // cardinality it must be clearly slower. Asserting only "not slower" keeps this stable on
        // both fast and slow CI machines while still catching a regrouping regression.
        val worstCase = rows.filter { it.scenario == "every track its own key" && it.trackCount == TRACK_COUNTS.max() }
        assertTrue("worst-case rows missing", worstCase.isNotEmpty())
        worstCase.forEach { row ->
            assertTrue(
                "new grouping must not be slower than the previous implementation at worst-case " +
                    "cardinality: ${row.summary()}",
                row.groupedNanos <= row.legacyNanos
            )
        }

        // The new grouping must stay close to linear, so its per-track cost may not blow up as the
        // library grows. The bound is deliberately generous.
        val unique = rows.filter { it.scenario == "every track its own key" }
        val smallest = unique.minByOrNull { it.trackCount }!!
        val largest = unique.maxByOrNull { it.trackCount }!!
        val perTrackGrowth = (largest.groupedNanos.toDouble() / largest.trackCount) /
            (smallest.groupedNanos.toDouble() / smallest.trackCount)
        assertTrue(
            "per-track grouping cost grew ${"%.1f".format(Locale.ROOT, perTrackGrowth)}x between " +
                "${smallest.trackCount} and ${largest.trackCount} tracks, which is not linear scaling: " +
                "${smallest.summary()} vs ${largest.summary()}",
            perTrackGrowth < MAX_PER_TRACK_GROWTH
        )
    }

    // ── Reference implementation ──

    /** Verbatim copy of the inline grouping that was removed from `PlayerViewModel`. */
    private fun legacyGroup(tracks: List<TrackData>) {
        val distinctTracks = tracks.distinctBy { it.uri.toString() }
        val tempArtistMap = mutableMapOf<String, MutableList<TrackData>>()
        val tempAlbumMap = mutableMapOf<String, MutableList<TrackData>>()

        distinctTracks.forEach { track ->
            val artistKey = track.artist
            val albumKey = track.album

            val existingArtistKey = tempArtistMap.keys.find { it.equals(artistKey, ignoreCase = true) }
            tempArtistMap.getOrPut(existingArtistKey ?: artistKey) { mutableListOf() }.add(track)

            val existingAlbumKey = tempAlbumMap.keys.find { it.equals(albumKey, ignoreCase = true) }
            tempAlbumMap.getOrPut(existingAlbumKey ?: albumKey) { mutableListOf() }.add(track)
        }

        val artistMap = tempArtistMap.toSortedMap(compareBy { it.lowercase() })
        val albumMap = tempAlbumMap.toSortedMap(compareBy { it.lowercase() })
        if (artistMap.isEmpty() && albumMap.isEmpty()) error("benchmark kept no groups")
    }

    private companion object {
        /** Generous bound: linear growth is 1.0, quadratic would be the full track-count ratio. */
        const val MAX_PER_TRACK_GROWTH = 8.0

        val TRACK_COUNTS = listOf(100, 1_000, 5_000, 10_000)

        const val REPORT_DIRECTORY = "build/reports"
        const val REPORT_NAME = "library-grouping-benchmark.md"
    }
}
