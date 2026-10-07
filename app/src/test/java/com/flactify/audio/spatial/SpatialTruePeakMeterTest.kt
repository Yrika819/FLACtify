package com.flactify.audio.spatial

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

class SpatialTruePeakMeterTest {

    @Test
    fun reportsAnIntersampleSinePeakAboveTheLargestInputSample() {
        val meter = SpatialTruePeakMeter()
        val amplitude = 0.9
        var samplePeak = 0.0
        repeat(256) { frame ->
            val sample = (amplitude * sin(PI * frame / 2.0 + PI / 4.0)).toFloat()
            samplePeak = max(samplePeak, abs(sample.toDouble()))
            meter.addStereoFrame(sample, sample)
        }

        val truePeak = meter.finish()
        assertTrue("fixture must have an intersample peak", samplePeak < 0.66)
        assertTrue("true peak did not recover the intersample maximum: $truePeak", truePeak > 0.88)
        assertTrue("true-peak overshoot is implausibly large: $truePeak", truePeak < 0.95)
    }

    @Test
    fun includesTheImpulseTailAfterTheLastInputFrame() {
        val meter = SpatialTruePeakMeter()
        repeat(32) { meter.addStereoFrame(0f, 0f) }
        meter.addStereoFrame(0.95f, -0.95f)

        val truePeak = meter.finish()
        assertTrue("the final impulse tail was not measured: $truePeak", truePeak > 0.8)
    }

    @Test
    fun marksNonFiniteInputAsInvalid() {
        val meter = SpatialTruePeakMeter()
        meter.addStereoFrame(Float.NaN, Float.POSITIVE_INFINITY)

        assertTrue(meter.finish().isNaN())
    }
}
