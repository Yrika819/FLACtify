package com.flactify.audio.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpatialHrtfRateSupportTest {

    @Test
    fun `built in HRTF data is selected only at its packaged sample rates`() {
        val expectedRates = listOf(24_000, 44_100, 48_000)

        expectedRates.forEach { sampleRateHz ->
            assertEquals(
                SpatialHrtfSource.BUILT_IN_DEFAULT,
                SpatialHrtfRateSupport.sourceFor(sampleRateHz)
            )
        }
    }

    @Test
    fun `pinned SOFA HRTF is selected for high resolution target rates`() {
        val expectedRates = listOf(88_200, 96_000, 176_400, 192_000)

        expectedRates.forEach { sampleRateHz ->
            assertEquals(
                SpatialHrtfSource.PINNED_CIPIC_SOFA,
                SpatialHrtfRateSupport.sourceFor(sampleRateHz)
            )
        }
    }

    @Test
    fun `unverified sample rates remain unsupported`() {
        listOf(8_000, 16_000, 32_000, 64_000, 88_000, 384_000).forEach { sampleRateHz ->
            assertNull(SpatialHrtfRateSupport.sourceFor(sampleRateHz))
        }
    }
}
