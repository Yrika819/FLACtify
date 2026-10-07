package com.flactify.audio

import com.flactify.audio.spatial.SpatialAudioMode
import com.flactify.audio.spatial.SpatialPipelineInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackDiagnosticsBusTest {

    @Test
    fun media3AudioSinkCounterStartsAtZeroAndRetainsLatestUnderrunContext() {
        val counter = Media3AudioSinkEventCounter()

        assertEquals(0L, counter.snapshot().underrunEventCount)
        val first = counter.record(4_096, 12L, 5L)
        val second = counter.record(8_192, null, 38L)

        assertEquals(1L, first.underrunEventCount)
        assertEquals(4_096, first.lastBufferSizeBytes)
        assertEquals(12L, first.lastBufferDurationMs)
        assertEquals(5L, first.lastElapsedSinceLastFeedMs)
        assertEquals(2L, second.underrunEventCount)
        assertEquals(8_192, second.lastBufferSizeBytes)
        assertNull(second.lastBufferDurationMs)
        assertEquals(38L, second.lastElapsedSinceLastFeedMs)
    }

    @Test
    fun playbackAudioObserverOverridesMedia3AudioUnderrunEvent() {
        assertTrue(
            PlaybackAudioObserver::class.java.declaredMethods.any { method ->
                method.name == "onAudioUnderrun" && method.parameterTypes.size == 4
            }
        )
    }

    @Test
    fun lateReleaseOfPreviousAudioTrackDoesNotClearReplacementOutputFormat() {
        val published = mutableListOf<ActualOutputInfo>()
        val tracker = AudioTrackOutputLifecycleTracker(published::add)
        val previous = ActualOutputInfo(PcmEncoding.PCM_FLOAT, 96_000, 12)
        val replacement = ActualOutputInfo(PcmEncoding.PCM_FLOAT, 192_000, 12)

        tracker.onTrackInitialized(previous)
        tracker.onTrackInitialized(replacement)
        tracker.onTrackReleased()

        assertEquals(replacement, published.last())
        assertEquals(2, published.size)

        tracker.onTrackReleased()

        assertEquals(ActualOutputInfo.UNKNOWN, published.last())
    }

    @Test
    fun media3UnderrunEventsStaySeparateFromAudioTrackFormatAndSurviveStreamReset() {
        PlaybackDiagnosticsBus.resetAll()
        try {
            val output = ActualOutputInfo(
                pcmEncoding = PcmEncoding.PCM_16BIT,
                sampleRateHz = 192_000,
                channelMask = 12,
                isOffload = false,
                isTunneling = false
            )
            val sinkInfo = Media3AudioSinkInfo(
                underrunEventCount = 2L,
                lastBufferSizeBytes = 8_192,
                lastBufferDurationMs = 21L,
                lastElapsedSinceLastFeedMs = 44L
            )
            PlaybackDiagnosticsBus.publishOutput(output)
            PlaybackDiagnosticsBus.publishMedia3AudioSink(sinkInfo)

            assertEquals(output, PlaybackDiagnosticsBus.actualOutput.value)
            assertEquals(sinkInfo, PlaybackDiagnosticsBus.media3AudioSink.value)

            PlaybackDiagnosticsBus.reset()

            assertEquals(sinkInfo, PlaybackDiagnosticsBus.media3AudioSink.value)
            PlaybackDiagnosticsBus.resetAll()
            assertEquals(Media3AudioSinkInfo.UNKNOWN, PlaybackDiagnosticsBus.media3AudioSink.value)
        } finally {
            PlaybackDiagnosticsBus.resetAll()
        }
    }

    @Test
    fun resetClearsPriorStreamPcmFactsButRetainsRequestedModeAndEngine() {
        PlaybackDiagnosticsBus.resetAll()
        try {
            PlaybackDiagnosticsBus.publishSpatial(
                SpatialPipelineInfo(
                    requestedMode = SpatialAudioMode.STUDIO,
                    effectiveMode = SpatialAudioMode.STUDIO,
                    engine = "Steam Audio",
                    engineVersion = "4.8.1",
                    inputEncoding = "PCM16",
                    inputSampleRateHz = 48_000,
                    inputChannelCount = 2,
                    internalFormat = "Float32",
                    frameSize = 128,
                    hrtfProfile = "Studio"
                )
            )

            PlaybackDiagnosticsBus.reset()

            val spatial = PlaybackDiagnosticsBus.spatial.value
            assertEquals(SpatialAudioMode.STUDIO, spatial.requestedMode)
            assertEquals(SpatialAudioMode.STUDIO, spatial.effectiveMode)
            assertEquals("Steam Audio", spatial.engine)
            assertEquals("AWAITING_PCM_FORMAT", spatial.bypassReason)
            assertNull(spatial.inputEncoding)
            assertNull(spatial.inputSampleRateHz)
            assertNull(spatial.inputChannelCount)
        } finally {
            PlaybackDiagnosticsBus.resetAll()
        }
    }
}
