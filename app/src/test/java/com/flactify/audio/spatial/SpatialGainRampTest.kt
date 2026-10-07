package com.flactify.audio.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque

class SpatialGainRampTest {

    @Test
    fun fadeOutUsesSmallMonotonicStepsAndEndsAtSilence() {
        val scheduler = ManualScheduler()
        val observedGains = mutableListOf<Float>()
        var completed = false
        val ramp = SpatialGainRamp(
            scheduleDelayed = scheduler::postDelayed,
            cancelScheduled = scheduler::removeCallbacks
        )

        ramp.start(
            from = 0.75f,
            to = 0f,
            setGain = { gain -> observedGains.add(gain) },
            onComplete = { completed = true }
        )

        assertTrue(observedGains.isEmpty())
        assertFalse(completed)
        repeat(7) {
            scheduler.runNext()
            assertFalse(completed)
        }

        scheduler.runNext()

        assertEquals(8, observedGains.size)
        assertTrue(observedGains.zipWithNext().all { (previous, next) -> next < previous })
        assertEquals(0f, observedGains.last())
        assertTrue(completed)
        assertEquals(80L, scheduler.elapsedMs)
    }

    @Test
    fun canceledFadeDoesNotApplyMoreGainsOrComplete() {
        val scheduler = ManualScheduler()
        val observedGains = mutableListOf<Float>()
        var completed = false
        val ramp = SpatialGainRamp(
            scheduleDelayed = scheduler::postDelayed,
            cancelScheduled = scheduler::removeCallbacks
        )

        val transition = ramp.start(
            from = 0f,
            to = 0.75f,
            setGain = { gain -> observedGains.add(gain) },
            onComplete = { completed = true }
        )
        scheduler.runNext()
        transition.cancel()
        val gainsAtCancellation = observedGains.size

        scheduler.runAll()

        assertEquals(gainsAtCancellation, observedGains.size)
        assertFalse(completed)
    }

    private class ManualScheduler {
        private data class Pending(val delayMs: Long, val task: Runnable)

        private val pending = ArrayDeque<Pending>()
        var elapsedMs: Long = 0
            private set

        fun postDelayed(task: Runnable, delayMs: Long) {
            pending.addLast(Pending(delayMs, task))
        }

        fun removeCallbacks(task: Runnable) {
            pending.removeAll { it.task === task }
        }

        fun runNext() {
            val next = pending.removeFirst()
            elapsedMs += next.delayMs
            next.task.run()
        }

        fun runAll() {
            while (pending.isNotEmpty()) runNext()
        }
    }
}
