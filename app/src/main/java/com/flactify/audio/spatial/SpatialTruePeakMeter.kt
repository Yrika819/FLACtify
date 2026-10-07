package com.flactify.audio.spatial

import kotlin.math.abs
import kotlin.math.max

/**
 * Offline true-peak estimate for diagnostics and tests only; never call from the playback thread.
 *
 * Uses the 48-tap, four-phase FIR interpolator from ITU-R BS.1770-5 Annex 2.
 * The returned value is linear full-scale amplitude; values above 1.0 are retained.
 */
internal class SpatialTruePeakMeter {
    private val history = Array(CHANNEL_COUNT) { DoubleArray(TAP_COUNT) }
    private var historyPosition = 0
    private var peakAmplitude = 0.0
    private var containsOnlyFiniteSamples = true
    private var finished = false

    fun addStereoFrame(left: Float, right: Float) {
        check(!finished) { "Cannot add samples after finishing the measurement" }

        val leftSample = left.toFiniteOrZero()
        val rightSample = right.toFiniteOrZero()
        history[0][historyPosition] = leftSample
        history[1][historyPosition] = rightSample
        measureCurrentFrame()
    }

    /** Flushes the FIR history once so peaks near the end of the stream are included. */
    fun finish(): Double {
        if (!finished) {
            repeat(TAP_COUNT - 1) {
                history[0][historyPosition] = 0.0
                history[1][historyPosition] = 0.0
                measureCurrentFrame()
            }
            finished = true
        }
        return if (containsOnlyFiniteSamples) peakAmplitude else Double.NaN
    }

    private fun Float.toFiniteOrZero(): Double {
        if (isFinite()) return toDouble()
        containsOnlyFiniteSamples = false
        return 0.0
    }

    private fun measureCurrentFrame() {
        for (phase in 0 until PHASE_COUNT) {
            for (channel in 0 until CHANNEL_COUNT) {
                var interpolated = 0.0
                for (tap in 0 until TAP_COUNT) {
                    val samplePosition = (historyPosition - tap + TAP_COUNT) % TAP_COUNT
                    interpolated += history[channel][samplePosition] *
                        INTERPOLATION_COEFFICIENTS[tap * PHASE_COUNT + phase]
                }
                peakAmplitude = max(peakAmplitude, abs(interpolated))
            }
        }
        historyPosition = (historyPosition + 1) % TAP_COUNT
    }

    private companion object {
        const val CHANNEL_COUNT = 2
        const val PHASE_COUNT = 4
        const val TAP_COUNT = 12

        // ITU-R BS.1770-5 Annex 2, order-48, four-phase FIR interpolation coefficients.
        val INTERPOLATION_COEFFICIENTS = doubleArrayOf(
            0.0017089843750, -0.0291748046875, -0.0189208984375, -0.0083007812500,
            0.0109863281250, 0.0292968750000, 0.0330810546875, 0.0148925781250,
            -0.0196533203125, -0.0517578125000, -0.0582275390625, -0.0266113281250,
            0.0332031250000, 0.0891113281250, 0.1015625000000, 0.0476074218750,
            -0.0594482421875, -0.1665039062500, -0.2003173828125, -0.1022949218750,
            0.1373291015625, 0.4650878906250, 0.7797851562500, 0.9721679687500,
            0.9721679687500, 0.7797851562500, 0.4650878906250, 0.1373291015625,
            -0.1022949218750, -0.2003173828125, -0.1665039062500, -0.0594482421875,
            0.0476074218750, 0.1015625000000, 0.0891113281250, 0.0332031250000,
            -0.0266113281250, -0.0582275390625, -0.0517578125000, -0.0196533203125,
            0.0148925781250, 0.0330810546875, 0.0292968750000, 0.0109863281250,
            -0.0083007812500, -0.0189208984375, -0.0291748046875, 0.0017089843750
        )
    }
}
