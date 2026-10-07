package com.flactify.audio.spatial

import java.nio.ByteBuffer

/**
 * Receives a transient view of PCM frames accepted by the downstream AudioOutput.
 *
 * Implementations must consume the supplied range synchronously and must not retain [buffer].
 * The normal PlaybackService supplies no observer.
 */
internal fun interface SpatialPcmOutputObserver {
    fun onAccepted(
        buffer: ByteBuffer,
        byteOffset: Int,
        frameIndex: Long,
        frameCount: Int,
        presentationTimeUs: Long,
        finalTailFrameCount: Int
    )
}

/** Creates one observer per spatial AudioOutput instance, off the steady-state write path. */
internal fun interface SpatialPcmOutputObserverFactory {
    fun create(
        outputId: Long,
        sampleRateHz: Int,
        encoding: NativeSpatialEngine.PcmEncoding
    ): SpatialPcmOutputObserver
}
