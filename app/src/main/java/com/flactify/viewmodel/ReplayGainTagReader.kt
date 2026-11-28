package com.flactify.viewmodel

import com.flactify.audio.ReplayGainTags
import org.jaudiotagger.tag.Tag

/** Reads ReplayGain 1.0 fields from jaudiotagger tags and OpusTags comments. */
internal object ReplayGainTagReader {
    private val supportedExtensions = setOf("flac", "wav", "ogg", "opus", "m4a", "mp3")

    fun supportsExtension(extension: String?): Boolean = extension?.lowercase() in supportedExtensions

    fun fromTag(tag: Tag?): ReplayGainTags = if (tag == null) ReplayGainTags.UNKNOWN else fromValues(
        mapOf(
            "REPLAYGAIN_TRACK_GAIN" to tag.getFirst("REPLAYGAIN_TRACK_GAIN"),
            "REPLAYGAIN_ALBUM_GAIN" to tag.getFirst("REPLAYGAIN_ALBUM_GAIN"),
            "REPLAYGAIN_TRACK_PEAK" to tag.getFirst("REPLAYGAIN_TRACK_PEAK"),
            "REPLAYGAIN_ALBUM_PEAK" to tag.getFirst("REPLAYGAIN_ALBUM_PEAK"),
            "REPLAYGAIN_REFERENCE_LOUDNESS" to tag.getFirst("REPLAYGAIN_REFERENCE_LOUDNESS"),
            "R128_TRACK_GAIN" to tag.getFirst("R128_TRACK_GAIN")
        )
    )

    fun fromComments(comments: Map<String, String>): ReplayGainTags = fromValues(comments)

    private fun fromValues(values: Map<String, String>): ReplayGainTags {
        val normalized = values.mapKeys { it.key.trim().uppercase() }
        return ReplayGainTags(
            trackGainDb = normalized["REPLAYGAIN_TRACK_GAIN"]?.let(::parseGainDb),
            albumGainDb = normalized["REPLAYGAIN_ALBUM_GAIN"]?.let(::parseGainDb),
            trackPeak = normalized["REPLAYGAIN_TRACK_PEAK"]?.let(::parsePeak),
            albumPeak = normalized["REPLAYGAIN_ALBUM_PEAK"]?.let(::parsePeak),
            referenceLoudnessDb = normalized["REPLAYGAIN_REFERENCE_LOUDNESS"]?.let(::parseGainDb),
            r128TrackGainDb = normalized["R128_TRACK_GAIN"]?.let(::parseR128)
        )
    }

    private fun parseGainDb(raw: String): Double? {
        val value = raw.trim().removeSuffix("dB").trim()
        return value.toDoubleOrNull()?.takeIf { it.isFinite() && it in -120.0..120.0 }
    }

    private fun parsePeak(raw: String): Double? = raw.trim().toDoubleOrNull()
        ?.takeIf { it.isFinite() && it >= 0.0 }

    /** Opus RFC 7845 R128_TRACK_GAIN: signed Q7.8 dB, deliberately not ReplayGain text. */
    private fun parseR128(raw: String): Double? = raw.trim().toIntOrNull()
        ?.takeIf { it in Short.MIN_VALUE..Short.MAX_VALUE }
        ?.let { it / 256.0 }
}

