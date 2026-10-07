package com.flactify.audio.spatial

import android.content.Context
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.audio.DefaultAudioSink

/** Keeps Studio processing at the Media3 AudioOutput boundary so Float32 PCM is processed too. */
@OptIn(UnstableApi::class)
internal class FlactifyRenderersFactory(
    context: Context,
    private val eosDrainState: SpatialEosDrainState,
    private val spatialMode: SpatialAudioMode,
    private val playbackQueueState: SpatialPlaybackQueueState
) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioOutputPlaybackParams: Boolean
    ): AudioSink {
        val builder = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput || spatialMode == SpatialAudioMode.STUDIO)
            .setEnableAudioOutputPlaybackParameters(
                enableAudioOutputPlaybackParams || spatialMode == SpatialAudioMode.STUDIO
            )
        return SpatialAudioSink(builder.build(), eosDrainState)
    }

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>
    ) {
        val firstNewRendererIndex = out.size
        super.buildAudioRenderers(
            context,
            extensionRendererMode,
            mediaCodecSelector,
            enableDecoderFallback,
            audioSink,
            eventHandler,
            eventListener,
            out
        )
        val coreRendererIndex =
            (firstNewRendererIndex until out.size).firstOrNull {
                out[it].javaClass == MediaCodecAudioRenderer::class.java
            } ?: return
        out[coreRendererIndex] = SpatialMediaCodecAudioRenderer(
            context,
            getCodecAdapterFactory(),
            mediaCodecSelector,
            enableDecoderFallback,
            eventHandler,
            eventListener,
            audioSink,
            eosDrainState,
            playbackQueueState
        )
    }
}
