package com.flactify.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayGainCalculatorTest {
    @Test
    fun disabledByDefaultDoesNotProduceAPlaybackCorrection() {
        val result = ReplayGainCalculator.calculate(
            ReplayGainTags(trackGainDb = -8.0, trackPeak = 0.8),
            ReplayGainPreferences()
        )
        assertEquals(ReplayGainCorrectionStatus.DISABLED, result.status)
        assertNull(result.appliedGainDb)
    }

    @Test
    fun albumSelectionFallsBackToTrackAndPreampIsIncluded() {
        val result = ReplayGainCalculator.calculate(
            ReplayGainTags(trackGainDb = -8.0, trackPeak = 0.5),
            ReplayGainPreferences(
                enabled = true,
                selection = ReplayGainSelection.ALBUM,
                preampDb = 2.0
            )
        )
        assertEquals(-8.0, result.selectedGainDb!!, 0.0)
        assertEquals(-6.0, result.requestedGainDb!!, 0.0)
        assertEquals(-6.0, result.appliedGainDb!!, 0.000001)
        assertEquals(ReplayGainCorrectionStatus.ACTIVE_DIAGNOSTIC_ONLY, result.status)
    }

    @Test
    fun peakGuardClampsBoostWithoutChangingAttenuation() {
        val guarded = ReplayGainCalculator.calculate(
            ReplayGainTags(trackGainDb = 8.0, trackPeak = 0.8),
            ReplayGainPreferences(enabled = true)
        )
        assertTrue(guarded.appliedGainDb!! < guarded.requestedGainDb!!)
        assertEquals(ReplayGainCorrectionStatus.CLIPPING_GUARDED_DIAGNOSTIC_ONLY, guarded.status)

        val attenuated = ReplayGainCalculator.calculate(
            ReplayGainTags(trackGainDb = -8.0, trackPeak = 0.8),
            ReplayGainPreferences(enabled = true)
        )
        assertEquals(-8.0, attenuated.appliedGainDb!!, 0.000001)
    }

    @Test
    fun missingTagsAndR128RemainExplicitlyDifferent() {
        val noTag = ReplayGainCalculator.calculate(
            ReplayGainTags(),
            ReplayGainPreferences(enabled = true)
        )
        assertEquals(ReplayGainCorrectionStatus.NO_TAG, noTag.status)

        val r128 = ReplayGainCalculator.calculate(
            ReplayGainTags(r128TrackGainDb = 1.0),
            ReplayGainPreferences(enabled = true)
        )
        assertEquals(ReplayGainCorrectionStatus.R128_ONLY, r128.status)
        assertEquals(1.0, r128.selectedGainDb!!, 0.0)
    }

    @Test
    fun unsupportedFormatCannotClaimCorrection() {
        val result = ReplayGainCalculator.calculate(
            ReplayGainTags(trackGainDb = -8.0),
            ReplayGainPreferences(enabled = true),
            formatSupported = false
        )
        assertEquals(ReplayGainCorrectionStatus.UNSUPPORTED_FORMAT, result.status)
        assertNull(result.appliedGainDb)
    }
}

