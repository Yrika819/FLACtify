package com.flactify.audio

import com.flactify.audio.spatial.SpatialPipelineInfo
import java.util.concurrent.atomic.AtomicLong

/**
 * What the *file* declares about itself.
 *
 * Every field except [container] is nullable and means "not reliably known". Nothing here is
 * ever derived from another field: in particular [bitDepth] is never guessed from
 * [sampleRateHz], because 44.1 kHz/24-bit and 96 kHz/16-bit are both perfectly legal.
 */
data class SourceAudioInfo(
    val container: String?,
    val sampleRateHz: Int?,
    val bitDepth: Int?,
    val channelCount: Int?,
    val bitrateKbps: Int? = null
) {
    val displayRate: String? get() = sampleRateHz?.let { formatRate(it) }

    companion object {
        val UNKNOWN = SourceAudioInfo(null, null, null, null)

        fun formatRate(hz: Int): String {
            val khz = hz / 1000.0
            // Fixed locale: with the default locale a comma-decimal region would render
            // "44,1 kHz" in the player badge.
            val text = if (khz % 1.0 == 0.0) khz.toInt().toString() else String.format(java.util.Locale.US, "%.1f", khz)
            return "$text kHz"
        }
    }
}

/**
 * The audio format Media3 reports for the renderer that is consuming the current track.
 *
 * Scope note, because this was previously mislabelled "decoder output": Media3's
 * `AudioRendererEventListener.onAudioInputFormatChanged` is documented as "called when the
 * audio format being processed by the audio renderer changes". Media3 does not promise that
 * this is the post-decode PCM format, and for some paths the renderer substitutes its own
 * format. Only the fields the callback actually reports are kept here; the PCM encoding that
 * reaches the AudioTrack is measured separately and authoritatively via
 * [ActualOutputInfo.pcmEncoding].
 */
data class RendererFormatInfo(
    val sampleRateHz: Int? = null,
    val channelCount: Int? = null,
    val decoderName: String? = null
) {
    val displayRate: String? get() = sampleRateHz?.let { SourceAudioInfo.formatRate(it) }

    companion object {
        val UNKNOWN = RendererFormatInfo()
    }
}

/**
 * What the platform actually opened for output.
 *
 * This is the closest publicly observable value to the true output format: Media3 reports the
 * `AudioTrackConfig` it built. It is a *measurement of what was requested from the platform*,
 * not a proof that the hardware received the samples unaltered - resampling, volume scaling and
 * mixing upstream of the driver are not observable from an app.
 *
 * @property channelMask the raw `CHANNEL_OUT_*` value. Media3 documents `AudioTrackConfig
 *   `.channelConfig` as "the channel configuration of the track. See `AudioFormat.CHANNEL_OUT_XXX`
 *   constants" - it is a bitmask such as `CHANNEL_OUT_STEREO` (12), **not** a channel count.
 * @property channelCount derived from [channelMask] only when the mask is one of the fixed
 *   channel layouts Android defines, otherwise null. Treating the raw value as a count would
 *   report stereo as "12 channels".
 */
data class ActualOutputInfo(
    val pcmEncoding: PcmEncoding? = null,
    val sampleRateHz: Int? = null,
    val channelMask: Int? = null,
    val isOffload: Boolean? = null,
    val isTunneling: Boolean? = null
) {
    val channelCount: Int? get() = channelMask?.let { channelCountForMask(it) }

    val displayRate: String? get() = sampleRateHz?.let { SourceAudioInfo.formatRate(it) }

    /**
     * True only when every core field was observed. Absent measurements are treated as unknown,
     * never as evidence that a conversion did or did not happen.
     */
    val isFullyObserved: Boolean
        get() = pcmEncoding != null && sampleRateHz != null && channelCount != null

    companion object {
        val UNKNOWN = ActualOutputInfo()

        /**
         * Maps a `CHANNEL_OUT_*` mask to the channel count it represents.
         *
         * Counts follow the constant names, which state the layout as `X.Y.Z`:
         * 5.1.2 is 5+1+2 = 8 channels, 5.1.4 is 10, 7.1.2 is 10, 7.1.4 is 12.
         *
         * Note `CHANNEL_OUT_STEREO` is 12 - a value that is also the channel count of
         * 7.1.4. The mask, not its magnitude, identifies the layout, so the table is keyed on
         * the constants and never on the raw number.
         *
         * A mask with no defined `CHANNEL_OUT_*` constant carries no count and returns null
         * rather than falling back to the number of set bits, which would invent a layout.
         */
        fun channelCountForMask(mask: Int): Int? = when (mask) {
            android.media.AudioFormat.CHANNEL_OUT_MONO -> 1
            android.media.AudioFormat.CHANNEL_OUT_STEREO -> 2
            android.media.AudioFormat.CHANNEL_OUT_QUAD,
            android.media.AudioFormat.CHANNEL_OUT_SURROUND -> 4

            android.media.AudioFormat.CHANNEL_OUT_5POINT1 -> 6
            android.media.AudioFormat.CHANNEL_OUT_6POINT1 -> 7
            android.media.AudioFormat.CHANNEL_OUT_5POINT1POINT2,
            android.media.AudioFormat.CHANNEL_OUT_7POINT1,
            android.media.AudioFormat.CHANNEL_OUT_7POINT1_SURROUND -> 8

            android.media.AudioFormat.CHANNEL_OUT_5POINT1POINT4,
            android.media.AudioFormat.CHANNEL_OUT_7POINT1POINT2 -> 10

            android.media.AudioFormat.CHANNEL_OUT_7POINT1POINT4 -> 12
            android.media.AudioFormat.CHANNEL_OUT_9POINT1POINT4 -> 14
            android.media.AudioFormat.CHANNEL_OUT_9POINT1POINT6 -> 16
            else -> null
        }
    }
}

/**
 * Summary of public Media3 audio-sink underrun events.
 *
 * This counts AnalyticsListener reports and keeps their buffer context. It is not the platform
 * AudioTrack.getUnderrunCount() value.
 */
data class Media3AudioSinkInfo(
    val underrunEventCount: Long? = null,
    val lastBufferSizeBytes: Int? = null,
    val lastBufferDurationMs: Long? = null,
    val lastElapsedSinceLastFeedMs: Long? = null
) {
    companion object {
        val UNKNOWN = Media3AudioSinkInfo()
    }
}

internal class Media3AudioSinkEventCounter {
    private val eventCount = AtomicLong()

    fun snapshot(): Media3AudioSinkInfo =
        Media3AudioSinkInfo(underrunEventCount = eventCount.get())

    fun record(
        bufferSizeBytes: Int,
        bufferDurationMs: Long?,
        elapsedSinceLastFeedMs: Long
    ): Media3AudioSinkInfo =
        Media3AudioSinkInfo(
            underrunEventCount = eventCount.incrementAndGet(),
            lastBufferSizeBytes = bufferSizeBytes,
            lastBufferDurationMs = bufferDurationMs,
            lastElapsedSinceLastFeedMs = elapsedSinceLastFeedMs
        )
}

/**
 * Everything the player screen and the diagnostics view need, with the four layers kept apart.
 *
 * The separation is the point of this type. `source` is what the file says, `route` is where
 * the audio is going, `capabilities` is what that route advertises it could accept, and
 * `actualOutput` is what was measured. Collapsing any two of them - for example reporting a
 * 24-bit source as a 24-bit hardware output - is the bug this model exists to prevent.
 */
data class PlaybackDiagnostics(
    val source: SourceAudioInfo = SourceAudioInfo.UNKNOWN,
    val renderer: RendererFormatInfo = RendererFormatInfo.UNKNOWN,
    val route: AudioRouteInfo? = null,
    val capabilities: DirectPlaybackCapability = DirectPlaybackCapability.unknown("未照会"),
    val actualOutput: ActualOutputInfo = ActualOutputInfo.UNKNOWN,
    val spatial: SpatialPipelineInfo = SpatialPipelineInfo(),
    val media3AudioSink: Media3AudioSinkInfo = Media3AudioSinkInfo.UNKNOWN
) {
    val isEmpty: Boolean
        get() = source == SourceAudioInfo.UNKNOWN &&
                renderer == RendererFormatInfo.UNKNOWN &&
                route == null &&
                actualOutput == ActualOutputInfo.UNKNOWN &&
                spatial == SpatialPipelineInfo() &&
                media3AudioSink == Media3AudioSinkInfo.UNKNOWN

    /**
     * Compact source description for the player badge, e.g. `FLAC • 96 kHz • 24 bit`.
     *
     * A field with no reliable source is omitted rather than filled in with a guess.
     */
    val sourceLabel: String
        get() = buildList {
            source.container?.takeIf { it.isNotBlank() }?.let { add(it) }
            source.displayRate?.let { add(it) }
            source.bitDepth?.let { add("$it bit") }
        }.joinToString(" • ")

    /**
     * Route label, e.g. `USB • RME DAC` or `Bluetooth • WH-1000XM5`.
     */
    val routeLabel: String? get() = route?.label

    /**
     * Direct capability as a capability, never as a claim about the current path.
     *
     * `Direct supported` means the platform reports the current route can accept a direct PCM
     * stream at this rate. It does not mean FLACtify is using one, and the UI must not imply
     * otherwise.
     */
    val directCapabilityLabel: String
        get() = when {
            !capabilities.isProbed -> "Direct unknown"
            capabilities.supportedEncodings.isEmpty() -> "Direct unavailable"
            else -> "Direct supported"
        }
}
