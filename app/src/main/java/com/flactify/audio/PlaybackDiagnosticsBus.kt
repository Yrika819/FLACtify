package com.flactify.audio

import com.flactify.audio.spatial.SpatialAudioMode
import com.flactify.audio.spatial.SpatialPipelineInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-local bridge carrying observed playback facts from [com.flactify.PlaybackService] to
 * the ViewModel.
 *
 * Renderer, actual output, and spatial pipeline facts come from the service-owned player. Spatial
 * mode and engine state survive compatible track changes; per-stream input format observations
 * are cleared until the new stream's PCM format is published.
 */
object PlaybackDiagnosticsBus {

    private val _renderer = MutableStateFlow(RendererFormatInfo.UNKNOWN)
    val renderer: StateFlow<RendererFormatInfo> = _renderer.asStateFlow()

    private val _actualOutput = MutableStateFlow(ActualOutputInfo.UNKNOWN)
    val actualOutput: StateFlow<ActualOutputInfo> = _actualOutput.asStateFlow()

    private val _spatial = MutableStateFlow(SpatialPipelineInfo())
    val spatial: StateFlow<SpatialPipelineInfo> = _spatial.asStateFlow()

    private val _media3AudioSink = MutableStateFlow(Media3AudioSinkInfo.UNKNOWN)
    val media3AudioSink: StateFlow<Media3AudioSinkInfo> = _media3AudioSink.asStateFlow()

    fun publishRendererFormat(info: RendererFormatInfo) {
        // The decoder name is a separate fact arriving on a separate callback; keep it rather
        // than letting a later format-only update blank it.
        _renderer.value = info.copy(decoderName = _renderer.value.decoderName ?: info.decoderName)
    }

    fun publishDecoderName(name: String?) {
        _renderer.value = _renderer.value.copy(decoderName = name)
    }

    fun publishOutput(info: ActualOutputInfo) {
        _actualOutput.value = info
    }

    fun publishMedia3AudioSink(info: Media3AudioSinkInfo) {
        _media3AudioSink.value = info
    }

    fun publishSpatial(info: SpatialPipelineInfo) {
        _spatial.value = info
    }

    /**
     * Clears per-item renderer facts. Actual output and Spatial values describe configured output
     * paths; Media3 may retain both across a compatible media-item transition.
     */
    fun resetForMediaItemTransition() {
        _renderer.value = RendererFormatInfo.UNKNOWN
    }

    /** Clears all observed per-stream facts when the pipeline is explicitly invalidated. */
    fun reset() {
        _renderer.value = RendererFormatInfo.UNKNOWN
        _actualOutput.value = ActualOutputInfo.UNKNOWN
        val spatial = _spatial.value
        _spatial.value = spatial.copy(
            inputEncoding = null,
            inputSampleRateHz = null,
            inputChannelCount = null,
            bypassReason = if (spatial.requestedMode == SpatialAudioMode.STUDIO) {
                "AWAITING_PCM_FORMAT"
            } else {
                null
            },
            error = null
        )
    }

    fun resetAll() {
        reset()
        _spatial.value = SpatialPipelineInfo()
        _media3AudioSink.value = Media3AudioSinkInfo.UNKNOWN
    }
}
