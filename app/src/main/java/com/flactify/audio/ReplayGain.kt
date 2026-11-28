package com.flactify.audio

import kotlin.math.log10
import kotlin.math.pow

/** Validated ReplayGain 1.0 metadata. Peak values are linear full-scale ratios. */
data class ReplayGainTags(
    val trackGainDb: Double? = null,
    val albumGainDb: Double? = null,
    val trackPeak: Double? = null,
    val albumPeak: Double? = null,
    val referenceLoudnessDb: Double? = null,
    /** True for Opus R128 metadata, which is not a ReplayGain dB string. */
    val r128TrackGainDb: Double? = null
) {
    val hasReplayGain: Boolean
        get() = trackGainDb != null || albumGainDb != null || trackPeak != null || albumPeak != null

    companion object {
        val UNKNOWN = ReplayGainTags()
    }
}

enum class ReplayGainSelection { TRACK, ALBUM }

/** User choices are persisted, but application to playback remains deliberately unavailable. */
data class ReplayGainPreferences(
    val enabled: Boolean = false,
    val selection: ReplayGainSelection = ReplayGainSelection.TRACK,
    val preampDb: Double = 0.0,
    val preventClipping: Boolean = true
)

enum class ReplayGainCorrectionStatus {
    DISABLED,
    NO_TAG,
    ACTIVE_DIAGNOSTIC_ONLY,
    CLIPPING_GUARDED_DIAGNOSTIC_ONLY,
    R128_ONLY,
    UNSUPPORTED_FORMAT,
}

/**
 * Current-item correction shown in diagnostics. This type never mutates PCM or player volume.
 * Positive corrections cannot be represented by Media3's [Player.volume] scalar without a
 * product-owned PCM gain stage, so application stays explicitly disabled.
 */
data class ReplayGainCorrection(
    val status: ReplayGainCorrectionStatus = ReplayGainCorrectionStatus.DISABLED,
    val selectedGainDb: Double? = null,
    val requestedGainDb: Double? = null,
    val appliedGainDb: Double? = null,
    val linearGain: Double? = null,
    val usedPeak: Double? = null,
    val message: String? = null
) {
    companion object {
        val UNKNOWN = ReplayGainCorrection()
    }
}

/** Framework-free calculation shared by diagnostics and unit tests. */
object ReplayGainCalculator {
    fun calculate(
        tags: ReplayGainTags,
        preferences: ReplayGainPreferences,
        formatSupported: Boolean = true
    ): ReplayGainCorrection {
        if (!preferences.enabled) {
            return ReplayGainCorrection(message = "ReplayGain is disabled")
        }
        if (!formatSupported) {
            return ReplayGainCorrection(
                status = ReplayGainCorrectionStatus.UNSUPPORTED_FORMAT,
                message = "This container is not supported for ReplayGain tag diagnostics"
            )
        }
        if (!tags.hasReplayGain && tags.r128TrackGainDb != null) {
            return ReplayGainCorrection(
                status = ReplayGainCorrectionStatus.R128_ONLY,
                selectedGainDb = tags.r128TrackGainDb,
                message = "R128 metadata is distinct from ReplayGain and is not applied"
            )
        }
        val selectedGain = when (preferences.selection) {
            ReplayGainSelection.ALBUM -> tags.albumGainDb ?: tags.trackGainDb
            ReplayGainSelection.TRACK -> tags.trackGainDb
        } ?: return ReplayGainCorrection(
            status = ReplayGainCorrectionStatus.NO_TAG,
            message = "No ReplayGain gain tag"
        )
        val selectedPeak = when (preferences.selection) {
            ReplayGainSelection.ALBUM -> tags.albumPeak ?: tags.trackPeak
            ReplayGainSelection.TRACK -> tags.trackPeak
        }
        val requested = selectedGain + preferences.preampDb
        val safe = if (preferences.preventClipping && selectedPeak.isUsablePeak()) {
            minOf(requested, -20.0 * log10(selectedPeak!!))
        } else {
            requested
        }
        val status = if (preferences.preventClipping && selectedPeak.isUsablePeak() && safe < requested) {
            ReplayGainCorrectionStatus.CLIPPING_GUARDED_DIAGNOSTIC_ONLY
        } else {
            ReplayGainCorrectionStatus.ACTIVE_DIAGNOSTIC_ONLY
        }
        return ReplayGainCorrection(
            status = status,
            selectedGainDb = selectedGain,
            requestedGainDb = requested,
            appliedGainDb = safe,
            linearGain = 10.0.pow(safe / 20.0),
            usedPeak = selectedPeak?.takeIf { it.isUsablePeak() },
            message = "Calculated only; playback application is unavailable"
        )
    }

    private fun Double?.isUsablePeak(): Boolean = this != null && isFinite() && this > 0.0
}

