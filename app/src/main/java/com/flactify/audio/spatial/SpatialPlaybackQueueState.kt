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

    fun updatePlaybackOrder(repeatMode: Int, shuffleModeEnabled: Boolean) {
        this.repeatMode = repeatMode
        this.shuffleModeEnabled = shuffleModeEnabled
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
        return timeline.isLastPeriod(
            periodIndex,
            period,
            Timeline.Window(),
            repeatMode,
            shuffleModeEnabled
        )
    }
}
