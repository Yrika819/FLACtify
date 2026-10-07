package com.flactify.audio.spatial

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SpatialAudioProcessorFallbackTest {

    @Test
    fun nativeInitializationFailurePassesPcmThroughAndCompletesFinalEos() {
        val state = SpatialPipelineState()
        val eosDrainState = SpatialEosDrainState()
        val processor = SpatialAudioProcessor(
            eosDrainState,
            state,
            engineFactory = { _, _ -> throw IllegalStateException("engine unavailable") }
        )
        val format = AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT)

        assertEquals(format, processor.configure(format))
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
        assertEquals("NATIVE_ENGINE_INIT_FAILED", state.info.bypassReason)
        assertEquals(SpatialAudioMode.OFF, state.info.effectiveMode)

        val input = ByteBuffer.allocateDirect(8)
            .order(ByteOrder.nativeOrder())
            .apply {
                putShort(1_000)
                putShort((-2_000).toShort())
                putShort(3_000)
                putShort((-4_000).toShort())
                flip()
            }
        processor.queueInput(input)

        assertFalse("active bypass processor must consume input", input.hasRemaining())
        val output = processor.getOutput()
        assertTrue(output.isDirect)
        assertEquals(1_000.toShort(), output.short)
        assertEquals((-2_000).toShort(), output.short)
        assertEquals(3_000.toShort(), output.short)
        assertEquals((-4_000).toShort(), output.short)
        assertFalse(output.hasRemaining())

        eosDrainState.beginFinalEosDrain()
        processor.queueEndOfStream()
        assertTrue("identity bypass has no native tail to drain", processor.isEnded())
        processor.reset()
    }
}
