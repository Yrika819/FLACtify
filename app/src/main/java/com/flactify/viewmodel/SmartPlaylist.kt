package com.flactify.viewmodel

enum class SmartPlaylistPreset {
    NONE,
    FREQUENT,
    RECENT,
    FAVORITES,
    LOW_PLAY_COUNT,
    GENRE,
    FORMAT
}

enum class SmartPlaylistOrder {
    LIBRARY,
    TITLE,
    ARTIST,
    ALBUM,
    YEAR,
    FORMAT,
    PLAY_COUNT_DESC,
    LAST_PLAYED_DESC
}

data class SmartPlaylistContext(
    val favorites: Set<String> = emptySet(),
    val playCounts: Map<String, Int> = emptyMap(),
    val lastPlayed: Map<String, Long> = emptyMap()
)

/**
 * Metadata conditions for a dynamic playlist.
 *
 * Every non-blank condition is applied (logical AND). Nullable fields keep the persisted playlist
 * format compact and make an empty query equivalent to the complete library. Matching uses the
 * same Unicode normalization as the library search, so a query written with full-width Latin or
 * half-width Katakana works against normalized metadata as well.
 */
data class SmartPlaylistQuery(
    val titleContains: String? = null,
    val artistContains: String? = null,
    val albumContains: String? = null,
    val genreContains: String? = null,
    val yearContains: String? = null,
    val composerContains: String? = null,
    val albumArtistContains: String? = null,
    val formatContains: String? = null,
    val preset: SmartPlaylistPreset = SmartPlaylistPreset.NONE,
    val order: SmartPlaylistOrder = SmartPlaylistOrder.LIBRARY,
    val limit: Int? = null
) {
    /** Returns whether [track] satisfies every configured condition. */
    fun matches(track: TrackData): Boolean =
        contains(track.title, titleContains) &&
            contains(track.artist, artistContains) &&
            contains(track.album, albumContains) &&
            contains(track.genre, genreContains) &&
            contains(track.year, yearContains) &&
            contains(track.composer, composerContains) &&
            contains(track.albumArtist, albumArtistContains) &&
            contains(track.formatExtension, formatContains)

    /** Filters and orders without inventing metadata such as a date-added value. */
    fun filter(tracks: List<TrackData>, context: SmartPlaylistContext = SmartPlaylistContext()): List<TrackData> {
        var result = tracks.filter(::matches)
        result = when (preset) {
            SmartPlaylistPreset.FAVORITES -> result.filter { context.favorites.contains(it.uri.toString()) }
            SmartPlaylistPreset.RECENT -> result.filter { (context.lastPlayed[it.uri.toString()] ?: 0L) > 0L }
                .sortedByDescending { context.lastPlayed[it.uri.toString()] ?: 0L }
            SmartPlaylistPreset.LOW_PLAY_COUNT -> result.filter { (context.playCounts[it.uri.toString()] ?: 0) <= 1 }
            else -> result
        }
        result = when (order) {
            SmartPlaylistOrder.TITLE -> result.sortedBy { LibrarySearch.normalize(it.title) }
            SmartPlaylistOrder.ARTIST -> result.sortedWith(compareBy<TrackData> { LibrarySearch.normalize(it.artist) }.thenBy { LibrarySearch.normalize(it.title) })
            SmartPlaylistOrder.ALBUM -> result.sortedWith(compareBy<TrackData> { LibrarySearch.normalize(it.album) }.thenBy { LibrarySearch.normalize(it.title) })
            SmartPlaylistOrder.YEAR -> result.sortedWith(compareBy<TrackData> { it.year.orEmpty() }.thenBy { LibrarySearch.normalize(it.title) })
            SmartPlaylistOrder.FORMAT -> result.sortedWith(compareBy<TrackData> { it.formatExtension.orEmpty() }.thenBy { LibrarySearch.normalize(it.title) })
            SmartPlaylistOrder.PLAY_COUNT_DESC -> result.sortedWith(compareByDescending<TrackData> { context.playCounts[it.uri.toString()] ?: 0 }.thenBy { LibrarySearch.normalize(it.title) })
            SmartPlaylistOrder.LAST_PLAYED_DESC -> result.sortedWith(compareByDescending<TrackData> { context.lastPlayed[it.uri.toString()] ?: 0L }.thenBy { LibrarySearch.normalize(it.title) })
            SmartPlaylistOrder.LIBRARY -> result
        }
        if (preset == SmartPlaylistPreset.FREQUENT && order == SmartPlaylistOrder.LIBRARY) {
            result = result.sortedWith(compareByDescending<TrackData> { context.playCounts[it.uri.toString()] ?: 0 }.thenBy { LibrarySearch.normalize(it.title) })
        }
        return limit?.takeIf { it > 0 }?.let(result::take) ?: result
    }

    private fun contains(value: String?, condition: String?): Boolean {
        val normalizedCondition = condition
            ?.trim()
            ?.takeUnless { it.isBlank() }
            ?.let(LibrarySearch::normalize)
            ?: return true
        return value?.let { LibrarySearch.normalize(it).contains(normalizedCondition) } == true
    }
}
