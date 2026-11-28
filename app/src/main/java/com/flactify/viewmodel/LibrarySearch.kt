package com.flactify.viewmodel

import java.text.Normalizer
import java.util.Locale

/**
 * Pure, locale-independent matching for the library search field.
 *
 * NFKC makes equivalent user input match across common Unicode compatibility forms. In
 * particular, full-width Latin text and half-width Japanese Katakana are compared with their
 * normal forms, while composed and combining-mark spellings compare consistently. Lowercasing
 * with [Locale.ROOT] keeps matching independent of the device's current locale.
 */
object LibrarySearch {
    /** Returns the canonical text used by both a query and metadata fields. */
    fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT)

    /** Returns true when [query] occurs in a title, artist, or album field. */
    fun matches(track: TrackData, query: String): Boolean =
        matchesNormalized(track, normalize(query))

    /**
     * Variant for filtering a list: normalize the query once, then reuse it for every track.
     * An empty query matches all tracks so callers can share this predicate with a search field.
     */
    fun matchesNormalized(track: TrackData, normalizedQuery: String): Boolean {
        if (normalizedQuery.isEmpty()) return true
        return normalize(track.title).contains(normalizedQuery) ||
            normalize(track.artist).contains(normalizedQuery) ||
            normalize(track.album).contains(normalizedQuery)
    }
}
