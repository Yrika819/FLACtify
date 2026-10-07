package com.flactify.audio.spatial

import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SpatialAudioOutputTest {

    @Test
    fun `identity output retries a partially written buffer without changing bytes or timestamp`() {
        val downstream = mockk<AudioOutput>()
        val capturedBytes = ByteArrayOutputStream()
        val calls = mutableListOf<Triple<ByteBuffer, Int, Long>>()

        every { downstream.write(any(), any(), any()) } answers {
            val buffer = firstArg<ByteBuffer>()
            calls += Triple(buffer, secondArg(), thirdArg())
            val bytesToConsume = if (calls.size == 1) 4 else buffer.remaining()
            repeat(bytesToConsume) {
                capturedBytes.write(buffer.get().toInt())
            }
            calls.size > 1
        }

        val inputBytes = byteArrayOf(0, 1, -1, 127, 22, -32, 42, 0, 1, 2, 3, 4)
        val input = ByteBuffer.allocateDirect(inputBytes.size)
            .order(ByteOrder.nativeOrder())
            .apply {
                put(inputBytes)
                flip()
            }
        val originalPosition = input.position()
        val output = SpatialAudioOutput(downstream)

        assertFalse(output.write(input, 1, 123_456L))
        assertEquals(originalPosition + 4, input.position())

        assertTrue(output.write(input, 1, 123_456L))
        assertEquals(input.limit(), input.position())
        assertArrayEquals(inputBytes, capturedBytes.toByteArray())
        assertEquals(2, calls.size)
        assertSame(input, calls[0].first)
        assertSame(input, calls[1].first)
        assertEquals(1, calls[0].second)
        assertEquals(1, calls[1].second)
        assertEquals(123_456L, calls[0].third)
        assertEquals(123_456L, calls[1].third)
    }

    @Test
    fun `lifecycle and route operations pass through to the real output`() {
        val downstream = mockk<AudioOutput>(relaxed = true)
        val output = SpatialAudioOutput(downstream)

        output.play()
        output.pause()
        output.flush()
        output.stop()
        output.setVolume(0.5f)
        output.release()

        verify(exactly = 1) { downstream.play() }
        verify(exactly = 1) { downstream.pause() }
        verify(exactly = 1) { downstream.flush() }
        verify(exactly = 1) { downstream.stop() }
        verify(exactly = 1) { downstream.setVolume(0.5f) }
        verify(exactly = 1) { downstream.release() }
    }

    @Test
    fun `provider preserves support and output configuration and wraps its created output`() {
        val delegateProvider = mockk<AudioOutputProvider>()
        val formatConfig = mockk<AudioOutputProvider.FormatConfig>()
        val outputConfig = mockk<AudioOutputProvider.OutputConfig>()
        val expectedSupport = AudioOutputProvider.FormatSupport.Builder()
            .setFormatSupportLevel(AudioOutputProvider.FORMAT_SUPPORTED_DIRECTLY)
            .build()
        val realOutput = mockk<AudioOutput>(relaxed = true)
        every { delegateProvider.getFormatSupport(formatConfig) } returns expectedSupport
        every { delegateProvider.getOutputConfig(formatConfig) } returns outputConfig
        every { delegateProvider.getAudioOutput(outputConfig) } returns realOutput

        val provider = SpatialAudioOutputProvider(delegateProvider, requireDecodedPcm = false)

        assertSame(expectedSupport, provider.getFormatSupport(formatConfig))
        assertSame(outputConfig, provider.getOutputConfig(formatConfig))
        val wrappedOutput = provider.getAudioOutput(outputConfig)
        assertTrue(wrappedOutput is SpatialAudioOutput)

        wrappedOutput.release()
        verify(exactly = 1) { realOutput.release() }
    }
}
