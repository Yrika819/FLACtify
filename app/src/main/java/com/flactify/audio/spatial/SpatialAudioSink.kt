package com.flactify.audio.spatial

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer

/**
 * Marks renderer EOS separately from the DefaultAudioSink's internal format and parameter drains.
 * A final native tail is pumped only after Media3 has submitted its ordinary PCM and called stop.
 *
 * The optional processor remains for processor-specific lifecycle tests. Production Studio output
 * is processed by SpatialAudioOutput after Media3's complete PCM pipeline.
 */
@OptIn(UnstableApi::class)
internal class SpatialAudioSink(
    private val downstream: AudioSink,
    private val eosDrainState: SpatialEosDrainState,
    private val spatialAudioProcessor: SpatialAudioProcessor? = null
) : ForwardingAudioSink(downstream) {
    private var releaseSpatialEngineAfterInput = false

    override fun configure(audioSinkConfig: AudioSink.AudioSinkConfig) {
        eosDrainState.clear()
        val format = audioSinkConfig.format
        releaseSpatialEngineAfterInput =
            format.sampleMimeType != MimeTypes.AUDIO_RAW ||
                format.pcmEncoding != C.ENCODING_PCM_16BIT
        super.configure(audioSinkConfig)
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int
    ): Boolean {
        val handled = super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        if (handled && releaseSpatialEngineAfterInput) {
            // Legacy processor tests verify the old processor engine is retired after conversion.
            spatialAudioProcessor?.reset()
            releaseSpatialEngineAfterInput = false
        }
        return handled
    }

    override fun setAudioOutputProvider(audioOutputProvider: AudioOutputProvider) {
        eosDrainState.clear()
        spatialAudioProcessor?.resetForDiscontinuity()
        super.setAudioOutputProvider(audioOutputProvider)
    }

    override fun handleDiscontinuity() {
        eosDrainState.markPotentialDiscontinuity()
        spatialAudioProcessor?.resetForDiscontinuity()
        super.handleDiscontinuity()
    }

    override fun playToEndOfStream() {
        try {
            super.playToEndOfStream()
            if (eosDrainState.isFinalEosDrain) {
                eosDrainState.pumpFinalEosTail()
            }
            if (downstream.isEnded() && eosDrainState.isFinalTailComplete()) {
                eosDrainState.clear()
            }
        } catch (exception: AudioSink.WriteException) {
            eosDrainState.clear()
            throw exception
        }
    }

    override fun isEnded(): Boolean {
        val ended = super.isEnded()
        if (!ended || !eosDrainState.isFinalTailComplete()) return false
        eosDrainState.clear()
        return true
    }

    override fun flush() {
        eosDrainState.clear()
        spatialAudioProcessor?.resetForDiscontinuity()
        super.flush()
    }

    override fun reset() {
        eosDrainState.clear()
        super.reset()
    }

    override fun release() {
        eosDrainState.clear()
        try {
            super.release()
        } finally {
            spatialAudioProcessor?.reset()
        }
    }
}
