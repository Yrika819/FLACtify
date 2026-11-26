package com.flactify

import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
    fun `the queue fingerprint is stable and sensitive to order and identity`() {
        val ids = listOf("content://a", "content://b", "content://c")
        val first = PlaybackShuffleRound.queueFingerprint(ids)

        assertEquals(first, PlaybackShuffleRound.queueFingerprint(ids.toList()))
        assertNotEquals(first, PlaybackShuffleRound.queueFingerprint(ids.reversed()))
        assertNotEquals(first, PlaybackShuffleRound.queueFingerprint(listOf("content://a", "content://b", "content://d")))
        assertNotEquals(first, PlaybackShuffleRound.queueFingerprint(listOf("content://a", "content://bc")))
    }

    @Test
    fun `a request captures the queue and start item the controller observed`() {
        val ids = listOf("content://a", "content://b", "content://c")

        val request = PlaybackShuffleRound.requestFor(ids, 2)

        assertEquals(3, request?.itemCount)
        assertEquals(2, request?.startIndex)
        assertEquals(PlaybackShuffleRound.queueFingerprint(ids), request?.queueFingerprint)
        assertNull("an empty queue has no round", PlaybackShuffleRound.requestFor(emptyList(), 0))
        assertNull("an out-of-range start has no round", PlaybackShuffleRound.requestFor(ids, 3))
    }

    @Test
    fun `a valid request applies when the active order does not start at the current item`() {
        val ids = listOf("content://a", "content://b", "content://c", "content://d")

        val decision = PlaybackShuffleRound.decide(
            request = PlaybackShuffleRound.requestFor(ids, 2),
            shuffleModeEnabled = true,
            state = PlaybackShuffleRound.State(
                itemCount = 4,
                currentIndex = 2,
                queueFingerprint = PlaybackShuffleRound.queueFingerprint(ids),
                activeOrderLength = 4,
                activeOrderFirstIndex = 0
            )
        )

        assertEquals(PlaybackShuffleRound.Decision.APPLY, decision)
    }

    @Test
    fun `an order that already starts at the current item is not regenerated`() {
        val ids = listOf("content://a", "content://b", "content://c", "content://d")

        val decision = PlaybackShuffleRound.decide(
            request = PlaybackShuffleRound.requestFor(ids, 2),
            shuffleModeEnabled = true,
            state = PlaybackShuffleRound.State(
                itemCount = 4,
                currentIndex = 2,
                queueFingerprint = PlaybackShuffleRound.queueFingerprint(ids),
                activeOrderLength = 4,
                activeOrderFirstIndex = 2
            )
        )

        assertEquals(PlaybackShuffleRound.Decision.ALREADY_APPLIED, decision)
    }

    @Test
    fun `shuffle off never rebuilds a round`() {
        val ids = listOf("content://a", "content://b")

        val decision = PlaybackShuffleRound.decide(
            request = PlaybackShuffleRound.requestFor(ids, 1),
            shuffleModeEnabled = false,
            state = PlaybackShuffleRound.State(
                itemCount = 2,
                currentIndex = 1,
                queueFingerprint = PlaybackShuffleRound.queueFingerprint(ids),
                activeOrderLength = 2,
                activeOrderFirstIndex = 0
            )
        )

        assertEquals(PlaybackShuffleRound.Decision.SHUFFLE_DISABLED, decision)
    }

    @Test
    fun `a delayed request for a different queue is rejected`() {
        val requestedIds = listOf("content://a", "content://b", "content://c")
        val currentIds = listOf("content://x", "content://y", "content://z")
        val baseState = PlaybackShuffleRound.State(
            itemCount = 3,
            currentIndex = 1,
            queueFingerprint = PlaybackShuffleRound.queueFingerprint(currentIds),
            activeOrderLength = 3,
            activeOrderFirstIndex = 2
        )

        assertEquals(
            PlaybackShuffleRound.Decision.STALE_QUEUE,
            PlaybackShuffleRound.decide(
                PlaybackShuffleRound.requestFor(requestedIds, 1), true, baseState
            )
        )
        assertEquals(
            PlaybackShuffleRound.Decision.STALE_QUEUE,
            PlaybackShuffleRound.decide(
                PlaybackShuffleRound.requestFor(requestedIds, 1), true, baseState.copy(itemCount = 4)
            )
        )
    }

    @Test
    fun `a delayed request for another current item is rejected`() {
        val ids = listOf("content://a", "content://b", "content://c")

        val decision = PlaybackShuffleRound.decide(
            request = PlaybackShuffleRound.requestFor(ids, 0),
            shuffleModeEnabled = true,
            state = PlaybackShuffleRound.State(
                itemCount = 3,
                currentIndex = 1,
                queueFingerprint = PlaybackShuffleRound.queueFingerprint(ids),
                activeOrderLength = 3,
                activeOrderFirstIndex = 1
            )
        )

        assertEquals(PlaybackShuffleRound.Decision.STALE_ITEM, decision)
    }

    @Test
    fun `a one-item queue has no round to rebuild`() {
        val ids = listOf("content://only")

        val decision = PlaybackShuffleRound.decide(
            request = PlaybackShuffleRound.requestFor(ids, 0),
            shuffleModeEnabled = true,
            state = PlaybackShuffleRound.State(
                itemCount = 1,
                currentIndex = 0,
                queueFingerprint = PlaybackShuffleRound.queueFingerprint(ids),
                activeOrderLength = 1,
                activeOrderFirstIndex = 0
            )
        )

        assertEquals(PlaybackShuffleRound.Decision.NOTHING_TO_REORDER, decision)
    }

    @Test
    fun `the same seed reproduces the same round`() {
        assertArrayEquals(
            PlaybackShuffleRound.orderStartingAt(9, 4, Random(42)),
            PlaybackShuffleRound.orderStartingAt(9, 4, Random(42))
        )
    }
}
