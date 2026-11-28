package com.flactify.viewmodel

import java.io.File

/** One media location and the optional metadata carried by an EXTINF line. */
data class M3uEntry(
    val location: String,
    val title: String? = null,
    val durationSeconds: Int? = null
)

/** Parsed M3U/M3U8 contents. Warnings describe ignored malformed or unsafe lines. */
data class M3uPlaylist(
    val entries: List<M3uEntry>,
    val warnings: List<String> = emptyList()
)

/**
 * Bounded, side-effect-free M3U/M3U8 text codec.
 *
 * Parsing never opens a location. Relative local paths are resolved against [baseDirectory] only
 * when one is supplied; paths that escape that directory are ignored with a warning. URI entries
 * (including content:// and https://) and explicit absolute paths are retained as-is. Serialization
 * returns text for the caller to write as UTF-8 and rejects control-line injection.
 */
object M3uPlaylistCodec {
    private const val MAX_ENTRIES = 10_000
    private const val MAX_LINE_LENGTH = 8 * 1024
    private const val MAX_DOCUMENT_CHARS = 8 * 1024 * 1024

    fun parse(text: String, baseDirectory: String? = null): M3uPlaylist {
        if (text.length > MAX_DOCUMENT_CHARS) {
            return M3uPlaylist(emptyList(), listOf("Playlist exceeds the $MAX_DOCUMENT_CHARS character limit"))
        }
        val base = baseDirectory?.let(::File)
        val entries = ArrayList<M3uEntry>()
        val warnings = ArrayList<String>()
        var pending: PendingExtInf? = null
        var entryLimitReported = false

        text.removePrefix("\uFEFF").split('\n').forEachIndexed { index, rawLine ->
            val line = rawLine.removeSuffix("\r")
            if (line.length > MAX_LINE_LENGTH) {
                warnings += "Line ${index + 1} exceeds $MAX_LINE_LENGTH characters"
                pending = null
                return@forEachIndexed
            }
            val value = line.trim()
            if (value.isEmpty() || value.equals("#EXTM3U", ignoreCase = true)) return@forEachIndexed

            when {
                value.startsWith("#EXTINF:", ignoreCase = true) -> {
                    pending = parseExtInf(value, index + 1, warnings)
                }
                value.startsWith('#') -> Unit // comments and HLS directives are not locations
                else -> {
                    val location = resolveLocation(value, base)
                    if (location == null) {
                        warnings += "Line ${index + 1} contains an unsafe path"
                    } else if (entries.size == MAX_ENTRIES) {
                        if (!entryLimitReported) {
                            warnings += "Playlist exceeds the $MAX_ENTRIES entry limit"
                            entryLimitReported = true
                        }
                    } else {
                        entries += M3uEntry(
                            location = location,
                            title = pending?.title,
                            durationSeconds = pending?.durationSeconds
                        )
                    }
                    pending = null
                }
            }
        }
        return M3uPlaylist(entries = entries, warnings = warnings)
    }

    fun serialize(playlist: M3uPlaylist): String {
        require(playlist.entries.size <= MAX_ENTRIES) { "Playlist exceeds the $MAX_ENTRIES entry limit" }
        val output = StringBuilder()
        appendBounded(output, "#EXTM3U\n")
        playlist.entries.forEach { entry ->
            requireSafeLine(entry.location, "location")
            entry.title?.let { requireSafeLine(it, "title") }
            require(entry.location.isNotBlank()) { "M3U location must not be blank" }
            if (entry.title != null || entry.durationSeconds != null) {
                appendBounded(
                    output,
                    "#EXTINF:${entry.durationSeconds ?: -1},${entry.title.orEmpty()}\n"
                )
            }
            appendBounded(output, "${entry.location}\n")
        }
        return output.toString()
    }

    private fun appendBounded(output: StringBuilder, value: String) {
        require(value.length <= MAX_DOCUMENT_CHARS - output.length) {
            "Playlist exceeds the $MAX_DOCUMENT_CHARS character limit"
        }
        output.append(value)
    }

    private fun parseExtInf(value: String, lineNumber: Int, warnings: MutableList<String>): PendingExtInf? {
        val body = value.substringAfter(':', missingDelimiterValue = "")
        val comma = body.indexOf(',')
        if (comma < 0) {
            warnings += "Line $lineNumber has malformed EXTINF metadata"
            return null
        }
        val duration = body.substring(0, comma).trim().toIntOrNull()
        if (duration == null) {
            warnings += "Line $lineNumber has malformed EXTINF duration"
            return null
        }
        val title = body.substring(comma + 1).takeIf { it.isNotEmpty() }
        return PendingExtInf(title = title, durationSeconds = duration)
    }

    private fun resolveLocation(value: String, base: File?): String? {
        if (value.indexOf('\u0000') >= 0) return null
        if (isUri(value) || isWindowsAbsolutePath(value) || value.startsWith('/')) return value
        if (base == null) return value

        val canonicalBase = runCatching { base.canonicalFile }.getOrNull() ?: return null
        val portableRelative = value.replace('\\', File.separatorChar)
        val candidate = runCatching { File(canonicalBase, portableRelative).canonicalFile }.getOrNull()
            ?: return null
        val basePath = canonicalBase.path.trimEnd(File.separatorChar) + File.separator
        return candidate.path.takeIf { it == canonicalBase.path || it.startsWith(basePath) }
    }

    private fun isUri(value: String): Boolean =
        value.matches(Regex("^[A-Za-z][A-Za-z0-9+.-]*:.*")) && !isWindowsAbsolutePath(value)

    private fun isWindowsAbsolutePath(value: String): Boolean =
        value.length >= 3 && value[0].isLetter() && value[1] == ':' &&
            (value[2] == '\\' || value[2] == '/')

    private fun requireSafeLine(value: String, field: String) {
        require(value.indexOf('\r') < 0 && value.indexOf('\n') < 0 && value.indexOf('\u0000') < 0) {
            "M3U $field contains a line-breaking or NUL character"
        }
        require(value.length <= MAX_LINE_LENGTH) { "M3U $field exceeds $MAX_LINE_LENGTH characters" }
    }

    private data class PendingExtInf(val title: String?, val durationSeconds: Int)
}

fun Playlist.toM3uPlaylist(): M3uPlaylist = M3uPlaylist(trackUris.map(::M3uEntry))

fun M3uPlaylist.toPlaylist(
    name: String,
    id: String = java.util.UUID.randomUUID().toString()
): Playlist = Playlist(id = id, name = name, trackUris = entries.map { it.location })
