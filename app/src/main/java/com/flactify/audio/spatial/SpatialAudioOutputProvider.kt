package com.flactify.audio.spatial

import android.media.AudioFormat as PlatformAudioFormat
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.ForwardingAudioOutputProvider

/**
 * Preserves the platform output provider while applying Studio HRTF to its actual PCM output.
 * Studio mode declines compressed formats so offload and passthrough cannot skip the PCM renderer.
 */
@OptIn(UnstableApi::class)
internal class SpatialAudioOutputProvider(
    delegate: AudioOutputProvider,
    private val requireDecodedPcm: Boolean,
    private val spatialPipelineState: SpatialPipelineState? = null,
    private val eosDrainState: SpatialEosDrainState? = null,
    private val spatialEngineFactory: ((Int) -> SpatialPcmEngine)? = null,
    private val outputObserverFactory: SpatialPcmOutputObserverFactory? = null,
    private val hrtfMixPercent: Int = SpatialHrtfMixPercent.DEFAULT_PERCENT
) : ForwardingAudioOutputProvider(delegate) {

    // Output creation, release, and provider teardown are owned by Media3's playback thread.
    private val activeOutputs = LinkedHashSet<SpatialAudioOutput>()
    private var nextOutputId = 1L

    override fun getFormatSupport(
        formatConfig: AudioOutputProvider.FormatConfig
    ): AudioOutputProvider.FormatSupport {
        if (requireDecodedPcm && formatConfig.format.sampleMimeType != MimeTypes.AUDIO_RAW) {
            return AudioOutputProvider.FormatSupport.UNSUPPORTED
        }
        return super.getFormatSupport(formatConfig)
    }

    override fun getOutputConfig(
        formatConfig: AudioOutputProvider.FormatConfig
    ): AudioOutputProvider.OutputConfig {
        if (!requireDecodedPcm) return super.getOutputConfig(formatConfig)

        // Pass the final policy into the platform provider so its buffer is sized for the actual
        // output path. Float output bypasses Media3's Sonic chain; PCM16 remains on that chain.
        val useOutputPlaybackParameters =
            formatConfig.enablePlaybackParameters &&
                formatConfig.format.pcmEncoding == C.ENCODING_PCM_FLOAT &&
                !formatConfig.enableOffload &&
                !formatConfig.enableTunneling
        val adjustedFormatConfig =
            if (formatConfig.enablePlaybackParameters == useOutputPlaybackParameters) {
                formatConfig
            } else {
                formatConfig.buildUpon()
                    .setEnablePlaybackParameters(useOutputPlaybackParameters)
                    .build()
            }
        return super.getOutputConfig(adjustedFormatConfig)
    }

    override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput {
        val audioOutput = super.getAudioOutput(config)
        val encoding = pcmEncodingFor(config.encoding)
        if (!requireDecodedPcm) {
            return wrapOutput(audioOutput)
        }

        val bypassReason = when {
            config.isOffload -> "OFFLOAD_OUTPUT_UNSUPPORTED"
            config.isTunneling -> "TUNNELED_OUTPUT_UNSUPPORTED"
            config.channelMask != PlatformAudioFormat.CHANNEL_OUT_STEREO ->
                "STUDIO_REQUIRES_STEREO"
            encoding == null -> "UNSUPPORTED_PCM_ENCODING"
            hrtfMixPercent == 0 -> "HRTF_MIX_ZERO"
            SpatialHrtfRateSupport.sourceFor(config.sampleRate) == null ->
                "HRTF_RATE_UNAVAILABLE"
            spatialEngineFactory == null -> "NATIVE_ENGINE_UNAVAILABLE"
            else -> null
        }

        if (bypassReason != null) {
            publishBypass(config, bypassReason)
            return wrapOutput(audioOutput)
        }

        val engine = try {
            checkNotNull(spatialEngineFactory)(config.sampleRate)
        } catch (failure: RuntimeException) {
            publishBypass(config, "NATIVE_ENGINE_INIT_FAILED", failure.message)
            return wrapOutput(audioOutput)
        } catch (failure: LinkageError) {
            publishBypass(config, "NATIVE_ENGINE_INIT_FAILED", failure.message)
            return wrapOutput(audioOutput)
        }

        publishActive(config)
        return wrapOutput(
            downstream = audioOutput,
            spatialEngine = engine,
            encoding = checkNotNull(encoding),
            sampleRateHz = config.sampleRate
        )
    }

    override fun release() {
        var releaseFailure: Throwable? = null
        for (output in activeOutputs.toList()) {
            try {
                output.release()
            } catch (failure: Throwable) {
                if (releaseFailure == null) {
                    releaseFailure = failure
                } else if (releaseFailure !== failure) {
                    releaseFailure.addSuppressed(failure)
                }
            }
        }
        try {
            super.release()
        } catch (failure: Throwable) {
            if (releaseFailure == null) {
                releaseFailure = failure
            } else if (releaseFailure !== failure) {
                releaseFailure.addSuppressed(failure)
            }
        }
        releaseFailure?.let { throw it }
    }

    private fun wrapOutput(
        downstream: AudioOutput,
        spatialEngine: SpatialPcmEngine? = null,
        encoding: NativeSpatialEngine.PcmEncoding? = null,
        sampleRateHz: Int = 0
    ): SpatialAudioOutput {
        val outputId = nextOutputId++
        val outputObserver =
            if (spatialEngine != null && encoding != null) {
                outputObserverFactory?.create(outputId, sampleRateHz, encoding)
            } else {
                null
            }
        return SpatialAudioOutput(
            downstream = downstream,
            spatialEngine = spatialEngine,
            encoding = encoding,
            sampleRateHz = sampleRateHz,
            eosDrainState = eosDrainState,
            onReleased = { releasedOutput -> activeOutputs.remove(releasedOutput) },
            outputObserver = outputObserver
        ).also { activeOutputs.add(it) }
    }

    private fun publishActive(config: AudioOutputProvider.OutputConfig) {
        val source = SpatialHrtfRateSupport.sourceFor(config.sampleRate)
        val profile = when (source) {
            SpatialHrtfSource.BUILT_IN_DEFAULT -> "CIPIC 124 (Steam Audio default HRTF)"
            SpatialHrtfSource.PINNED_CIPIC_SOFA ->
                "CIPIC 124 (Steam Audio SOFA, HRTF resampled)"
            null -> "Unknown"
        }
        spatialPipelineState?.publish(
            SpatialPipelineInfo(
                requestedMode = SpatialAudioMode.STUDIO,
                effectiveMode = SpatialAudioMode.STUDIO,
                engine = "Steam Audio",
                engineVersion = "4.8.1",
                inputEncoding = encodingName(config.encoding),
                inputSampleRateHz = config.sampleRate,
                inputChannelCount = Integer.bitCount(config.channelMask),
                internalFormat = "Float32",
                frameSize = SpatialPcmEngine.FRAME_SIZE,
                hrtfProfile = profile,
                speakerLayout = "L(-30°, 0°, front), R(+30°, 0°, front)",
                algorithmLatencyFrames = SpatialPcmEngine.FRAME_SIZE,
                hrtfMixPercent = hrtfMixPercent
            )
        )
    }

    private fun publishBypass(
        config: AudioOutputProvider.OutputConfig,
        reason: String,
        error: String? = null
    ) {
        spatialPipelineState?.publish(
            SpatialPipelineInfo(
                requestedMode = SpatialAudioMode.STUDIO,
                inputEncoding = encodingName(config.encoding),
                inputSampleRateHz = config.sampleRate.takeIf { it > 0 },
                inputChannelCount = Integer.bitCount(config.channelMask),
                hrtfMixPercent = hrtfMixPercent,
                bypassReason = reason,
                error = error
            )
        )
    }

    private fun pcmEncodingFor(encoding: Int): NativeSpatialEngine.PcmEncoding? =
        when (encoding) {
            C.ENCODING_PCM_16BIT -> NativeSpatialEngine.PcmEncoding.PCM_16
            C.ENCODING_PCM_FLOAT -> NativeSpatialEngine.PcmEncoding.FLOAT_32
            else -> null
        }

    private fun encodingName(encoding: Int): String = when (encoding) {
        C.ENCODING_PCM_16BIT -> "PCM16"
        C.ENCODING_PCM_FLOAT -> "Float32"
        C.ENCODING_PCM_24BIT -> "PCM24"
        C.ENCODING_PCM_32BIT -> "PCM32"
        else -> "encoding($encoding)"
    }
}
