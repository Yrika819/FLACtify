package com.flactify.viewmodel

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SleepTimerControllerTest {
    @Test
    fun `timer invokes callback after configured duration`() = runTest {
        var callbackCount = 0
        val controller = SleepTimerController(this) { callbackCount++ }

        controller.setSleepTimer(1)
        advanceTimeBy(60_000L)
        runCurrent()

        assertEquals(1, callbackCount)
    }

    @Test
    fun `cancel prevents callback`() = runTest {
        var callbackCount = 0
        val controller = SleepTimerController(this) { callbackCount++ }

        controller.setSleepTimer(1)
        controller.cancel()
        advanceTimeBy(60_000L)
        runCurrent()

        assertEquals(0, callbackCount)
    }
}
