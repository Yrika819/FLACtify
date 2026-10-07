package com.flactify.audio.spatial

import android.media.AudioDeviceInfo
import com.flactify.audio.ActualOutputInfo
import com.flactify.audio.PlaybackDiagnosticsBus
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioSink
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

class SpatialAudioOutputPcmTest {

    @Test
    fun pcmOutputPreservesFramesAcrossArbitraryChunksAndPartialWrites() {
        val sourceSamples = ShortArray(22) { index -> (index * 37 + 101).toShort() }
        val sinkOutput = RecordingAudioOutput(maxWriteBytes = 8)
        val engine = BlockSpatialEngine(blockFrames = 4)
        val output = newSpatialOutput(sinkOutput, engine)
        val initialTimeUs = 1_234_567L

        writeChunks(output, sourceSamples, intArrayOf(1, 3, 2, 5), initialTimeUs)

        val expected = ShortArray(16)
        for (frame in 0 until 8) {
            expected[frame * 2] = sourceSamples[frame * 2]
            expected[frame * 2 + 1] = (-sourceSamples[frame * 2 + 1]).toShort()
        }
        assertArrayEquals(expected.toLittleEndianBytes(), sinkOutput.captured.toByteArray())
        assertTrue("the downstream fake must apply backpressure", sinkOutput.partialWriteCount >= 2)
        assertEquals(
            listOf(initialTimeUs, initialTimeUs, initialTimeUs + 83L, initialTimeUs + 83L),
            sinkOutput.calls.map { it.presentationTimeUs }
        )
        assertTrue(
            "retries must keep their processed buffer and metadata",
            sinkOutput.partialRetriesKeptBufferAndMetadata
        )
    }

    @Test
    fun acceptedOutputObserverCapturesOnlyBytesConsumedAcrossPartialWrites() {
        val sourceSamples = ShortArray(22) { index -> (index * 41 + 17).toShort() }
        val sinkOutput = RecordingAudioOutput(maxWriteBytes = 8)
        val observer = RecordingSpatialOutputObserver(bytesPerFrame = 4)
        val output = SpatialAudioOutput(
            downstream = sinkOutput,
            spatialEngine = BlockSpatialEngine(blockFrames = 4),
            encoding = NativeSpatialEngine.PcmEncoding.PCM_16,
            sampleRateHz = 48_000,
            outputObserver = observer
        )

        writeChunks(output, sourceSamples, intArrayOf(1, 3, 2, 5), 2_000L)

        assertArrayEquals(sinkOutput.captured.toByteArray(), observer.capturedBytes())
        assertEquals(sinkOutput.calls.size, observer.eventCount)
        assertTrue("the downstream fake must exercise partial writes", sinkOutput.partialWriteCount >= 2)
        var expectedFrameIndex = 0L
        for (index in 0 until observer.eventCount) {
            assertEquals(expectedFrameIndex, observer.frameIndex(index))
            assertEquals(2, observer.frameCount(index))
            assertEquals(sinkOutput.calls[index].presentationTimeUs, observer.presentationTimeUs(index))
            assertEquals(0, observer.finalTailFrameCount(index))
            expectedFrameIndex += observer.frameCount(index)
        }
        assertEquals(8L, expectedFrameIndex)
        assertTrue(sinkOutput.partialRetriesKeptBufferAndMetadata)
    }

    @Test
    fun float32PcmKeepsSampleBitsAcrossPartialOutputWrites() {
        val source = floatArrayOf(0.25f, -0.125f, 1f, -1f, 0.5f, -0.5f, 0f, -0f)
        val sinkOutput = RecordingAudioOutput(maxWriteBytes = 8, bytesPerFrame = 8)
        val engine = FloatCopySpatialEngine()
        val output = SpatialAudioOutput(
            downstream = sinkOutput,
            spatialEngine = engine,
            encoding = NativeSpatialEngine.PcmEncoding.FLOAT_32,
            sampleRateHz = 96_000
        )
        val input = ByteBuffer.allocateDirect(source.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
        source.forEach(input::putFloat)
        input.flip()

        var handled = false
        var attempts = 0
        while (!handled && attempts++ < 10) {
            handled = output.write(
                input,
                if (attempts == 1) 1 else 0,
                if (attempts == 1) 500L else C.TIME_END_OF_SOURCE
            )
        }

        assertTrue("Float32 PCM was not fully accepted", handled)
        assertEquals(input.limit(), input.position())
        assertArrayEquals(source.toNativeBytes(), sinkOutput.captured.toByteArray())
        assertTrue(sinkOutput.partialRetriesKeptBufferAndMetadata)
        assertEquals(listOf(500L, 500L, 500L, 500L), sinkOutput.calls.map { it.presentationTimeUs })
        assertEquals(NativeSpatialEngine.PcmEncoding.FLOAT_32, engine.lastEncoding)
    }

    @Test
    fun discontinuityDropsBufferedPreSeekFramesBeforeAcceptingNewPcm() {
        val sinkOutput = RecordingAudioOutput(maxWriteBytes = 64)
        val engine = BlockSpatialEngine(blockFrames = 4)
        val output = newSpatialOutput(sinkOutput, engine)
        val preSeek = shortBuffer(shortArrayOf(10, 11, 12, 13))
        assertTrue(output.write(preSeek, 1, 0L))
        assertEquals(preSeek.limit(), preSeek.position())

        output.resetForDiscontinuity()
        val postSeekSamples = shortArrayOf(21, 22, 23, 24, 25, 26, 27, 28)
        writeChunks(output, postSeekSamples, intArrayOf(4), 5_000_000L)

        val expected = ShortArray(postSeekSamples.size) { index ->
            if (index % 2 == 0) postSeekSamples[index] else (-postSeekSamples[index]).toShort()
        }
        assertArrayEquals(expected.toLittleEndianBytes(), sinkOutput.captured.toByteArray())
    }

    @Test
    fun outputFlushDropsBufferedPreSeekFramesBeforeAcceptingNewPcm() {
        val sinkOutput = RecordingAudioOutput(maxWriteBytes = 64)
        val engine = BlockSpatialEngine(blockFrames = 4)
        val output = newSpatialOutput(sinkOutput, engine)

        val preSeek = shortBuffer(shortArrayOf(10, 11, 12, 13))
        assertTrue(output.write(preSeek, 1, 0L))
        assertEquals(0, sinkOutput.captured.size())

        output.flush()
        assertEquals("flush resets the HRTF state", 1, engine.resetCount)

        val postSeek = shortBuffer(shortArrayOf(21, 22, 23, 24, 25, 26, 27, 28))
        assertTrue(output.write(postSeek, 1, 5_000_000L))
        assertEquals(postSeek.limit(), postSeek.position())
        assertArrayEquals(
            shortArrayOf(21, -22, 23, -24, 25, -26, 27, -28).toLittleEndianBytes(),
            sinkOutput.captured.toByteArray()
        )
    }

    @Test
    fun releasingOutputTwiceClosesEngineAndDownstreamOnce() {
        val sinkOutput = RecordingAudioOutput(maxWriteBytes = 64)
        val engine = BlockSpatialEngine(blockFrames = 4)
        val output = newSpatialOutput(sinkOutput, engine)

        output.release()
        output.release()

        assertEquals(1, engine.closeCount)
        assertEquals(1, sinkOutput.releaseCount)
    }

    @Test
    fun processedStreamDiscontinuityPreservesHrtfForContinuousNextBuffer() {
        val sinkOutput = RecordingAudioOutput(maxWriteBytes = 64)
        val engine = BlockSpatialEngine(blockFrames = 2)
        val eosState = SpatialEosDrainState()
        val output = newSpatialOutput(sinkOutput, engine, eosState)
        val sink = SpatialAudioSink(noOpAudioSink(), eosState)

        val first = shortBuffer(shortArrayOf(1, 2, 3, 4))
        assertTrue(output.write(first, 1, 1_000L))
        sink.handleDiscontinuity()
        val second = shortBuffer(shortArrayOf(5, 6, 7, 8))
        assertTrue(output.write(second, 1, 1_041L))

        assertEquals("a compatible stream transition must retain the HRTF engine", 0, engine.resetCount)
        assertArrayEquals(
            shortArrayOf(1, -2, 3, -4, 5, -6, 7, -8).toLittleEndianBytes(),
            sinkOutput.captured.toByteArray()
        )
    }

    @Test
    fun processedStreamBoundaryPreservesHrtfAcrossRoundedPeriodOffset() {
        val sinkOutput = RecordingAudioOutput(maxWriteBytes = 64)
        val engine = BlockSpatialEngine(blockFrames = 2)
        val eosState = SpatialEosDrainState()
        val output = newSpatialOutput(sinkOutput, engine, eosState)
        val sink = SpatialAudioSink(noOpAudioSink(), eosState)

        assertTrue(output.write(shortBuffer(shortArrayOf(1, 2, 3, 4)), 1, 1_000_000L))
        eosState.markProcessedStreamChange()
        sink.handleDiscontinuity()

        assertTrue(output.write(shortBuffer(shortArrayOf(5, 6, 7, 8)), 1, 999_374L))

        assertEquals("a rounded compatible period offset must retain convolution state", 0, engine.resetCount)
        assertArrayEquals(
            shortArrayOf(1, -2, 3, -4, 5, -6, 7, -8).toLittleEndianBytes(),
            sinkOutput.captured.toByteArray()
        )
    }

    @Test
    fun actualTimestampJumpResetsHrtfAfterMedia3DiscontinuitySignal() {
        val sinkOutput = RecordingAudioOutput(maxWriteBytes = 64)
        val engine = BlockSpatialEngine(blockFrames = 2)
        val eosState = SpatialEosDrainState()
        val output = newSpatialOutput(sinkOutput, engine, eosState)
        val sink = SpatialAudioSink(noOpAudioSink(), eosState)

        assertTrue(output.write(shortBuffer(shortArrayOf(1, 2, 3, 4)), 1, 1_000L))
        sink.handleDiscontinuity()
        assertEquals("the signal alone must preserve potentially compatible state", 0, engine.resetCount)

        assertTrue(output.write(shortBuffer(shortArrayOf(5, 6, 7, 8)), 1, 1_000_000L))

        assertEquals("a real timestamp jump must reset the HRTF engine", 1, engine.resetCount)
    }

    @Test
    fun compatibleProcessedStreamBoundaryKeepsPartialBlockAndEmitsNoInterTrackTail() {
        val sinkOutput = RecordingAudioOutput(maxWriteBytes = 64)
        val engine = BlockSpatialEngine(blockFrames = 4)
        val eosState = SpatialEosDrainState()
        val output = newSpatialOutput(sinkOutput, engine, eosState)
        val sink = SpatialAudioSink(noOpAudioSink(), eosState)

        val firstTrackFrames = shortBuffer(shortArrayOf(1, 2, 3, 4))
        assertTrue(output.write(firstTrackFrames, 1, 1_000L))
        assertEquals(firstTrackFrames.limit(), firstTrackFrames.position())
        assertEquals("two frames wait for the four-frame HRTF block", 0, sinkOutput.captured.size())

        sink.handleDiscontinuity()

        val secondTrackFrames = shortBuffer(shortArrayOf(5, 6, 7, 8))
        assertTrue(output.write(secondTrackFrames, 1, 1_041L))
        assertEquals(secondTrackFrames.limit(), secondTrackFrames.position())
        assertEquals("a compatible transition must not reset convolution state", 0, engine.resetCount)
        assertEquals("a playlist transition must not drain a final EOS tail", 0, engine.tailDrainCount)
        assertArrayEquals(
            shortArrayOf(1, -2, 3, -4, 5, -6, 7, -8).toLittleEndianBytes(),
            sinkOutput.captured.toByteArray()
        )
    }

    @Test
    fun mediaItemTransitionResetPreservesSpatialOutputConfiguration() {
        val activeInfo = SpatialPipelineInfo(
            requestedMode = SpatialAudioMode.STUDIO,
            effectiveMode = SpatialAudioMode.STUDIO,
            engine = "Steam Audio",
            engineVersion = "4.8.1",
            inputEncoding = "PCM16",
            inputSampleRateHz = 48_000,
            inputChannelCount = 2,
            internalFormat = "Float32",
            frameSize = 128,
            hrtfProfile = "CIPIC",
            speakerLayout = "stereo",
            algorithmLatencyFrames = 128
        )
        val activeOutputInfo = ActualOutputInfo(
            pcmEncoding = com.flactify.audio.PcmEncoding.PCM_16BIT,
            sampleRateHz = 48_000,
            channelMask = android.media.AudioFormat.CHANNEL_OUT_STEREO,
            isOffload = false,
            isTunneling = false
        )
        PlaybackDiagnosticsBus.resetAll()
        PlaybackDiagnosticsBus.publishSpatial(activeInfo)
        PlaybackDiagnosticsBus.publishOutput(activeOutputInfo)
        PlaybackDiagnosticsBus.publishDecoderName("test decoder")

        PlaybackDiagnosticsBus.resetForMediaItemTransition()

        assertEquals(activeInfo, PlaybackDiagnosticsBus.spatial.value)
        assertEquals(null, PlaybackDiagnosticsBus.renderer.value.decoderName)
        assertEquals(activeOutputInfo, PlaybackDiagnosticsBus.actualOutput.value)
        PlaybackDiagnosticsBus.resetAll()
    }

    @Test
    fun finalEosWritesConvolutionTailBeforeStoppingAndOffsetsOutputPosition() {
        val sourceSamples = ShortArray(22) { index -> (index * 19 + 301).toShort() }
        val sinkOutput = RecordingAudioOutput(maxWriteBytes = 8)
        val engine = BlockSpatialEngine(blockFrames = 4)
        val eosState = SpatialEosDrainState()
        val output = newSpatialOutput(sinkOutput, engine, eosState)

        writeChunks(output, sourceSamples, intArrayOf(1, 3, 2, 5), 0L)
        eosState.beginFinalEosDrain()
        output.stop()

        assertEquals("final EOS must defer AudioTrack stop until the tail is accepted", 0, sinkOutput.stopCount)
        var attempts = 0
        var drained = false
        while (!drained && attempts++ < 20) {
            drained = output.pumpFinalEosTail()
        }

        assertTrue("final spatial tail did not finish", drained)
        assertEquals(1, sinkOutput.stopCount)
        val expected = ShortArray(26)
        for (frame in 0 until 11) {
            expected[frame * 2] = sourceSamples[frame * 2]
            expected[frame * 2 + 1] = (-sourceSamples[frame * 2 + 1]).toShort()
        }
        expected[22] = 111
        expected[23] = -222
        expected[24] = 333
        expected[25] = -444
        assertArrayEquals(expected.toLittleEndianBytes(), sinkOutput.captured.toByteArray())
        assertEquals("the AudioTrack tail must not advance media time", 229L, output.positionUs)
    }

    @Test
    fun acceptedOutputObserverMarksFinalTailFramesAtTheEndOfAcceptedPcm() {
        val sourceSamples = ShortArray(22) { index -> (index * 19 + 301).toShort() }
        val sinkOutput = RecordingAudioOutput(maxWriteBytes = 8)
        val engine = BlockSpatialEngine(blockFrames = 4)
        val observer = RecordingSpatialOutputObserver(bytesPerFrame = 4)
        val eosState = SpatialEosDrainState()
        val output = SpatialAudioOutput(
            downstream = sinkOutput,
            spatialEngine = engine,
            encoding = NativeSpatialEngine.PcmEncoding.PCM_16,
            sampleRateHz = 48_000,
            eosDrainState = eosState,
            outputObserver = observer
        )

        writeChunks(output, sourceSamples, intArrayOf(1, 3, 2, 5), 0L)
        eosState.beginFinalEosDrain()
        output.stop()
        var drained = false
        var attempts = 0
        while (!drained && attempts++ < 20) {
            drained = output.pumpFinalEosTail()
        }

        assertTrue("final HRTF tail must finish", drained)
        assertArrayEquals(sinkOutput.captured.toByteArray(), observer.capturedBytes())
        assertEquals(2, observer.totalFinalTailFrames())
        var tailStarted = false
        for (index in 0 until observer.eventCount) {
            val eventTailFrames = observer.finalTailFrameCount(index)
            if (tailStarted) {
                assertEquals(observer.frameCount(index), eventTailFrames)
            }
            if (eventTailFrames > 0) tailStarted = true
        }
        assertTrue("the observer must identify the final tail", tailStarted)
    }

    @Test
    fun spatialSinkKeepsFinalEosOpenUntilPartialHrtfTailWritesFinish() {
        val sourceSamples = ShortArray(22) { index -> (index * 19 + 301).toShort() }
        val sinkOutput = RecordingAudioOutput(maxWriteBytes = 8)
        val engine = BlockSpatialEngine(blockFrames = 4)
        val eosState = SpatialEosDrainState()
        val output = newSpatialOutput(sinkOutput, engine, eosState)
        var endOfStreamHandled = false
        val media3Sink = Proxy.newProxyInstance(
            AudioSink::class.java.classLoader,
            arrayOf(AudioSink::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "playToEndOfStream" -> {
                    if (!endOfStreamHandled) {
                        endOfStreamHandled = true
                        output.stop()
                    }
                    null
                }
                "isEnded" -> endOfStreamHandled && output.positionUs >= 229L
                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    java.lang.Float.TYPE -> 0f
                    java.lang.Double.TYPE -> 0.0
                    else -> null
                }
            }
        } as AudioSink
        val sink = SpatialAudioSink(media3Sink, eosState)

        writeChunks(output, sourceSamples, intArrayOf(1, 3, 2, 5), 0L)
        // The renderer marks final EOS separately from Media3's internal drains.
        eosState.beginFinalEosDrain()
        var attempts = 0
        while (!sink.isEnded && attempts++ < 20) {
            sink.playToEndOfStream()
        }

        assertTrue("Media3 sink ended before the HRTF tail drained", sink.isEnded)
        assertEquals("AudioTrack stop must be sent exactly once", 1, sinkOutput.stopCount)
        assertTrue("tail output must exercise downstream backpressure", sinkOutput.partialWriteCount >= 2)
        assertTrue(
            "partial tail retries must keep the same buffer and timestamp",
            sinkOutput.partialRetriesKeptBufferAndMetadata
        )
        val expected = ShortArray(26)
        for (frame in 0 until 11) {
            expected[frame * 2] = sourceSamples[frame * 2]
            expected[frame * 2 + 1] = (-sourceSamples[frame * 2 + 1]).toShort()
        }
        expected[22] = 111
        expected[23] = -222
        expected[24] = 333
        expected[25] = -444
        assertArrayEquals(expected.toLittleEndianBytes(), sinkOutput.captured.toByteArray())
        assertEquals("tail frames must not advance media time", 229L, output.positionUs)
    }

    @Test
    fun nonFinalStopDoesNotDrainOrInjectTheSpatialTail() {
        val source = ShortArray(8) { (it + 1).toShort() }
        val sinkOutput = RecordingAudioOutput(maxWriteBytes = 64)
        val engine = BlockSpatialEngine(blockFrames = 4)
        val output = newSpatialOutput(sinkOutput, engine)

        writeChunks(output, source, intArrayOf(2), 0L)
        output.stop()

        assertEquals(1, sinkOutput.stopCount)
        assertEquals(8 * Short.SIZE_BYTES, sinkOutput.captured.size())
        assertEquals(0, engine.tailDrainCount)
    }

    private fun newSpatialOutput(
        sinkOutput: RecordingAudioOutput,
        engine: BlockSpatialEngine,
        eosState: SpatialEosDrainState = SpatialEosDrainState()
    ) = SpatialAudioOutput(
        downstream = sinkOutput,
        spatialEngine = engine,
        encoding = NativeSpatialEngine.PcmEncoding.PCM_16,
        sampleRateHz = 48_000,
        eosDrainState = eosState
    )

    private fun writeChunks(
        output: SpatialAudioOutput,
        samples: ShortArray,
        pattern: IntArray,
        startTimeUs: Long
    ) {
        var firstFrame = 0
        var patternIndex = 0
        while (firstFrame < samples.size / 2) {
            val frameCount = min(pattern[patternIndex++ % pattern.size], samples.size / 2 - firstFrame)
            val input = ByteBuffer.allocateDirect(frameCount * 2 * Short.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
            for (frame in firstFrame until firstFrame + frameCount) {
                input.putShort(samples[frame * 2])
                input.putShort(samples[frame * 2 + 1])
            }
            input.flip()

            val presentationTimeUs = startTimeUs + firstFrame * 1_000_000L / 48_000L
            var handled = false
            var attempts = 0
            while (!handled && attempts++ < 100) {
                handled = output.write(
                    input,
                    if (attempts == 1) 1 else 0,
                    if (attempts == 1) presentationTimeUs else C.TIME_END_OF_SOURCE
                )
            }
            assertTrue("spatial AudioOutput did not consume its input", handled)
            assertEquals(input.limit(), input.position())
            firstFrame += frameCount
        }
    }

    private fun noOpAudioSink(): AudioSink =
        Proxy.newProxyInstance(
            AudioSink::class.java.classLoader,
            arrayOf(AudioSink::class.java)
        ) { _, method, _ ->
            when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                java.lang.Float.TYPE -> 0f
                java.lang.Double.TYPE -> 0.0
                else -> null
            }
        } as AudioSink

    private fun shortBuffer(samples: ShortArray): ByteBuffer =
        ByteBuffer.allocateDirect(samples.size * Short.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .apply {
                samples.forEach(::putShort)
                flip()
            }

    private fun ShortArray.toLittleEndianBytes(): ByteArray {
        val bytes = ByteBuffer.allocate(size * Short.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        forEach(bytes::putShort)
        return bytes.array()
    }

    private fun FloatArray.toNativeBytes(): ByteArray {
        val bytes = ByteBuffer.allocate(size * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
        forEach(bytes::putFloat)
        return bytes.array()
    }

    private class FloatCopySpatialEngine : SpatialPcmEngine {
        var lastEncoding: NativeSpatialEngine.PcmEncoding? = null
            private set

        override fun process(
            input: ByteBuffer,
            inputFrames: Int,
            output: ByteBuffer,
            encoding: NativeSpatialEngine.PcmEncoding
        ): Long {
            require(encoding == NativeSpatialEngine.PcmEncoding.FLOAT_32)
            lastEncoding = encoding
            repeat(inputFrames * 2) { output.putFloat(input.float) }
            return (inputFrames.toLong() shl 32) or inputFrames.toLong()
        }

        override fun drainTail(
            output: ByteBuffer,
            encoding: NativeSpatialEngine.PcmEncoding
        ) = 0

        override fun reset() = Unit
        override fun close() = Unit
    }

    private class BlockSpatialEngine(
        private val blockFrames: Int
    ) : SpatialPcmEngine {
        private val pending = ShortArray(blockFrames * 2)
        private var pendingFrames = 0
        private var tailWritten = false
        var tailDrainCount = 0
            private set
        var resetCount = 0
            private set
        var closeCount = 0
            private set

        override fun process(
            input: ByteBuffer,
            inputFrames: Int,
            output: ByteBuffer,
            encoding: NativeSpatialEngine.PcmEncoding
        ): Long {
            require(encoding == NativeSpatialEngine.PcmEncoding.PCM_16)
            val initialInputPosition = input.position()
            val initialOutputPosition = output.position()
            var consumedFrames = 0
            var producedFrames = 0

            while (consumedFrames < inputFrames) {
                pending[pendingFrames * 2] = input.short
                pending[pendingFrames * 2 + 1] = input.short
                pendingFrames++
                consumedFrames++
                if (pendingFrames == blockFrames) {
                    for (frame in 0 until blockFrames) {
                        output.putShort(pending[frame * 2])
                        output.putShort((-pending[frame * 2 + 1]).toShort())
                    }
                    pendingFrames = 0
                    producedFrames += blockFrames
                }
            }
            assertEquals(inputFrames * 2 * Short.SIZE_BYTES, input.position() - initialInputPosition)
            assertEquals(producedFrames * 2 * Short.SIZE_BYTES, output.position() - initialOutputPosition)
            return (consumedFrames.toLong() shl 32) or producedFrames.toLong()
        }

        override fun drainTail(
            output: ByteBuffer,
            encoding: NativeSpatialEngine.PcmEncoding
        ): Int {
            tailDrainCount++
            val start = output.position()
            if (pendingFrames > 0) {
                for (frame in 0 until pendingFrames) {
                    output.putShort(pending[frame * 2])
                    output.putShort((-pending[frame * 2 + 1]).toShort())
                }
                pendingFrames = 0
            }
            if (!tailWritten && output.remaining() >= 2 * Short.SIZE_BYTES * 2) {
                output.putShort(111)
                output.putShort((-222).toShort())
                output.putShort(333)
                output.putShort((-444).toShort())
                tailWritten = true
            }
            return (output.position() - start) / (2 * Short.SIZE_BYTES)
        }

        override fun reset() {
            resetCount++
            pendingFrames = 0
            tailWritten = false
        }

        override fun close() {
            closeCount++
        }
    }

    private class RecordingSpatialOutputObserver(
        private val bytesPerFrame: Int,
        private val capacityBytes: Int = 1_048_576,
        private val capacityEvents: Int = 8_192
    ) : SpatialPcmOutputObserver {
        private val bytes = ByteArray(capacityBytes)
        private val frameIndexes = LongArray(capacityEvents)
        private val frameCounts = IntArray(capacityEvents)
        private val presentationTimesUs = LongArray(capacityEvents)
        private val finalTailFrameCounts = IntArray(capacityEvents)
        private var bytesWritten = 0
        var eventCount = 0
            private set

        override fun onAccepted(
            buffer: ByteBuffer,
            byteOffset: Int,
            frameIndex: Long,
            frameCount: Int,
            presentationTimeUs: Long,
            finalTailFrameCount: Int
        ) {
            require(frameCount > 0)
            require(frameCount * bytesPerFrame <= capacityBytes - bytesWritten)
            require(eventCount < capacityEvents)
            frameIndexes[eventCount] = frameIndex
            frameCounts[eventCount] = frameCount
            presentationTimesUs[eventCount] = presentationTimeUs
            finalTailFrameCounts[eventCount] = finalTailFrameCount
            repeat(frameCount * bytesPerFrame) { offset ->
                bytes[bytesWritten + offset] = buffer.get(byteOffset + offset)
            }
            bytesWritten += frameCount * bytesPerFrame
            eventCount++
        }

        fun capturedBytes(): ByteArray = bytes.copyOf(bytesWritten)
        fun frameIndex(index: Int): Long = frameIndexes[index]
        fun frameCount(index: Int): Int = frameCounts[index]
        fun presentationTimeUs(index: Int): Long = presentationTimesUs[index]
        fun finalTailFrameCount(index: Int): Int = finalTailFrameCounts[index]
        fun totalFinalTailFrames(): Int {
            var total = 0
            for (index in 0 until eventCount) total += finalTailFrameCounts[index]
            return total
        }
    }

    private class RecordingAudioOutput(
        private val maxWriteBytes: Int,
        private val bytesPerFrame: Int = 2 * Short.SIZE_BYTES
    ) : AudioOutput {
        data class WriteCall(val presentationTimeUs: Long, val encodedAccessUnitCount: Int)

        private val sampleRateHz = 48_000
        val captured = ByteArrayOutputStream()
        val calls = mutableListOf<WriteCall>()
        var releaseCount = 0
            private set
        var partialWriteCount = 0
            private set
        var stopCount = 0
            private set
        var partialRetriesKeptBufferAndMetadata = true
            private set
        private var previousBuffer: ByteBuffer? = null
        private var previousMetadata: WriteCall? = null
        private var writtenFrames = 0L

        override fun play() = Unit
        override fun pause() = Unit
        override fun flush() {
            captured.reset()
            writtenFrames = 0L
        }
        override fun stop() {
            stopCount++
        }
        override fun release() {
            releaseCount++
        }
        override fun setVolume(volume: Float) = Unit
        override fun isOffloadedPlayback() = false
        override fun getAudioSessionId() = C.AUDIO_SESSION_ID_UNSET
        override fun getSampleRate() = sampleRateHz
        override fun getBufferSizeInFrames() = 4_096L
        override fun getPositionUs() = writtenFrames * 1_000_000L / sampleRateHz
        override fun getPlaybackParameters() = PlaybackParameters.DEFAULT
        override fun isStalled() = false
        override fun addListener(listener: AudioOutput.Listener) = Unit
        override fun removeListener(listener: AudioOutput.Listener) = Unit
        override fun setPlaybackParameters(playbackParams: PlaybackParameters) = Unit
        override fun setOffloadDelayPadding(delayInFrames: Int, paddingInFrames: Int) = Unit
        override fun setOffloadEndOfStream() = Unit
        override fun attachAuxEffect(effectId: Int) = Unit
        override fun setAuxEffectSendLevel(level: Float) = Unit
        override fun setPreferredDevice(preferredDevice: AudioDeviceInfo?) = Unit

        override fun write(
            buffer: ByteBuffer,
            encodedAccessUnitCount: Int,
            presentationTimeUs: Long
        ): Boolean {
            val call = WriteCall(presentationTimeUs, encodedAccessUnitCount)
            if (previousBuffer != null) {
                partialRetriesKeptBufferAndMetadata =
                    partialRetriesKeptBufferAndMetadata &&
                        previousBuffer === buffer &&
                        previousMetadata == call
            }
            val remaining = buffer.remaining()
            val bytesToWrite = min(maxWriteBytes, remaining)
                .let { it - it % bytesPerFrame }
            repeat(bytesToWrite) { captured.write(buffer.get().toInt()) }
            writtenFrames += bytesToWrite / bytesPerFrame
            calls += call
            if (buffer.hasRemaining()) {
                partialWriteCount++
                previousBuffer = buffer
                previousMetadata = call
                return false
            }
            previousBuffer = null
            previousMetadata = null
            return true
        }
    }
}
