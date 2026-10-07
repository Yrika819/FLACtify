package com.flactify.audio

import android.media.AudioFormat
import android.media.AudioManager

/**
 * Pure, framework-free description of the linear PCM encodings FLACtify cares about.
 *
 * The mapping helpers use `AudioFormat` constants, which are compile-time `static final`
 * ints and therefore inline at the call site, keeping this file usable from plain JVM tests.
 */
enum class PcmEncoding(
    val audioFormatEncoding: Int,
    /** Nominal bits of PCM payload. Float32 carries a 24-bit significand, not 32 bits of audio. */
    val bitDepth: Int?
) {
    PCM_16BIT(AudioFormat.ENCODING_PCM_16BIT, 16),
    PCM_24BIT_PACKED(AudioFormat.ENCODING_PCM_24BIT_PACKED, 24),
    PCM_32BIT(AudioFormat.ENCODING_PCM_32BIT, 32),
    PCM_FLOAT(AudioFormat.ENCODING_PCM_FLOAT, 24),
    UNKNOWN(AudioFormat.ENCODING_INVALID, null);

    companion object {
        /**
         * FLACtify probes these three encodings. `PCM_32BIT` is deliberately excluded: Android
         * direct playback is reported per encoding, and a route that only advertises 32-bit
         * integer PCM is vanishingly rare on consumer hardware. Excluding it keeps the probe to
         * three queries instead of four without changing any decision the UI makes.
         */
        val PROBED: List<PcmEncoding> = listOf(PCM_16BIT, PCM_24BIT_PACKED, PCM_FLOAT)

        fun fromAudioFormatEncoding(encoding: Int): PcmEncoding =
            entries.firstOrNull { it.audioFormatEncoding == encoding } ?: UNKNOWN
    }
}

/**
 * Result of asking AudioPolicy whether one PCM encoding can bypass the framework mixer.
 *
 * Kept deliberately finer-grained than a boolean. "Unsupported", "offload only" and "we could
 * not ask" are three different situations and collapsing them (as a simple `!= NOT_SUPPORTED`
 * test does) produces confidently wrong UI.
 */
enum class DirectSupport {
    /** `DIRECT_PLAYBACK_BITSTREAM_SUPPORTED` was set: PCM can reach the route unaltered. */
    SUPPORTED,

    /** The platform answered `DIRECT_PLAYBACK_NOT_SUPPORTED` for this exact format. */
    UNAVAILABLE,

    /**
     * Only an offload bit was set. Offload means the platform decodes on a DSP, which is a
     * different path from FLACtify feeding a software-decoded PCM stream straight through, so
     * this is explicitly *not* counted as direct playback support.
     */
    OFFLOAD_ONLY,

    /** The question could not be asked (platform too old, no AudioManager, or the call threw). */
    UNKNOWN;

    val isSupported: Boolean get() = this == SUPPORTED
}

/** One entry from `AudioManager.getDirectProfilesForAttributes`. */
data class DirectProfile(
    val encoding: PcmEncoding,
    val sampleRateHz: Int
)

/**
 * Direct-playback capability for a specific (encoding, sample rate) pair.
 *
 * This is a *capability*. It is never evidence that direct playback is currently in use; see
 * [PlaybackDiagnostics.actualOutput] for what was measured.
 */
data class EncodingCapability(
    val encoding: PcmEncoding,
    val sampleRateHz: Int,
    val support: DirectSupport
)

/**
 * Direct-playback capability for one route, as far as the public Android API can reveal it.
 *
 * @param probedAtSampleRateHz the sample rate the probe was run at, or null if never queried.
 * @param probedAtChannelCount the channel count the probe was run at, or null if unknown.
 * @param conditionLabel human-readable description of what was actually asked, e.g.
 *   `96 kHz / 2 ch`. Null when the probe could not be run.
 * @param advertisedProfiles profiles the route advertises. Diagnostics only - never used to
 *   decide support, because the advertised list and the runtime answer disagree on real devices.
 */
data class DirectPlaybackCapability(
    val probedAtSampleRateHz: Int?,
    val probedAtChannelCount: Int?,
    val conditionLabel: String?,
    val perEncoding: List<EncodingCapability>,
    val advertisedProfiles: List<DirectProfile>
) {
    fun supportFor(encoding: PcmEncoding): DirectSupport =
        perEncoding.firstOrNull { it.encoding == encoding }?.support ?: DirectSupport.UNKNOWN

    /** The full per-encoding entry, including the rate it was probed at. */
    fun capabilityFor(encoding: PcmEncoding): EncodingCapability? =
        perEncoding.firstOrNull { it.encoding == encoding }

    val floatSupported: Boolean get() = supportFor(PcmEncoding.PCM_FLOAT).isSupported
    val pcm24Supported: Boolean get() = supportFor(PcmEncoding.PCM_24BIT_PACKED).isSupported
    val pcm16Supported: Boolean get() = supportFor(PcmEncoding.PCM_16BIT).isSupported

    /** True only when the platform was actually able to answer. */
    val isProbed: Boolean get() = probedAtSampleRateHz != null && probedAtChannelCount != null

    val supportedEncodings: List<PcmEncoding> get() = perEncoding.filter { it.support.isSupported }.map { it.encoding }

    companion object {
        /**
         * Capability for a case that cannot be asked. Every encoding is [DirectSupport.UNKNOWN]
         * rather than [DirectSupport.UNAVAILABLE] so the UI can say "unknown" instead of "no".
         *
         * @param reason why no probe was run, shown to the user instead of a bare "unknown".
         */
        fun unknown(reason: String): DirectPlaybackCapability =
            DirectPlaybackCapability(
                null,
                null,
                reason,
                PcmEncoding.PROBED.map { EncodingCapability(it, 0, DirectSupport.UNKNOWN) },
                emptyList()
            )
    }
}

/**
 * Pure classification of `AudioManager.getDirectPlaybackSupport` results.
 *
 * Kept separate from the `AudioManager` call so it can be exercised by plain JVM unit tests.
 */
object DirectPlaybackClassifier {

    /**
     * Maps a channel count to the `AudioFormat.CHANNEL_OUT_*` mask to probe with.
     *
     * A direct-playback question is asked about a concrete `AudioFormat`, so the mask must
     * actually describe the layout being reported. The previous implementation mapped
     * everything that was not mono to `CHANNEL_OUT_STEREO`, so a 6-channel source was probed
     * as stereo while the UI displayed "6 ch" - the stated condition and the format sent to
     * AudioPolicy disagreed.
     *
     * Android's channel-count / channel-mask table names exactly one canonical output mask
     * per count: 1 MONO, 2 STEREO, 4 QUAD, 6 5POINT1, 8 7POINT1_SURROUND. Counts outside
     * that list (3, 5, 7, 9+) are composites such as STEREO|FRONT_CENTER, which a bare
     * channel count cannot identify, so they return null rather than guessing.
     *
     * `CHANNEL_OUT_7POINT1` is deliberately not used: Android deprecated it in API 23 as
     * "Not the typical 7.1 surround configuration. Use CHANNEL_OUT_7POINT1_SURROUND instead."
     *
     * Only constants available since API 5 (and API 23 for 7POINT1_SURROUND) are used, so
     * every mask is valid on `minSdk` 26. `CHANNEL_OUT_6POINT1` (7 channels) was added in
     * API 34 and is therefore absent.
     *
     * @return a `CHANNEL_OUT_*` mask, or null when no canonical layout exists for [channelCount].
     */
    fun channelMaskFor(channelCount: Int): Int? = when (channelCount) {
        1 -> android.media.AudioFormat.CHANNEL_OUT_MONO
        2 -> android.media.AudioFormat.CHANNEL_OUT_STEREO
        4 -> android.media.AudioFormat.CHANNEL_OUT_QUAD
        6 -> android.media.AudioFormat.CHANNEL_OUT_5POINT1
        8 -> android.media.AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
        else -> null
    }

    /**
     * Interprets the bitfield returned by `AudioManager.getDirectPlaybackSupport`.
     *
     * Per the platform documentation the return value is `DIRECT_PLAYBACK_NOT_SUPPORTED` or a
     * combination of `DIRECT_PLAYBACK_OFFLOAD_SUPPORTED` (1),
     * `DIRECT_PLAYBACK_OFFLOAD_GAPLESS_SUPPORTED` (3) and `DIRECT_PLAYBACK_BITSTREAM_SUPPORTED` (4).
     * A "supported" answer for a PCM stream is the bitstream bit; the offload bits describe DSP
     * decoding of compressed formats and are deliberately mapped to [DirectSupport.OFFLOAD_ONLY].
     */
    fun classify(supportResult: Int?): DirectSupport = when {
        supportResult == null -> DirectSupport.UNKNOWN
        supportResult == AudioManager.DIRECT_PLAYBACK_NOT_SUPPORTED -> DirectSupport.UNAVAILABLE
        supportResult and AudioManager.DIRECT_PLAYBACK_BITSTREAM_SUPPORTED != 0 -> DirectSupport.SUPPORTED
        supportResult and AudioManager.DIRECT_PLAYBACK_OFFLOAD_GAPLESS_SUPPORTED != 0 -> DirectSupport.OFFLOAD_ONLY
        supportResult and AudioManager.DIRECT_PLAYBACK_OFFLOAD_SUPPORTED != 0 -> DirectSupport.OFFLOAD_ONLY
        else -> DirectSupport.UNKNOWN
    }

    /**
     * Builds the capability for a route from per-encoding probe results.
     *
     * @param results one entry per [PcmEncoding.PROBED] candidate; a null element means that
     *   individual query did not return a usable answer.
     */
    fun capability(
        probedAtSampleRateHz: Int?,
        probedAtChannelCount: Int?,
        probedAtChannelMask: Int?,
        results: Map<PcmEncoding, Int?>,
        advertisedProfiles: List<DirectProfile>
    ): DirectPlaybackCapability = DirectPlaybackCapability(
        probedAtSampleRateHz = probedAtSampleRateHz,
        probedAtChannelCount = probedAtChannelCount,
        conditionLabel = if (probedAtSampleRateHz != null && probedAtChannelCount != null) {
            "${probedAtSampleRateHz} Hz / $probedAtChannelCount ch / mask 0x${probedAtChannelMask?.toString(16)}"
        } else null,
        perEncoding = PcmEncoding.PROBED.map { encoding ->
            EncodingCapability(encoding, probedAtSampleRateHz ?: 0, classify(results[encoding]))
        },
        advertisedProfiles = advertisedProfiles
    )
}
