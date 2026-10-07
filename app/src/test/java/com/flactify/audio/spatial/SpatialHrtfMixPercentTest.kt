package com.flactify.audio.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SpatialHrtfMixPercentTest {
    @Test
    fun convertsZeroHalfAndFullStrengthToNormalizedMix() {
        assertEquals(0.0f, SpatialHrtfMixPercent.toUnitMix(0), 0.0f)
        assertEquals(0.5f, SpatialHrtfMixPercent.toUnitMix(50), 0.0f)
        assertEquals(1.0f, SpatialHrtfMixPercent.toUnitMix(100), 0.0f)
    }

    @Test
    fun preservesTheExistingFullStrengthStudioDefault() {
        assertEquals(100, SpatialHrtfMixPercent.DEFAULT_PERCENT)
    }

    @Test
    fun rejectsPercentagesOutsideTheSupportedRange() {
        assertThrows(IllegalArgumentException::class.java) {
            SpatialHrtfMixPercent.toUnitMix(-1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SpatialHrtfMixPercent.toUnitMix(101)
        }
    }
}
