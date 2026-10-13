package com.flactify.viewmodel

/**
 * Pure library grouping shared by every path that publishes the artist/album library.
 *
 * Both the scan/startup path (`PlayerViewModel.updateLibraryState`) and the tag-edit path
 * (`PlayerViewModel.refreshTrackAfterEdit`) used to carry two divergent inline copies of the same
 * algorithm. Extracting it here keeps the two in lock-step and makes the grouping rules directly
 * testable without a ViewModel, a scanner or an emulator.
 *
 * Rules preserved from the original implementation:
 * - duplicate URIs collapse to the first occurrence, preserving the remaining order;
 * - tracks with an artist that equals an already seen artist ignoring case join that group;
 * - the first spelling seen for a group becomes the display key, later spellings are only appended;
 * - the order of tracks inside a group is the order the tracks appear in the input;
 * - the resulting artist/album map is ordered case-insensitively by display key.
 *
 * The grouping is intentionally free of Android UI types, coroutines and side effects so a caller can
 * run it on any dispatcher and publish only its result.
 */
object LibraryGrouping {

    /**
     * Result of a grouping pass. [tracks] is the de-duplicated input order the library UI and the
     * track list are built from; the maps hold the same instances grouped by artist/album.
     */
    data class Groups(
        val tracks: List<TrackData>,
        val artistMap: Map<String, List<TrackData>>,
        val albumMap: Map<String, List<TrackData>>
    )

    /**
     * Groups [tracks] by artist and album.
     *
     * Group assignment is O(n) in the track count: every track pays exactly two proportional hash
     * lookups, independent of how many distinct artists or albums the library contains. Sorting the
     * final maps adds O(k log k), where k is the number of distinct display keys. The previous
     * implementation rescanned the whole key set for every track, which is O(n x k) before sorting
     * and approaches O(n^2) on a library where nearly every track has its own artist or album.
     */
    fun group(tracks: List<TrackData>): Groups {
        val distinctTracks = tracks.distinctBy { it.uri.toString() }

        val artistGroups = LinkedHashMap<String, MutableList<TrackData>>()
        val artistKeys = HashMap<String, String>()
        val albumGroups = LinkedHashMap<String, MutableList<TrackData>>()
        val albumKeys = HashMap<String, String>()

        for (track in distinctTracks) {
            val foldedArtist = caseFold(track.artist)
            val artistKey = artistKeys.getOrPut(foldedArtist) { track.artist }
            artistGroups.getOrPut(artistKey) { ArrayList() }.add(track)

            val foldedAlbum = caseFold(track.album)
            val albumKey = albumKeys.getOrPut(foldedAlbum) { track.album }
            albumGroups.getOrPut(albumKey) { ArrayList() }.add(track)
        }

        return Groups(
            tracks = distinctTracks,
            artistMap = artistGroups.toSortedMap(compareBy { it.lowercase() }),
            albumMap = albumGroups.toSortedMap(compareBy { it.lowercase() })
        )
    }

    /**
     * Canonical case-folding key used to look up an existing group.
     *
     * The original code compared candidate keys with `String.equals(ignoreCase = true)`, which on the
     * JVM is `java.lang.String.equalsIgnoreCase`. That method compares UTF-16 code units with the
     * (`toUpperCase`, then `toLowerCase` from uppercase) pair, so normalising each code unit with
     * exactly the same pair yields the identical equivalence relation:
     *
     * - two strings fold to the same key iff `equalsIgnoreCase` is true for them;
     * - every string folds to a same-length key, so distinct characters can never merge by accident.
     *
     * Deliberately *not* `lowercase()`: `lowercase()` applies the one-to-many Unicode mappings of
     * the root locale, so it splits pairs that `equalsIgnoreCase` merges (U+017F `ſ` vs `s`, U+03C2
     * Greek final sigma vs `σ`) and expands characters `equalsIgnoreCase` never would (U+0130 `İ`).
     * Using it would silently change which groups a library shows.
     */
    fun caseFold(value: String): String {
        val length = value.length
        val folded = CharArray(length)
        for (index in 0 until length) {
            val codeUnit = value[index]
            folded[index] = Character.toLowerCase(Character.toUpperCase(codeUnit))
        }
        return String(folded)
    }
}
