package com.flactify.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerArtworkSizingTest {
    @Test
    fun artworkUsesTheWholeBodyViewportOnTallScreens() {
        assertEquals(300.dp, constrainedArtworkSize(380.dp, 760.dp))
    }

    @Test
    fun artworkFitsNarrowScreens() {
        assertEquals(240.dp, constrainedArtworkSize(240.dp, 760.dp))
    }

    @Test
    fun artworkShrinksPredictablyOnlyOnCompactScreens() {
        assertEquals(168.dp, constrainedArtworkSize(380.dp, 400.dp))
    }

    @Test
    fun artworkCollapsesWhenNoSpaceIsAvailable() {
        assertEquals(0.dp, constrainedArtworkSize(240.dp, 0.dp))
    }
}
