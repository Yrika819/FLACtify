package com.flactify.audio.spatial

import kotlin.math.PI
import kotlin.math.cos

/**
 * Applies a short cosine-shaped gain ramp on the caller's control thread.
 * This is used only during player replacement; it does not run on the audio callback path.
 */
internal class SpatialGainRamp(
    private val scheduleDelayed: (Runnable, Long) -> Unit,
    private val cancelScheduled: (Runnable) -> Unit,
    private val stepCount: Int = DEFAULT_STEP_COUNT,
    private val stepDelayMs: Long = DEFAULT_STEP_DELAY_MS
) {
    init {
        require(stepCount > 0)
        require(stepDelayMs > 0L)
    }

    interface Transition {
        fun cancel()
    }

    fun start(
        from: Float,
        to: Float,
        setGain: (Float) -> Unit,
        onComplete: () -> Unit
    ): Transition {
        require(from.isFinite() && from in 0f..1f)
        require(to.isFinite() && to in 0f..1f)

        var step = 0
        var cancelled = false
        var completed = false
        lateinit var task: Runnable
        task = Runnable {
            if (cancelled || completed) return@Runnable

            step += 1
            val progress = step.toDouble() / stepCount
            val easedProgress =
                if (step == stepCount) {
                    1.0
                } else {
                    (1.0 - cos(PI * progress)) * 0.5
                }
            setGain((from + (to - from) * easedProgress).toFloat())

            if (step == stepCount) {
                completed = true
                onComplete()
            } else {
                scheduleDelayed(task, stepDelayMs)
            }
        }

        scheduleDelayed(task, stepDelayMs)
        return object : Transition {
            override fun cancel() {
                if (cancelled || completed) return
                cancelled = true
                cancelScheduled(task)
            }
        }
    }

    private companion object {
        const val DEFAULT_STEP_COUNT = 8
        const val DEFAULT_STEP_DELAY_MS = 10L
    }
}
