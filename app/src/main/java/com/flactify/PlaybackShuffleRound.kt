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
}
