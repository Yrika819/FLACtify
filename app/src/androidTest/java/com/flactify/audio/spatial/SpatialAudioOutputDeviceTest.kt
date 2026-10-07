package com.flactify.audio.spatial

import android.os.Handler
import android.os.HandlerThread
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@OptIn(UnstableApi::class)
class SpatialAudioOutputDeviceTest {

    @Test
    fun identityProviderWritesSilentPcmToPlatformAudioTrack() {
        val playbackThread = HandlerThread("SpatialAudioOutputTest").apply { start() }
        try {
            val task = FutureTask<Unit> {
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                val delegate = AudioTrackAudioOutputProvider.Builder(context).build()
                val provider = SpatialAudioOutputProvider(delegate, requireDecodedPcm = false)
                val providerListener = object : AudioOutputProvider.Listener {
                    override fun onFormatSupportChanged() = Unit
                }
                var output: AudioOutput? = null

                try {
                    provider.addListener(providerListener)
                    val format = Format.Builder()
                        .setSampleMimeType(MimeTypes.AUDIO_RAW)
                        .setSampleRate(44_100)
                        .setChannelCount(2)
                        .setPcmEncoding(C.ENCODING_PCM_16BIT)
                        .build()
                    val formatConfig = AudioOutputProvider.FormatConfig.Builder(format)
                        .setEnableOffload(false)
                        .setEnableTunneling(false)
                        .build()
                    val support = provider.getFormatSupport(formatConfig)
                    assertTrue(
                        "PCM16 stereo must be supported by the platform output, support=${support.supportLevel}",
                        support.supportLevel != AudioOutputProvider.FORMAT_UNSUPPORTED
                    )

                    val created = provider.getAudioOutput(provider.getOutputConfig(formatConfig))
                    output = created
                    created.play()

                    // Zero samples make this a silent hardware-path check while the screen may stay locked.
                    val input = ByteBuffer.allocateDirect(128 * 2 * Short.SIZE_BYTES)
                        .order(ByteOrder.nativeOrder())
                        .apply {
                            repeat(capacity() / Short.SIZE_BYTES) { putShort(0) }
                            flip()
                        }
                    assertTrue(input.isDirect)

                    val timestampUs = 0L
                    var fullyConsumed = false
                    var attempts = 0
                    while (!fullyConsumed && attempts < 20) {
                        fullyConsumed = created.write(input, 1, timestampUs)
                        if (!fullyConsumed) Thread.sleep(5)
                        attempts++
                    }

                    assertTrue("AudioTrack did not consume the buffer within the retry bound", fullyConsumed)
                    assertEquals(input.limit(), input.position())
                } finally {
                    output?.pause()
                    output?.stop()
                    output?.release()
                    provider.removeListener(providerListener)
                    provider.release()
                }
                Unit
            }
            Handler(playbackThread.looper).post(task)
            task.get(30, TimeUnit.SECONDS)
        } finally {
            playbackThread.quitSafely()
            playbackThread.join(1_000)
        }
    }
}
