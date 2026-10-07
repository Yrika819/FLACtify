package com.flactify.audio

import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink

/**
 * Observes what the running player actually does, as opposed to what the file claims.
 *
 * Independent playback facts, deliberately kept separate so that none can
 * stand in for another:
 *
 *  - [onAudioInputFormatChanged] is the format the audio *renderer* reports it is processing.
 *    Media3 does not document this as the post-decode PCM format, so it is recorded as
 *    [RendererFormatInfo] and no PCM claim is derived from it.
 *  - [onAudioDecoderInitialized] supplies the decoder name, which is a genuine fact.
 *  - [onAudioTrackInitialized] is the `AudioTrackConfig` the platform was actually given. Its
 *    `channelConfig` is a `CHANNEL_OUT_*` mask, not a count, so it is kept as a mask and only
 *    converted when the mask is one of Android's fixed channel layouts.
 */
internal class AudioTrackOutputLifecycleTracker(
    private val onOutputFormat: (ActualOutputInfo) -> Unit
) {
    private var activeTrackCount = 0

    @Synchronized
    fun onTrackInitialized(info: ActualOutputInfo) {
        activeTrackCount++
        onOutputFormat(info)
    }

    @Synchronized
    fun onTrackReleased() {
        if (activeTrackCount > 0) activeTrackCount--
        if (activeTrackCount == 0) onOutputFormat(ActualOutputInfo.UNKNOWN)
    }
}

@OptIn(UnstableApi::class)
class PlaybackAudioObserver(
    private val onRendererFormat: (RendererFormatInfo) -> Unit,
    private val onDecoderName: (String?) -> Unit,
    private val onOutputFormat: (ActualOutputInfo) -> Unit,
    private val onMedia3AudioSink: (Media3AudioSinkInfo) -> Unit
) : AnalyticsListener {

    private val handler = Handler(Looper.getMainLooper())
    private val underrunEvents = Media3AudioSinkEventCounter()
    private val outputLifecycle = AudioTrackOutputLifecycleTracker { info ->
        post { onOutputFormat(info) }
    }

    init {
        post { onMedia3AudioSink(underrunEvents.snapshot()) }
    }

    private fun audioFormat(
        inputFormat: androidx.media3.common.Format
    ): RendererFormatInfo = RendererFormatInfo(
        // Media3 uses Format.NO_VALUE (-1) for unset int fields.
        sampleRateHz = inputFormat.sampleRate.takeIf { it != NO_VALUE && it > 0 },
        channelCount = inputFormat.channelCount.takeIf { it != NO_VALUE && it > 0 }
    )

    override fun onAudioInputFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        inputFormat: androidx.media3.common.Format,
        @Suppress("UNUSED_PARAMETER")
        decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?
    ) {
        val rendererFormat = audioFormat(inputFormat)
        post { onRendererFormat(rendererFormat) }
    }

    override fun onAudioDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        @Suppress("UNUSED_PARAMETER") initializedTimestampMs: Long,
        @Suppress("UNUSED_PARAMETER") initializationDurationMs: Long
    ) {
        val name = decoderName.trim().takeIf { it.isNotEmpty() }
        post { onDecoderName(name) }
    }

    /**
     * Records Media3's public underrun notification and its buffer context.
     *
     * This is an event count, not the platform's raw AudioTrack underrun counter.
     */
    override fun onAudioUnderrun(
        @Suppress("UNUSED_PARAMETER")
        eventTime: AnalyticsListener.EventTime,
        bufferSize: Int,
        bufferSizeMs: Long,
        elapsedSinceLastFeedMs: Long
    ) {
        val info = underrunEvents.record(
            bufferSizeBytes = bufferSize,
            bufferDurationMs = bufferSizeMs.takeIf { it != C.TIME_UNSET },
            elapsedSinceLastFeedMs = elapsedSinceLastFeedMs
        )
        post { onMedia3AudioSink(info) }
    }

    /**
     * The only public Media3 signal for the format the audio sink actually opened.
     *
     * `AudioTrackConfig` is a plain value holder, so no platform allocation happens per callback.
     */
    override fun onAudioTrackInitialized(
        eventTime: AnalyticsListener.EventTime,
        audioTrackConfig: AudioSink.AudioTrackConfig
    ) {
        val info = ActualOutputInfo(
            pcmEncoding = PcmEncoding.fromAudioFormatEncoding(audioTrackConfig.encoding)
                .takeIf { it != PcmEncoding.UNKNOWN },
            sampleRateHz = audioTrackConfig.sampleRate.takeIf { it > 0 },
            channelMask = audioTrackConfig.channelConfig.takeIf { it > 0 },
            isOffload = audioTrackConfig.offload,
            isTunneling = audioTrackConfig.tunneling
        )
        outputLifecycle.onTrackInitialized(info)
    }

    override fun onAudioTrackReleased(
        eventTime: AnalyticsListener.EventTime,
        audioTrackConfig: AudioSink.AudioTrackConfig
    ) {
        // Replacements can initialize before the previous track's release callback arrives.
        // Keep the newest observed config until every announced track has been released.
        outputLifecycle.onTrackReleased()
    }

    /** Analytics callbacks arrive on the playback thread; hop before touching observable state. */
    private fun post(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else handler.post(block)
    }

    companion object {
        private const val NO_VALUE = -1

        fun attach(player: ExoPlayer, observer: PlaybackAudioObserver): AnalyticsListener {
            player.addAnalyticsListener(observer)
            return observer
        }
    }
}
