package com.flactify.audio.spatial

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/** Device-only measurements for selecting a provisional Steam Audio block size. */
@RunWith(AndroidJUnit4::class)
class NativeSpatialEnginePerformanceDeviceTest {

    @Test
    fun measuresSteadyStateBlockLatencyForSupportedRatesAndCandidateSizes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pinnedSofaHrtfData = SpatialHrtfAsset.load(context)
        for (sampleRateHz in listOf(44_100, 48_000, 88_200, 96_000, 176_400, 192_000)) {
            val hrtfData = if (
                SpatialHrtfRateSupport.sourceFor(sampleRateHz) == SpatialHrtfSource.PINNED_CIPIC_SOFA
            ) {
                pinnedSofaHrtfData
            } else {
                null
            }
            for (frameSize in CANDIDATE_FRAME_SIZES) {
                val samples = FloatArray(frameSize * CHANNELS) { sample ->
                    val frame = sample / CHANNELS
                    val channelOffset = if (sample % CHANNELS == 0) 0.0 else 0.25
                    sin(2.0 * PI * (440.0 + channelOffset) * frame / sampleRateHz).toFloat() * 0.2f
                }
                val input = directBuffer(samples)
                val output = ByteBuffer.allocateDirect(samples.size * Float.SIZE_BYTES)
                    .order(ByteOrder.nativeOrder())
                val elapsedNanos = LongArray(MEASURED_BLOCKS)

                NativeSpatialEngine(sampleRateHz, frameSize, hrtfData).use { engine ->
                    repeat(WARMUP_BLOCKS) {
                        processOneBlock(engine, input, output, frameSize)
                    }
                    repeat(MEASURED_BLOCKS) { iteration ->
                        input.position(0)
                        output.position(0)
                        val startNanos = System.nanoTime()
                        val counts = engine.process(
                            input,
                            frameSize,
                            output,
                            NativeSpatialEngine.PcmEncoding.FLOAT_32
                        )
                        elapsedNanos[iteration] = System.nanoTime() - startNanos
                        assertEquals(frameSize, (counts ushr 32).toInt())
                        assertEquals(frameSize, counts.toInt())
                    }
                }

                elapsedNanos.sort()
                val meanUs = elapsedNanos.average() / NANOS_PER_MICROSECOND
                val p95Us = percentileUs(elapsedNanos, 0.95)
                val p99Us = percentileUs(elapsedNanos, 0.99)
                val p999Us = percentileUs(elapsedNanos, 0.999)
                val worstUs = elapsedNanos.last() / NANOS_PER_MICROSECOND
                val blockBudgetUs = frameSize * NANOS_PER_SECOND / sampleRateHz /
                    NANOS_PER_MICROSECOND

                Log.i(
                    TAG,
                    "rateHz=$sampleRateHz frameSize=$frameSize " +
                        "meanUs=$meanUs p95Us=$p95Us p99Us=$p99Us " +
                        "p999Us=$p999Us worstUs=$worstUs blockBudgetUs=$blockBudgetUs"
                )
            }
        }
    }

    private fun processOneBlock(
        engine: NativeSpatialEngine,
        input: ByteBuffer,
        output: ByteBuffer,
        frameSize: Int
    ) {
        input.position(0)
        output.position(0)
        val counts = engine.process(
            input,
            frameSize,
            output,
            NativeSpatialEngine.PcmEncoding.FLOAT_32
        )
        assertEquals(frameSize, (counts ushr 32).toInt())
        assertEquals(frameSize, counts.toInt())
    }

    private fun percentileUs(sortedNanos: LongArray, percentile: Double): Double {
        val index = ((sortedNanos.size * percentile).toInt() - 1)
            .coerceIn(0, sortedNanos.lastIndex)
        return sortedNanos[index] / NANOS_PER_MICROSECOND
    }

    private fun directBuffer(samples: FloatArray): ByteBuffer =
        ByteBuffer.allocateDirect(samples.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .apply {
                asFloatBuffer().put(samples)
                limit(samples.size * Float.SIZE_BYTES)
            }

    private companion object {
        const val TAG = "SpatialPerf"
        const val CHANNELS = 2
        const val WARMUP_BLOCKS = 200
        const val MEASURED_BLOCKS = 5_000
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val NANOS_PER_MICROSECOND = 1_000.0
        val CANDIDATE_FRAME_SIZES = intArrayOf(128, 256, 512)
    }
}
