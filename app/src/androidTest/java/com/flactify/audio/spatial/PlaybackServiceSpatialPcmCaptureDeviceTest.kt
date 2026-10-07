package com.flactify.audio.spatial

import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Base64
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.flactify.audio.AudioRouteMonitor
import com.flactify.audio.AudioRouteType
import com.flactify.audio.PlaybackDiagnosticsBus
import com.flactify.audio.RouteConfidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Captures service-owned post-HRTF PCM accepted by the AudioTrack-facing output. */
@RunWith(AndroidJUnit4::class)
class PlaybackServiceSpatialPcmCaptureDeviceTest {

    @Test
    fun compatiblePlaylistItemsKeepContinuousFramesAndDrainTailOnlyAtFinalEos() =
        withStudioCapture { instrumentation, context, controller, session, window, fixture ->
            val secondItemTransitioned = AtomicBoolean()
            val transitionListener = object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    if (mediaItem?.mediaId == SECOND_ITEM_ID) {
                        secondItemTransitioned.set(true)
                    }
                }
            }
            controller.addListener(transitionListener)
            onMainThread(instrumentation) {
                controller.setMediaItems(
                    listOf(
                        mediaItem(FIRST_ITEM_ID, fixture),
                        mediaItem(SECOND_ITEM_ID, fixture)
                    )
                )
                controller.prepare()
                controller.play()
            }

            await("Studio Float32 service output") {
                isStudioActive(48_000) && onMainThread(instrumentation) {
                    controller.playbackState == Player.STATE_READY && controller.isPlaying
                }
            }
            assertLibertyRouteIfRequested(context)

            await(
                "second compatible item emits a capture window while actively playing",
                timeoutDetails = {
                    onMainThread(instrumentation) {
                        "state=${controller.playbackState}, isPlaying=${controller.isPlaying}, " +
                            "playWhenReady=${controller.playWhenReady}, " +
                            "suppression=${controller.playbackSuppressionReason}, " +
                            "index=${controller.currentMediaItemIndex}, " +
                            "itemCount=${controller.mediaItemCount}, positionMs=${controller.currentPosition}, " +
                            "durationMs=${controller.duration}, seekable=${controller.isCurrentMediaItemSeekable}, " +
                            "acceptedFrames=${window.totalAcceptedFrames()}, " +
                            "tailFrames=${window.totalFinalTailFrames()}, tails=${describeTailEvents(window)}"
                    }
                }
            ) {
                secondItemTransitioned.get() &&
                    onMainThread(instrumentation) {
                        controller.playbackState == Player.STATE_READY &&
                            controller.currentMediaItemIndex == 1 &&
                            controller.isPlaying
                    } &&
                    window.totalAcceptedFrames() >= FIRST_ITEM_FRAME_COUNT + 128L
            }
            onMainThread(instrumentation) { controller.pause() }
            await(
                "explicit pause is acknowledged before resuming toward final EOS",
                timeoutDetails = {
                    onMainThread(instrumentation) {
                        "state=${controller.playbackState}, isPlaying=${controller.isPlaying}, " +
                            "playWhenReady=${controller.playWhenReady}, index=${controller.currentMediaItemIndex}, " +
                            "positionMs=${controller.currentPosition}"
                    }
                }
            ) {
                onMainThread(instrumentation) {
                    controller.playbackState == Player.STATE_READY &&
                        controller.currentMediaItemIndex == 1 &&
                        !controller.playWhenReady &&
                        !controller.isPlaying
                }
            }
            onMainThread(instrumentation) { controller.removeListener(transitionListener) }
            android.util.Log.i(
                CAPTURE_TAG,
                "before-final-eos itemIndex=${onMainThread(instrumentation) { controller.currentMediaItemIndex }} " +
                    "positionMs=${onMainThread(instrumentation) { controller.currentPosition }} " +
                    "acceptedFrames=${window.totalAcceptedFrames()} tails=${describeTailEvents(window)}"
            )
            onMainThread(instrumentation) { controller.play() }
            await(
                "final EOS after the second compatible item",
                timeoutDetails = {
                    onMainThread(instrumentation) {
                        "state=${controller.playbackState}, isPlaying=${controller.isPlaying}, " +
                            "playWhenReady=${controller.playWhenReady}, " +
                            "suppression=${controller.playbackSuppressionReason}, " +
                            "index=${controller.currentMediaItemIndex}, positionMs=${controller.currentPosition}, " +
                            "durationMs=${controller.duration}, error=${controller.playerError}, " +
                            "acceptedFrames=${window.totalAcceptedFrames()}, " +
                            "tailFrames=${window.totalFinalTailFrames()}, tails=${describeTailEvents(window)}"
                    }
                }
            ) {
                onMainThread(instrumentation) {
                    controller.playbackState == Player.STATE_ENDED
                }
            }

            onMainThread(instrumentation) { controller.removeListener(transitionListener) }
            assertStudioOutputDiagnostics(48_000)
            assertContinuousSpatialFrames(window, expectedRateHz = 48_000)
            assertTrue(
                "tone fixture produced only silence or near-silence in PCM accepted by AudioOutput",
                peakFloat(
                    window.copyAcceptedBytes(),
                    frameLimit = FIRST_ITEM_FRAME_COUNT.toInt()
                ) > 1.0e-4f
            )
            assertTerminalTail(window)
            assertFalse("bounded capture overflowed", window.overflowed)
            logCapture("gapless-eos", context, window)
        }

    @Test
    fun seekIntoSeekableSilenceStartsFreshHrtfFrameClock() =
        withStudioCapture(
            fixtureAsset = SEEKABLE_SILENCE_FIXTURE_ASSET,
            fixtureFileName = SEEKABLE_SILENCE_FIXTURE_FILE_NAME
        ) { instrumentation, context, controller, session, window, fixture ->
            val playerStateEvents = Collections.synchronizedList(mutableListOf<String>())
            fun recordPlayerEvent(event: String) {
                playerStateEvents.add(
                    "elapsedMs=${SystemClock.elapsedRealtime()}, $event, " +
                        "state=${controller.playbackState}, playWhenReady=${controller.playWhenReady}, " +
                        "suppression=${controller.playbackSuppressionReason}, " +
                        "index=${controller.currentMediaItemIndex}, positionMs=${controller.currentPosition}"
                )
            }
            fun playerStateTrace(): String = synchronized(playerStateEvents) {
                playerStateEvents.joinToString(separator = " | ")
            }
            val playerStateListener = object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    recordPlayerEvent("playbackStateChanged=$playbackState")
                }

                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    recordPlayerEvent("playWhenReadyChanged=$playWhenReady reason=$reason")
                }

                override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
                    recordPlayerEvent("suppressionChanged=$playbackSuppressionReason")
                }
            }
            onMainThread(instrumentation) {
                controller.addListener(playerStateListener)
                controller.setMediaItem(mediaItem(FIRST_ITEM_ID, fixture))
                controller.prepare()
                controller.play()
            }

            await("seekable 192 kHz pre-seek PCM") {
                onMainThread(instrumentation) {
                    controller.playbackState == Player.STATE_READY &&
                        controller.isCurrentMediaItemSeekable &&
                        controller.currentPosition >= 250L &&
                        controller.currentPosition < 1_500L &&
                        controller.isPlaying
                } && window.totalAcceptedFrames() >= 128L
            }
            assertTrue(
                "pre-seek silence unexpectedly contains a signal",
                peakFloat(window.copyAcceptedBytes(), frameLimit = 128) < 1.0e-4f
            )

            val preSeekEventCount = window.eventCount
            val preSeekOutputId = window.outputIdAt(preSeekEventCount - 1)
            val preSeekAcceptedFrames = window.totalAcceptedFrames()
            val seekBufferingObserved = AtomicBoolean()
            val seekReadyObserved = AtomicBoolean()
            val seekListener = object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_BUFFERING -> seekBufferingObserved.set(true)
                        Player.STATE_READY -> if (seekBufferingObserved.get()) {
                            seekReadyObserved.set(true)
                        }
                    }
                }
            }
            onMainThread(instrumentation) {
                controller.addListener(seekListener)
                controller.pause()
            }
            await(
                "pause is acknowledged before the seek request",
                timeoutDetails = {
                    onMainThread(instrumentation) {
                        "state=${controller.playbackState}, isPlaying=${controller.isPlaying}, " +
                            "playWhenReady=${controller.playWhenReady}, " +
                            "trace=${playerStateTrace()}"
                    }
                }
            ) {
                onMainThread(instrumentation) {
                    !controller.playWhenReady && !controller.isPlaying
                }
            }
            onMainThread(instrumentation) { controller.seekTo(SEEK_TARGET_MS) }
            await(
                "Media3 completed buffering at the seek destination",
                timeoutDetails = {
                    onMainThread(instrumentation) {
                        "seekBufferingObserved=${seekBufferingObserved.get()}, " +
                            "seekReadyObserved=${seekReadyObserved.get()}, state=${controller.playbackState}, " +
                            "isPlaying=${controller.isPlaying}, playWhenReady=${controller.playWhenReady}, " +
                            "suppression=${controller.playbackSuppressionReason}, " +
                            "index=${controller.currentMediaItemIndex}, positionMs=${controller.currentPosition}, " +
                            "durationMs=${controller.duration}, seekable=${controller.isCurrentMediaItemSeekable}, " +
                            "trace=${playerStateTrace()}"
                    }
                }
            ) {
                seekReadyObserved.get() &&
                    onMainThread(instrumentation) {
                        controller.isCurrentMediaItemSeekable &&
                            !controller.playWhenReady &&
                            !controller.isPlaying &&
                            controller.currentPosition in (SEEK_TARGET_MS - 50L)..(SEEK_TARGET_MS + 50L)
                    }
            }
            onMainThread(instrumentation) { controller.removeListener(seekListener) }
            onMainThread(instrumentation) { controller.play() }
            assertLibertyRouteIfRequested(context)
            await(
                "playback resumes after the seek",
                timeoutDetails = {
                    "playerStateTrace=${playerStateTrace()}, route=${AudioRouteMonitor(context).currentRoute()}"
                }
            ) {
                onMainThread(instrumentation) {
                    controller.playbackState == Player.STATE_READY &&
                        controller.playWhenReady &&
                        controller.isPlaying
                }
            }

            await("post-seek PCM after AudioOutput recreation") {
                window.eventCount > preSeekEventCount &&
                    window.outputIdAt(window.eventCount - 1) != preSeekOutputId &&
                    window.totalAcceptedFrames() - preSeekAcceptedFrames >= 128L
            }
            await(
                "final EOS after seek into silence",
                timeoutDetails = {
                    onMainThread(instrumentation) {
                        "state=${controller.playbackState}, isPlaying=${controller.isPlaying}, " +
                            "playWhenReady=${controller.playWhenReady}, " +
                            "suppression=${controller.playbackSuppressionReason}, " +
                            "positionMs=${controller.currentPosition}, durationMs=${controller.duration}, " +
                            "seekable=${controller.isCurrentMediaItemSeekable}, " +
                            "events=${window.eventCount}, frames=${window.totalAcceptedFrames()}, " +
                            "tailFrames=${window.totalFinalTailFrames()}, " +
                            "playerStateTrace=${playerStateTrace()}, " +
                            "route=${AudioRouteMonitor(context).currentRoute()}"
                    }
                }
            ) {
                onMainThread(instrumentation) {
                    controller.playbackState == Player.STATE_ENDED
                }
            }
            onMainThread(instrumentation) { controller.removeListener(playerStateListener) }

            assertStudioOutputDiagnostics(SEEKABLE_SILENCE_RATE_HZ)
            assertContinuousSpatialFrames(
                window,
                expectedRateHz = SEEKABLE_SILENCE_RATE_HZ,
                allowOutputRecreation = true
            )
            val firstPostSeekOutputEventIndex =
                (preSeekEventCount until window.eventCount)
                    .firstOrNull { window.outputIdAt(it) != preSeekOutputId } ?: -1
            assertTrue(
                "Media3 did not recreate the output after seek",
                firstPostSeekOutputEventIndex >= preSeekEventCount
            )
            assertEquals(
                "seek must reset the frame clock at the recreated output boundary " +
                    "(outputId=${window.outputIdAt(firstPostSeekOutputEventIndex)}, " +
                    "events=${window.eventCount})",
                0L,
                window.frameIndexAt(firstPostSeekOutputEventIndex)
            )
            val firstPostSeekRendererTimeUs =
                window.presentationTimeUsAt(firstPostSeekOutputEventIndex)
            val firstPostSeekMediaTimeUs =
                firstPostSeekRendererTimeUs - MEDIA3_INITIAL_RENDERER_POSITION_OFFSET_US
            assertTrue(
                "first post-seek PCM timestamp was not near the 10 s seek target " +
                    "(rendererPtsUs=$firstPostSeekRendererTimeUs, mediaTimeUs=$firstPostSeekMediaTimeUs)",
                firstPostSeekMediaTimeUs in 9_950_000L..10_050_000L
            )
            val capturedBytes = window.copyAcceptedBytes()
            val firstPostSeekByteOffset =
                window.capturedByteOffsetAt(firstPostSeekOutputEventIndex)
            val firstPostSeekByteCount =
                window.capturedByteCountAt(firstPostSeekOutputEventIndex)
            assertTrue(
                "silent post-seek output contains an unexpected signal",
                peakFloat(
                    capturedBytes.copyOfRange(
                        firstPostSeekByteOffset,
                        firstPostSeekByteOffset + firstPostSeekByteCount
                    ),
                    frameLimit = minOf(128, window.frameCountAt(firstPostSeekOutputEventIndex))
                ) < 1.0e-4f
            )
            assertTerminalTail(window)
            assertFalse("bounded capture overflowed after seek", window.overflowed)
            logCapture("seek-eos", context, window)
        }

    private fun withStudioCapture(
        fixtureAsset: String = FIXTURE_ASSET,
        fixtureFileName: String = FIXTURE_FILE_NAME,
        body: (
            Instrumentation,
            Context,
            MediaController,
            SpatialPcmCaptureSession,
            SpatialPcmCaptureSession.CaptureWindow,
            File
        ) -> Unit
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val appContext = instrumentation.targetContext
        val routeArguments = InstrumentationRegistry.getArguments()
        val preferences = appContext.getSharedPreferences(SETTINGS_FILE, Context.MODE_PRIVATE)
        val hadPreviousMode = preferences.contains(SpatialAudioMode.PREFERENCE_KEY)
        val previousMode = preferences.getString(SpatialAudioMode.PREFERENCE_KEY, null)
        val captureSession = SpatialPcmCaptureSession()
        val captureWindow = captureSession.newWindow()
        var fixture: File? = null
        var controllerFuture: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
        var controller: MediaController? = null

        appContext.stopService(Intent(appContext, SpatialCapturePlaybackService::class.java))
        PlaybackDiagnosticsBus.resetAll()
        assertTrue(
            "could not persist Studio for capture service startup",
            preferences.edit()
                .putString(SpatialAudioMode.PREFERENCE_KEY, SpatialAudioMode.STUDIO.wireValue)
                .commit()
        )
        captureSession.activate(captureWindow)
        SpatialPcmCaptureRegistry.install(captureSession)

        try {
            fixture = decodeFixtureToAppCache(
                instrumentation.context,
                appContext,
                fixtureAsset,
                fixtureFileName
            )
            assertLibertyRouteIfRequested(appContext)
            val token = SessionToken(
                appContext,
                ComponentName(appContext, SpatialCapturePlaybackService::class.java)
            )
            controllerFuture = onMainThread(instrumentation) {
                MediaController.Builder(appContext, token).buildAsync()
            }
            controller = controllerFuture.get(CONTROLLER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            body(
                instrumentation,
                appContext,
                checkNotNull(controller),
                captureSession,
                captureWindow,
                checkNotNull(fixture)
            )
        } finally {
            controller?.let { connectedController ->
                onMainThread(instrumentation) {
                    connectedController.pause()
                    connectedController.clearMediaItems()
                    connectedController.stop()
                }
            }
            controllerFuture?.let { future ->
                onMainThread(instrumentation) { MediaController.releaseFuture(future) }
            }
            appContext.stopService(Intent(appContext, SpatialCapturePlaybackService::class.java))
            SpatialPcmCaptureRegistry.clear(captureSession)
            if (hadPreviousMode) {
                preferences.edit()
                    .putString(SpatialAudioMode.PREFERENCE_KEY, previousMode)
                    .commit()
            } else {
                preferences.edit().remove(SpatialAudioMode.PREFERENCE_KEY).commit()
            }
            fixture?.delete()
            PlaybackDiagnosticsBus.resetAll()
        }
    }

    private fun assertStudioOutputDiagnostics(sampleRateHz: Int) {
        val spatial = PlaybackDiagnosticsBus.spatial.value
        assertEquals(SpatialAudioMode.STUDIO, spatial.requestedMode)
        assertEquals(SpatialAudioMode.STUDIO, spatial.effectiveMode)
        assertEquals(sampleRateHz, spatial.inputSampleRateHz)
        assertEquals("Float32", spatial.inputEncoding)
        assertEquals(2, spatial.inputChannelCount)
        assertEquals("Steam Audio", spatial.engine)
        assertEquals("4.8.1", spatial.engineVersion)
        assertEquals(SpatialPcmEngine.FRAME_SIZE, spatial.frameSize)
        assertEquals(null, spatial.bypassReason)
        assertEquals(null, spatial.error)

        val output = PlaybackDiagnosticsBus.actualOutput.value
        assertTrue("AudioTrack output was not fully observed: $output", output.isFullyObserved)
        assertEquals(sampleRateHz, output.sampleRateHz)
        assertEquals(false, output.isOffload)
        assertEquals(false, output.isTunneling)
    }

    private fun assertContinuousSpatialFrames(
        window: SpatialPcmCaptureSession.CaptureWindow,
        expectedRateHz: Int,
        allowOutputRecreation: Boolean = false
    ) {
        assertFalse("bounded capture overflowed", window.overflowed)
        assertTrue("no post-HRTF PCM was captured", window.eventCount > 0)
        assertTrue(
            "captured bytes do not equal the observer's accepted-frame accounting",
            window.acceptedByteCount.toLong() ==
                window.totalAcceptedFrames() * CHANNELS * Float.SIZE_BYTES
        )
        for (index in 0 until window.eventCount) {
            assertEquals("PCM sample rate changed in the capture", expectedRateHz, window.sampleRateHzAt(index))
            assertEquals(
                "Spatial output encoding changed in the capture",
                NativeSpatialEngine.PcmEncoding.FLOAT_32,
                window.encodingAt(index)
            )
            if (index == 0) continue

            assertTrue(
                "accepted output timestamps moved backwards at event $index",
                window.presentationTimeUsAt(index) >= window.presentationTimeUsAt(index - 1)
            )
            val previousOutputId = window.outputIdAt(index - 1)
            val currentOutputId = window.outputIdAt(index)
            if (currentOutputId != previousOutputId) {
                assertTrue(
                    "AudioOutput was recreated in a path that requires continuity at event $index",
                    allowOutputRecreation
                )
                assertEquals(
                    "a recreated AudioOutput must begin a fresh spatial frame clock at event $index",
                    0L,
                    window.frameIndexAt(index)
                )
                assertTrue(
                    "presentation timestamp moved backwards after AudioOutput recreation at event $index",
                    window.presentationTimeUsAt(index) >= window.presentationTimeUsAt(index - 1)
                )
            } else {
                assertEquals(
                    "accepted output frames were duplicated or dropped at event $index",
                    window.frameIndexAt(index - 1) + window.frameCountAt(index - 1),
                    window.frameIndexAt(index)
                )
            }
        }
    }

    private fun assertTerminalTail(window: SpatialPcmCaptureSession.CaptureWindow) {
        var tailStarted = false
        var tailFrames = 0L
        for (index in 0 until window.eventCount) {
            val acceptedFrames = window.frameCountAt(index)
            val finalTailFrames = window.finalTailFrameCountAt(index)
            assertTrue("tail marker exceeds the accepted event frame count", finalTailFrames <= acceptedFrames)
            if (finalTailFrames > 0) {
                tailStarted = true
                tailFrames += finalTailFrames
            } else if (tailStarted) {
                assertEquals("non-tail PCM was accepted after the final tail began", 0, acceptedFrames)
            }
        }
        assertTrue("final EOS did not expose an HRTF tail", tailFrames > 0)
    }

    private fun assertLibertyRouteIfRequested(context: Context) {
        val arguments = InstrumentationRegistry.getArguments()
        if (!arguments.getBoolean(REQUIRE_EXPECTED_AUDIO_ROUTE_ARGUMENT)) return
        assertEquals(
            AudioRouteType.BLUETOOTH.name,
            arguments.getString(EXPECTED_AUDIO_ROUTE_TYPE_ARGUMENT)
        )
        val expectedName = checkNotNull(arguments.getString(EXPECTED_AUDIO_ROUTE_NAME_ARGUMENT)) {
            "Expected audio route name was not supplied"
        }
        assertEquals("Liberty 4 Pro", expectedName)
        val route = AudioRouteMonitor(context).currentRoute()
        assertTrue("AudioManager prediction is not actual AudioTrack routing: $route", route?.confidence != RouteConfidence.ACTIVE_ROUTE)
        assertEquals("Playback route is not Bluetooth: $route", AudioRouteType.BLUETOOTH, route?.type)
        assertTrue(
            "Playback route name does not identify Liberty 4 Pro: $route",
            route?.deviceName?.contains(expectedName, ignoreCase = true) == true
        )
    }

    private fun logCapture(
        scenario: String,
        context: Context,
        window: SpatialPcmCaptureSession.CaptureWindow
    ) {
        val route = AudioRouteMonitor(context).currentRoute()
        android.util.Log.i(
            CAPTURE_TAG,
            "scenario=$scenario events=${window.eventCount} frames=${window.totalAcceptedFrames()} " +
                "tailFrames=${window.totalFinalTailFrames()} route=$route"
        )
    }

    private fun isStudioActive(sampleRateHz: Int): Boolean {
        val info = PlaybackDiagnosticsBus.spatial.value
        return info.requestedMode == SpatialAudioMode.STUDIO &&
            info.effectiveMode == SpatialAudioMode.STUDIO &&
            info.inputSampleRateHz == sampleRateHz &&
            info.inputEncoding == "Float32" &&
            info.bypassReason == null &&
            info.error == null
    }

    private fun mediaItem(id: String, file: File): MediaItem =
        MediaItem.Builder().setMediaId(id).setUri(Uri.fromFile(file)).build()

    private fun decodeFixtureToAppCache(
        instrumentationContext: Context,
        appContext: Context,
        assetName: String,
        fileName: String
    ): File {
        val encoded = instrumentationContext.assets.open(assetName).bufferedReader().use {
            it.readText()
        }
        val bytes = Base64.decode(encoded, Base64.DEFAULT)
        assertTrue("test fixture does not contain a FLAC stream", bytes.size >= 4 &&
            bytes[0] == 'f'.code.toByte() && bytes[1] == 'L'.code.toByte() &&
            bytes[2] == 'a'.code.toByte() && bytes[3] == 'C'.code.toByte())
        return File(appContext.cacheDir, fileName).also { file ->
            FileOutputStream(file).use { it.write(bytes) }
        }
    }

    private fun peakFloat(bytes: ByteArray, frameLimit: Int = Int.MAX_VALUE): Float {
        assertEquals("captured PCM must be interleaved Float32 stereo", 0, bytes.size % (CHANNELS * Float.SIZE_BYTES))
        val frames = minOf(bytes.size / (CHANNELS * Float.SIZE_BYTES), frameLimit)
        assertTrue("capture contains no complete PCM frame", frames > 0)
        val floats = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()).asFloatBuffer()
        var peak = 0f
        for (sample in 0 until frames * CHANNELS) {
            val value = floats.get(sample)
            assertTrue("captured PCM contains a non-finite sample: $value", value.isFinite())
            peak = maxOf(peak, kotlin.math.abs(value))
        }
        return peak
    }

    private fun describeTailEvents(window: SpatialPcmCaptureSession.CaptureWindow): String {
        val tails = ArrayList<String>()
        for (index in 0 until window.eventCount) {
            val tailFrames = window.finalTailFrameCountAt(index)
            if (tailFrames > 0) {
                tails += "event=$index output=${window.outputIdAt(index)} " +
                    "frame=${window.frameIndexAt(index)} count=${window.frameCountAt(index)} " +
                    "tail=$tailFrames ptsUs=${window.presentationTimeUsAt(index)}"
            }
        }
        return tails.joinToString(prefix = "[", postfix = "]")
    }

    private fun await(
        label: String,
        timeoutDetails: () -> String = { "" },
        condition: () -> Boolean
    ) {
        val deadline = SystemClock.elapsedRealtime() + PLAYBACK_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            Thread.sleep(POLL_INTERVAL_MS)
        }
        throw AssertionError(
            "Timed out waiting for $label; ${timeoutDetails()}, " +
                "spatial=${PlaybackDiagnosticsBus.spatial.value}, " +
                "actualOutput=${PlaybackDiagnosticsBus.actualOutput.value}"
        )
    }

    private fun <T : Any> onMainThread(instrumentation: Instrumentation, action: () -> T): T {
        lateinit var result: T
        instrumentation.runOnMainSync { result = action() }
        return result
    }

    private companion object {
        const val SETTINGS_FILE = "flactify_settings"
        const val REQUIRE_EXPECTED_AUDIO_ROUTE_ARGUMENT = "requireExpectedAudioRoute"
        const val EXPECTED_AUDIO_ROUTE_TYPE_ARGUMENT = "expectedAudioRouteType"
        const val EXPECTED_AUDIO_ROUTE_NAME_ARGUMENT = "expectedAudioRouteName"
        const val CAPTURE_TAG = "SpatialPcmCapture"
        const val CONTROLLER_TIMEOUT_SECONDS = 30L
        const val PLAYBACK_TIMEOUT_MS = 30_000L
        const val POLL_INTERVAL_MS = 10L
        const val CHANNELS = 2
        const val FIRST_ITEM_FRAME_COUNT = 192_000L
        const val SEEK_TARGET_MS = 10_000L
        // Media3 1.11.1 offsets renderer timestamps by 1e12 us for the initial period.
        const val MEDIA3_INITIAL_RENDERER_POSITION_OFFSET_US = 1_000_000_000_000L
        const val SEEKABLE_SILENCE_RATE_HZ = 192_000
        const val SEEKABLE_SILENCE_FIXTURE_ASSET = "audio/flac_192000_24_stereo_silence_15s.flac.b64"
        const val SEEKABLE_SILENCE_FIXTURE_FILE_NAME = "spatial-capture-seekable-silence-192000-24-stereo.flac"
        const val FIRST_ITEM_ID = "spatial-capture-first"
        const val SECOND_ITEM_ID = "spatial-capture-second"
        const val FIXTURE_ASSET = "audio/spatial_capture_tone_then_silence_48000_16_stereo.flac.b64"
        const val FIXTURE_FILE_NAME = "spatial-capture-tone-then-silence-48000-16-stereo.flac"
    }
}
