package com.flactify.audio.spatial

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Owns one Steam Audio stereo HRTF virtual-surround instance.
 *
 * A SpatialAudioOutput owns this engine. Its fixed-frame FIFO accepts arbitrary PCM chunks;
 * Media3's AudioOutput write contract governs downstream buffer ownership and retry accounting.
 */
internal class NativeSpatialEngine(
    sampleRateHz: Int,
    frameSize: Int,
    sofaHrtfData: ByteBuffer? = null,
    hrtfMixPercent: Int = SpatialHrtfMixPercent.DEFAULT_PERCENT
) : SpatialPcmEngine {

    enum class PcmEncoding(internal val bytesPerSample: Int) {
        PCM_16(Short.SIZE_BYTES),
        FLOAT_32(Float.SIZE_BYTES)
    }

    // Retain the direct asset buffer for the native HRTF lifetime.
    private val pinnedSofaHrtfData = sofaHrtfData
    private var nativeHandle: Long = 0L

    init {
        SpatialHrtfMixPercent.toUnitMix(hrtfMixPercent)
        val source = SpatialHrtfRateSupport.sourceFor(sampleRateHz)
            ?: throw UnsupportedOperationException(
                "Steam Audio HRTF data is unavailable at $sampleRateHz Hz"
            )
        if (source == SpatialHrtfSource.PINNED_CIPIC_SOFA) {
            requireNotNull(pinnedSofaHrtfData) {
                "Pinned CIPIC SOFA data is required at $sampleRateHz Hz"
            }
            require(pinnedSofaHrtfData.isDirect && pinnedSofaHrtfData.hasRemaining()) {
                "Pinned CIPIC SOFA data must be a non-empty direct buffer"
            }
        }
        nativeHandle = nativeCreate(
            sampleRateHz,
            frameSize,
            hrtfMixPercent,
            pinnedSofaHrtfData,
            if (source == SpatialHrtfSource.PINNED_CIPIC_SOFA) {
                pinnedSofaHrtfData?.remaining() ?: 0
            } else {
                0
            }
        )
        if (nativeHandle == 0L) {
            throw UnsupportedOperationException(
                "Steam Audio 4.8.1 could not create its HRTF at $sampleRateHz Hz"
            )
        }
    }

    /**
     * Accepts as many input frames as the bounded native adapter can retain and writes any
     * processed frames that fit in [output]. The returned Long packs consumed frames in the high
     * 32 bits and produced frames in the low 32 bits.
     *
     * Input and output must be distinct direct native-order buffers. Their positions advance only
     * by frames accepted by, or emitted from, the engine.
     */
    @Synchronized
    override fun process(
        input: ByteBuffer,
        inputFrames: Int,
        output: ByteBuffer,
        encoding: PcmEncoding
    ): Long {
        check(nativeHandle != 0L) { "Spatial engine is closed" }
        require(input.isDirect && output.isDirect) { "Spatial PCM buffers must be direct" }
        require(input !== output) { "Spatial PCM input and output buffers must be distinct" }
        require(input.order() == ByteOrder.nativeOrder() && output.order() == ByteOrder.nativeOrder()) {
            "Spatial PCM buffers must use native byte order"
        }
        require(inputFrames >= 0) { "inputFrames must be non-negative" }

        val bytesPerFrame = encoding.bytesPerSample * CHANNEL_COUNT
        val requiredInputBytes = inputFrames.toLong() * bytesPerFrame
        require(requiredInputBytes <= input.remaining()) { "Input buffer is shorter than inputFrames" }
        require(output.remaining() % bytesPerFrame == 0) {
            "Output buffer size must be stereo frame-aligned"
        }

        val outputFrames = output.remaining() / bytesPerFrame
        val counts = nativeProcess(
            nativeHandle,
            input,
            input.position(),
            inputFrames,
            output,
            output.position(),
            outputFrames,
            encoding.ordinal
        )
        val consumedFrames = (counts ushr 32).toInt()
        val producedFrames = counts.toInt()
        check(consumedFrames in 0..inputFrames) { "Native adapter returned invalid input count" }
        check(producedFrames in 0..outputFrames) { "Native adapter returned invalid output count" }
        input.position(input.position() + consumedFrames * bytesPerFrame)
        output.position(output.position() + producedFrames * bytesPerFrame)
        return counts
    }

    /**
     * Begins the final stream drain, including a padded partial frame and Steam Audio's
     * convolution tail. Returns emitted frames; call again until it returns zero.
     */
    @Synchronized
    override fun drainTail(output: ByteBuffer, encoding: PcmEncoding): Int {
        check(nativeHandle != 0L) { "Spatial engine is closed" }
        require(output.isDirect) { "Spatial PCM output must be direct" }
        require(output.order() == ByteOrder.nativeOrder()) {
            "Spatial PCM output must use native byte order"
        }
        val bytesPerFrame = encoding.bytesPerSample * CHANNEL_COUNT
        require(output.remaining() % bytesPerFrame == 0) {
            "Output buffer size must be stereo frame-aligned"
        }

        val outputFrames = output.remaining() / bytesPerFrame
        val producedFrames = nativeDrainTail(
            nativeHandle,
            output,
            output.position(),
            outputFrames,
            encoding.ordinal
        )
        check(producedFrames in 0..outputFrames) { "Native adapter returned invalid tail count" }
        output.position(output.position() + producedFrames * bytesPerFrame)
        return producedFrames
    }

    @Synchronized
    override fun reset() {
        check(nativeHandle != 0L) { "Spatial engine is closed" }
        nativeReset(nativeHandle)
    }

    @Synchronized
    override fun close() {
        if (nativeHandle != 0L) {
            nativeRelease(nativeHandle)
            nativeHandle = 0L
        }
    }

    private external fun nativeCreate(
        sampleRateHz: Int,
        frameSize: Int,
        hrtfMixPercent: Int,
        sofaHrtfData: ByteBuffer?,
        sofaHrtfDataSize: Int
    ): Long
    private external fun nativeProcess(
        handle: Long,
        input: ByteBuffer,
        inputPosition: Int,
        inputFrames: Int,
        output: ByteBuffer,
        outputPosition: Int,
        outputFrames: Int,
        encoding: Int
    ): Long

    private external fun nativeDrainTail(
        handle: Long,
        output: ByteBuffer,
        outputPosition: Int,
        outputFrames: Int,
        encoding: Int
    ): Int

    private external fun nativeReset(handle: Long)
    private external fun nativeRelease(handle: Long)

    private companion object {
        const val CHANNEL_COUNT = 2

        init {
            System.loadLibrary("flactify_spatial")
        }
    }
}
