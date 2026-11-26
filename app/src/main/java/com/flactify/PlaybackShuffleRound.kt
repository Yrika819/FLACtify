package com.flactify

import kotlin.random.Random

/**
 * Builds the shuffle order for one listening round.
 *
 * Media3's default shuffle order places the current item somewhere inside the permutation, so
 * starting playback from an arbitrary queue item leaves every item before that position unplayed
 * and playback stops at the end of the order. The order built here always starts at the current
 * item: with repeat OFF the player then visits every remaining item exactly once and stops, with
 * repeat ALL it wraps into the next round, and repeat ONE is unaffected because it repeats the
 * current item.
 */
internal object PlaybackShuffleRound {

    /**
     * A round request describes the queue and start item the caller intended. It is built from
     * the items the caller is loading, never from the asynchronously-reflected MediaController
     * state, so the service can reject a command that arrives after another queue or item became
     * current.
     */
    data class Request(
        val queueFingerprint: Long,
        val itemCount: Int,
        val startIndex: Int
    )

    /** Observable playback-order snapshot used to decide whether rebuilding is necessary. */
    data class State(
        val itemCount: Int,
        val currentIndex: Int,
        val queueFingerprint: Long,
        val activeOrderLength: Int,
        val activeOrderFirstIndex: Int
    )

    enum class Decision {
        /** Build a new order that starts at the current item. */
        APPLY,

        /** The active order already starts at the current item; keep it untouched. */
        ALREADY_APPLIED,

        /** Shuffle is off; the service must not build an order. */
        SHUFFLE_DISABLED,

        /** The request described a different queue than the one loaded now. */
        STALE_QUEUE,

        /** The request described a different current item than the one playing now. */
        STALE_ITEM,

        /** Zero or one item needs no shuffle order. */
        NOTHING_TO_REORDER
    }

    /**
     * Returns the Media3 shuffle-order indices for [itemCount] items starting at [startIndex], or
     * null when there is nothing to reorder or [startIndex] is not an item index.
     */
    fun orderStartingAt(
        itemCount: Int,
        startIndex: Int,
        random: Random = Random.Default
    ): IntArray? {
        if (itemCount <= 1) return null
        if (startIndex !in 0 until itemCount) return null
        val order = IntArray(itemCount)
        order[0] = startIndex
        var slot = 1
        for (index in 0 until itemCount) {
            if (index != startIndex) order[slot++] = index
        }
        // Fisher-Yates over the tail only, so the current item stays first in the round.
        for (position in itemCount - 1 downTo 2) {
            val swap = 1 + random.nextInt(position)
            val value = order[position]
            order[position] = order[swap]
            order[swap] = value
        }
        return order
    }

    /** Stable identity of a queue. Order and item boundaries are significant. */
    fun queueFingerprint(mediaIds: List<String>): Long {
        var hash = FNV_OFFSET_BASIS
        for (mediaId in mediaIds) {
            for (character in mediaId) {
                hash = hash xor character.code.toLong()
                hash *= FNV_PRIME
            }
            // A separator keeps ["a", "bc"] distinct from ["ab", "c"].
            hash = hash xor 0x1FL
            hash *= FNV_PRIME
        }
        return hash xor mediaIds.size.toLong()
    }

    /** Builds a request from the queue the caller intends to load. */
    fun requestFor(mediaIds: List<String>, startIndex: Int): Request? {
        if (mediaIds.isEmpty()) return null
        if (startIndex !in mediaIds.indices) return null
        return Request(
            queueFingerprint = queueFingerprint(mediaIds),
            itemCount = mediaIds.size,
            startIndex = startIndex
        )
    }

    /**
     * Decides whether the service should rebuild the active order. A null [request] means the
     * service itself initiated the round (for example when an external controller enabled shuffle).
     */
    fun decide(
        request: Request?,
        shuffleModeEnabled: Boolean,
        state: State
    ): Decision {
        if (!shuffleModeEnabled) return Decision.SHUFFLE_DISABLED
        // Queue identity is checked before the size shortcut so a delayed command for an old
        // queue cannot be reported as a successful no-op after that queue was cleared.
        if (request != null &&
            (request.itemCount != state.itemCount ||
                request.queueFingerprint != state.queueFingerprint)
        ) {
            return Decision.STALE_QUEUE
        }
        if (state.itemCount <= 1) return Decision.NOTHING_TO_REORDER
        if (state.currentIndex !in 0 until state.itemCount) {
            return Decision.NOTHING_TO_REORDER
        }
        if (request != null && request.startIndex != state.currentIndex) {
            return Decision.STALE_ITEM
        }
        if (state.activeOrderLength == state.itemCount &&
            state.activeOrderFirstIndex == state.currentIndex
        ) {
            return Decision.ALREADY_APPLIED
        }
        return Decision.APPLY
    }

    private const val FNV_OFFSET_BASIS = 1469598103934665603L
    private const val FNV_PRIME = 1099511628211L
}
