package com.flactify.audio.spatial

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import kotlin.math.abs
import androidx.media3.exoplayer.audio.AudioOutput
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Optional PCM HRTF stage at Media3's AudioOutputProvider boundary.
 *
 * Media3's AudioProcessor chain is skipped for Float32 output. This wrapper receives the actual
 * post-conversion PCM and keeps the platform AudioOutput as the sole owner of device writes.
 */
@OptIn(UnstableApi::class)
internal class SpatialAudioOutput(
    private val downstream: AudioOutput,
    private val spatialEngine: SpatialPcmEngine? = null,
    private val encoding: NativeSpatialEngine.PcmEncoding? = null,
    private val sampleRateHz: Int = 0,
    private val eosDrainState: SpatialEosDrainState? = null,
    private val onReleased: ((SpatialAudioOutput) -> Unit)? = null,
    private val outputObserver: SpatialPcmOutputObserver? = null
) : AudioOutput by downstream {

    private val bytesPerFrame = encoding?.bytesPerSample?.times(CHANNEL_COUNT) ?: 0
    private val processedOutput = if (spatialEngine != null && encoding != null) {
        require(sampleRateHz > 0) { "A positive PCM sample rate is required for spatial output" }
        ByteBuffer.allocateDirect(MAX_OUTPUT_FRAMES * bytesPerFrame)
            .order(ByteOrder.nativeOrder())
            .apply { limit(0) }
    } else {
        null
    }

    private var pendingIdentityBuffer: ByteBuffer? = null
    private var pendingIdentityTimestampUs = 0L
    private var pendingIdentityAccessUnitCount = 0

    private var pendingInputBuffer: ByteBuffer? = null
    private var pendingInputTimestampUs = 0L
    private var pendingInputAccessUnitCount = 0
    private var pendingOutputWrite = false
    private var pendingOutputTimestampUs = 0L
    private var pendingOutputAccessUnitCount = 0
    private var pendingOutputStartFrame = 0L

    private var streamStartTimeUs: Long? = null
    private var acceptedInputFrames = 0L
    private var producedOutputFrames = 0L
    private var finalInputFrames = 0L
    private var writtenTailFrames = 0L
    private var finalStopRequested = false
    private var finalDrainStarted = false
    private var pendingPotentialDiscontinuity = false
    private var pendingProcessedStreamChange = false
    private var finalTailDrained = false
    private var downstreamStopSent = false
    private var released = false

    internal val isFinalTailComplete: Boolean
        get() = spatialEngine == null || !finalStopRequested || (finalTailDrained && downstreamStopSent)

    init {
        require(spatialEngine == null || encoding != null) {
            "A PCM encoding is required when a spatial engine is supplied"
        }
        if (spatialEngine != null) {
            require(sampleRateHz > 0) {
                "A positive PCM sample rate is required for spatial output"
            }
        }
        eosDrainState?.register(this)
    }

    override fun write(
        buffer: ByteBuffer,
        encodedAccessUnitCount: Int,
        presentationTimeUs: Long
    ): Boolean {
        check(!released) { "AudioOutput is released" }
        if (!finalDrainStarted && downstreamStopSent) downstreamStopSent = false
        if (spatialEngine == null) {
            return writeIdentity(buffer, encodedAccessUnitCount, presentationTimeUs)
        }

        val isRetry = pendingInputBuffer === buffer
        check(pendingInputBuffer == null || isRetry) {
            "Media3 supplied a new buffer before the prior spatial output write completed"
        }
        val inputAccessUnitCount =
            if (isRetry) pendingInputAccessUnitCount else encodedAccessUnitCount
        val inputPresentationTimeUs =
            if (isRetry) pendingInputTimestampUs else presentationTimeUs
        require(buffer.isDirect) { "Media3 PCM input must be direct" }
        require(buffer.order() == ByteOrder.nativeOrder()) {
            "Media3 PCM input must use native byte order"
        }
        require(buffer.remaining() % bytesPerFrame == 0) {
            "Media3 PCM input must be stereo frame-aligned"
        }
        if (!isRetry) resolvePotentialDiscontinuity(inputPresentationTimeUs)

        if (pendingOutputWrite && !writePendingOutput()) {
            rememberPendingInput(buffer, inputPresentationTimeUs, inputAccessUnitCount)
            return false
        }

        while (buffer.hasRemaining()) {
            if (streamStartTimeUs == null) {
                streamStartTimeUs =
                    inputPresentationTimeUs - acceptedInputFrames * 1_000_000L / sampleRateHz
            }

            val outputBuffer = checkNotNull(processedOutput)
            outputBuffer.clear()
            val outputStartFrame = producedOutputFrames
            val inputFrames = buffer.remaining() / bytesPerFrame
            val inputPosition = buffer.position()
            val outputPosition = outputBuffer.position()
            val outputCapacityFrames = outputBuffer.remaining() / bytesPerFrame
            val frameCounts = spatialEngine.process(
                buffer,
                inputFrames,
                outputBuffer,
                checkNotNull(encoding)
            )
            val consumedFrames = (frameCounts ushr 32).toInt()
            val producedFrames = frameCounts.toInt()
            check(consumedFrames in 0..inputFrames) {
                "Spatial engine returned an invalid consumed-frame count"
            }
            check(producedFrames in 0..outputCapacityFrames) {
                "Spatial engine returned an invalid produced-frame count"
            }
            check(buffer.position() - inputPosition == consumedFrames * bytesPerFrame) {
                "Spatial engine's consumed-frame count did not match the input position"
            }
            check(outputBuffer.position() - outputPosition == producedFrames * bytesPerFrame) {
                "Spatial engine's produced-frame count did not match the output position"
            }
            acceptedInputFrames += consumedFrames
            producedOutputFrames += producedFrames

            if (producedFrames > 0) {
                outputBuffer.flip()
                pendingOutputStartFrame = outputStartFrame
                pendingOutputTimestampUs = timeForOutputFrame(outputStartFrame)
                pendingOutputAccessUnitCount = inputAccessUnitCount
                pendingOutputWrite = true
                if (!writePendingOutput()) {
                    rememberPendingInput(buffer, inputPresentationTimeUs, inputAccessUnitCount)
                    return false
                }
            } else {
                outputBuffer.limit(0)
            }

            check(consumedFrames > 0 || producedFrames > 0 || !buffer.hasRemaining()) {
                "Spatial engine made no progress while input remained"
            }
        }

        clearPendingInput()
        return true
    }

    /**
     * Pumps at most the output that currently fits downstream. Returning false keeps EOS open;
     * Media3 calls the renderer drain again after AudioTrack accepts more data.
     */
    internal fun pumpFinalEosTail(): Boolean {
        if (spatialEngine == null) return true
        if (!finalStopRequested) return false
        if (finalTailDrained && downstreamStopSent) return true

        if (pendingOutputWrite && !writePendingOutput()) return false

        val outputBuffer = checkNotNull(processedOutput)
        while (!finalTailDrained) {
            outputBuffer.clear()
            val outputStartFrame = producedOutputFrames
            val producedFrames = checkNotNull(spatialEngine).drainTail(
                outputBuffer,
                checkNotNull(encoding)
            )
            check(producedFrames >= 0) { "Spatial engine returned a negative tail-frame count" }
            if (producedFrames == 0) {
                finalTailDrained = true
                outputBuffer.limit(0)
                break
            }

            outputBuffer.flip()
            producedOutputFrames += producedFrames
            pendingOutputStartFrame = outputStartFrame
            pendingOutputTimestampUs = timeForOutputFrame(outputStartFrame)
            pendingOutputAccessUnitCount = 0
            pendingOutputWrite = true
            if (!writePendingOutput()) return false
        }

        if (finalTailDrained && !downstreamStopSent) {
            downstream.stop()
            downstreamStopSent = true
        }
        return finalTailDrained && downstreamStopSent
    }

    override fun getPositionUs(): Long {
        val downstreamPositionUs = downstream.positionUs
        if (!finalDrainStarted || sampleRateHz <= 0 || writtenTailFrames == 0L) {
            return downstreamPositionUs
        }
        val tailPositionUs = writtenTailFrames * 1_000_000L / sampleRateHz
        return (downstreamPositionUs - tailPositionUs).coerceAtLeast(0L)
    }

    internal fun markPotentialDiscontinuity(isProcessedStreamChange: Boolean = false) {
        pendingPotentialDiscontinuity = true
        pendingProcessedStreamChange = isProcessedStreamChange
    }

    internal fun resetForDiscontinuity() {
        clearWriteState()
        resetStreamState()
        spatialEngine?.reset()
    }

    override fun flush() {
        clearWriteState()
        resetStreamState()
        spatialEngine?.reset()
        downstream.flush()
    }

    override fun stop() {
        if (spatialEngine != null && eosDrainState?.isFinalEosDrain == true) {
            if (!finalStopRequested) {
                finalStopRequested = true
                finalDrainStarted = true
                finalInputFrames = acceptedInputFrames
            }
            return
        }
        if (!downstreamStopSent) {
            downstream.stop()
            downstreamStopSent = true
        }
    }

    override fun release() {
        if (released) return
        released = true
        clearWriteState()
        eosDrainState?.unregister(this)
        try {
            spatialEngine?.close()
        } finally {
            try {
                downstream.release()
            } finally {
                onReleased?.invoke(this)
            }
        }
    }

    private fun writeIdentity(
        buffer: ByteBuffer,
        encodedAccessUnitCount: Int,
        presentationTimeUs: Long
    ): Boolean {
        val isRetry = pendingIdentityBuffer === buffer
        check(pendingIdentityBuffer == null || isRetry) {
            "Media3 supplied a new buffer before the prior AudioOutput write completed"
        }
        val handled = downstream.write(
            buffer,
            if (isRetry) pendingIdentityAccessUnitCount else encodedAccessUnitCount,
            if (isRetry) pendingIdentityTimestampUs else presentationTimeUs
        )
        if (handled) {
            pendingIdentityBuffer = null
        } else if (!isRetry) {
            pendingIdentityBuffer = buffer
            pendingIdentityAccessUnitCount = encodedAccessUnitCount
            pendingIdentityTimestampUs = presentationTimeUs
        }
        return handled
    }

    private fun writePendingOutput(): Boolean {
        val outputBuffer = checkNotNull(processedOutput)
        val positionBeforeWrite = outputBuffer.position()
        val handled = downstream.write(
            outputBuffer,
            pendingOutputAccessUnitCount,
            pendingOutputTimestampUs
        )
        val positionAfterWrite = outputBuffer.position()
        check(positionAfterWrite >= positionBeforeWrite) {
            "AudioOutput moved the processed buffer position backwards"
        }
        val consumedBytes = positionAfterWrite - positionBeforeWrite
        check(consumedBytes % bytesPerFrame == 0) {
            "AudioOutput consumed a partial spatial PCM frame"
        }
        check(!handled || !outputBuffer.hasRemaining()) {
            "AudioOutput reported success before consuming the processed buffer"
        }
        if (consumedBytes > 0) {
            val firstWrittenFrame = pendingOutputStartFrame + positionBeforeWrite / bytesPerFrame
            val frameCount = consumedBytes / bytesPerFrame
            val endWrittenFrame = firstWrittenFrame + frameCount
            val finalTailFrameCount =
                if (finalDrainStarted) {
                    val firstTailFrame = maxOf(firstWrittenFrame, finalInputFrames)
                    (endWrittenFrame - firstTailFrame).coerceAtLeast(0L).toInt()
                } else {
                    0
                }
            if (finalDrainStarted) writtenTailFrames += finalTailFrameCount

            outputObserver?.onAccepted(
                buffer = outputBuffer,
                byteOffset = positionBeforeWrite,
                frameIndex = firstWrittenFrame,
                frameCount = frameCount,
                presentationTimeUs = pendingOutputTimestampUs,
                finalTailFrameCount = finalTailFrameCount
            )
        }
        if (handled) {
            pendingOutputWrite = false
            outputBuffer.clear()
            outputBuffer.limit(0)
        }
        return handled
    }

    private fun rememberPendingInput(
        buffer: ByteBuffer,
        timestampUs: Long,
        accessUnitCount: Int
    ) {
        if (pendingInputBuffer == null) {
            pendingInputBuffer = buffer
            pendingInputTimestampUs = timestampUs
            pendingInputAccessUnitCount = accessUnitCount
        }
    }

    private fun clearPendingInput() {
        pendingInputBuffer = null
        pendingInputTimestampUs = 0L
        pendingInputAccessUnitCount = 0
    }

    private fun clearWriteState() {
        pendingIdentityBuffer = null
        pendingIdentityTimestampUs = 0L
        pendingIdentityAccessUnitCount = 0
        clearPendingInput()
        pendingOutputWrite = false
        pendingOutputTimestampUs = 0L
        pendingOutputAccessUnitCount = 0
        pendingOutputStartFrame = 0L
        processedOutput?.clear()
        processedOutput?.limit(0)
    }

    private fun resolvePotentialDiscontinuity(presentationTimeUs: Long) {
        if (!pendingPotentialDiscontinuity) return
        pendingPotentialDiscontinuity = false
        val isProcessedStreamChange = pendingProcessedStreamChange
        pendingProcessedStreamChange = false

        val streamStart = streamStartTimeUs
        if (streamStart == null || sampleRateHz <= 0) return

        if (presentationTimeUs == C.TIME_UNSET) {
            resetForDiscontinuity()
            return
        }

        val expectedTimeUs =
            streamStart + acceptedInputFrames * 1_000_000L / sampleRateHz
        val discontinuityUs = presentationTimeUs - expectedTimeUs
        val roundedProcessedPeriodOffset =
            isProcessedStreamChange &&
                abs(discontinuityUs) <= MAX_COMPATIBLE_PERIOD_OFFSET_US
        if (abs(discontinuityUs) > DISCONTINUITY_TOLERANCE_US &&
            !roundedProcessedPeriodOffset
        ) {
            resetForDiscontinuity()
        }
    }

    private fun resetStreamState() {
        streamStartTimeUs = null
        acceptedInputFrames = 0L
        pendingPotentialDiscontinuity = false
        pendingProcessedStreamChange = false
        producedOutputFrames = 0L
        finalInputFrames = 0L
        writtenTailFrames = 0L
        finalStopRequested = false
        finalDrainStarted = false
        finalTailDrained = false
        downstreamStopSent = false
    }

    private fun timeForOutputFrame(frame: Long): Long =
        checkNotNull(streamStartTimeUs) + frame * 1_000_000L / sampleRateHz

    private companion object {
        const val CHANNEL_COUNT = 2
        const val MAX_OUTPUT_FRAMES = 8_192
        const val DISCONTINUITY_TOLERANCE_US = 2L
        // Media3 can place an adjacent processed period within 1 ms of the sample-count clock.
        const val MAX_COMPATIBLE_PERIOD_OFFSET_US = 1_000L
    }
}
