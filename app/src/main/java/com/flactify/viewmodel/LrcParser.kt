package com.flactify.viewmodel

/**
 * A word marker parsed from an enhanced LRC line.
 *
 * The player currently exposes line-level lyrics, but retaining the word timing here keeps
 * enhanced LRC input lossless for a future word-level presentation.
 */
internal data class LrcWord(
    val timestampMs: Long,
    val text: String
)

/** A line in an LRC document, including optional enhanced-LRC word timings. */
internal data class LrcLine(
    val timestampMs: Long,
    val text: String,
    val words: List<LrcWord> = emptyList()
)

/**
 * Small, dependency-free LRC parser.
 *
 * LRC timestamps are represented as milliseconds because that is the timing contract used by
 * the player. Fractions with more than three digits are accepted, with sub-millisecond precision
 * truncated at the contract boundary. Metadata tags (including [offset]) are not lyric lines.
 */
internal object LrcParser {
    private val lineTimestamp = Regex(
        """^\[(\d+):(\d+)(?:[.:](\d+))?\]"""
    )
    private val wordTimestamp = Regex(
        """<(\d+):(\d+)(?:[.:](\d+))?>"""
    )
    private val offsetTag = Regex("""^\[offset\s*:\s*([+-]?\d+)\]$""", RegexOption.IGNORE_CASE)

    /**
     * Parses [raw] into the line-level shape consumed by the existing player.
     *
     * A null or empty input produces no entries. Timed lines are sorted by effective timestamp;
     * untimed lines retain their source order and remain after timed lines, matching the prior
     * presentation behavior.
     */
    fun parse(raw: String?): List<Pair<Long, String>> =
        parseDetailed(raw).map { it.timestampMs to it.text }

    /** Parses LRC while retaining enhanced-LRC word timing for callers that can use it. */
    fun parseDetailed(raw: String?): List<LrcLine> {
        if (raw.isNullOrEmpty()) return emptyList()

        // Lyrics are external metadata. Bound both dimensions before materializing line and
        // timestamp collections so a corrupt tag cannot turn parsing into an unbounded allocation.
        val boundedRaw = raw.take(MAX_INPUT_CHARS).removePrefix("\uFEFF")
        val lines = boundedRaw.lineSequence().take(MAX_LINES).toList()
        val offsetMs = lines.asSequence()
            .mapNotNull { line ->
                offsetTag.matchEntire(line.trim())?.groupValues?.getOrNull(1)?.toLongOrNull()
            }
            .firstOrNull() ?: 0L

        val timed = mutableListOf<IndexedLine>()
        val untimed = mutableListOf<IndexedLine>()
        var sourceOrder = 0

        lines.forEach { sourceLine ->
            val line = sourceLine.removePrefix("\uFEFF")
            val lineTimestamps = mutableListOf<Long>()
            var cursor = 0
            while (cursor < line.length && line[cursor] == '[') {
                val close = line.indexOf(']', cursor + 1)
                if (close < 0) break
                val candidate = line.substring(cursor, close + 1)
                val match = lineTimestamp.matchEntire(candidate) ?: break
                val timestamp = timestampMs(
                    minutes = match.groupValues[1],
                    seconds = match.groupValues[2],
                    fraction = match.groupValues[3].ifEmpty { null }
                ) ?: break
                lineTimestamps += timestamp
                cursor = close + 1
            }

            val wordMatches = wordTimestamp.findAll(line, cursor).toList()
            val hasWordMarkers = wordMatches.isNotEmpty()
            val textStart = if (lineTimestamps.isNotEmpty()) cursor else 0
            val textWithMarkers = line.substring(textStart)
            val cleanedText = wordTimestamp.replace(textWithMarkers, "").trim()
            val words = parseWords(
                textWithMarkers = textWithMarkers,
                matches = wordTimestamp.findAll(textWithMarkers).toList(),
                offsetMs = offsetMs
            )

            // Enhanced LRC found in the wild sometimes omits the outer line timestamp. Use the
            // first word timestamp in that form rather than dropping the line entirely.
            val effectiveTimestamps = if (lineTimestamps.isNotEmpty()) {
                lineTimestamps
            } else if (hasWordMarkers) {
                // [timestamp] is absent here, so use the raw first word timestamp; the common
                // offset is applied below exactly once, just as it is for line timestamps.
                wordTimestamp.find(textWithMarkers)?.let { match ->
                    timestampMs(
                        minutes = match.groupValues[1],
                        seconds = match.groupValues[2],
                        fraction = match.groupValues[3].ifEmpty { null }
                    )
                }?.let(::listOf).orEmpty()
            } else {
                emptyList()
            }

            if (effectiveTimestamps.isNotEmpty() && cleanedText.isNotEmpty()) {
                effectiveTimestamps.forEach { timestamp ->
                    timed += IndexedLine(
                        timestampMs = addOffset(timestamp, offsetMs),
                        text = cleanedText,
                        words = words,
                        sourceOrder = sourceOrder++
                    )
                }
            } else if (isUntimedLyric(line, cleanedText, lineTimestamps.isNotEmpty())) {
                untimed += IndexedLine(-1L, cleanedText, emptyList(), sourceOrder++)
            }
        }

        timed.sortWith(compareBy<IndexedLine> { it.timestampMs }.thenBy { it.sourceOrder })
        return (timed + untimed).map { LrcLine(it.timestampMs, it.text, it.words) }
    }

    private fun parseWords(
        textWithMarkers: String,
        matches: List<MatchResult>,
        offsetMs: Long
    ): List<LrcWord> {
        if (matches.isEmpty()) return emptyList()
        return matches.mapIndexedNotNull { index, match ->
            val timestamp = timestampMs(
                minutes = match.groupValues[1],
                seconds = match.groupValues[2],
                fraction = match.groupValues[3].ifEmpty { null }
            ) ?: return@mapIndexedNotNull null
            val start = match.range.last + 1
            val end = matches.getOrNull(index + 1)?.range?.first ?: textWithMarkers.length
            val text = textWithMarkers.substring(start, end).trim()
            LrcWord(addOffset(timestamp, offsetMs), text)
        }
    }

    private fun isUntimedLyric(line: String, text: String, hadTimestamp: Boolean): Boolean {
        if (hadTimestamp || text.isEmpty()) return false
        // Bracketed metadata and malformed timestamp-looking lines are not lyric text. Plain
        // untimed lines (including lines beginning with '<' only when not a valid word marker)
        // remain available to the expanded lyrics view.
        if (line.trimStart().startsWith('[')) return false
        return true
    }

    private fun timestampMs(minutes: String, seconds: String, fraction: String?): Long? {
        val minuteValue = minutes.toLongOrNull() ?: return null
        val secondValue = seconds.toLongOrNull() ?: return null
        if (secondValue !in 0..59) return null
        if (minuteValue > (Long.MAX_VALUE - secondValue * 1_000L) / 60_000L) return null

        val fractionMs = when {
            fraction.isNullOrEmpty() -> 0L
            fraction.length == 1 -> fraction.toLongOrNull()?.times(100L)
            fraction.length == 2 -> fraction.toLongOrNull()?.times(10L)
            else -> fraction.take(3).toLongOrNull()
        } ?: return null

        val baseMs = minuteValue * 60_000L + secondValue * 1_000L
        if (baseMs > Long.MAX_VALUE - fractionMs) return null
        return baseMs + fractionMs
    }

    private fun addOffset(timestampMs: Long, offsetMs: Long): Long = try {
        Math.addExact(timestampMs, offsetMs)
    } catch (_: ArithmeticException) {
        if (offsetMs < 0L) Long.MIN_VALUE else Long.MAX_VALUE
    }

    private data class IndexedLine(
        val timestampMs: Long,
        val text: String,
        val words: List<LrcWord>,
        val sourceOrder: Int
    )

    private const val MAX_INPUT_CHARS = 1_000_000
    private const val MAX_LINES = 100_000
}
