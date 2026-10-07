package com.flactify.audio.spatial

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class SpatialAudioProcessorDeviceTest {

    @Test
    fun processorOutputIsInvariantToMedia3InputChunkBoundaries() {
        val signal = IntArray(4_096) { index ->
            when (index % 2) {
                0 -> ((index / 2 * 613) % 30_000) - 15_000
                else -> ((index / 2 * 937) % 28_000) - 14_000
            }
        }
        val expected = render(signal, intArrayOf(128))
        val actual = render(signal, intArrayOf(1, 7, 31, 89, 3, 127, 256))

        assertArrayEquals(expected, actual)
    }

    @Test
    fun unsupportedRateAndChannelLayoutStayBypassedWithAnExplicitReason() {
        for ((sampleRate, channels, reason) in listOf(
            Triple(32_000, 2, "HRTF_RATE_UNAVAILABLE"),
            Triple(48_000, 6, "STUDIO_REQUIRES_STEREO")
        )) {
            val state = SpatialPipelineState()
            val processor = SpatialAudioProcessor(SpatialEosDrainState(), state)
            val inputFormat = AudioProcessor.AudioFormat(sampleRate, channels, C.ENCODING_PCM_16BIT)

            assertEquals(AudioProcessor.AudioFormat.NOT_SET, processor.configure(inputFormat))
            assertFalse(processor.isActive())
            processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
            assertEquals(reason, state.info.bypassReason)
            processor.reset()
        }
    }

    @Test
    fun sofaBackedProcessorKeepsEveryHighResolutionInputRateActive() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sofaData = SpatialHrtfAsset.load(context)

        for (sampleRateHz in listOf(88_200, 96_000, 176_400, 192_000)) {
            val state = SpatialPipelineState()
            val eosDrainState = SpatialEosDrainState()
            val processor = newProcessor(sofaData, state, eosDrainState)
            val inputFormat = AudioProcessor.AudioFormat(sampleRateHz, 2, C.ENCODING_PCM_16BIT)

            assertEquals(inputFormat, processor.configure(inputFormat))
            assertTrue(processor.isActive())
            processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
            assertEquals(SpatialAudioMode.STUDIO, state.info.effectiveMode)
            assertTrue(state.info.hrtfProfile.orEmpty().contains("HRTF resampled"))

            val input = pcmBuffer(intArrayOf(Short.MAX_VALUE.toInt(), 0), 0, 1)
            processor.queueInput(input)
            assertFalse(input.hasRemaining())
            val output = ArrayList<Short>()
            drainAvailable(processor, output)
            eosDrainState.beginFinalEosDrain()
            processor.queueEndOfStream()
            processor.drainEndOfStream(output)
            assertTrue("no processed output at $sampleRateHz Hz", output.any { it.toInt() != 0 })
            processor.reset()
        }
    }

    @Test
    fun compatiblePeriodTransitionKeepsPartialHrtfFrameWithoutDrainingTail() {
        val signal = IntArray(256) { if (it % 2 == 0) 16_000 else -8_000 }
        val processor = newProcessor()
        val output = ArrayList<Short>()

        processor.configure(AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT))
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)

        val first = pcmBuffer(signal, 0, 64)
        processor.queueInput(first)
        assertFalse(first.hasRemaining())
        drainAvailable(processor, output)

        processor.queueEndOfStream()
        assertTrue("non-final EOS should finish without draining the HRTF tail", processor.isEnded())

        processor.configure(AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT))
        processor.flush(
            AudioProcessor.StreamMetadata.Builder()
                .setPositionOffsetUs(64L * 1_000_000L / 48_000L)
                .build()
        )
        val second = pcmBuffer(signal, 64, 64)
        processor.queueInput(second)
        assertFalse(second.hasRemaining())
        drainAvailable(processor, output)

        processor.queueEndOfStream()
        processor.drainEndOfStream(output)

        val expected = render(signal, intArrayOf(128))
        assertArrayEquals(expected, ShortArray(output.size) { output[it] })
        processor.reset()
    }

    @Test
    fun seekResetDiscardsOldPartialFrameAndFilterHistory() {
        val signal = IntArray(256) { if (it % 2 == 0) 10_000 else -4_000 }
        val processor = newProcessor()
        val partial = pcmBuffer(signal, 0, 64)

        processor.configure(AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT))
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
        processor.queueInput(partial)
        assertFalse(partial.hasRemaining())
        processor.resetForDiscontinuity()
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)

        val output = ArrayList<Short>()
        val afterSeek = pcmBuffer(signal, 0, 128)
        processor.queueInput(afterSeek)
        drainAvailable(processor, output)
        processor.queueEndOfStream()
        processor.drainEndOfStream(output)

        assertArrayEquals(render(signal.copyOf(256), intArrayOf(128)), output.toShortArray())
        processor.reset()
    }

    @Test
    fun finalEosPadsPartialFrameAndDrainsHrtfTail() {
        val signal = intArrayOf(Short.MAX_VALUE.toInt(), 0, 0, 0, 0, 0, 0, 0)
        val eosDrainState = SpatialEosDrainState()
        val processor = newProcessor(eosDrainState = eosDrainState)
        val output = ArrayList<Short>()

        processor.configure(AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT))
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
        val input = pcmBuffer(signal, 0, 4)
        processor.queueInput(input)
        assertFalse(input.hasRemaining())
        drainAvailable(processor, output)

        eosDrainState.beginFinalEosDrain()
        processor.queueEndOfStream()
        processor.drainEndOfStream(output)

        assertTrue("final EOS must emit padded frame and convolution tail", output.size > signal.size)
        assertTrue(output.all { it.toInt() in Short.MIN_VALUE..Short.MAX_VALUE })
        processor.reset()
    }

    private fun render(signal: IntArray, chunkPattern: IntArray): ShortArray {
        require(signal.size % 2 == 0)
        val processor = newProcessor()
        val output = ArrayList<Short>()
        processor.configure(AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT))
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)

        val totalFrames = signal.size / 2
        var offsetFrames = 0
        var pattern = 0
        while (offsetFrames < totalFrames) {
            val frameCount = chunkPattern[pattern++ % chunkPattern.size].coerceAtMost(totalFrames - offsetFrames)
            val input = pcmBuffer(signal, offsetFrames, frameCount)
            while (input.hasRemaining()) {
                processor.queueInput(input)
                drainAvailable(processor, output)
            }
            offsetFrames += frameCount
            drainAvailable(processor, output)
        }
        processor.queueEndOfStream()
        processor.drainEndOfStream(output)
        processor.reset()
        return output.toShortArray()
    }

    private fun drainAvailable(processor: SpatialAudioProcessor, output: MutableList<Short>) {
        var buffer = processor.getOutput()
        while (buffer.hasRemaining()) {
            while (buffer.remaining() >= Short.SIZE_BYTES) {
                output += buffer.short
            }
            buffer = processor.getOutput()
        }
    }

    private fun SpatialAudioProcessor.drainEndOfStream(collected: MutableList<Short>) {
        var attempts = 0
        while (!isEnded() && attempts++ < 10_000) {
            drainAvailable(this, collected)
            if (!isEnded() && !getOutput().hasRemaining()) {
                queueInput(AudioProcessor.EMPTY_BUFFER)
                drainAvailable(this, collected)
            }
        }
        assertTrue("Media3 processor did not finish EOS drain", isEnded())
    }

    private fun newProcessor(
        sofaData: ByteBuffer? = null,
        state: SpatialPipelineState = SpatialPipelineState(),
        eosDrainState: SpatialEosDrainState = SpatialEosDrainState()
    ): SpatialAudioProcessor = SpatialAudioProcessor(
        eosDrainState,
        state,
        engineFactory = { sampleRateHz, frameSize ->
            NativeSpatialEngine(sampleRateHz, frameSize, sofaData)
        }
    )

    private fun pcmBuffer(samples: IntArray, offsetFrames: Int, frameCount: Int): ByteBuffer =
        ByteBuffer.allocateDirect(frameCount * 2 * Short.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .apply {
                for (frame in offsetFrames until offsetFrames + frameCount) {
                    putShort(samples[frame * 2].toShort())
                    putShort(samples[frame * 2 + 1].toShort())
                }
                flip()
            }
}
