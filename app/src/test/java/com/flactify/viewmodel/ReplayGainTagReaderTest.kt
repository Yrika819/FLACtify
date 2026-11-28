package com.flactify.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReplayGainTagReaderTest {
    @Test
    fun parsesReplayGainFieldsCaseInsensitively() {
        val tags = ReplayGainTagReader.fromComments(
            mapOf(
                "replaygain_track_gain" to "-7.23 dB",
                "REPLAYGAIN_ALBUM_GAIN" to "-5.0",
                "REPLAYGAIN_TRACK_PEAK" to "0.891234"
            )
        )
        assertEquals(-7.23, tags.trackGainDb!!, 0.000001)
        assertEquals(-5.0, tags.albumGainDb!!, 0.0)
        assertEquals(0.891234, tags.trackPeak!!, 0.000001)
    }

    @Test
    fun r128UsesSignedQ7_8AndRejectsReplayGainText() {
        assertEquals(1.0, ReplayGainTagReader.fromComments(mapOf("R128_TRACK_GAIN" to "256")).r128TrackGainDb!!, 0.0)
        assertEquals(-589 / 256.0, ReplayGainTagReader.fromComments(mapOf("r128_track_gain" to "-589")).r128TrackGainDb!!, 0.0)
        assertNull(ReplayGainTagReader.fromComments(mapOf("R128_TRACK_GAIN" to "1.0")).r128TrackGainDb)
        assertNull(ReplayGainTagReader.fromComments(mapOf("REPLAYGAIN_TRACK_PEAK" to "0.5 dB")).trackPeak)
    }

    @Test
    fun supportedFormatSetIsExplicit() {
        assertEquals(true, ReplayGainTagReader.supportsExtension("FLAC"))
        assertEquals(true, ReplayGainTagReader.supportsExtension("opus"))
        assertEquals(false, ReplayGainTagReader.supportsExtension("aac"))
    }
}

