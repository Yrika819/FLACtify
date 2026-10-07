package com.flactify.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SleepTimerController(
    private val scope: CoroutineScope,
    private val onTimerFinished: () -> Unit
) {
    private var timerJob: Job? = null

    fun setSleepTimer(minutes: Int) {
        timerJob?.cancel()
        timerJob = null
        if (minutes > 0) {
            timerJob = scope.launch {
                delay(minutes * 60_000L)
                onTimerFinished()
                timerJob = null
            }
        }
    }

    fun cancel() {
        timerJob?.cancel()
        timerJob = null
    }
}
