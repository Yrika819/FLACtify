package com.flactify.audio.spatial

import android.content.Context
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

/** Supplies the sink with the renderer's current period before Media3 drains a stream boundary. */
@OptIn(UnstableApi::class)
internal class SpatialMediaCodecAudioRenderer(
    context: Context,
    codecAdapterFactory: MediaCodecAdapter.Factory,
    mediaCodecSelector: MediaCodecSelector,
    enableDecoderFallback: Boolean,
    eventHandler: Handler?,
    eventListener: AudioRendererEventListener?,
    audioSink: AudioSink,
    private val eosDrainState: SpatialEosDrainState,
    private val playbackQueueState: SpatialPlaybackQueueState
) : MediaCodecAudioRenderer(
    context,
    codecAdapterFactory,
    mediaCodecSelector,
    enableDecoderFallback,
    eventHandler,
    eventListener,
    audioSink
) {
    override fun onProcessedStreamChange() {
        eosDrainState.markProcessedStreamChange()
        super.onProcessedStreamChange()
    }

    override fun renderToEndOfStream() {
        eosDrainState.beginRendererEosDrain(
            playbackQueueState.shouldDrainFinalHrtfTail(
                isCurrentStreamFinal(),
                getTimeline(),
                getMediaPeriodId()
            )
        )
        super.renderToEndOfStream()
    }
}
