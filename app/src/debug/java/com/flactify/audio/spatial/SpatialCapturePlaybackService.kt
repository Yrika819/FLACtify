package com.flactify.audio.spatial

import com.flactify.PlaybackService

/** Debug-only service variant used to capture accepted PCM in instrumentation tests. */
class SpatialCapturePlaybackService : PlaybackService() {
    internal override fun createSpatialPcmOutputObserverFactory(): SpatialPcmOutputObserverFactory? =
        SpatialPcmCaptureRegistry.current()
}
