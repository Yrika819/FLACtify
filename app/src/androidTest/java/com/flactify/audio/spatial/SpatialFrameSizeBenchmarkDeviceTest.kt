package com.flactify.audio.spatial

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.sin

/** Measures one fixed native HRTF block at a time on a physical Android device. */
@RunWith(AndroidJUnit4::class)
class SpatialFrameSizeBenchmarkDeviceTest {

    @Test
    fun reportsProcessingTimeQuantilesForSupportedRatesAndFrameSizes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sampleRatesHz = intArrayOf(44_100, 48_000, 88_200, 96_000, 176_400, 192_000)
        val frameSizes = intArrayOf(128, 256, 512)
        var pinnedSofaData: ByteBuffer? = null

        for (sampleRateHz in sampleRatesHz) {
            val hrtfSource = checkNotNull(SpatialHrtfRateSupport.sourceFor(sampleRateHz)) {
                "No licensed HRTF source is configured for $sampleRateHz Hz"
            }
            val sofaData = if (hrtfSource == SpatialHrtfSource.PINNED_CIPIC_SOFA) {
                pinnedSofaData ?: SpatialHrtfAsset.load(context).also { pinnedSofaData = it }
            } else {
                null
            }

            for (frameSize in frameSizes) {
                val engine = NativeSpatialEngine(sampleRateHz, frameSize, sofaData)
                try {
                    val bytesPerFrame = 2 * Float.SIZE_BYTES
                    val input = ByteBuffer.allocateDirect(frameSize * bytesPerFrame)
                        .order(ByteOrder.nativeOrder())
                    repeat(frameSize) { frame ->
                        val phase = 2.0 * Math.PI * 440.0 * frame / sampleRateHz
                        input.putFloat((sin(phase) * 0.2).toFloat())
                        input.putFloat((sin(phase * 1.5) * 0.15).toFloat())
                    }
                    input.flip()
                    val output = ByteBuffer.allocateDirect(frameSize * bytesPerFrame)
                        .order(ByteOrder.nativeOrder())
                    repeat(WARMUP_BLOCKS) {
                        processOneBlock(engine, input, output, frameSize, sampleRateHz)
                    }

                    val elapsedNanos = LongArray(MEASURED_BLOCKS)
                    repeat(MEASURED_BLOCKS) { index ->
                        input.position(0)
                        output.clear()
                        val startedAtNanos = System.nanoTime()
                        val counts = engine.process(
                            input,
                            frameSize,
                            output,
                            NativeSpatialEngine.PcmEncoding.FLOAT_32
                        )
                        elapsedNanos[index] = System.nanoTime() - startedAtNanos
                        assertWholeBlock(counts, frameSize, sampleRateHz)
                    }

                    elapsedNanos.sort()
                    var totalNanos = 0L
                    for (durationNanos in elapsedNanos) totalNanos += durationNanos
                    val meanUs = totalNanos / MEASURED_BLOCKS / 1_000.0
                    val deadlineUs = frameSize * 1_000_000.0 / sampleRateHz
                    Log.i(
                        TAG,
                        "device=${android.os.Build.MODEL} rateHz=$sampleRateHz " +
                            "frameSize=$frameSize blocks=$MEASURED_BLOCKS " +
                            "deadlineUs=$deadlineUs meanUs=$meanUs " +
                            "p95Us=${percentileUs(elapsedNanos, 950)} " +
                            "p99Us=${percentileUs(elapsedNanos, 990)} " +
                            "p999Us=${percentileUs(elapsedNanos, 999)} " +
                            "worstUs=${elapsedNanos.last() / 1_000.0}"
                    )
                } finally {
                    engine.close()
                }
            }
        }
    }

    private fun processOneBlock(
        engine: NativeSpatialEngine,
        input: ByteBuffer,
        output: ByteBuffer,
        frameSize: Int,
        sampleRateHz: Int
    ) {
        input.position(0)
        output.clear()
        val counts = engine.process(
            input,
            frameSize,
            output,
            NativeSpatialEngine.PcmEncoding.FLOAT_32
        )
        assertWholeBlock(counts, frameSize, sampleRateHz)
    }

    private fun assertWholeBlock(counts: Long, frameSize: Int, sampleRateHz: Int) {
        val consumedFrames = (counts ushr 32).toInt()
        val producedFrames = counts.toInt()
        check(consumedFrames == frameSize && producedFrames == frameSize) {
            "Expected one whole processed HRTF block; rateHz=$sampleRateHz " +
                "frameSize=$frameSize consumed=$consumedFrames produced=$producedFrames"
        }
    }

    private fun percentileUs(sortedNanos: LongArray, percentilePerThousand: Int): Double {
        val rank = ceil(sortedNanos.size * percentilePerThousand / 1_000.0)
            .toInt()
            .coerceAtLeast(1)
        return sortedNanos[rank - 1] / 1_000.0
    }

    private companion object {
        const val TAG = "SpatialBlockBenchmark"
        const val WARMUP_BLOCKS = 500
        const val MEASURED_BLOCKS = 4_000
    }
}
