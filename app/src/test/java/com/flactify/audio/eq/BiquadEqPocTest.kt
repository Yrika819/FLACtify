package com.flactify.audio.eq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

class BiquadEqPocTest {
    @Test
    fun `peaking response reaches requested center gain`() {
        val coefficients = BiquadEqPoc.design(
            type = BiquadEqPoc.Type.PEAKING,
            sampleRateHz = 48_000.0,
            frequencyHz = 1_000.0,
            gainDb = 6.0,
            q = 1.0
        )

        assertEquals(6.0, coefficients.magnitudeDb(48_000.0, 1_000.0), 0.01)
        assertTrue(coefficients.poles().all { it.magnitude < 1.0 })
    }

    @Test
    fun `shelf responses have finite stable coefficients`() {
        for (type in listOf(BiquadEqPoc.Type.LOW_SHELF, BiquadEqPoc.Type.HIGH_SHELF)) {
            val coefficients = BiquadEqPoc.design(
                type = type,
                sampleRateHz = 192_000.0,
                frequencyHz = if (type == BiquadEqPoc.Type.LOW_SHELF) 100.0 else 12_000.0,
                gainDb = -12.0,
                shelfSlope = 0.7
            )
            assertTrue(coefficients.poles().all { it.magnitude < 1.0 })
            assertTrue(coefficients.magnitudeDb(192_000.0, 100.0).isFinite())
            assertTrue(coefficients.magnitudeDb(192_000.0, 90_000.0).isFinite())
        }
    }

    @Test
    fun `bounded synthetic sine remains finite with twelve db boost`() {
        val state = BiquadEqPoc.State(
            BiquadEqPoc.design(
                type = BiquadEqPoc.Type.PEAKING,
                sampleRateHz = 48_000.0,
                frequencyHz = 1_000.0,
                gainDb = 12.0,
                q = 0.7
            )
        )
        var peak = 0.0
        repeat(48_000) { frame ->
            val sample = 0.25 * sin(2.0 * PI * 1_000.0 * frame / 48_000.0)
            val output = state.process(sample, sample)
            peak = max(peak, max(kotlin.math.abs(output.first), kotlin.math.abs(output.second)))
            assertTrue(output.first.isFinite() && output.second.isFinite())
        }
        assertTrue("the PoC must report headroom honestly", peak < 1.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `design rejects frequency at Nyquist`() {
        BiquadEqPoc.design(
            type = BiquadEqPoc.Type.HIGH_SHELF,
            sampleRateHz = 48_000.0,
            frequencyHz = 24_000.0,
            gainDb = 6.0
        )
    }
}
