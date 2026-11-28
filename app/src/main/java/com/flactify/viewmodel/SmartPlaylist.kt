package com.flactify.viewmodel

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
    val albumArtistContains: String? = null
) {
    /** Returns whether [track] satisfies every configured condition. */
    fun matches(track: TrackData): Boolean =
        contains(track.title, titleContains) &&
            contains(track.artist, artistContains) &&
            contains(track.album, albumContains) &&
            contains(track.genre, genreContains) &&
            contains(track.year, yearContains) &&
            contains(track.composer, composerContains) &&
            contains(track.albumArtist, albumArtistContains)

    /** Filters while preserving the order of the source library. */
    fun filter(tracks: List<TrackData>): List<TrackData> = tracks.filter(::matches)

    private fun contains(value: String?, condition: String?): Boolean {
        val normalizedCondition = condition
            ?.trim()
            ?.takeUnless { it.isBlank() }
            ?.let(LibrarySearch::normalize)
            ?: return true
        return value?.let { LibrarySearch.normalize(it).contains(normalizedCondition) } == true
    }
}
