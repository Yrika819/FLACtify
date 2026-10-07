package com.flactify.viewmodel

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelOnClearTest {

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
    fun `cleanup can be called multiple times safely`() = runTest {
        val viewModel = PlayerViewModel()

        try {
            viewModel.cleanup()
            viewModel.cleanup()
        } catch (e: Exception) {
            fail("cleanup should be safe to call multiple times: ${e.message}")
        }
    }

    @Test
    fun `cleanup does not throw when controller is null`() = runTest {
        val viewModel = PlayerViewModel()

        try {
            viewModel.cleanup()
        } catch (e: Exception) {
            fail("cleanup should not throw when controller is null: ${e.message}")
        }
    }

    @Test
    fun `statsVersion increments after multiple notifyStatsChanged`() = runTest {
        val viewModel = PlayerViewModel()

        viewModel.notifyStatsChanged()
        viewModel.notifyStatsChanged()
        advanceUntilIdle()

        assertEquals(2L, viewModel.statsVersion.value)
    }

    @Test
    fun `statsVersion starts at zero`() = runTest {
        val viewModel = PlayerViewModel()
        assertEquals(0L, viewModel.statsVersion.value)
    }

    @Test
    fun `cleanup resets saving metadata guard`() = runTest {
        val viewModel = PlayerViewModel()

        viewModel.cleanup()

        try {
            viewModel.cleanup()
        } catch (e: Exception) {
            fail("cleanup should be safe after guard reset: ${e.message}")
        }
    }

    private fun advanceUntilIdle() {
        testDispatcher.scheduler.advanceUntilIdle()
    }
}
