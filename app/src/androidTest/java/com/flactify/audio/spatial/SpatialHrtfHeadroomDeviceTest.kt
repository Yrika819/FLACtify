package com.flactify.audio.spatial

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Offline signal-level measurements for the pinned Studio HRTF on a physical Android device. */
@RunWith(AndroidJUnit4::class)
class SpatialHrtfHeadroomDeviceTest {

    @Test
    fun fullScalePcm16SweepStaysInsideLimiterCeiling() {
        val sampleRateHz = 48_000
        val signal = makeSignal(SignalKind.LOG_SWEEP, sampleRateHz)
        val inputFrames = signal.samples.size / CHANNELS
        val input = ByteBuffer.allocateDirect(inputFrames * CHANNELS * Short.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
        signal.samples.forEach { sample ->
            input.putShort((sample * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
        }
        input.flip()

        val outputCapacityFrames = inputFrames + 4_096
        val output = ByteBuffer.allocateDirect(outputCapacityFrames * CHANNELS * Short.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
        NativeSpatialEngine(sampleRateHz, FRAME_SIZE).use { engine ->
            val counts = engine.process(
                input,
                inputFrames,
                output,
                NativeSpatialEngine.PcmEncoding.PCM_16
            )
            assertEquals(inputFrames, (counts ushr 32).toInt())
            while (engine.drainTail(output, NativeSpatialEngine.PcmEncoding.PCM_16) > 0) {
                // Drain the final HRTF and limiter lookahead buffers.
            }
        }

        output.flip()
        val pcm = output.asShortBuffer()
        var peak = 0
        while (pcm.hasRemaining()) {
            peak = max(peak, abs(pcm.get().toInt()))
        }
        assertTrue(
            "PCM16 output exceeded the limiter ceiling: peak=$peak",
            peak <= (OUTPUT_SAMPLE_PEAK_CEILING * 32768.0).toInt() + 2
        )
    }

    @Test
    fun recordsSampleAndTruePeakRmsAndCrestForSyntheticSignalsAtBoundaryRates() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sofaData = SpatialHrtfAsset.load(context)

        val referenceBySignal = mutableMapOf<SignalKind, OutputStats>()
        for (sampleRateHz in listOf(48_000, 44_100, 88_200, 96_000, 176_400, 192_000)) {
            val hrtfData = if (sampleRateHz > 48_000) sofaData else null
            for (signalKind in SignalKind.values()) {
                val input = makeSignal(signalKind, sampleRateHz)
                val stats = renderAndMeasure(input, sampleRateHz, hrtfData)
                assertTrue("empty output for " + signalKind + " at " + sampleRateHz + " Hz", stats.sampleCount > 0)
                assertTrue("non-finite HRTF output for " + signalKind + " at " + sampleRateHz + " Hz", stats.isFinite)
                assertTrue("negative peak for " + signalKind + " at " + sampleRateHz + " Hz", stats.peak >= 0.0)
                assertTrue("negative RMS for " + signalKind + " at " + sampleRateHz + " Hz", stats.rms >= 0.0)
                assertTrue(
                    "non-finite true peak for " + signalKind + " at " + sampleRateHz + " Hz: " + stats.truePeak,
                    stats.truePeak.isFinite()
                )
                assertTrue(
                    "sample peak exceeded the safety ceiling for " + signalKind + " at " + sampleRateHz +
                        " Hz: " + stats.peak,
                    stats.peak <= OUTPUT_SAMPLE_PEAK_CEILING + SAMPLE_PEAK_TOLERANCE
                )
                if (signalKind != SignalKind.IMPULSE) {
                    if (sampleRateHz == 48_000) {
                        referenceBySignal[signalKind] = stats
                    } else if (sampleRateHz > 48_000) {
                        val reference = checkNotNull(referenceBySignal[signalKind])
                        val peakError = abs(stats.peak - reference.peak) / reference.peak
                        val rmsError = abs(stats.rms - reference.rms) / reference.rms
                        assertTrue(
                            "SOFA rate gain mismatch for " + signalKind + " at " + sampleRateHz + " Hz: " +
                                "peak=" + stats.peak + " reference=" + reference.peak,
                            peakError <= MAX_RATE_LEVEL_ERROR
                        )
                        assertTrue(
                            "SOFA RMS mismatch for " + signalKind + " at " + sampleRateHz + " Hz: " +
                                "rms=" + stats.rms + " reference=" + reference.rms,
                            rmsError <= MAX_RATE_LEVEL_ERROR
                        )
                    }
                }

                Log.i(
                    TAG,
                    "rateHz=" + sampleRateHz + " signal=" + signalKind +
                        " inputPeak=" + input.peak + " inputRms=" + input.rms +
                        " outputSamplePeak=" + stats.peak + " outputTruePeak=" + stats.truePeak +
                        " outputTruePeakDbtp=" + stats.truePeakDbtp + " outputRms=" + stats.rms +
                        " outputCrestDb=" + stats.crestDb + " outputSamples=" + stats.sampleCount
                )
            }
        }
    }

    private fun renderAndMeasure(
        signal: Signal,
        sampleRateHz: Int,
        sofaHrtfData: ByteBuffer?
    ): OutputStats {
        val blockFrames = FRAME_SIZE
        val input = ByteBuffer.allocateDirect(blockFrames * CHANNELS * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
        val output = ByteBuffer.allocateDirect(OUTPUT_CAPACITY_FRAMES * CHANNELS * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
        val stats = OutputStats()
        val totalFrames = signal.samples.size / CHANNELS

        NativeSpatialEngine(sampleRateHz, blockFrames, sofaHrtfData).use { engine ->
            var frameOffset = 0
            while (frameOffset < totalFrames) {
                val frameCount = minOf(blockFrames, totalFrames - frameOffset)
                input.clear()
                repeat(frameCount * CHANNELS) { sample ->
                    input.putFloat(signal.samples[frameOffset * CHANNELS + sample])
                }
                input.flip()
                output.clear()
                val counts = engine.process(
                    input,
                    frameCount,
                    output,
                    NativeSpatialEngine.PcmEncoding.FLOAT_32
                )
                assertEquals("engine must accept the full signal chunk", frameCount, (counts ushr 32).toInt())
                output.flip()
                stats.add(output)
                frameOffset += frameCount
            }

            var tailFrames: Int
            do {
                output.clear()
                tailFrames = engine.drainTail(output, NativeSpatialEngine.PcmEncoding.FLOAT_32)
                output.flip()
                stats.add(output)
            } while (tailFrames > 0)
        }
        stats.finish()
        return stats
    }

    private fun makeSignal(kind: SignalKind, sampleRateHz: Int): Signal {
        val frames = sampleRateHz
        val samples = FloatArray(frames * CHANNELS)
        when (kind) {
            SignalKind.IMPULSE -> samples[0] = 0.5f
            SignalKind.LOG_SWEEP -> {
                val durationSeconds = frames.toDouble() / sampleRateHz
                val startHz = 20.0
                val endHz = minOf(20_000.0, sampleRateHz * 0.45)
                val logRatio = ln(endHz / startHz)
                for (frame in 0 until frames) {
                    val timeSeconds = frame.toDouble() / sampleRateHz
                    val phase = 2.0 * PI * startHz * durationSeconds / logRatio *
                        (exp(logRatio * timeSeconds / durationSeconds) - 1.0)
                    val sample = sin(phase).toFloat()
                    samples[frame * CHANNELS] = sample
                    samples[frame * CHANNELS + 1] = sample
                }
            }
            SignalKind.PINK_NOISE -> {
                val random = Random(PINK_NOISE_SEED)
                var left0 = 0.0
                var left1 = 0.0
                var left2 = 0.0
                var right0 = 0.0
                var right1 = 0.0
                var right2 = 0.0
                for (frame in 0 until frames) {
                    val leftWhite = random.nextDouble(-1.0, 1.0)
                    val rightWhite = random.nextDouble(-1.0, 1.0)
                    left0 = 0.99765 * left0 + leftWhite * 0.0990460
                    left1 = 0.96300 * left1 + leftWhite * 0.2965164
                    left2 = 0.57000 * left2 + leftWhite * 1.0526913
                    right0 = 0.99765 * right0 + rightWhite * 0.0990460
                    right1 = 0.96300 * right1 + rightWhite * 0.2965164
                    right2 = 0.57000 * right2 + rightWhite * 1.0526913
                    samples[frame * CHANNELS] =
                        ((left0 + left1 + left2 + leftWhite * 0.1848) * 0.05).toFloat()
                    samples[frame * CHANNELS + 1] =
                        ((right0 + right1 + right2 + rightWhite * 0.1848) * 0.05).toFloat()
                }
                normalizeRms(samples, TARGET_PINK_RMS)
            }
        }

        var peak = 0.0
        var sumSquares = 0.0
        for (sample in samples) {
            val value = sample.toDouble()
            peak = max(peak, abs(value))
            sumSquares += value * value
        }
        return Signal(samples, peak, sqrt(sumSquares / samples.size))
    }

    private fun normalizeRms(samples: FloatArray, targetRms: Double) {
        var sumSquares = 0.0
        for (sample in samples) sumSquares += sample.toDouble() * sample
        val rms = sqrt(sumSquares / samples.size)
        if (rms == 0.0) return
        val scale = targetRms / rms
        for (index in samples.indices) samples[index] = (samples[index] * scale).toFloat()
    }

    private data class Signal(
        val samples: FloatArray,
        val peak: Double,
        val rms: Double
    )

    private class OutputStats {
        var peak = 0.0
            private set
        private var sumSquares = 0.0
        var sampleCount = 0L
            private set
        var isFinite = true
            private set
        var rms = 0.0
            private set
        var crestDb = Double.NEGATIVE_INFINITY
            private set
        var truePeak = 0.0
            private set
        var truePeakDbtp = Double.NEGATIVE_INFINITY
            private set
        private val truePeakMeter = SpatialTruePeakMeter()

        fun add(buffer: ByteBuffer) {
            check(buffer.remaining() % BYTES_PER_STEREO_FRAME == 0) {
                "HRTF output must contain complete Float32 stereo frames"
            }
            while (buffer.remaining() >= BYTES_PER_STEREO_FRAME) {
                val left = buffer.float.toDouble()
                val right = buffer.float.toDouble()
                if (!left.isFinite() || !right.isFinite()) isFinite = false
                peak = max(peak, max(abs(left), abs(right)))
                sumSquares += left * left + right * right
                sampleCount += CHANNELS
                truePeakMeter.addStereoFrame(left.toFloat(), right.toFloat())
            }
        }

        fun finish() {
            truePeak = truePeakMeter.finish()
            if (!truePeak.isFinite()) isFinite = false
            truePeakDbtp = if (truePeak > 0.0) 20.0 * kotlin.math.log10(truePeak)
            else Double.NEGATIVE_INFINITY
            if (sampleCount > 0) {
                rms = sqrt(sumSquares / sampleCount)
                crestDb = if (rms == 0.0) Double.NEGATIVE_INFINITY
                else 20.0 * kotlin.math.log10(peak / rms)
            }
        }
    }

    private enum class SignalKind {
        IMPULSE,
        LOG_SWEEP,
        PINK_NOISE
    }

    private companion object {
        const val TAG = "SpatialHeadroom"
        const val CHANNELS = 2
        const val BYTES_PER_STEREO_FRAME = CHANNELS * Float.SIZE_BYTES
        const val FRAME_SIZE = SpatialPcmEngine.FRAME_SIZE
        const val OUTPUT_CAPACITY_FRAMES = 512
        const val PINK_NOISE_SEED = 0x51A7
        const val TARGET_PINK_RMS = 0.125
        const val MAX_RATE_LEVEL_ERROR = 0.15
        const val OUTPUT_SAMPLE_PEAK_CEILING = 0.85
        const val SAMPLE_PEAK_TOLERANCE = 1e-5
    }
}
