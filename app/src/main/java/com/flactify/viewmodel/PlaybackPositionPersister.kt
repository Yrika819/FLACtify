package com.flactify.viewmodel

/** A playback position that is ready to be written to durable storage. */
data class PlaybackCheckpoint(
    val uri: String,
    val positionMs: Long
)

/**
 * Decides when the playback position is worth persisting.
 *
 * The progress poller fires roughly every 250 ms so the seek bar animates smoothly, and
 * previously each of those ticks also wrote two keys to `SharedPreferences` - about four
 * writes per second, for the entire time anything is playing, to preserve a value that only
 * needs to be accurate to a few seconds. UI smoothness and durability are separate concerns,
 * so this class decouples them: [onProgress] is the frequent path and stays cheap, while
 * [flush] forces a write at the moments that actually matter.
 *
 * Blocking calls are the caller's job; this type performs no I/O and is unit-testable.
 *
 * @param persistIntervalMs minimum gap between two writes driven by [onProgress].
 */
class PlaybackPositionPersister(
    private val persistIntervalMs: Long = DEFAULT_PERSIST_INTERVAL_MS,
    private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() }
) {
    private var pendingUri: String? = null
    private var pendingPositionMs: Long = 0L
    private var hasPending: Boolean = false
    private var hasWritten: Boolean = false
    private var lastWriteAtMs: Long = 0L

    /**
     * Frequent path, called on every progress tick.
     *
     * @return a checkpoint to persist, or null when the interval has not elapsed yet. A null
     *   return is the normal case and means the caller does no disk work at all.
     */
    fun onProgress(uri: String?, positionMs: Long): PlaybackCheckpoint? {
        if (uri.isNullOrEmpty()) return null
        pendingUri = uri
        pendingPositionMs = positionMs
        hasPending = true

        if (!hasWritten || clock() - lastWriteAtMs >= persistIntervalMs) {
            return write()
        }
        return null
    }

    /**
     * Writes immediately if anything is outstanding. Call on pause, on media transition and
     * when the ViewModel is cleared, so the worst-case resume error stays at one interval.
     */
    fun flush(): PlaybackCheckpoint? = if (hasPending) write() else null

    /**
     * Drops the pending state without writing. Used when the queue is replaced outright, where
     * the new playlist's own restore path decides where to start.
     */
    fun reset() {
        pendingUri = null
        pendingPositionMs = 0L
        hasPending = false
    }

    private fun write(): PlaybackCheckpoint? {
        val uri = pendingUri ?: return null
        lastWriteAtMs = clock()
        hasWritten = true
        hasPending = false
        return PlaybackCheckpoint(uri, pendingPositionMs)
    }

    companion object {
        /**
         * Five seconds. Bounds worst-case resume drift to five seconds while cutting the write
         * rate from ~4/s to ~0.2/s, a 20x reduction.
         */
        const val DEFAULT_PERSIST_INTERVAL_MS = 5_000L
    }
}
