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
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@OptIn(UnstableApi::class)
class Media3FloatOutputBoundaryDeviceTest {
    @Test
    fun highResolutionPcmUsesFloatOnlyWhenEnabledAndKeepsInputRate() {
        val playbackThread = HandlerThread("Media3FloatOutputBoundary").apply { start() }
        try {
            val task = FutureTask<Unit> {
                val sampleRates = intArrayOf(44_100, 48_000, 88_200, 96_000, 176_400, 192_000)
                val highResolutionEncodings = intArrayOf(
                    C.ENCODING_PCM_24BIT,
                    C.ENCODING_PCM_32BIT
                )
                for (sampleRate in sampleRates) {
                    for (inputEncoding in highResolutionEncodings) {
                        val spatialOff = passThroughSink(
                            sampleRate = sampleRate,
                            inputEncoding = inputEncoding,
                            enableFloatOutput = false
                        )
                        assertEquals(
                            "Spatial-off Media3 output encoding for $inputEncoding at $sampleRate Hz",
                            C.ENCODING_PCM_16BIT,
                            spatialOff.outputEncoding
                        )
                        assertEquals(sampleRate, spatialOff.sampleRate)
                        assertEquals(8 * 2 * Short.SIZE_BYTES, spatialOff.outputByteCount)

                        val spatialOnCandidate = passThroughSink(
                            sampleRate = sampleRate,
                            inputEncoding = inputEncoding,
                            enableFloatOutput = true
                        )
                        assertEquals(
                            "Float-enabled Media3 output encoding for $inputEncoding at $sampleRate Hz",
                            C.ENCODING_PCM_FLOAT,
                            spatialOnCandidate.outputEncoding
                        )
                        assertEquals(sampleRate, spatialOnCandidate.sampleRate)
                        assertEquals(8 * 2 * Float.SIZE_BYTES, spatialOnCandidate.outputByteCount)
                    }
                }

                val floatInput = passThroughSink(
                    sampleRate = 96_000,
                    inputEncoding = C.ENCODING_PCM_FLOAT,
                    enableFloatOutput = true
                )
                assertEquals(C.ENCODING_PCM_FLOAT, floatInput.outputEncoding)
                assertEquals(8 * 2 * Float.SIZE_BYTES, floatInput.outputByteCount)

                val pcm16 = passThroughSink(
                    sampleRate = 44_100,
                    inputEncoding = C.ENCODING_PCM_16BIT,
                    enableFloatOutput = true
                )
                assertEquals(
                    "Enabling Float32 output must not promote ordinary PCM16 tracks",
                    C.ENCODING_PCM_16BIT,
                    pcm16.outputEncoding
                )
                Unit
            }
            Handler(playbackThread.looper).post(task)
            task.get(30, TimeUnit.SECONDS)
        } finally {
            playbackThread.quitSafely()
            playbackThread.join(1_000)
        }
    }

    @Test
    fun studioFloatBoundaryProcessesHighResolutionPcmAtTheOutputProvider() {
        val playbackThread = HandlerThread("Media3SpatialFloatBoundary").apply { start() }
        try {
            val task = FutureTask<Unit> {
                val highResolution = spatialPolicySink(
                    sampleRate = 96_000,
                    inputEncoding = C.ENCODING_PCM_24BIT
                )
                assertEquals(C.ENCODING_PCM_FLOAT, highResolution.output.outputEncoding)
                assertEquals(8 * 2 * Float.SIZE_BYTES, highResolution.output.outputByteCount)
                assertTrue(
                    "Studio Float32 output must route speed changes through AudioOutput",
                    highResolution.output.usePlaybackParameters
                )
                assertEquals(
                    PlaybackParameters(1.25f),
                    highResolution.output.playbackParameters
                )
                assertEquals(8L, highResolution.engine.processedFrames)
                assertEquals(NativeSpatialEngine.PcmEncoding.FLOAT_32, highResolution.engine.lastEncoding)
                assertEquals(SpatialAudioMode.STUDIO, highResolution.spatialInfo.effectiveMode)
                assertEquals("Float32", highResolution.spatialInfo.inputEncoding)
                assertEquals(null, highResolution.spatialInfo.bypassReason)

                val pcm16 = spatialPolicySink(
                    sampleRate = 44_100,
                    inputEncoding = C.ENCODING_PCM_16BIT
                )
                assertEquals(C.ENCODING_PCM_16BIT, pcm16.output.outputEncoding)
                assertEquals(8 * 2 * Short.SIZE_BYTES, pcm16.output.outputByteCount)
                assertTrue(
                    "PCM16 must keep Media3's Sonic speed and pitch processing",
                    !pcm16.output.usePlaybackParameters
                )
                assertEquals(PlaybackParameters.DEFAULT, pcm16.output.playbackParameters)
                assertEquals(8L, pcm16.engine.processedFrames)
                assertEquals(NativeSpatialEngine.PcmEncoding.PCM_16, pcm16.engine.lastEncoding)
                assertEquals(SpatialAudioMode.STUDIO, pcm16.spatialInfo.effectiveMode)
                Unit
            }
            Handler(playbackThread.looper).post(task)
            task.get(30, TimeUnit.SECONDS)
        } finally {
            playbackThread.quitSafely()
            playbackThread.join(1_000)
        }
    }

    @Test
    fun spatialOutputProcessesBothPcmEncodingsAndReleasesItsNativeEngines() {
        val playbackThread = HandlerThread("SpatialOutputLifecycle").apply { start() }
        try {
            val task = FutureTask<Unit> {
                val eosDrainState = SpatialEosDrainState()
                val stateTransitions = mutableListOf<SpatialPipelineInfo>()
                val state = SpatialPipelineState(SpatialAudioMode.STUDIO) { stateTransitions += it }
                val outputProvider = CapturingOutputProvider()
                val spatialEngines = mutableListOf<ObservingSpatialPcmEngine>()
                val engineFactory: (Int) -> SpatialPcmEngine = { sampleRate ->
                    ObservingSpatialPcmEngine(sampleRate).also(spatialEngines::add)
                }
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                val sink = SpatialAudioSink(
                    DefaultAudioSink.Builder(context)
                        .setEnableFloatOutput(true)
                        .setAudioOutputProvider(
                            SpatialAudioOutputProvider(
                                outputProvider,
                                requireDecodedPcm = true,
                                spatialPipelineState = state,
                                eosDrainState = eosDrainState,
                                spatialEngineFactory = engineFactory
                            )
                        )
                        .build(),
                    eosDrainState
                )
                var released = false

                fun send(input: ByteBuffer, presentationTimeUs: Long) {
                    var handled = false
                    var attempts = 0
                    while (!handled && attempts++ < 1_000) {
                        handled = sink.handleBuffer(input, presentationTimeUs, 1)
                    }
                    assertTrue("spatial sink did not consume input PCM", handled)
                    assertEquals(input.limit(), input.position())
                }

                val pcm16Format = rawFormat(44_100, C.ENCODING_PCM_16BIT)
                try {
                    sink.configure(AudioSink.AudioSinkConfig.Builder(pcm16Format).build())
                    send(silence(128, C.ENCODING_PCM_16BIT), 0L)
                    assertEquals(NativeSpatialEngine.PcmEncoding.PCM_16, spatialEngines.last().lastEncoding)

                    val highResolutionFormat = rawFormat(96_000, C.ENCODING_PCM_24BIT)
                    sink.configure(AudioSink.AudioSinkConfig.Builder(highResolutionFormat).build())
                    val highResolutionStartUs = 128L * 1_000_000L / 44_100
                    send(silence(128, C.ENCODING_PCM_24BIT), highResolutionStartUs)
                    assertEquals(C.ENCODING_PCM_FLOAT, outputProvider.output.probe().outputEncoding)
                    assertEquals(NativeSpatialEngine.PcmEncoding.FLOAT_32, spatialEngines.last().lastEncoding)
                    assertEquals(SpatialAudioMode.STUDIO, state.info.effectiveMode)
                    assertEquals("Float32", state.info.inputEncoding)
                    assertEquals(null, state.info.bypassReason)

                    sink.configure(AudioSink.AudioSinkConfig.Builder(pcm16Format).build())
                    val pcm16AfterHighResolutionStartUs =
                        highResolutionStartUs + 128L * 1_000_000L / 96_000
                    send(silence(128, C.ENCODING_PCM_16BIT), pcm16AfterHighResolutionStartUs)
                    assertEquals(NativeSpatialEngine.PcmEncoding.PCM_16, spatialEngines.last().lastEncoding)
                    assertEquals(SpatialAudioMode.STUDIO, state.info.effectiveMode)
                    assertTrue("output diagnostics should reflect observed route transitions", stateTransitions.isNotEmpty())

                    sink.release()
                    released = true
                    assertTrue("sink release must close every output-owned engine", spatialEngines.all { it.closed })
                } finally {
                    if (!released) sink.release()
                }
                Unit
            }
            Handler(playbackThread.looper).post(task)
            task.get(60, TimeUnit.SECONDS)
        } finally {
            playbackThread.quitSafely()
            playbackThread.join(1_000)
        }
    }

    @Test
    fun studioConfiguresPlaybackSpeedBeforePlatformBufferSizing() {
        val delegate = PlaybackSpeedAwareOutputProvider()
        val spatialProvider = SpatialAudioOutputProvider(
            delegate = delegate,
            requireDecodedPcm = true
        )
        val pcm16FormatConfig = AudioOutputProvider.FormatConfig.Builder(
            rawFormat(48_000, C.ENCODING_PCM_16BIT)
        )
            .setAudioAttributes(AudioAttributes.DEFAULT)
            .setEnablePlaybackParameters(true)
            .build()
        val pcm16WithPlaybackParameters = delegate.getOutputConfig(pcm16FormatConfig)
        val pcm16WithoutPlaybackParameters = delegate.getOutputConfig(
            pcm16FormatConfig.buildUpon()
                .setEnablePlaybackParameters(false)
                .build()
        )
        val studioPcm16 = spatialProvider.getOutputConfig(pcm16FormatConfig)
        assertTrue(!studioPcm16.usePlaybackParameters)
        assertEquals(
            "Studio PCM16 must use the delegate's non-speed-adjusted buffer size",
            pcm16WithoutPlaybackParameters.bufferSize,
            studioPcm16.bufferSize
        )
        assertTrue(
            "The provider fixture must expose its playback-speed buffer policy",
            pcm16WithPlaybackParameters.bufferSize >
                pcm16WithoutPlaybackParameters.bufferSize
        )

        val floatFormatConfig = AudioOutputProvider.FormatConfig.Builder(
            rawFormat(96_000, C.ENCODING_PCM_FLOAT)
        )
            .setAudioAttributes(AudioAttributes.DEFAULT)
            .setEnablePlaybackParameters(true)
            .build()
        val floatWithPlaybackParameters = delegate.getOutputConfig(floatFormatConfig)
        val studioFloat = spatialProvider.getOutputConfig(floatFormatConfig)
        assertTrue(studioFloat.usePlaybackParameters)
        assertEquals(floatWithPlaybackParameters.bufferSize, studioFloat.bufferSize)

        val floatWithoutPlaybackParameters = spatialProvider.getOutputConfig(
            floatFormatConfig.buildUpon()
                .setEnablePlaybackParameters(false)
                .build()
        )
        assertTrue(!floatWithoutPlaybackParameters.usePlaybackParameters)
        assertEquals(
            pcm16WithoutPlaybackParameters.bufferSize,
            floatWithoutPlaybackParameters.bufferSize
        )
        spatialProvider.release()
    }

    private fun rawFormat(sampleRate: Int, encoding: Int): Format =
        Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setSampleRate(sampleRate)
            .setChannelCount(2)
            .setPcmEncoding(encoding)
            .build()

    private fun silence(frameCount: Int, encoding: Int): ByteBuffer {
        val bytesPerSample = when (encoding) {
            C.ENCODING_PCM_16BIT -> Short.SIZE_BYTES
            C.ENCODING_PCM_24BIT -> 3
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> Int.SIZE_BYTES
            else -> error("Unsupported test encoding: $encoding")
        }
        return ByteBuffer.allocateDirect(frameCount * 2 * bytesPerSample)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                repeat(capacity()) { put(0) }
                flip()
            }
    }

    private fun passThroughSink(
        sampleRate: Int,
        inputEncoding: Int,
        enableFloatOutput: Boolean
    ): OutputProbe {
        val provider = CapturingOutputProvider()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sink = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setAudioOutputProvider(provider)
            .build()
        try {
            val format = Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_RAW)
                .setSampleRate(sampleRate)
                .setChannelCount(2)
                .setPcmEncoding(inputEncoding)
                .build()
            sink.configure(AudioSink.AudioSinkConfig.Builder(format).build())

            val bytesPerSample = when (inputEncoding) {
                C.ENCODING_PCM_16BIT -> Short.SIZE_BYTES
                C.ENCODING_PCM_24BIT -> 3
                C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> Int.SIZE_BYTES
                else -> error("Unsupported test encoding: $inputEncoding")
            }
            val input = ByteBuffer.allocateDirect(8 * 2 * bytesPerSample)
                .order(ByteOrder.LITTLE_ENDIAN)
            repeat(input.capacity()) { input.put(0) }
            input.flip()

            var handled = false
            var attempts = 0
            while (!handled && attempts++ < 100) {
                handled = sink.handleBuffer(input, 0L, 1)
            }
            assertTrue("DefaultAudioSink did not consume the input buffer", handled)
            assertEquals(input.limit(), input.position())
            return provider.output.probe()
        } finally {
            sink.release()
        }
    }

    private fun spatialPolicySink(
        sampleRate: Int,
        inputEncoding: Int
    ): SpatialPolicyProbe {
        val engine = ObservingSpatialPcmEngine(sampleRate)
        val state = SpatialPipelineState(SpatialAudioMode.STUDIO)
        val provider = CapturingOutputProvider()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sink = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(true)
            .setEnableAudioOutputPlaybackParameters(true)
            .setAudioOutputProvider(
                SpatialAudioOutputProvider(
                    provider,
                    requireDecodedPcm = true,
                    spatialPipelineState = state,
                    spatialEngineFactory = { rate ->
                        assertEquals(sampleRate, rate)
                        engine
                    }
                )
            )
            .build()
        try {
            val format = Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_RAW)
                .setSampleRate(sampleRate)
                .setChannelCount(2)
                .setPcmEncoding(inputEncoding)
                .build()
            sink.configure(AudioSink.AudioSinkConfig.Builder(format).build())

            val bytesPerSample = when (inputEncoding) {
                C.ENCODING_PCM_16BIT -> Short.SIZE_BYTES
                C.ENCODING_PCM_24BIT -> 3
                C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> Int.SIZE_BYTES
                else -> error("Unsupported test encoding: $inputEncoding")
            }
            val input = ByteBuffer.allocateDirect(8 * 2 * bytesPerSample)
                .order(ByteOrder.LITTLE_ENDIAN)
            repeat(input.capacity()) { input.put(0) }
            input.flip()

            var handled = false
            var attempts = 0
            while (!handled && attempts++ < 100) {
                handled = sink.handleBuffer(input, 0L, 1)
            }
            assertTrue("DefaultAudioSink did not consume the input buffer", handled)
            assertEquals(input.limit(), input.position())
            sink.setPlaybackParameters(PlaybackParameters(1.25f))
            return SpatialPolicyProbe(provider.output.probe(), engine, state.info)
        } finally {
            sink.release()
        }
    }

    private data class SpatialPolicyProbe(
        val output: OutputProbe,
        val engine: ObservingSpatialPcmEngine,
        val spatialInfo: SpatialPipelineInfo
    )

    private data class OutputProbe(
        val outputEncoding: Int,
        val sampleRate: Int,
        val outputByteCount: Int,
        val usePlaybackParameters: Boolean,
        val playbackParameters: PlaybackParameters
    )

    private class ObservingSpatialPcmEngine(
        val sampleRateHz: Int
    ) : SpatialPcmEngine {
        var processedFrames = 0L
            private set
        var lastEncoding: NativeSpatialEngine.PcmEncoding? = null
            private set
        var closed = false
            private set

        override fun process(
            input: ByteBuffer,
            inputFrames: Int,
            output: ByteBuffer,
            encoding: NativeSpatialEngine.PcmEncoding
        ): Long {
            lastEncoding = encoding
            val bytesPerFrame = encoding.bytesPerSample * 2
            val bytes = inputFrames * bytesPerFrame
            require(output.remaining() >= bytes)
            repeat(bytes) { output.put(input.get()) }
            processedFrames += inputFrames
            return (inputFrames.toLong() shl 32) or inputFrames.toLong()
        }

        override fun drainTail(
            output: ByteBuffer,
            encoding: NativeSpatialEngine.PcmEncoding
        ): Int = 0

        override fun reset() = Unit
        override fun close() {
            closed = true
        }
    }

    private class PlaybackSpeedAwareOutputProvider : AudioOutputProvider {
        override fun getFormatSupport(formatConfig: AudioOutputProvider.FormatConfig) =
            AudioOutputProvider.FormatSupport.Builder()
                .setFormatSupportLevel(AudioOutputProvider.FORMAT_SUPPORTED_DIRECTLY)
                .build()

        override fun getOutputConfig(formatConfig: AudioOutputProvider.FormatConfig) =
            AudioOutputProvider.OutputConfig.Builder()
                .setEncoding(formatConfig.format.pcmEncoding)
                .setSampleRate(formatConfig.format.sampleRate)
                .setChannelMask(PlatformAudioFormat.CHANNEL_OUT_STEREO)
                .setBufferSize(
                    if (formatConfig.enablePlaybackParameters) BUFFER_SIZE_WITH_SPEED
                    else BUFFER_SIZE_NORMAL
                )
                .setAudioAttributes(formatConfig.audioAttributes)
                .setAudioSessionId(formatConfig.audioSessionId)
                .setUsePlaybackParameters(formatConfig.enablePlaybackParameters)
                .build()

        override fun getAudioOutput(config: AudioOutputProvider.OutputConfig) =
            CapturingAudioOutput(
                config.encoding,
                config.sampleRate,
                config.usePlaybackParameters
            )

        override fun addListener(listener: AudioOutputProvider.Listener) = Unit
        override fun removeListener(listener: AudioOutputProvider.Listener) = Unit
        override fun release() = Unit

        private companion object {
            const val BUFFER_SIZE_NORMAL = 4_096
            const val BUFFER_SIZE_WITH_SPEED = BUFFER_SIZE_NORMAL * 8
        }
    }

    private class CapturingOutputProvider : AudioOutputProvider {
        lateinit var output: CapturingAudioOutput
            private set

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
                .setUsePlaybackParameters(formatConfig.enablePlaybackParameters)
                .build()

        override fun getAudioOutput(config: AudioOutputProvider.OutputConfig) =
            CapturingAudioOutput(
                config.encoding,
                config.sampleRate,
                config.usePlaybackParameters
            ).also { output = it }

        override fun addListener(listener: AudioOutputProvider.Listener) = Unit
        override fun removeListener(listener: AudioOutputProvider.Listener) = Unit
        override fun release() = Unit
    }

    private class CapturingAudioOutput(
        private val encoding: Int,
        private val sampleRate: Int,
        val usePlaybackParameters: Boolean
    ) : AudioOutput {
        private val bytesPerFrame = when (encoding) {
            C.ENCODING_PCM_16BIT -> 2 * Short.SIZE_BYTES
            C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_32BIT -> 2 * Int.SIZE_BYTES
            else -> error("Unsupported output encoding: $encoding")
        }
        private var byteCount = 0
        private var writtenFrames = 0L

        private var playbackParameters = PlaybackParameters.DEFAULT

        fun probe() = OutputProbe(
            encoding,
            sampleRate,
            byteCount,
            usePlaybackParameters,
            playbackParameters
        )

        override fun play() = Unit
        override fun pause() = Unit
        override fun flush() {
            byteCount = 0
            writtenFrames = 0L
        }
        override fun stop() = Unit
        override fun release() = Unit
        override fun setVolume(volume: Float) = Unit
        override fun isOffloadedPlayback() = false
        override fun getAudioSessionId() = C.AUDIO_SESSION_ID_UNSET
        override fun getSampleRate() = sampleRate
        override fun getBufferSizeInFrames() = 4_096L
        override fun getPositionUs() = writtenFrames * 1_000_000L / sampleRate
        override fun getPlaybackParameters() = playbackParameters
        override fun isStalled() = false
        override fun addListener(listener: AudioOutput.Listener) = Unit
        override fun removeListener(listener: AudioOutput.Listener) = Unit
        override fun setPlaybackParameters(playbackParams: PlaybackParameters) {
            playbackParameters = playbackParams
        }
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
            assertTrue("Media3 must hand the output provider a direct buffer", buffer.isDirect)
            val acceptedBytes = buffer.remaining()
            assertEquals("AudioOutput input must be frame-aligned", 0, acceptedBytes % bytesPerFrame)
            byteCount += acceptedBytes
            writtenFrames += acceptedBytes / bytesPerFrame
            buffer.position(buffer.limit())
            return true
        }
    }
}
