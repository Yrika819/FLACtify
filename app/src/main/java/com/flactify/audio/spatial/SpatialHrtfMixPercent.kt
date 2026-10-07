package com.flactify.audio.spatial

internal object SpatialHrtfMixPercent {
    const val DEFAULT_PERCENT = 100
    const val MIN_PERCENT = 0
    const val MAX_PERCENT = 100

    fun isValidPercent(percent: Int): Boolean = percent in MIN_PERCENT..MAX_PERCENT

    fun toUnitMix(percent: Int): Float {
        require(isValidPercent(percent)) {
            "HRTF mix percentage must be between $MIN_PERCENT and $MAX_PERCENT"
        }
        return percent / MAX_PERCENT.toFloat()
    }
}
