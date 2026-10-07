package com.flactify.audio.spatial

internal enum class SpatialHrtfSource {
    BUILT_IN_DEFAULT,
    PINNED_CIPIC_SOFA
}

/** Selects only rates verified by the pinned Steam Audio default map or its SOFA resampler. */
internal object SpatialHrtfRateSupport {
    fun sourceFor(sampleRateHz: Int): SpatialHrtfSource? = when (sampleRateHz) {
        24_000, 44_100, 48_000 -> SpatialHrtfSource.BUILT_IN_DEFAULT
        88_200, 96_000, 176_400, 192_000 -> SpatialHrtfSource.PINNED_CIPIC_SOFA
        else -> null
    }
}
