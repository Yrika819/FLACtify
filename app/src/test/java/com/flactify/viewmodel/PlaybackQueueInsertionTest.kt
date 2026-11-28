package com.flactify.viewmodel

import android.net.TestUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackQueueInsertionTest {
    @Test
    fun `next inserts immediately after current and keeps current item`() {
        val queue = listOf(track("one"), track("two"), track("three"))

        val plan = PlaybackQueueInsertionPlanner.plan(
            queue = queue,
            currentIndex = 1,
            selected = track("next"),
            destination = QueueInsertionDestination.NEXT,
            shuffleEnabled = false
        )

        requireNotNull(plan)
        assertEquals(listOf("one", "two", "next", "three"), plan.queue.map { it.title })
        assertEquals(2, plan.insertionIndex)
        assertEquals(1, plan.currentIndex)
    }

    @Test
    fun `end appends and keeps current item and source order`() {
        val queue = listOf(track("one"), track("two"), track("three"))

        val plan = PlaybackQueueInsertionPlanner.plan(
            queue = queue,
            currentIndex = 1,
            selected = track("last"),
            destination = QueueInsertionDestination.END,
            shuffleEnabled = false
        )

        requireNotNull(plan)
        assertEquals(listOf("one", "two", "three", "last"), plan.queue.map { it.title })
        assertEquals(3, plan.insertionIndex)
        assertEquals(1, plan.currentIndex)
    }

    @Test
    fun `next is rejected while shuffle owns playback order`() {
        assertNull(
            PlaybackQueueInsertionPlanner.plan(
                queue = listOf(track("one"), track("two")),
                currentIndex = 0,
                selected = track("next"),
                destination = QueueInsertionDestination.NEXT,
                shuffleEnabled = true
            )
        )
    }

    @Test
    fun `end remains a raw queue append while shuffle is enabled`() {
        val plan = PlaybackQueueInsertionPlanner.plan(
            queue = listOf(track("one"), track("two")),
            currentIndex = 0,
            selected = track("last"),
            destination = QueueInsertionDestination.END,
            shuffleEnabled = true
        )

        requireNotNull(plan)
        assertEquals(listOf("one", "two", "last"), plan.queue.map { it.title })
        assertEquals(0, plan.currentIndex)
    }

    @Test
    fun `missing current item permits insertion without selecting a new current item`() {
        val plan = PlaybackQueueInsertionPlanner.plan(
            queue = emptyList(),
            currentIndex = -1,
            selected = track("first"),
            destination = QueueInsertionDestination.NEXT,
            shuffleEnabled = false
        )

        requireNotNull(plan)
        assertEquals(listOf("first"), plan.queue.map { it.title })
        assertEquals(0, plan.insertionIndex)
        assertEquals(-1, plan.currentIndex)
    }

    @Test
    fun `invalid current index is rejected`() {
        assertNull(
            PlaybackQueueInsertionPlanner.plan(
                queue = listOf(track("one")),
                currentIndex = 2,
                selected = track("next"),
                destination = QueueInsertionDestination.NEXT,
                shuffleEnabled = false
            )
        )
    }

    private fun track(title: String): TrackData = TrackData(
        title = title,
        artist = "artist",
        originalIndex = 0,
        uri = TestUri("content://tracks/$title")
    )
}
