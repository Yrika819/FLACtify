package com.flactify

import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Phase 6A shuffle contract: a round starts at the item that is playing now and contains every
 * queue item exactly once, so normal auto-advance plays all items and then stops.
 */
class PlaybackShuffleRoundTest {

    @Test
    fun `round starts at the current item and contains every item exactly once`() {
        val order = PlaybackShuffleRound.orderStartingAt(5, 2, Random(7))

        assertEquals(2, order?.get(0))
        assertEquals((0..4).toSet(), order?.toSet())
        assertEquals(5, order?.size)
    }

    @Test
    fun `every queue position can start a full round`() {
        for (start in 0 until 6) {
            val order = PlaybackShuffleRound.orderStartingAt(6, start, Random(start.toLong()))
            assertEquals(start, order?.get(0))
            assertEquals((0 until 6).toList(), order?.sorted())
        }
    }

    @Test
    fun `the tail is actually shuffled`() {
        val orders = (0 until 8).mapNotNull { seed ->
            PlaybackShuffleRound.orderStartingAt(6, 0, Random(seed.toLong()))?.toList()
        }

        assertEquals(8, orders.size)
        // Every order starts at the current item; at least two of them differ in the tail.
        assertEquals(setOf(0), orders.map { it.first() }.toSet())
        assertEquals(true, orders.map { it.drop(1) }.toSet().size > 1)
    }

    @Test
    fun `queues that need no reordering are left alone`() {
        assertNull(PlaybackShuffleRound.orderStartingAt(0, 0))
        assertNull(PlaybackShuffleRound.orderStartingAt(1, 0))
        assertNull(PlaybackShuffleRound.orderStartingAt(3, 3))
        assertNull(PlaybackShuffleRound.orderStartingAt(3, -1))
    }

    @Test
    fun `the same seed reproduces the same round`() {
        assertArrayEquals(
            PlaybackShuffleRound.orderStartingAt(9, 4, Random(42)),
            PlaybackShuffleRound.orderStartingAt(9, 4, Random(42))
        )
    }
}
