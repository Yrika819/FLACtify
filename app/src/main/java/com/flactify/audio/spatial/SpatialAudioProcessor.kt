package com.flactify.audio.spatial

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class SpatialAudioMode(val wireValue: String) {
    OFF("off"),
    STUDIO("studio");

    companion object {
        const val SESSION_COMMAND_ACTION = "com.flactify.audio.spatial.SET_MODE"
        const val SESSION_EXTRA_MODE = "spatial_mode"
        const val SESSION_EXTRA_HRTF_MIX_PERCENT = "spatial_hrtf_mix_percent"
        const val PREFERENCE_KEY = "spatial_audio_mode"
        const val PREFERENCE_KEY_HRTF_MIX_PERCENT = "spatial_hrtf_mix_percent"

        fun fromWireValue(value: String?): SpatialAudioMode =
            entries.firstOrNull { it.wireValue == value } ?: OFF
    }
}

data class SpatialPipelineInfo(
    val requestedMode: SpatialAudioMode = SpatialAudioMode.OFF,
    val effectiveMode: SpatialAudioMode = SpatialAudioMode.OFF,
    val engine: String? = null,
    val engineVersion: String? = null,
    val inputEncoding: String? = null,
    val inputSampleRateHz: Int? = null,
    val inputChannelCount: Int? = null,
    val internalFormat: String? = null,
    val frameSize: Int? = null,
    val hrtfProfile: String? = null,
    val hrtfMixPercent: Int? = null,
    val speakerLayout: String? = null,
    val algorithmLatencyFrames: Int? = null,
    val bypassReason: String? = null,
    val error: String? = null
)

internal class SpatialPipelineState(
    initialMode: SpatialAudioMode = SpatialAudioMode.STUDIO,
    initialHrtfMixPercent: Int = SpatialHrtfMixPercent.DEFAULT_PERCENT,
    private val onInfo: (SpatialPipelineInfo) -> Unit = {}
) {
    @Volatile
    var info: SpatialPipelineInfo = SpatialPipelineInfo(
        requestedMode = initialMode,
        hrtfMixPercent = initialHrtfMixPercent,
        bypassReason = if (initialMode == SpatialAudioMode.STUDIO) "AWAITING_PCM_FORMAT" else null
    )
        private set

    fun publish(next: SpatialPipelineInfo) {
        info = next
        onInfo(next)
    }

    fun setRequestedMode(
        mode: SpatialAudioMode,
        hrtfMixPercent: Int = SpatialHrtfMixPercent.DEFAULT_PERCENT
    ) {
        SpatialHrtfMixPercent.toUnitMix(hrtfMixPercent)
        publish(
            SpatialPipelineInfo(
                requestedMode = mode,
                hrtfMixPercent = hrtfMixPercent,
                bypassReason = if (mode == SpatialAudioMode.STUDIO) "AWAITING_PCM_FORMAT" else null
            )
        )
    }
}

/**
 * Legacy processor-path harness retained for isolated processor lifecycle tests.
 *
 * Production Studio rendering is performed by SpatialAudioOutput after Media3 has completed its
 * trim, channel-map, and PCM-conversion stages, including its Float32 conversion path.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class SpatialAudioProcessor(
    private val eosDrainState: SpatialEosDrainState,
    private val state: SpatialPipelineState,
    private val engineFactory: (Int, Int) -> NativeSpatialEngine = { sampleRate, frameSize ->
        NativeSpatialEngine(sampleRate, frameSize)
    }
) : AudioProcessor {
    private var pendingFormat = AudioProcessor.AudioFormat.NOT_SET
    private var activeFormat = AudioProcessor.AudioFormat.NOT_SET
    private var pendingActive = false
    private var outputBuffer = ByteBuffer.allocateDirect(OUTPUT_CAPACITY_BYTES)
        .order(ByteOrder.nativeOrder())
        .apply { limit(0) }
    private var engine: NativeSpatialEngine? = null
    private var eosQueued = false
    private var drainFinalTail = false
    private var tailDrained = false
    private var tailDrainCalls = 0
    private var lastTailFrames = 0

    internal val eosDrainDiagnosticSnapshot: String
        get() = "eosQueued=$eosQueued, finalTail=$drainFinalTail, tailDrained=$tailDrained, " +
            "outputPending=${outputBuffer.hasRemaining()}, engineConfigured=${engine != null}, " +
            "tailDrainCalls=$tailDrainCalls, lastTailFrames=$lastTailFrames"

    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        pendingFormat = inputAudioFormat
        val bypassReason = unsupportedReason(inputAudioFormat)
        pendingActive = bypassReason == null
        return if (pendingActive) inputAudioFormat else AudioProcessor.AudioFormat.NOT_SET
    }

    override fun isActive(): Boolean = pendingActive

    override fun queueInput(inputBuffer: ByteBuffer) {
        val format = activeFormat
        if (format == AudioProcessor.AudioFormat.NOT_SET) {
            return
        }
        check(!outputBuffer.hasRemaining()) { "Media3 must drain processor output before queueInput" }

        outputBuffer.clear()
        if (eosQueued) {
            require(!inputBuffer.hasRemaining()) { "Non-empty input queued after end of stream" }
            if (engine == null) {
                tailDrained = true
                outputBuffer.limit(0)
            } else {
                fillTailBuffer()
            }
        } else {
            val bytesPerFrame = format.bytesPerFrame
            require(inputBuffer.isDirect) { "Media3 PCM input must be direct" }
            require(inputBuffer.order() == ByteOrder.nativeOrder()) {
                "Media3 PCM input must use native byte order"
            }
            require(inputBuffer.remaining() % bytesPerFrame == 0) {
                "Media3 PCM input must be frame-aligned"
            }
            val selectedEngine = engine
            if (selectedEngine == null) {
                copyPcmThrough(inputBuffer, bytesPerFrame)
            } else {
                selectedEngine.process(
                    inputBuffer,
                    inputBuffer.remaining() / bytesPerFrame,
                    outputBuffer,
                    encodingFor(format.encoding)
                )
                outputBuffer.flip()
            }
        }
    }

    override fun queueEndOfStream() {
        eosQueued = true
        drainFinalTail = eosDrainState.isFinalEosDrain && engine != null
        tailDrained = !drainFinalTail
        tailDrainCalls = 0
        lastTailFrames = 0
        // Media3's AudioProcessingPipeline retains the previous getOutput() buffer reference
        // across queueEndOfStream(). Generate the tail when its next queueInput(EMPTY_BUFFER)
        // arrives so the pipeline can capture the newly filled output buffer itself.
    }

    override fun getOutput(): ByteBuffer {
        if (
            outputBuffer.hasRemaining().not() &&
            eosQueued &&
            drainFinalTail &&
            !tailDrained
        ) {
            outputBuffer.clear()
            fillTailBuffer()
        }
        return if (outputBuffer.hasRemaining()) outputBuffer else AudioProcessor.EMPTY_BUFFER
    }

    override fun isEnded(): Boolean =
        eosQueued &&
            (!drainFinalTail || tailDrained) &&
            !outputBuffer.hasRemaining()

    override fun flush() {
        flush(AudioProcessor.StreamMetadata.DEFAULT)
    }

    override fun flush(streamMetadata: AudioProcessor.StreamMetadata) {
        eosQueued = false
        drainFinalTail = false
        tailDrained = false
        outputBuffer.clear()
        outputBuffer.limit(0)

        if (!pendingActive) {
            engine?.close()
            engine = null
            activeFormat = AudioProcessor.AudioFormat.NOT_SET
            publishBypass(pendingFormat, unsupportedReason(pendingFormat) ?: "PROCESSOR_DISABLED")
            return
        }

        val sameFormat = activeFormat == pendingFormat
        if (engine == null || !sameFormat) {
            engine?.close()
            engine = null
            try {
                engine = engineFactory(pendingFormat.sampleRate, FRAME_SIZE)
            } catch (failure: RuntimeException) {
                activeFormat = pendingFormat
                publishBypass(pendingFormat, "NATIVE_ENGINE_INIT_FAILED", failure.message)
                return
            } catch (failure: LinkageError) {
                activeFormat = pendingFormat
                publishBypass(pendingFormat, "NATIVE_ENGINE_INIT_FAILED", failure.message)
                return
            }
        }
        activeFormat = pendingFormat
        publishActive(activeFormat)
    }

    override fun reset() {
        engine?.close()
        engine = null
        pendingFormat = AudioProcessor.AudioFormat.NOT_SET
        activeFormat = AudioProcessor.AudioFormat.NOT_SET
        pendingActive = false
        eosQueued = false
        drainFinalTail = false
        tailDrained = false
        outputBuffer.clear()
        outputBuffer.limit(0)
    }

    /** Called from AudioSink.flush(), which Media3 uses to invalidate a seek or output recreation. */
    fun resetForDiscontinuity() {
        engine?.reset()
        eosQueued = false
        drainFinalTail = false
        tailDrained = false
        outputBuffer.clear()
        outputBuffer.limit(0)
    }

    private fun copyPcmThrough(inputBuffer: ByteBuffer, bytesPerFrame: Int) {
        val copyFrames = minOf(
            inputBuffer.remaining() / bytesPerFrame,
            outputBuffer.remaining() / bytesPerFrame
        )
        val copyBytes = copyFrames * bytesPerFrame
        if (copyBytes > 0) {
            val originalLimit = inputBuffer.limit()
            try {
                inputBuffer.limit(inputBuffer.position() + copyBytes)
                outputBuffer.put(inputBuffer)
            } finally {
                inputBuffer.limit(originalLimit)
            }
        }
        outputBuffer.flip()
    }

    private fun fillTailBuffer() {
        val selectedEngine = checkNotNull(engine) { "Spatial engine is not configured" }
        tailDrainCalls++
        val frames = selectedEngine.drainTail(outputBuffer, encodingFor(activeFormat.encoding))
        lastTailFrames = frames
        if (frames == 0) {
            tailDrained = true
            outputBuffer.limit(0)
        } else {
            outputBuffer.flip()
        }
    }

    private fun publishActive(format: AudioProcessor.AudioFormat) {
        val hrtfProfile = when (SpatialHrtfRateSupport.sourceFor(format.sampleRate)) {
            SpatialHrtfSource.BUILT_IN_DEFAULT -> "CIPIC 124 (Steam Audio default HRTF)"
            SpatialHrtfSource.PINNED_CIPIC_SOFA -> "CIPIC 124 (Steam Audio SOFA, HRTF resampled)"
            null -> "Unknown"
        }
        state.publish(
            SpatialPipelineInfo(
                requestedMode = SpatialAudioMode.STUDIO,
                effectiveMode = SpatialAudioMode.STUDIO,
                engine = "Steam Audio",
                engineVersion = "4.8.1",
                inputEncoding = encodingName(format.encoding),
                inputSampleRateHz = format.sampleRate,
                inputChannelCount = format.channelCount,
                internalFormat = "Float32",
                frameSize = FRAME_SIZE,
                hrtfProfile = hrtfProfile,
                speakerLayout = "L(-30°, 0°, front), R(+30°, 0°, front)",
                algorithmLatencyFrames = FRAME_SIZE
            )
        )
    }

    private fun publishBypass(
        format: AudioProcessor.AudioFormat,
        reason: String,
        error: String? = null
    ) {
        state.publish(
            SpatialPipelineInfo(
                requestedMode = SpatialAudioMode.STUDIO,
                inputEncoding = format.takeIf { it != AudioProcessor.AudioFormat.NOT_SET }
                    ?.let { encodingName(it.encoding) },
                inputSampleRateHz = format.sampleRate.takeIf { it > 0 },
                inputChannelCount = format.channelCount.takeIf { it > 0 },
                bypassReason = reason,
                error = error
            )
        )
    }

    private fun unsupportedReason(format: AudioProcessor.AudioFormat): String? = when {
        format.channelCount != 2 -> "STUDIO_REQUIRES_STEREO"
        SpatialHrtfRateSupport.sourceFor(format.sampleRate) == null ->
            "HRTF_RATE_UNAVAILABLE"
        format.encoding != C.ENCODING_PCM_16BIT && format.encoding != C.ENCODING_PCM_FLOAT ->
            "UNSUPPORTED_PCM_ENCODING"
        else -> null
    }

    private fun encodingFor(encoding: Int): NativeSpatialEngine.PcmEncoding = when (encoding) {
        C.ENCODING_PCM_16BIT -> NativeSpatialEngine.PcmEncoding.PCM_16
        C.ENCODING_PCM_FLOAT -> NativeSpatialEngine.PcmEncoding.FLOAT_32
        else -> error("Unsupported spatial PCM encoding $encoding")
    }

    private fun encodingName(encoding: Int): String = when (encoding) {
        C.ENCODING_PCM_16BIT -> "PCM16"
        C.ENCODING_PCM_FLOAT -> "Float32"
        else -> "encoding($encoding)"
    }

    private companion object {
        const val FRAME_SIZE = 128
        const val OUTPUT_CAPACITY_BYTES = 16 * 1024
    }
}
