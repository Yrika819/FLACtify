package com.flactify.audio.spatial

import java.nio.ByteBuffer

/**
 * Batched PCM contract between Media3's output boundary and the native spatial renderer.
 * Implementations accept arbitrary input chunk sizes and retain incomplete processing frames.
 */
internal interface SpatialPcmEngine : AutoCloseable {
    /** Packs consumed input frames in the high 32 bits and produced output frames in the low 32. */
    fun process(
        input: ByteBuffer,
        inputFrames: Int,
        output: ByteBuffer,
        encoding: NativeSpatialEngine.PcmEncoding
    ): Long

    /** Emits remaining input and the final renderer tail. Call until zero is returned. */
    fun drainTail(output: ByteBuffer, encoding: NativeSpatialEngine.PcmEncoding): Int

    fun reset()

    override fun close()

    companion object {
        const val FRAME_SIZE = 512
    }
}
