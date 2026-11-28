package com.flactify.viewmodel

/** Where a selected track should be inserted in the source queue. */
internal enum class QueueInsertionDestination {
    NEXT,
    END
}

/**
 * A queue mutation that can be applied without rebuilding the player or changing playback.
 *
 * [currentIndex] is -1 when the source queue has no current item. The planner deliberately does
 * not touch repeat mode or shuffle state; the caller remains responsible for applying the
 * insertion through the existing Media3 player/session owner.
 */
internal data class PlaybackQueueInsertionPlan(
    val queue: List<TrackData>,
    val currentIndex: Int,
    val insertionIndex: Int
)

/**
 * Plans queue insertion while preserving the current item and its source index.
 *
 * This is intentionally a pure boundary for now. The library UI has no queue-action affordance,
 * and MediaController cannot choose the next item in the service-owned shuffle permutation. A
 * NEXT plan is therefore rejected while shuffle is enabled instead of silently inserting at a
 * raw queue index that may not play next. END remains a source-queue append; callers must not
 * present it as chronological playback order while shuffle is active.
 */
internal object PlaybackQueueInsertionPlanner {
    /**
     * Returns a plan, or null when [currentIndex] is invalid or NEXT is ambiguous under shuffle.
     * A missing current item is represented by -1 and permits both destinations without starting
     * playback as a side effect.
     */
    fun plan(
        queue: List<TrackData>,
        currentIndex: Int,
        selected: TrackData,
        destination: QueueInsertionDestination,
        shuffleEnabled: Boolean
    ): PlaybackQueueInsertionPlan? {
        if (currentIndex !in -1..queue.lastIndex) return null
        if (destination == QueueInsertionDestination.NEXT && shuffleEnabled) return null

        val insertionIndex = when (destination) {
            QueueInsertionDestination.NEXT -> if (currentIndex >= 0) currentIndex + 1 else 0
            QueueInsertionDestination.END -> queue.size
        }
        val updatedQueue = queue.toMutableList().apply { add(insertionIndex, selected) }
        val updatedCurrentIndex = when {
            currentIndex < 0 -> -1
            insertionIndex <= currentIndex -> currentIndex + 1
            else -> currentIndex
        }
        return PlaybackQueueInsertionPlan(
            queue = updatedQueue,
            currentIndex = updatedCurrentIndex,
            insertionIndex = insertionIndex
        )
    }
}
