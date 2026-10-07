package com.flactify.audio.spatial

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class NativeSpatialEngineDeviceTest {

    @Test
    fun supportedDefaultHrtfRatesRenderStereoAndAreChunkBoundaryInvariant() {
        for (sampleRateHz in listOf(24_000, 44_100, 48_000)) {
            val expected = render(sampleRateHz, intArrayOf(128))
            val actual = render(sampleRateHz, intArrayOf(1, 7, 31, 89, 3, 127, 256))
            assertEquals(2_048 * 2, actual.size)
            assertTrue("non-finite HRTF output at $sampleRateHz Hz", actual.all(Float::isFinite))
            assertTrue("silent HRTF output at $sampleRateHz Hz", actual.any { kotlin.math.abs(it) > 1.0e-7f })
            assertArrayEquals("chunking changed output at $sampleRateHz Hz", expected, actual, 1.0e-6f)
        }
    }

    @Test
    fun pinnedCipicSofaHrtfResamplesAtEachHighResolutionStreamRate() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sofaData = SpatialHrtfAsset.load(context)

        for (sampleRateHz in listOf(88_200, 96_000, 176_400, 192_000)) {
            val expected = render(sampleRateHz, intArrayOf(128), sofaData)
            val actual = render(sampleRateHz, intArrayOf(1, 7, 31, 89, 3, 127, 256), sofaData)
            assertEquals(2_048 * 2, actual.size)
            assertTrue("non-finite SOFA HRTF output at $sampleRateHz Hz", actual.all(Float::isFinite))
            assertTrue("silent SOFA HRTF output at $sampleRateHz Hz", actual.any { kotlin.math.abs(it) > 1.0e-7f })
            assertArrayEquals("chunking changed SOFA output at $sampleRateHz Hz", expected, actual, 1.0e-6f)
        }
    }

    @Test
    fun highResolutionRatesDoNotSilentlyFallBackToTheDefaultHrtf() {
        for (sampleRateHz in listOf(88_200, 96_000, 176_400, 192_000)) {
            val failure = runCatching {
                NativeSpatialEngine(sampleRateHz, FRAME_SIZE)
            }.exceptionOrNull()
            assertTrue(
                "expected pinned SOFA data to be required at $sampleRateHz Hz, got $failure",
                failure is IllegalArgumentException
            )
        }
    }

    @Test
    fun outputBackpressureRetainsEveryFrameWithoutDuplication() {
        val sampleRateHz = 48_000
        val frameCount = 1_024
        val source = makeSignal(frameCount)
        val expected = render(sampleRateHz, intArrayOf(128)).copyOf(source.size)
        val input = directBuffer(source, frameCount)
        val output = ByteBuffer.allocateDirect(13 * 2 * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
        val actual = FloatArray(frameCount * 2)
        var outputFramesWritten = 0
        var observedInputBackpressure = false
        var iterations = 0

        NativeSpatialEngine(sampleRateHz, FRAME_SIZE).use { engine ->
            while (outputFramesWritten < frameCount && iterations++ < 10_000) {
                output.clear()
                val offeredInputFrames = input.remaining() / (2 * Float.SIZE_BYTES)
                val counts = engine.process(
                    input,
                    offeredInputFrames,
                    output,
                    NativeSpatialEngine.PcmEncoding.FLOAT_32
                )
                val consumedFrames = (counts ushr 32).toInt()
                val producedFrames = counts.toInt()
                observedInputBackpressure =
                    observedInputBackpressure || consumedFrames < offeredInputFrames
                output.flip()
                output.asFloatBuffer().get(actual, outputFramesWritten * 2, producedFrames * 2)
                outputFramesWritten += producedFrames
                if (offeredInputFrames == 0 && producedFrames == 0) break
            }
        }

        assertTrue("input should be backpressured by the small output buffer", observedInputBackpressure)
        assertEquals(frameCount, outputFramesWritten)
        assertArrayEquals(expected, actual, 1.0e-6f)
    }

    @Test
    fun closeCanBeRepeatedAndRejectsFurtherUse() {
        val engine = NativeSpatialEngine(48_000, FRAME_SIZE)
        engine.close()
        engine.close()

        val failure = runCatching { engine.reset() }.exceptionOrNull()
        assertTrue("closed engine should reject use, got $failure", failure is IllegalStateException)
    }

    @Test
    fun pcm16IsConvertedToFloatAndInvalidFloatSamplesAreSanitized() {
        val pcm16Input = ByteBuffer.allocateDirect(FRAME_SIZE * 2 * Short.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .apply {
                repeat(FRAME_SIZE) { frame ->
                    putShort(if (frame == 0) Short.MAX_VALUE else 0)
                    putShort(0)
                }
                flip()
            }
        val pcm16Output = ByteBuffer.allocateDirect(FRAME_SIZE * 2 * Short.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())

        NativeSpatialEngine(48_000, FRAME_SIZE).use { engine ->
            val counts = engine.process(
                pcm16Input,
                FRAME_SIZE,
                pcm16Output,
                NativeSpatialEngine.PcmEncoding.PCM_16
            )
            assertEquals(FRAME_SIZE, (counts ushr 32).toInt())
            assertEquals(FRAME_SIZE, counts.toInt())
            pcm16Output.position(0)
            assertTrue(
                "the first output block must account for the limiter lookahead delay",
                pcm16Output.asShortBuffer().let { output ->
                    (0 until FRAME_SIZE * 2).all { output.get(it).toInt() == 0 }
                }
            )

            val followingSilence = ByteBuffer.allocateDirect(FRAME_SIZE * 2 * Short.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
                .apply {
                    repeat(FRAME_SIZE * 2) { putShort(0) }
                    flip()
                }
            pcm16Output.clear()
            val delayedCounts = engine.process(
                followingSilence,
                FRAME_SIZE,
                pcm16Output,
                NativeSpatialEngine.PcmEncoding.PCM_16
            )
            assertEquals(FRAME_SIZE, (delayedCounts ushr 32).toInt())
            assertEquals(FRAME_SIZE, delayedCounts.toInt())
            pcm16Output.position(0)
            assertTrue(pcm16Output.asShortBuffer().let { output ->
                (0 until FRAME_SIZE * 2).any { output.get(it).toInt() != 0 }
            })
        }

        val invalidFloatInput = directBuffer(
            FloatArray(FRAME_SIZE * 2) { if (it == 0) Float.NaN else Float.POSITIVE_INFINITY },
            FRAME_SIZE
        )
        val floatOutput = directBuffer(FloatArray(FRAME_SIZE * 2), FRAME_SIZE)
        NativeSpatialEngine(48_000, FRAME_SIZE).use { engine ->
            engine.process(
                invalidFloatInput,
                FRAME_SIZE,
                floatOutput,
                NativeSpatialEngine.PcmEncoding.FLOAT_32
            )
            floatOutput.clear()
            engine.process(
                directBuffer(FloatArray(FRAME_SIZE * 2), FRAME_SIZE),
                FRAME_SIZE,
                floatOutput,
                NativeSpatialEngine.PcmEncoding.FLOAT_32
            )
            floatOutput.position(0)
            val samples = FloatArray(FRAME_SIZE * 2)
            floatOutput.asFloatBuffer().get(samples)
            assertTrue(samples.all(Float::isFinite))
        }
    }

    @Test
    fun finalTailFlushesPartialFrameAndCanBeRepeatedAfterReset() {
        val signal = FloatArray(64 * 2).also { it[0] = 0.5f }
        val collectedTail = ArrayList<Float>()

        NativeSpatialEngine(48_000, FRAME_SIZE).use { engine ->
            val input = directBuffer(signal, 64)
            val regularOutput = directBuffer(FloatArray(64 * 2), 64)
            val inputCounts = engine.process(
                input,
                64,
                regularOutput,
                NativeSpatialEngine.PcmEncoding.FLOAT_32
            )
            assertEquals(64, (inputCounts ushr 32).toInt())
            assertEquals(0, inputCounts.toInt())

            var calls = 0
            while (calls++ < 1_000) {
                val tailBuffer = directBuffer(FloatArray(13 * 2), 13)
                val producedFrames = engine.drainTail(
                    tailBuffer,
                    NativeSpatialEngine.PcmEncoding.FLOAT_32
                )
                if (producedFrames == 0) break
                tailBuffer.position(0)
                val samples = FloatArray(producedFrames * 2)
                tailBuffer.asFloatBuffer().get(samples)
                samples.forEach(collectedTail::add)
            }

            assertTrue("tail did not finish draining", calls < 1_000)
            assertTrue("final EOS produced no tail samples", collectedTail.isNotEmpty())
            assertTrue(collectedTail.all(Float::isFinite))
            assertTrue(collectedTail.any { kotlin.math.abs(it) > 1.0e-7f })

            engine.reset()
            val repeatedInput = directBuffer(signal, 64)
            val repeatedOutput = directBuffer(FloatArray(64 * 2), 64)
            engine.process(
                repeatedInput,
                64,
                repeatedOutput,
                NativeSpatialEngine.PcmEncoding.FLOAT_32
            )
            assertTrue(
                "reset did not re-arm final tail",
                engine.drainTail(
                    directBuffer(FloatArray(13 * 2), 13),
                    NativeSpatialEngine.PcmEncoding.FLOAT_32
                ) > 0
            )
        }
    }

    @Test
    fun resetDiscardsPartialFrameAndFilterHistory() {
        val sampleRateHz = 48_000
        val clean = render(sampleRateHz, intArrayOf(128))
        val input = makeSignal(128)
        val output = FloatArray(128 * 2)

        NativeSpatialEngine(sampleRateHz, FRAME_SIZE).use { engine ->
            val partial = directBuffer(input, 64)
            val partialOutput = directBuffer(FloatArray(128 * 2), 128)
            engine.process(partial, 64, partialOutput, NativeSpatialEngine.PcmEncoding.FLOAT_32)
            engine.reset()

            val wholeInput = directBuffer(input, 128)
            val wholeOutput = directBuffer(FloatArray(128 * 2), 128)
            val counts = engine.process(
                wholeInput,
                128,
                wholeOutput,
                NativeSpatialEngine.PcmEncoding.FLOAT_32
            )
            assertEquals(128, (counts ushr 32).toInt())
            assertEquals(128, counts.toInt())
            wholeOutput.position(0)
            wholeOutput.asFloatBuffer().get(output)
        }

        assertTrue(output.all(Float::isFinite))
        assertArrayEquals(clean.copyOf(output.size), output, 1.0e-6f)
    }

    @Test
    fun dryWetMixUsesAlignedLimiterDelayAndLinearPercentages() {
        val frames = FRAME_SIZE * 2
        val source = makeSignal(frames)
        val dry = renderWithMix(source, 0)
        val wet = renderWithMix(source, 100)
        val half = renderWithMix(source, 50)

        assertTrue(
            "the first limiter-delayed block should be silent at 0% HRTF mix",
            dry.take(FRAME_SIZE * 2).all { it == 0.0f }
        )
        assertArrayEquals(
            "the delayed dry block should contain the first source block unchanged",
            source.copyOfRange(0, FRAME_SIZE * 2),
            dry.copyOfRange(FRAME_SIZE * 2, frames * 2),
            0.0f
        )
        val expectedHalf = FloatArray(dry.size) { index -> (dry[index] + wet[index]) * 0.5f }
        assertArrayEquals(
            "50% mix must use frame-aligned dry and processed samples",
            expectedHalf,
            half,
            1.0e-6f
        )
    }

    private fun renderWithMix(source: FloatArray, mixPercent: Int): FloatArray {
        val frames = source.size / 2
        val input = directBuffer(source, frames)
        val output = directBuffer(FloatArray(source.size), frames)
        NativeSpatialEngine(
            sampleRateHz = 48_000,
            frameSize = FRAME_SIZE,
            hrtfMixPercent = mixPercent
        ).use { engine ->
            val counts = engine.process(
                input,
                frames,
                output,
                NativeSpatialEngine.PcmEncoding.FLOAT_32
            )
            assertEquals(frames, (counts ushr 32).toInt())
            assertEquals(frames, counts.toInt())
        }
        output.position(0)
        return FloatArray(source.size).also { output.asFloatBuffer().get(it) }
    }

    private fun render(
        sampleRateHz: Int,
        chunks: IntArray,
        sofaData: ByteBuffer? = null
    ): FloatArray {
        val frames = 2_048
        val source = makeSignal(frames, sampleRateHz)
        val output = directBuffer(FloatArray(frames * 2), frames)

        NativeSpatialEngine(sampleRateHz, FRAME_SIZE, sofaData).use { engine ->
            var sourceFrame = 0
            var patternIndex = 0
            while (sourceFrame < frames) {
                val chunkFrames = chunks[patternIndex++ % chunks.size].coerceAtMost(frames - sourceFrame)
                val input = ByteBuffer.allocateDirect(chunkFrames * 2 * Float.SIZE_BYTES)
                    .order(ByteOrder.nativeOrder())
                val floatInput = input.asFloatBuffer()
                floatInput.put(source, sourceFrame * 2, chunkFrames * 2)
                input.limit(chunkFrames * 2 * Float.SIZE_BYTES)
                val consumedAndProduced = engine.process(
                    input,
                    chunkFrames,
                    output,
                    NativeSpatialEngine.PcmEncoding.FLOAT_32
                )
                val consumed = (consumedAndProduced ushr 32).toInt()
                assertEquals("native input backpressure in test stream", chunkFrames, consumed)
                sourceFrame += consumed
            }
        }

        output.position(0)
        return FloatArray(frames * 2).also { output.asFloatBuffer().get(it) }
    }

    private fun makeSignal(frames: Int, sampleRateHz: Int = 48_000): FloatArray = FloatArray(frames * 2) { sample ->
        val frame = sample / 2
        val channel = sample % 2
        val sine = sin(2.0 * PI * 440.0 * frame / sampleRateHz).toFloat() * 0.2f
        val impulse = if (frame == 0 && channel == 0) 0.5f else 0.0f
        sine + impulse
    }

    private fun directBuffer(samples: FloatArray, frames: Int): ByteBuffer =
        ByteBuffer.allocateDirect(frames * 2 * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .apply {
                asFloatBuffer().put(samples, 0, frames * 2)
                limit(frames * 2 * Float.SIZE_BYTES)
            }

    private companion object {
        const val FRAME_SIZE = 128
    }
}
