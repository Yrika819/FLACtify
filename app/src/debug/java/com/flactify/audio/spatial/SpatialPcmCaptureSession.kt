package com.flactify.audio.spatial

import java.nio.ByteBuffer

/** Fixed-capacity, test-only sink for PCM accepted by Media3's platform AudioOutput. */
internal class SpatialPcmCaptureSession(
    private val byteCapacity: Int = DEFAULT_BYTE_CAPACITY,
    private val eventCapacity: Int = DEFAULT_EVENT_CAPACITY
) : SpatialPcmOutputObserverFactory {

    @Volatile
    private var activeWindow: CaptureWindow? = null

    fun newWindow(): CaptureWindow = CaptureWindow(byteCapacity, eventCapacity)

    fun activate(window: CaptureWindow) {
        activeWindow = window
    }

    override fun create(
        outputId: Long,
        sampleRateHz: Int,
        encoding: NativeSpatialEngine.PcmEncoding
    ): SpatialPcmOutputObserver =
        SpatialPcmOutputObserver { buffer, byteOffset, frameIndex, frameCount, timeUs, tailFrames ->
            activeWindow?.recordAccepted(
                buffer = buffer,
                byteOffset = byteOffset,
                outputId = outputId,
                sampleRateHz = sampleRateHz,
                encoding = encoding,
                frameIndex = frameIndex,
                frameCount = frameCount,
                presentationTimeUs = timeUs,
                finalTailFrameCount = tailFrames
            )
        }

    class CaptureWindow internal constructor(
        byteCapacity: Int,
        eventCapacity: Int
    ) {
        private val acceptedBytes = ByteArray(byteCapacity)
        private val capturedByteOffsets = IntArray(eventCapacity)
        private val capturedByteCounts = IntArray(eventCapacity)
        private val outputIds = LongArray(eventCapacity)
        private val sampleRatesHz = IntArray(eventCapacity)
        private val encodings = Array(eventCapacity) { NativeSpatialEngine.PcmEncoding.FLOAT_32 }
        private val frameIndexes = LongArray(eventCapacity)
        private val frameCounts = IntArray(eventCapacity)
        private val presentationTimesUs = LongArray(eventCapacity)
        private val finalTailFrameCounts = IntArray(eventCapacity)

        private var nextByteOffset = 0

        @Volatile
        private var publishedByteCount = 0

        @Volatile
        private var publishedEventCount = 0

        @Volatile
        var overflowed: Boolean = false
            private set

        val eventCount: Int
            get() = publishedEventCount

        val acceptedByteCount: Int
            get() = publishedByteCount

        internal fun recordAccepted(
            buffer: ByteBuffer,
            byteOffset: Int,
            outputId: Long,
            sampleRateHz: Int,
            encoding: NativeSpatialEngine.PcmEncoding,
            frameIndex: Long,
            frameCount: Int,
            presentationTimeUs: Long,
            finalTailFrameCount: Int
        ) {
            val bytesPerFrame = CHANNELS * encoding.bytesPerSample
            if (
                frameCount <= 0 ||
                finalTailFrameCount !in 0..frameCount ||
                byteOffset < 0 ||
                byteOffset > buffer.limit() ||
                frameCount.toLong() * bytesPerFrame > buffer.limit() - byteOffset ||
                frameCount.toLong() * bytesPerFrame > acceptedBytes.size - nextByteOffset ||
                publishedEventCount >= outputIds.size
            ) {
                overflowed = true
                return
            }

            val eventIndex = publishedEventCount
            val byteCount = frameCount * bytesPerFrame
            capturedByteOffsets[eventIndex] = nextByteOffset
            capturedByteCounts[eventIndex] = byteCount
            outputIds[eventIndex] = outputId
            sampleRatesHz[eventIndex] = sampleRateHz
            encodings[eventIndex] = encoding
            frameIndexes[eventIndex] = frameIndex
            frameCounts[eventIndex] = frameCount
            presentationTimesUs[eventIndex] = presentationTimeUs
            finalTailFrameCounts[eventIndex] = finalTailFrameCount

            for (offset in 0 until byteCount) {
                acceptedBytes[nextByteOffset + offset] = buffer.get(byteOffset + offset)
            }
            nextByteOffset += byteCount
            publishedByteCount = nextByteOffset
            publishedEventCount = eventIndex + 1
        }

        fun copyAcceptedBytes(): ByteArray = acceptedBytes.copyOf(publishedByteCount)

        fun totalAcceptedFrames(): Long {
            var total = 0L
            for (index in 0 until publishedEventCount) total += frameCounts[index]
            return total
        }

        fun totalFinalTailFrames(): Long {
            var total = 0L
            for (index in 0 until publishedEventCount) total += finalTailFrameCounts[index]
            return total
        }

        fun outputIdAt(index: Int): Long = outputIds[index]
        fun sampleRateHzAt(index: Int): Int = sampleRatesHz[index]
        fun encodingAt(index: Int): NativeSpatialEngine.PcmEncoding = encodings[index]
        fun frameIndexAt(index: Int): Long = frameIndexes[index]
        fun frameCountAt(index: Int): Int = frameCounts[index]
        fun presentationTimeUsAt(index: Int): Long = presentationTimesUs[index]
        fun finalTailFrameCountAt(index: Int): Int = finalTailFrameCounts[index]
        internal fun capturedByteOffsetAt(index: Int): Int = capturedByteOffsets[index]
        internal fun capturedByteCountAt(index: Int): Int = capturedByteCounts[index]
    }

    private companion object {
        const val CHANNELS = 2
        const val DEFAULT_BYTE_CAPACITY = 16 * 1024 * 1024
        const val DEFAULT_EVENT_CAPACITY = 8_192
    }
}

/** Installed by one instrumentation test before its debug-only playback service is started. */
internal object SpatialPcmCaptureRegistry {
    @Volatile
    private var installedSession: SpatialPcmCaptureSession? = null

    fun install(session: SpatialPcmCaptureSession) {
        check(installedSession == null) { "A PCM capture session is already installed" }
        installedSession = session
    }

    fun current(): SpatialPcmCaptureSession? = installedSession

    fun clear(session: SpatialPcmCaptureSession) {
        if (installedSession === session) installedSession = null
    }
}
