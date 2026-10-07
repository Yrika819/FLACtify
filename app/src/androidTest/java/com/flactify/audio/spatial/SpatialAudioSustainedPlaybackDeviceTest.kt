package com.flactify.audio.spatial

import android.media.AudioTrack
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.Process
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.AudioTrackAudioOutput
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
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
import kotlin.math.PI
import kotlin.math.sin

/** Sustained Studio path check; run on a physical target device. */
@RunWith(AndroidJUnit4::class)
@OptIn(UnstableApi::class)
class SpatialAudioSustainedPlaybackDeviceTest {

    @Test
    fun studioPcmRunsThroughMedia3AndRealAudioTrackWithoutUnderruns() {
        val playbackThread = HandlerThread("SpatialSustainedPlayback").apply { start() }
        try {
            val task = FutureTask { runSustainedPlayback() }
            Handler(playbackThread.looper).post(task)
            val durationMs = requestedDurationMs()
            val stats = task.get(durationMs + EOS_DRAIN_TIMEOUT_MS + TASK_SETUP_TIMEOUT_MS,
                TimeUnit.MILLISECONDS)

            assertEquals("Studio must be active at the AudioOutput PCM boundary", "STUDIO", stats.mode)
            val expectedSampleRateHz = requestedSampleRateHz()
            assertEquals(expectedSampleRateHz, stats.spatialSampleRateHz)
            assertEquals(expectedSampleRateHz, stats.audioTrackSampleRateHz)
            assertEquals("the test must use the real platform AudioTrack", AudioTrack.STATE_INITIALIZED,
                stats.audioTrackState)
            assertEquals("AudioTrack must be playing before final EOS", AudioTrack.PLAYSTATE_PLAYING,
                stats.audioTrackPlayState)
            assertEquals("AudioTrack underruns", 0, stats.underrunCount)
            assertEquals("no-progress windows after warm-up", 0, stats.noProgressWindows)
            assertEquals("AudioTrack playback-head no-progress windows", 0,
                stats.audioTrackNoProgressWindows)
            assertTrue(
                "only ${stats.nativeHeapSampleCount} heap samples were collected for " +
                    "${stats.durationMs} ms",
                stats.nativeHeapSampleCount >= stats.durationMs / 1_000L
            )
            val startupAllowanceMs = minOf(stats.durationMs / 2, AUDIO_TRACK_STARTUP_ALLOWANCE_MS)
            val minimumPlayedFrames =
                (stats.durationMs - startupAllowanceMs) * expectedSampleRateHz / 1_000L
            assertTrue(
                "AudioTrack playback head advanced only ${stats.audioTrackPlaybackHeadFrames} " +
                    "of at least $minimumPlayedFrames frames",
                stats.audioTrackPlaybackHeadFrames >= minimumPlayedFrames
            )
            assertTrue("AudioOutput reported stalls: ${stats.stalledWindows}", stats.stalledWindows <= 1)
            assertTrue("only ${stats.positionUs} us played for ${stats.durationMs} ms",
                stats.positionUs >= stats.durationMs * 900L)
            assertTrue("not enough PCM reached the sink: ${stats.acceptedFrames}",
                stats.acceptedFrames >= stats.durationMs * expectedSampleRateHz / 1_000L)

            Log.i(
                TAG,
                "device=${stats.deviceModel} api=${stats.apiLevel} durationMs=${stats.durationMs} " +
                    "sampleRateHz=${stats.spatialSampleRateHz} " +
                    "audioTrackRateHz=${stats.audioTrackSampleRateHz} " +
                    "audioTrackPlayState=${stats.audioTrackPlayState} " +
                    "audioTrackPlaybackHeadFrames=${stats.audioTrackPlaybackHeadFrames} " +
                    "audioTrackNoProgressWindows=${stats.audioTrackNoProgressWindows} " +
                    "underruns=${stats.underrunCount} stalledWindows=${stats.stalledWindows} " +
                    "noProgressWindows=${stats.noProgressWindows} positionUs=${stats.positionUs} " +
                    "cpuPercent=${stats.processCpuPercent} " +
                    "playbackThreadCpuPercent=${stats.playbackThreadCpuPercent} " +
                    "javaHeapDeltaBytes=${stats.javaHeapDeltaBytes} " +
                    "javaHeapPeakDeltaBytes=${stats.javaHeapPeakDeltaBytes} " +
                    "javaHeapPostWarmupDeltaBytes=${stats.javaHeapPostWarmupDeltaBytes} " +
                    "javaHeapPostWarmupPeakDeltaBytes=${stats.javaHeapPostWarmupPeakDeltaBytes} " +
                    "nativeHeapDeltaBytes=${stats.nativeHeapDeltaBytes} " +
                    "nativeHeapPeakDeltaBytes=${stats.nativeHeapPeakDeltaBytes} " +
                    "nativeHeapPostWarmupDeltaBytes=${stats.nativeHeapPostWarmupDeltaBytes} " +
                    "nativeHeapPostWarmupPeakDeltaBytes=${stats.nativeHeapPostWarmupPeakDeltaBytes} " +
                    "nativeHeapBeforeEosDeltaBytes=${stats.nativeHeapBeforeEosDeltaBytes} " +
                    "nativeHeapEosDeltaBytes=${stats.nativeHeapEosDeltaBytes} " +
                    "nativeHeapLastSampleDeltaBytes=${stats.nativeHeapLastSampleDeltaBytes} " +
                    "nativeHeapSampleCount=${stats.nativeHeapSampleCount} " +
                    "nativeHeapLastSampleElapsedMs=${stats.nativeHeapLastSampleElapsedMs} " +
                    "thermalStatus=${stats.initialThermalStatus}->${stats.finalThermalStatus}"
            )
        } finally {
            playbackThread.quitSafely()
            playbackThread.join(1_000)
        }
    }

    private fun runSustainedPlayback(): PlaybackStats {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val sampleRateHz = requestedSampleRateHz()
        val framesPerBuffer = sampleRateHz / 100
        require(sampleRateHz % 100 == 0) { "Sample rate must support 10 ms input buffers" }
        val state = SpatialPipelineState(SpatialAudioMode.STUDIO)
        val eosDrainState = SpatialEosDrainState()
        var pinnedSofaHrtfData: ByteBuffer? = null
        val baseProvider = AudioTrackAudioOutputProvider.Builder(context).build()
        val provider = ObservingSpatialOutputProvider(
            baseProvider,
            state,
            eosDrainState
        ) { sampleRate ->
            val hrtfData =
                if (
                    SpatialHrtfRateSupport.sourceFor(sampleRate) ==
                    SpatialHrtfSource.PINNED_CIPIC_SOFA
                ) {
                    pinnedSofaHrtfData ?: SpatialHrtfAsset.load(context).also {
                        pinnedSofaHrtfData = it
                    }
                } else {
                    null
                }
            NativeSpatialEngine(sampleRate, SpatialPcmEngine.FRAME_SIZE, hrtfData)
        }
        val sink = SpatialAudioSink(
            DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(true)
                .build(),
            eosDrainState
        )

        try {
            sink.setAudioOutputProvider(provider)
            val pcmFormat = Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_RAW)
                .setSampleRate(sampleRateHz)
                .setChannelCount(2)
                .setPcmEncoding(C.ENCODING_PCM_16BIT)
                .build()
            sink.configure(AudioSink.AudioSinkConfig.Builder(pcmFormat).build())
            sink.setVolume(0f)
            sink.play()

            val input = makeToneBuffer(sampleRateHz, framesPerBuffer)
            val durationMs = requestedDurationMs()
            val requestedFrames = durationMs * sampleRateHz / 1_000L
            val startedAtNanos = System.nanoTime()
            val cpuStartMs = Process.getElapsedCpuTime()
            val playbackThreadCpuStartNanos = Debug.threadCpuTimeNanos()
            val runtime = Runtime.getRuntime()
            val thermalService = context.getSystemService(PowerManager::class.java)
            val initialThermalStatus = thermalStatus(thermalService)
            var javaHeapPeak = usedJavaHeapBytes(runtime)
            var nativeHeapPeak = Debug.getNativeHeapAllocatedSize()
            var nativeHeapLastSampleBytes = nativeHeapPeak
            var nativeHeapSampleCount = 0
            var nativeHeapLastSampleElapsedMs = 0L
            val initialJavaHeap = javaHeapPeak
            val initialNativeHeap = nativeHeapPeak
            var javaHeapWarmupBaseline: Long? = null
            var nativeHeapWarmupBaseline: Long? = null
            var javaHeapPostWarmupPeak = 0L
            var nativeHeapPostWarmupPeak = 0L
            var acceptedFrames = 0L
            var stalledWindows = 0
            var noProgressWindows = 0
            var audioTrackNoProgressWindows = 0
            var previousPositionUs = 0L
            var previousAudioTrackPlaybackHeadFrames = 0L
            var audioTrackPlaybackHeadFrames = 0L
            var nextSampleNanos = startedAtNanos + SAMPLE_INTERVAL_NANOS

            while (acceptedFrames < requestedFrames) {
                input.position(0)
                val presentationTimeUs = acceptedFrames * 1_000_000L / sampleRateHz
                var accepted = false
                var retryDeadlineNanos = System.nanoTime() + BUFFER_RETRY_TIMEOUT_NANOS
                while (!accepted) {
                    accepted = sink.handleBuffer(input, presentationTimeUs, 1)
                    if (!accepted) {
                        check(System.nanoTime() < retryDeadlineNanos) {
                            "DefaultAudioSink stopped accepting PCM at frame $acceptedFrames"
                        }
                        Thread.sleep(1)
                    }
                }
                check(!input.hasRemaining()) { "sink reported success with PCM still pending" }
                acceptedFrames += framesPerBuffer

                val targetTimeNanos = startedAtNanos +
                    acceptedFrames * NANOS_PER_SECOND / sampleRateHz
                while (true) {
                    val nowNanos = System.nanoTime()
                    if (nowNanos >= nextSampleNanos) {
                        val output = checkNotNull(provider.spatialAudioOutput) {
                            "Media3 did not create SpatialAudioOutput"
                        }
                        val positionUs = output.positionUs
                        if (nowNanos - startedAtNanos >= WARMUP_NANOS && positionUs <= previousPositionUs) {
                            noProgressWindows++
                        }
                        if (output.isStalled) stalledWindows++
                        previousPositionUs = positionUs
                        val audioTrack = checkNotNull(provider.audioTrackOutput) {
                            "Media3 did not create the platform AudioTrack"
                        }.audioTrack
                        val observedPlaybackHeadFrames =
                            audioTrack.playbackHeadPosition.toLong() and 0xFFFF_FFFFL
                        if (nowNanos - startedAtNanos >= WARMUP_NANOS &&
                            observedPlaybackHeadFrames <= previousAudioTrackPlaybackHeadFrames
                        ) {
                            audioTrackNoProgressWindows++
                        }
                        previousAudioTrackPlaybackHeadFrames = observedPlaybackHeadFrames
                        audioTrackPlaybackHeadFrames =
                            maxOf(audioTrackPlaybackHeadFrames, observedPlaybackHeadFrames)
                        val javaHeapNow = usedJavaHeapBytes(runtime)
                        val nativeHeapNow = Debug.getNativeHeapAllocatedSize()
                        nativeHeapLastSampleBytes = nativeHeapNow
                        nativeHeapSampleCount++
                        nativeHeapLastSampleElapsedMs =
                            (nowNanos - startedAtNanos) / 1_000_000L
                        javaHeapPeak = maxOf(javaHeapPeak, javaHeapNow)
                        nativeHeapPeak = maxOf(nativeHeapPeak, nativeHeapNow)
                        if (nowNanos - startedAtNanos >= WARMUP_NANOS) {
                            if (javaHeapWarmupBaseline == null) {
                                javaHeapWarmupBaseline = javaHeapNow
                                javaHeapPostWarmupPeak = javaHeapNow
                            } else {
                                javaHeapPostWarmupPeak = maxOf(javaHeapPostWarmupPeak, javaHeapNow)
                            }
                            if (nativeHeapWarmupBaseline == null) {
                                nativeHeapWarmupBaseline = nativeHeapNow
                                nativeHeapPostWarmupPeak = nativeHeapNow
                            } else {
                                nativeHeapPostWarmupPeak = maxOf(nativeHeapPostWarmupPeak, nativeHeapNow)
                            }
                        }
                        nextSampleNanos += SAMPLE_INTERVAL_NANOS
                    }
                    if (nowNanos >= targetTimeNanos) break
                    val remainingNanos = targetTimeNanos - nowNanos
                    if (remainingNanos > 0) TimeUnit.NANOSECONDS.sleep(remainingNanos)
                }
            }

            val audioTrackOutput = checkNotNull(provider.audioTrackOutput) {
                "Media3 did not create AudioTrackAudioOutput"
            }
            val track = audioTrackOutput.audioTrack
            check(track.state == AudioTrack.STATE_INITIALIZED) {
                "AudioTrack state=${track.state}"
            }
            check(state.info.effectiveMode == SpatialAudioMode.STUDIO) {
                "Spatial processing did not activate: ${state.info}"
            }
            val audioTrackPlayState = track.playState
            audioTrackPlaybackHeadFrames = maxOf(
                audioTrackPlaybackHeadFrames,
                track.playbackHeadPosition.toLong() and 0xFFFF_FFFFL
            )

            val nativeHeapBeforeEosBytes = Debug.getNativeHeapAllocatedSize()
            val eosDeadlineNanos = System.nanoTime() + EOS_DRAIN_TIMEOUT_MS * 1_000_000L
            while (!sink.isEnded && System.nanoTime() < eosDeadlineNanos) {
                sink.playToEndOfStream()
                if (!sink.isEnded) Thread.sleep(10)
            }
            val nativeHeapAfterEosBytes = Debug.getNativeHeapAllocatedSize()
            val eosDiagnostics =
                "sinkEnded=${sink.isEnded}, sinkPending=${sink.hasPendingData()}, " +
                    "spatialPositionUs=${provider.spatialAudioOutput?.positionUs}, " +
                    "tailComplete=${eosDrainState.isFinalTailComplete()}, " +
                    "spatialInfo=${state.info}, trackState=${track.state}, playState=${track.playState}, " +
                    "playbackHeadFrames=${track.playbackHeadPosition}, " +
                    "underruns=${track.underrunCount}"
            Log.i(TAG, "eosDiagnostics $eosDiagnostics")
            check(sink.isEnded) { "Media3 did not finish EOS: $eosDiagnostics" }

            val elapsedNanos = System.nanoTime() - startedAtNanos
            val cpuElapsedMs = Process.getElapsedCpuTime() - cpuStartMs
            val playbackThreadCpuElapsedNanos = Debug.threadCpuTimeNanos() - playbackThreadCpuStartNanos
            val javaHeapEnd = usedJavaHeapBytes(runtime)
            val nativeHeapEnd = Debug.getNativeHeapAllocatedSize()
            val javaHeapSteadyBaseline = javaHeapWarmupBaseline ?: initialJavaHeap
            val nativeHeapSteadyBaseline = nativeHeapWarmupBaseline ?: initialNativeHeap
            val javaHeapSteadyPeak = javaHeapWarmupBaseline?.let { javaHeapPostWarmupPeak } ?: javaHeapPeak
            val nativeHeapSteadyPeak = nativeHeapWarmupBaseline?.let { nativeHeapPostWarmupPeak } ?: nativeHeapPeak
            return PlaybackStats(
                deviceModel = android.os.Build.MODEL,
                apiLevel = android.os.Build.VERSION.SDK_INT,
                durationMs = durationMs,
                mode = state.info.effectiveMode.name,
                spatialSampleRateHz = state.info.inputSampleRateHz ?: -1,
                audioTrackSampleRateHz = track.sampleRate,
                audioTrackState = track.state,
                audioTrackPlayState = audioTrackPlayState,
                audioTrackPlaybackHeadFrames = audioTrackPlaybackHeadFrames,
                acceptedFrames = acceptedFrames,
                underrunCount = track.underrunCount,
                stalledWindows = stalledWindows,
                noProgressWindows = noProgressWindows,
                audioTrackNoProgressWindows = audioTrackNoProgressWindows,
                positionUs = checkNotNull(provider.spatialAudioOutput).positionUs,
                processCpuPercent = cpuElapsedMs * 100.0 / (elapsedNanos / 1_000_000.0),
                playbackThreadCpuPercent = if (playbackThreadCpuStartNanos >= 0L &&
                    playbackThreadCpuElapsedNanos >= 0L
                ) playbackThreadCpuElapsedNanos * 100.0 / elapsedNanos else -1.0,
                javaHeapDeltaBytes = javaHeapEnd - initialJavaHeap,
                javaHeapPeakDeltaBytes = javaHeapPeak - initialJavaHeap,
                javaHeapPostWarmupDeltaBytes = javaHeapEnd - javaHeapSteadyBaseline,
                javaHeapPostWarmupPeakDeltaBytes = javaHeapSteadyPeak - javaHeapSteadyBaseline,
                nativeHeapDeltaBytes = nativeHeapEnd - initialNativeHeap,
                nativeHeapPeakDeltaBytes = nativeHeapPeak - initialNativeHeap,
                nativeHeapPostWarmupDeltaBytes = nativeHeapEnd - nativeHeapSteadyBaseline,
                nativeHeapPostWarmupPeakDeltaBytes = nativeHeapSteadyPeak - nativeHeapSteadyBaseline,
                nativeHeapBeforeEosDeltaBytes = nativeHeapBeforeEosBytes - nativeHeapSteadyBaseline,
                nativeHeapEosDeltaBytes = nativeHeapAfterEosBytes - nativeHeapBeforeEosBytes,
                nativeHeapLastSampleDeltaBytes = nativeHeapLastSampleBytes - nativeHeapSteadyBaseline,
                nativeHeapSampleCount = nativeHeapSampleCount,
                nativeHeapLastSampleElapsedMs = nativeHeapLastSampleElapsedMs,
                initialThermalStatus = initialThermalStatus,
                finalThermalStatus = thermalStatus(thermalService)
            )
        } finally {
            sink.pause()
            sink.reset()
            sink.release()
            baseProvider.release()
        }
    }

    private fun makeToneBuffer(sampleRateHz: Int, framesPerBuffer: Int): ByteBuffer {
        val input = ByteBuffer.allocateDirect(framesPerBuffer * CHANNELS * Short.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
        repeat(framesPerBuffer) { frame ->
            val left = sin(2.0 * PI * 440.0 * frame / sampleRateHz) * 8_192.0
            val right = sin(2.0 * PI * 660.0 * frame / sampleRateHz) * 6_144.0
            input.putShort(left.toInt().toShort())
            input.putShort(right.toInt().toShort())
        }
        input.flip()
        return input
    }

    private fun requestedDurationMs(): Long =
        InstrumentationRegistry.getArguments()
            .getString(DURATION_ARGUMENT)
            ?.toLongOrNull()
            ?: DEFAULT_DURATION_MS

    private fun requestedSampleRateHz(): Int {
        val sampleRateHz = InstrumentationRegistry.getArguments()
            .getString(SAMPLE_RATE_ARGUMENT)
            ?.toIntOrNull()
            ?: DEFAULT_SAMPLE_RATE_HZ
        require(SpatialHrtfRateSupport.sourceFor(sampleRateHz) != null) {
            "Unsupported Spatial HRTF sample rate: $sampleRateHz"
        }
        return sampleRateHz
    }

    private fun thermalStatus(powerManager: PowerManager): Int =
        if (android.os.Build.VERSION.SDK_INT >= 29) powerManager.currentThermalStatus else -1

    private fun usedJavaHeapBytes(runtime: Runtime): Long = runtime.totalMemory() - runtime.freeMemory()

    private data class PlaybackStats(
        val deviceModel: String,
        val apiLevel: Int,
        val durationMs: Long,
        val mode: String,
        val spatialSampleRateHz: Int,
        val audioTrackSampleRateHz: Int,
        val audioTrackState: Int,
        val audioTrackPlayState: Int,
        val audioTrackPlaybackHeadFrames: Long,
        val acceptedFrames: Long,
        val underrunCount: Int,
        val stalledWindows: Int,
        val noProgressWindows: Int,
        val audioTrackNoProgressWindows: Int,
        val positionUs: Long,
        val processCpuPercent: Double,
        val playbackThreadCpuPercent: Double,
        val javaHeapDeltaBytes: Long,
        val javaHeapPeakDeltaBytes: Long,
        val javaHeapPostWarmupDeltaBytes: Long,
        val javaHeapPostWarmupPeakDeltaBytes: Long,
        val nativeHeapDeltaBytes: Long,
        val nativeHeapPeakDeltaBytes: Long,
        val nativeHeapPostWarmupDeltaBytes: Long,
        val nativeHeapPostWarmupPeakDeltaBytes: Long,
        val nativeHeapBeforeEosDeltaBytes: Long,
        val nativeHeapEosDeltaBytes: Long,
        val nativeHeapLastSampleDeltaBytes: Long,
        val nativeHeapSampleCount: Int,
        val nativeHeapLastSampleElapsedMs: Long,
        val initialThermalStatus: Int,
        val finalThermalStatus: Int
    )

    private class ObservingSpatialOutputProvider(
        private val delegate: AudioTrackAudioOutputProvider,
        state: SpatialPipelineState,
        eosDrainState: SpatialEosDrainState,
        engineFactory: (Int) -> SpatialPcmEngine
    ) : AudioOutputProvider {
        var audioTrackOutput: AudioTrackAudioOutput? = null
            private set
        var spatialAudioOutput: SpatialAudioOutput? = null
            private set

        private val capturingDelegate = object : AudioOutputProvider by delegate {
            override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput {
                val output = delegate.getAudioOutput(config)
                audioTrackOutput = output as AudioTrackAudioOutput
                return output
            }
        }

        private val spatialProvider = SpatialAudioOutputProvider(
            capturingDelegate,
            requireDecodedPcm = true,
            spatialPipelineState = state,
            eosDrainState = eosDrainState,
            spatialEngineFactory = engineFactory
        )

        override fun getFormatSupport(formatConfig: AudioOutputProvider.FormatConfig) =
            spatialProvider.getFormatSupport(formatConfig)

        override fun getOutputConfig(formatConfig: AudioOutputProvider.FormatConfig) =
            spatialProvider.getOutputConfig(formatConfig)

        override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput =
            (spatialProvider.getAudioOutput(config) as SpatialAudioOutput).also {
                spatialAudioOutput = it
            }

        override fun addListener(listener: AudioOutputProvider.Listener) =
            spatialProvider.addListener(listener)

        override fun removeListener(listener: AudioOutputProvider.Listener) =
            spatialProvider.removeListener(listener)

        override fun release() = spatialProvider.release()
    }

    private companion object {
        const val TAG = "SpatialSustained"
        const val DURATION_ARGUMENT = "spatialPlaybackDurationMs"
        const val SAMPLE_RATE_ARGUMENT = "spatialSampleRateHz"
        const val DEFAULT_DURATION_MS = 10 * 60 * 1_000L
        const val DEFAULT_SAMPLE_RATE_HZ = 48_000
        const val CHANNELS = 2
        const val SAMPLE_INTERVAL_NANOS = 1_000_000_000L
        const val WARMUP_NANOS = 5_000_000_000L
        const val AUDIO_TRACK_STARTUP_ALLOWANCE_MS = 2_000L
        const val BUFFER_RETRY_TIMEOUT_NANOS = 5_000_000_000L
        const val EOS_DRAIN_TIMEOUT_MS = 30_000L
        const val TASK_SETUP_TIMEOUT_MS = 30_000L
        const val NANOS_PER_SECOND = 1_000_000_000L
    }
}
