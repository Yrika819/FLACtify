package com.flactify.viewmodel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Real-time wait for library state that is produced on a real dispatcher.
 *
 * Phase 2A groups the library on `Dispatchers.Default` and publishes the result on
 * `Dispatchers.Main`, so the grouping no longer runs on the test dispatcher that `advanceUntilIdle()`
 * drives. Waiting with `delay` would be driven by the virtual clock of `runTest` and would spin
 * through its own timeout while the real work is still in flight, so the wait blocks a real thread
 * instead and keeps the calling dispatcher free.
 */
internal suspend fun awaitLibraryState(
    timeoutMs: Long = 60_000,
    message: String = "the published library state to settle",
    settled: () -> Boolean
) {
    withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!settled()) {
            if (System.currentTimeMillis() > deadline) {
                error("timed out waiting for $message")
            }
            try {
                Thread.sleep(2)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                error("interrupted while waiting for $message")
            }
        }
    }
}
