package com.flactify.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The progress poller fires about every 250 ms. Before this policy existed, every one of those
 * ticks wrote two SharedPreferences keys, so roughly four disk writes per second ran for the
 * entire duration of playback to preserve a value that only needs to be accurate to seconds.
 */
class PlaybackPositionPersisterTest {

    private var now = 0L
    private fun persister(intervalMs: Long = 5_000L) =
        PlaybackPositionPersister(intervalMs) { now }

    @Test
    fun `first progress tick writes immediately`() {
        val p = persister()
        val checkpoint = p.onProgress("content://track", 1_000L)

        assertNotNull(checkpoint)
        assertEquals("content://track", checkpoint!!.uri)
        assertEquals(1_000L, checkpoint.positionMs)
    }

    @Test
    fun `ticks inside the interval do no work`() {
        val p = persister()
        p.onProgress("content://track", 0L)

        // 20 further ticks over 5 seconds at the 250 ms poll rate.
        var writes = 0
        for (i in 1..20) {
            now = i * 250L
            if (p.onProgress("content://track", i * 250L) != null) writes++
        }

        // Only the tick exactly at the boundary may write; none of the 19 before it.
        assertEquals(1, writes)
    }

    @Test
    fun `write rate is bounded by the interval over a long playback`() {
        val p = persister(5_000L)
        p.onProgress("content://track", 0L)

        var writes = 1
        // One hour of playback at 250 ms per tick = 14400 ticks.
        for (i in 1..14_400) {
            now = i * 250L
            if (p.onProgress("content://track", i * 250L) != null) writes++
        }

        // One hour is 720 writes at the interval versus 14400 before, a 20x reduction.
        assertEquals(721, writes)
    }

    @Test
    fun `flush writes the latest position regardless of interval`() {
        val p = persister()
        p.onProgress("content://track", 0L)

        now = 100L
        p.onProgress("content://track", 42_000L)

        val flushed = p.flush()
        assertNotNull(flushed)
        assertEquals(42_000L, flushed!!.positionMs)
    }

    @Test
    fun `flush with nothing pending does no work`() {
        assertNull(persister().flush())
    }

    @Test
    fun `flush does not write the same position twice`() {
        val p = persister()
        p.onProgress("content://track", 0L)      // writes immediately
        p.onProgress("content://track", 5_000L)  // inside the interval, stays pending

        assertNotNull(p.flush())
        assertNull("A second flush with nothing new must not rewrite", p.flush())
    }

    @Test
    fun `null uri never produces a checkpoint`() {
        assertNull(persister().onProgress(null, 1_000L))
        assertNull(persister().onProgress("", 1_000L))
    }

    @Test
    fun `reset drops pending state without writing`() {
        val p = persister()
        p.onProgress("content://track", 0L)
        p.onProgress("content://track", 9_000L)
        p.reset()

        assertNull(p.flush())
    }

    @Test
    fun `uri change is picked up on the next flush`() {
        val p = persister()
        p.onProgress("content://old", 0L)
        p.onProgress("content://new", 7_000L)

        assertEquals("content://new", p.flush()!!.uri)
    }
}
