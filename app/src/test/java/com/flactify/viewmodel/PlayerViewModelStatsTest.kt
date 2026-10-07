package com.flactify.viewmodel

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelStatsTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `statsVersion increments after notifyStatsChanged`() = runTest {
        val viewModel = PlayerViewModel()
        val initialVersion = viewModel.statsVersion.first()

        viewModel.notifyStatsChanged()
        advanceUntilIdle()

        val newVersion = viewModel.statsVersion.first()
        assertEquals(initialVersion + 1, newVersion)
    }

    @Test
    fun `statsVersion increments multiple times`() = runTest {
        val viewModel = PlayerViewModel()

        viewModel.notifyStatsChanged()
        viewModel.notifyStatsChanged()
        viewModel.notifyStatsChanged()
        advanceUntilIdle()

        val version = viewModel.statsVersion.first()
        assertEquals(3L, version)
    }

    @Test
    fun `getStatsSummary returns empty stats when no data`() = runTest {
        val viewModel = PlayerViewModel()
        val summary = viewModel.getStatsSummary()

        assertEquals(0, summary.totalTracks)
        assertEquals(0, summary.totalPlayCount)
        assertEquals(0, summary.totalSkipCount)
        assertEquals(0L, summary.lastPlayed)
    }

    private fun advanceUntilIdle() {
        testDispatcher.scheduler.advanceUntilIdle()
    }
}
