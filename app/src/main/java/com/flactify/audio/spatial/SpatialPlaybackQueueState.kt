package com.flactify.audio.spatial

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.MediaSource

/**
 * Playback-order snapshot used to distinguish a renderer stream boundary from playlist EOS.
 *
 * Media3 may finish a renderer stream while moving between periods or playlist items. Only the
 * last known period in the active repeat/shuffle order is eligible to drain the HRTF tail.
 */
internal class SpatialPlaybackQueueState {
    @Volatile
    private var repeatMode: Int = Player.REPEAT_MODE_OFF

    @Volatile
    private var shuffleModeEnabled: Boolean = false

    /**
     * Window index of the last item in the active shuffle order, or [C.INDEX_UNSET] when the
     * currently active order is not known yet. Only meaningful while [shuffleModeEnabled] is true.
     */
    @Volatile
    private var shuffleOrderLastIndex: Int = C.INDEX_UNSET

    fun updatePlaybackOrder(
        repeatMode: Int,
        shuffleModeEnabled: Boolean,
        shuffleOrderLastIndex: Int = C.INDEX_UNSET
    ) {
        this.repeatMode = repeatMode
        this.shuffleModeEnabled = shuffleModeEnabled
        this.shuffleOrderLastIndex = shuffleOrderLastIndex
    }

    @OptIn(UnstableApi::class)
    fun shouldDrainFinalHrtfTail(
        rendererStreamIsFinal: Boolean,
        timeline: Timeline,
        mediaPeriodId: MediaSource.MediaPeriodId?
    ): Boolean {
        if (!rendererStreamIsFinal || timeline.isEmpty ||
            mediaPeriodId == null || mediaPeriodId.isAd ||
            mediaPeriodId.nextAdGroupIndex != C.INDEX_UNSET
        ) {
            return false
        }

        val periodIndex = timeline.getIndexOfPeriod(mediaPeriodId.periodUid)
        if (periodIndex == C.INDEX_UNSET) return false

        val period = timeline.getPeriod(periodIndex, Timeline.Period())
        if (!shuffleModeEnabled) {
            return timeline.isLastPeriod(
                periodIndex,
                period,
                Timeline.Window(),
                repeatMode,
                false
            )
        }
        // Timeline.isLastPeriod() ignores the actual shuffle order, so it would treat the last
        // window of the playlist as terminal even when the active order has unplayed items left.
        if (repeatMode != Player.REPEAT_MODE_OFF) return false
        val lastIndex = shuffleOrderLastIndex
        if (lastIndex == C.INDEX_UNSET || period.windowIndex != lastIndex) return false
        val window = timeline.getWindow(period.windowIndex, Timeline.Window())
        return window.lastPeriodIndex == periodIndex
    }
}
