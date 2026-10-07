package com.flactify.viewmodel

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlinx.coroutines.test.runTest

class RecommendationControllerTest {
    @Test
    fun `getRecommendations delegates to manager`() = runTest {
        val manager = mockk<RecommendationManager>()
        val expected = listOf(
            RecommendedTrack("Song", "Artist", "reason", "Source")
        )
        every { manager.getRecommendations(emptyMap(), emptyMap()) } returns Result.success(expected)

        val result = RecommendationController(manager).getRecommendations(emptyMap(), emptyMap())

        assertEquals(expected, result.getOrThrow())
        verify(exactly = 1) { manager.getRecommendations(emptyMap(), emptyMap()) }
    }

    @Test
    fun `clearCache delegates to manager`() {
        val manager = mockk<RecommendationManager>(relaxed = true)

        RecommendationController(manager).clearCache()

        verify(exactly = 1) { manager.clearCache() }
    }
}
