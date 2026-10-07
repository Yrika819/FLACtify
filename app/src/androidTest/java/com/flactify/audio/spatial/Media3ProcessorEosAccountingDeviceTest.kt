package com.flactify.audio.spatial

import android.media.AudioDeviceInfo
import android.media.AudioFormat as PlatformAudioFormat
import android.os.Handler
import android.os.HandlerThread
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessingPipeline
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.collect.ImmutableList
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.math.min

@RunWith(AndroidJUnit4::class)
@OptIn(UnstableApi::class)
class Media3ProcessorEosAccountingDeviceTest {
    @Test
    fun identityOutputPreservesPcmBytesAcrossPartialWritesAndMetadataRetries() {
        val frameCount = 257
        val input = ByteBuffer.allocateDirect(frameCount * 2 * Short.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
        val expected = ByteBuffer.allocate(frameCount * 2 * Short.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
        repeat(frameCount * 2) { sampleIndex ->
            val sample = ((sampleIndex * 1_973 + 12_345) % 65_536 - 32_768).toShort()
            input.putShort(sample)
            expected.putShort(sample)
        }
        input.flip()

        val output = RecordingAudioOutput(sampleRate = 44_100, partialWriteBytes = 12)
        val identityOutput = SpatialAudioOutput(output)
        var handled = false
        var attempts = 0
        while (!handled && attempts++ < 100) {
            handled = identityOutput.write(
                input,
                if (attempts == 1) 1 else 0,
                if (attempts == 1) 0L else C.TIME_END_OF_SOURCE
            )
        }

        assertTrue("identity output did not finish partial writes", handled)
        assertEquals(input.limit(), input.position())
        assertArrayEquals(expected.array(), output.captured.toByteArray())
        assertTrue("the test must exercise partial writes", output.partialWrites > 1)
        assertTrue(
            "retry metadata must stay fixed while the same buffer is pending: " +
                output.writeRetryViolation,
            output.writeRetryContractMaintained
        )
    }

    @Test
    fun spatialProcessorQueuesFinalTailOnlyWhenMedia3RequestsOutput() {
        val state = SpatialPipelineState(SpatialAudioMode.STUDIO)
        val eosDrainState = SpatialEosDrainState()
        val processor = SpatialAudioProcessor(eosDrainState, state)
        val pipeline = AudioProcessingPipeline(ImmutableList.of(processor))
        val format = AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT)

        try {
            pipeline.configure(format)
            pipeline.flush(AudioProcessor.StreamMetadata.DEFAULT)
            val input = ByteBuffer.allocateDirect(128 * 2 * Short.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
                .apply {
                    putShort(Short.MAX_VALUE)
                    putShort(0)
                    repeat(128 * 2 - 2) { putShort(0) }
                    flip()
                }
            pipeline.queueInput(input)
            assertFalse(input.hasRemaining())
            drainPipelineOutput(pipeline)

            eosDrainState.beginFinalEosDrain()
            pipeline.queueEndOfStream()
            var tailFrames = 0
            var attempts = 0
            while (!pipeline.isEnded() && attempts++ < 1_000) {
                val output = pipeline.output
                if (output.hasRemaining()) {
                    tailFrames += output.remaining() / (2 * Short.SIZE_BYTES)
                    output.position(output.limit())
                }
            }

            assertTrue("Media3 did not finish processor EOS", pipeline.isEnded())
            assertTrue("final HRTF tail was not emitted", tailFrames > 0)
        } finally {
            pipeline.reset()
        }
    }

    @Test
    fun defaultAudioSinkDrainsProcessorTailThroughAccountedPartialWrites() {
        val playbackThread = HandlerThread("Media3ProcessorEosTest").apply { start() }
        try {
            val task = FutureTask<Unit> {
                val eosDrainState = SpatialEosDrainState()
                val processor = TailMarkerProcessor(eosDrainState)
                val output = RecordingAudioOutput(sampleRate = 44_100, partialWriteBytes = 12)
                val provider = RecordingAudioOutputProvider(output)
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                val delegate = DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(processor))
                    .setAudioOutputProvider(provider)
                    .build()
                val sink = SpatialAudioSink(delegate, eosDrainState, null)

                try {
                    val format = Format.Builder()
                        .setSampleMimeType(MimeTypes.AUDIO_RAW)
                        .setSampleRate(44_100)
                        .setChannelCount(2)
                        .setPcmEncoding(C.ENCODING_PCM_16BIT)
                        .build()
                    sink.configure(AudioSink.AudioSinkConfig.Builder(format).build())

                    val inputFrames = 1_000
                    val input = ByteBuffer.allocateDirect(inputFrames * 2 * Short.SIZE_BYTES)
                        .order(ByteOrder.nativeOrder())
                    repeat(inputFrames * 2) { input.putShort(0) }
                    input.flip()

                    var inputHandled = false
                    var inputAttempts = 0
                    while (!inputHandled && inputAttempts++ < 100) {
                        inputHandled = sink.handleBuffer(input, 0L, 1)
                    }
                    assertTrue("sink did not accept PCM input", inputHandled)
                    assertEquals(input.limit(), input.position())

                    // Media3 queues processor EOS while switching a pending stream configuration.
                    // Spatial processing must distinguish that from true final EOS.
                    sink.configure(AudioSink.AudioSinkConfig.Builder(format).build())
                    val transitionInput = ByteBuffer.allocateDirect(2 * Short.SIZE_BYTES)
                        .order(ByteOrder.nativeOrder())
                        .apply {
                            putShort(0)
                            putShort(0)
                            flip()
                        }
                    var transitionHandled = false
                    var transitionAttempts = 0
                    while (!transitionHandled && transitionAttempts++ < 1_000) {
                        transitionHandled = sink.handleBuffer(transitionInput, 22_675L, 1)
                    }
                    assertTrue("sink did not accept the next configured stream", transitionHandled)
                    assertEquals(transitionInput.limit(), transitionInput.position())

                    eosDrainState.beginFinalEosDrain()
                    sink.handleDiscontinuity()
                    assertFalse(
                        "a seek/discontinuity must cancel pending final-tail eligibility",
                        eosDrainState.isFinalEosDrain
                    )

                    // Model the renderer's separate terminal signal after the discontinuity.
                    eosDrainState.beginFinalEosDrain()
                    val inputByteCount = inputFrames * 2 * Short.SIZE_BYTES
                    assertEquals(inputByteCount + 2 * Short.SIZE_BYTES, output.captured.size())
                    assertEquals("configuration drains must not emit a final HRTF tail", 0, processor.tailEmissionCount)

                    var eosHandled = false
                    var eosAttempts = 0
                    while (!eosHandled && eosAttempts++ < 1_000) {
                        sink.playToEndOfStream()
                        eosHandled = sink.isEnded()
                    }
                    assertTrue(
                        "sink did not drain its processor EOS tail: attempts=$eosAttempts, " +
                            "queuedEOS=${processor.eosQueueCount}, capturedBytes=${output.captured.size()}, " +
                            "outputPositionUs=${output.getPositionUs()}, sinkPending=${sink.hasPendingData()}, " +
                            "sinkPositionUs=${sink.getCurrentPositionUs(true)}",
                        eosHandled
                    )
                    assertEquals(
                        "configuration drain and final EOS each queue one processor EOS",
                        2,
                        processor.eosQueueCount
                    )
                    assertEquals(
                        "AudioOutput must receive original PCM, the transition sample, and each queued EOS tail",
                        inputByteCount + 4 * Short.SIZE_BYTES,
                        output.captured.size()
                    )
                    val bytes = output.captured.toByteArray()
                    assertTailMarker(bytes, inputByteCount + 2 * Short.SIZE_BYTES)
                    assertEquals("only true final EOS may emit the HRTF tail", 1, processor.tailEmissionCount)
                    assertFalse("the final EOS gate must clear after the sink ends", eosDrainState.isFinalEosDrain)
                    assertTrue("the test must exercise AudioOutput backpressure", output.partialWrites > 1)
                    assertTrue(
                        "sink must write only complete PCM stereo frames",
                        output.allWritesWereWholeStereoFrames
                    )
                    assertTrue(
                        "partial writes must retry with the same buffer, timestamp, and access unit count: " +
                            output.writeRetryViolation,
                        output.writeRetryContractMaintained
                    )
                } finally {
                    sink.release()
                }
                Unit
            }
            Handler(playbackThread.looper).post(task)
            task.get(30, TimeUnit.SECONDS)
        } finally {
            playbackThread.quitSafely()
            playbackThread.join(1_000)
        }
    }

    private fun assertTailMarker(bytes: ByteArray, offset: Int) {
        val tail = ByteBuffer.wrap(bytes, offset, 2 * Short.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
        assertEquals(0x1234.toShort(), tail.short)
        assertEquals((-0x1234).toShort(), tail.short)
    }

    private fun drainPipelineOutput(pipeline: AudioProcessingPipeline) {
        var attempts = 0
        while (attempts++ < 1_000) {
            val output = pipeline.output
            if (!output.hasRemaining()) return
            output.position(output.limit())
        }
        error("Media3 did not drain the processor's regular output")
    }

    private class TailMarkerProcessor(
        private val eosDrainState: SpatialEosDrainState
    ) : BaseAudioProcessor() {
        var eosQueueCount = 0
            private set
        var tailEmissionCount = 0
            private set
        override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
            if (
                inputAudioFormat.encoding != C.ENCODING_PCM_16BIT ||
                inputAudioFormat.channelCount != 2
            ) {
                throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
            }
            return inputAudioFormat
        }

        override fun queueInput(inputBuffer: ByteBuffer) {
            if (!inputBuffer.hasRemaining()) return
            val output = replaceOutputBuffer(inputBuffer.remaining())
            output.put(inputBuffer)
            output.flip()
        }

        override fun onQueueEndOfStream() {
            eosQueueCount++
            if (!eosDrainState.isFinalEosDrain) return
            tailEmissionCount++
            val output = replaceOutputBuffer(2 * Short.SIZE_BYTES)
            output.putShort(0x1234.toShort())
            output.putShort((-0x1234).toShort())
            output.flip()
        }
    }

    private class RecordingAudioOutputProvider(
        private val output: RecordingAudioOutput
    ) : AudioOutputProvider {
        override fun getFormatSupport(formatConfig: AudioOutputProvider.FormatConfig) =
            AudioOutputProvider.FormatSupport.Builder()
                .setFormatSupportLevel(AudioOutputProvider.FORMAT_SUPPORTED_DIRECTLY)
                .build()

        override fun getOutputConfig(formatConfig: AudioOutputProvider.FormatConfig) =
            AudioOutputProvider.OutputConfig.Builder()
                .setEncoding(formatConfig.format.pcmEncoding)
                .setSampleRate(formatConfig.format.sampleRate)
                .setChannelMask(PlatformAudioFormat.CHANNEL_OUT_STEREO)
                .setBufferSize(16_384)
                .setAudioAttributes(formatConfig.audioAttributes)
                .setAudioSessionId(C.AUDIO_SESSION_ID_UNSET)
                .build()

        override fun getAudioOutput(config: AudioOutputProvider.OutputConfig) =
            SpatialAudioOutput(output)
        override fun addListener(listener: AudioOutputProvider.Listener) = Unit
        override fun removeListener(listener: AudioOutputProvider.Listener) = Unit
        override fun release() = Unit
    }

    private class RecordingAudioOutput(
        private val sampleRate: Int,
        private val partialWriteBytes: Int
    ) : AudioOutput {
        val captured = java.io.ByteArrayOutputStream()
        var partialWrites = 0
            private set
        var allWritesWereWholeStereoFrames = true
            private set
        var writeRetryContractMaintained = true
            private set
        var writeRetryViolation: String? = null
            private set
        private var writeCallCount = 0
        private var pendingBuffer: ByteBuffer? = null
        private var pendingTimestampUs: Long? = null
        private var pendingAccessUnitCount: Int? = null
        private var writtenFrames = 0L
        private var listener: AudioOutput.Listener? = null

        override fun play() = Unit
        override fun pause() = Unit
        override fun flush() {
            captured.reset()
            writtenFrames = 0
        }
        override fun stop() = Unit
        override fun release() = Unit
        override fun setVolume(volume: Float) = Unit
        override fun isOffloadedPlayback() = false
        override fun getAudioSessionId() = C.AUDIO_SESSION_ID_UNSET
        override fun getSampleRate() = sampleRate
        override fun getBufferSizeInFrames() = 4_096L
        override fun getPositionUs() = writtenFrames * 1_000_000L / sampleRate
        override fun getPlaybackParameters() = PlaybackParameters.DEFAULT
        override fun isStalled() = false
        override fun addListener(listener: AudioOutput.Listener) {
            this.listener = listener
        }
        override fun removeListener(listener: AudioOutput.Listener) {
            if (this.listener === listener) this.listener = null
        }
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
            writeCallCount++
            assertTrue("Media3 must hand AudioOutput direct PCM buffers", buffer.isDirect)
            if (pendingBuffer != null && (
                    pendingBuffer !== buffer ||
                        pendingTimestampUs != presentationTimeUs ||
                        pendingAccessUnitCount != encodedAccessUnitCount
                )
            ) {
                writeRetryContractMaintained = false
                if (writeRetryViolation == null) {
                    writeRetryViolation =
                        "call=$writeCallCount, sameBuffer=${pendingBuffer === buffer}, " +
                            "timestamp=$pendingTimestampUs->$presentationTimeUs, " +
                            "accessUnits=$pendingAccessUnitCount->$encodedAccessUnitCount, " +
                            "remaining=${buffer.remaining()}"
                }
            }
            val acceptedBytes = min(buffer.remaining(), partialWriteBytes)
            if (acceptedBytes < buffer.remaining()) partialWrites++
            if (acceptedBytes % (2 * Short.SIZE_BYTES) != 0) {
                allWritesWereWholeStereoFrames = false
            }
            val written = ByteArray(acceptedBytes)
            buffer.get(written)
            captured.write(written)
            writtenFrames += acceptedBytes / (2 * Short.SIZE_BYTES)
            val fullyConsumed = !buffer.hasRemaining()
            if (fullyConsumed) {
                pendingBuffer = null
                pendingTimestampUs = null
                pendingAccessUnitCount = null
            } else if (pendingBuffer == null) {
                pendingBuffer = buffer
                pendingTimestampUs = presentationTimeUs
                pendingAccessUnitCount = encodedAccessUnitCount
            }
            return fullyConsumed
        }
    }
}
