package com.flactify.audio.spatial

import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.source.MediaSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialPlaybackQueueStateTest {

    @Test
    fun onlyLastPlaylistPeriodDrainsFinalHrtfTail() {
        val state = SpatialPlaybackQueueState()
        val timeline = TestTimeline(periodWindows = listOf(0, 1))

        assertFalse(
            state.shouldDrainFinalHrtfTail(
                rendererStreamIsFinal = true,
                timeline,
                MediaSource.MediaPeriodId("period-0")
            )
        )
        assertFalse(
            state.shouldDrainFinalHrtfTail(
                rendererStreamIsFinal = false,
                timeline,
                MediaSource.MediaPeriodId("period-1")
            )
        )
        assertTrue(
            state.shouldDrainFinalHrtfTail(
                rendererStreamIsFinal = true,
                timeline,
                MediaSource.MediaPeriodId("period-1")
            )
        )
    }

    @Test
    fun repeatModesPreventTreatingAnItemBoundaryAsFinalEos() {
        val state = SpatialPlaybackQueueState()
        val timeline = TestTimeline(periodWindows = listOf(0, 1))

        state.updatePlaybackOrder(Player.REPEAT_MODE_ONE, shuffleModeEnabled = false)
        assertFalse(
            state.shouldDrainFinalHrtfTail(
                rendererStreamIsFinal = true,
                timeline,
                MediaSource.MediaPeriodId("period-1")
            )
        )

        state.updatePlaybackOrder(Player.REPEAT_MODE_ALL, shuffleModeEnabled = false)
        assertFalse(state.shouldDrainFinalHrtfTail(
                rendererStreamIsFinal = true,
                timeline,
                MediaSource.MediaPeriodId("period-1")
            ))
    }

    @Test
    fun unknownTimelineAndAdStreamsDoNotClaimFinalHrtfEos() {
        val state = SpatialPlaybackQueueState()
        val timeline = TestTimeline(periodWindows = listOf(0))

        assertFalse(state.shouldDrainFinalHrtfTail(
                rendererStreamIsFinal = true,
                Timeline.EMPTY,
                MediaSource.MediaPeriodId("period-0")
            ))
        assertFalse(state.shouldDrainFinalHrtfTail(rendererStreamIsFinal = true, timeline, null))
        assertFalse(
            state.shouldDrainFinalHrtfTail(
                rendererStreamIsFinal = true,
                timeline,
                MediaSource.MediaPeriodId("period-0", 0, 0, 0L)
            )
        )
        assertFalse(
            state.shouldDrainFinalHrtfTail(
                rendererStreamIsFinal = true,
                timeline,
                MediaSource.MediaPeriodId("period-0", 0L, 0)
            )
        )
    }

    private class TestTimeline(private val periodWindows: List<Int>) : Timeline() {
        private val periodUids = periodWindows.indices.map { "period-$it" }
        private val windowCount = (periodWindows.maxOrNull() ?: -1) + 1

        override fun getWindowCount(): Int = windowCount

        override fun getWindow(
            windowIndex: Int,
            window: Timeline.Window,
            defaultPositionProjectionUs: Long
        ): Timeline.Window {
            window.uid = "window-$windowIndex"
            window.firstPeriodIndex = periodWindows.indexOf(windowIndex)
            window.lastPeriodIndex = periodWindows.lastIndexOf(windowIndex)
            return window
        }

        override fun getPeriodCount(): Int = periodWindows.size

        override fun getPeriod(
            periodIndex: Int,
            period: Timeline.Period,
            setIds: Boolean
        ): Timeline.Period {
            period.set(
                if (setIds) periodUids[periodIndex] else null,
                periodUids[periodIndex],
                periodWindows[periodIndex],
                1_000_000L,
                0L
            )
            return period
        }

        override fun getIndexOfPeriod(uid: Any): Int = periodUids.indexOf(uid)

        override fun getUidOfPeriod(periodIndex: Int): Any = periodUids[periodIndex]
    }
}
