package com.flactify.audio.spatial

import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Base64
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.flactify.PlaybackService
import com.flactify.audio.AudioRouteMonitor
import com.flactify.audio.AudioRouteType
import com.flactify.audio.PlaybackDiagnosticsBus
import com.flactify.audio.RouteConfidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Verifies service-owned ExoPlayer handling of repository and long-duration FLAC fixtures. */
@RunWith(AndroidJUnit4::class)
class PlaybackServiceSpatialFlacDeviceTest {

    @Test
    fun servicePlayerProcessesRepositoryFlacFixturesAtTheirDecodedPcmRates() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val appContext = instrumentation.targetContext
        val routeArguments = InstrumentationRegistry.getArguments()
        val requireExpectedAudioRoute = when (
            routeArguments.getString(REQUIRE_EXPECTED_AUDIO_ROUTE_ARGUMENT)
        ) {
            null, "false" -> false
            "true" -> true
            else -> error("Expected ${REQUIRE_EXPECTED_AUDIO_ROUTE_ARGUMENT} to be true or false")
        }
        if (requireExpectedAudioRoute) {
            assertEquals(
                "Expected route type argument was not forwarded; keys=${routeArguments.keySet()}",
                AudioRouteType.BLUETOOTH.name,
                routeArguments.getString(EXPECTED_AUDIO_ROUTE_TYPE_ARGUMENT)
            )
            assertEquals(
                "Expected route name argument was not forwarded; keys=${routeArguments.keySet()}",
                "Liberty 4 Pro",
                routeArguments.getString(EXPECTED_AUDIO_ROUTE_NAME_ARGUMENT)
            )
            assertExpectedActiveAudioRouteIfRequested(appContext)
        }
        val preferences = appContext.getSharedPreferences(SETTINGS_FILE, Context.MODE_PRIVATE)
        val hadPreviousMode = preferences.contains(SpatialAudioMode.PREFERENCE_KEY)
        val previousMode = preferences.getString(SpatialAudioMode.PREFERENCE_KEY, null)
        val fixtureFiles = mutableListOf<File>()
        var controllerFuture: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
        var controller: MediaController? = null

        appContext.stopService(Intent(appContext, PlaybackService::class.java))
        PlaybackDiagnosticsBus.resetAll()
        assertTrue(
            "could not persist Studio for PlaybackService startup",
            preferences.edit()
                .putString(SpatialAudioMode.PREFERENCE_KEY, SpatialAudioMode.STUDIO.wireValue)
                .commit()
        )

        try {
            val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
            controllerFuture = onMainThread(instrumentation) {
                MediaController.Builder(appContext, token).buildAsync()
            }
            val connectedController = controllerFuture.get(CONTROLLER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            controller = connectedController

            // Expected values describe Media3's observed DSP boundary, not FLAC bit depth.
            val fixtures = listOf(
                Fixture("flac_44100_16_stereo.flac", 44_100, "Float32"),
                Fixture("flac_96000_24_stereo.flac", 96_000, "Float32"),
                Fixture("flac_192000_24_stereo.flac", 192_000, "Float32")
            )
            for (fixture in fixtures) {
                val file = copyFixtureToAppCache(instrumentation.context, appContext, fixture.name)
                fixtureFiles += file

                PlaybackDiagnosticsBus.reset()
                val mediaItems = if (fixture.sampleRateHz == 44_100) {
                    listOf(
                        MediaItem.Builder()
                            .setMediaId("spatial-same-format-first")
                            .setUri(Uri.fromFile(file))
                            .build(),
                        MediaItem.Builder()
                            .setMediaId("spatial-same-format-second")
                            .setUri(Uri.fromFile(file))
                            .build()
                    )
                } else {
                    listOf(MediaItem.fromUri(Uri.fromFile(file)))
                }
                onMainThread(instrumentation) {
                    connectedController.setMediaItems(mediaItems)
                    connectedController.prepare()
                    connectedController.play()
                }

                await(
                    "${fixture.name} Studio PCM activation or terminal playback result",
                    diagnosticSnapshot = {
                        val info = PlaybackDiagnosticsBus.spatial.value
                        val playerSnapshot = onMainThread(instrumentation) {
                            "playbackState=${connectedController.playbackState}, " +
                                "playerError=${connectedController.playerError}"
                        }
                        "spatialInfo=$info, $playerSnapshot"
                    }
                ) {
                    val info = PlaybackDiagnosticsBus.spatial.value
                    val activeStudio =
                        info.requestedMode == SpatialAudioMode.STUDIO &&
                            info.effectiveMode == SpatialAudioMode.STUDIO &&
                            info.inputSampleRateHz == fixture.sampleRateHz &&
                            info.inputEncoding == fixture.expectedDspInputEncoding &&
                            info.bypassReason == null
                    val terminalSpatialResult =
                        info.error != null ||
                            (info.bypassReason != null &&
                                info.bypassReason != "AWAITING_PCM_FORMAT")
                    val playerSnapshot = onMainThread(instrumentation) {
                        connectedController.playbackState to connectedController.playerError
                    }
                    activeStudio ||
                        terminalSpatialResult ||
                        playerSnapshot.second != null ||
                        playerSnapshot.first == Player.STATE_ENDED
                }

                val activeInfo = PlaybackDiagnosticsBus.spatial.value
                val playbackSnapshot = onMainThread(instrumentation) {
                    "playbackState=${connectedController.playbackState}, " +
                        "playerError=${connectedController.playerError}"
                }
                assertTrue(
                    "Studio did not activate at ${fixture.sampleRateHz} Hz: " +
                        "spatialInfo=$activeInfo, $playbackSnapshot",
                    activeInfo.requestedMode == SpatialAudioMode.STUDIO &&
                        activeInfo.effectiveMode == SpatialAudioMode.STUDIO &&
                        activeInfo.inputSampleRateHz == fixture.sampleRateHz &&
                        activeInfo.inputEncoding == fixture.expectedDspInputEncoding &&
                        activeInfo.bypassReason == null
                )
                assertEquals(SpatialAudioMode.STUDIO, activeInfo.effectiveMode)
                assertEquals(fixture.sampleRateHz, activeInfo.inputSampleRateHz)
                assertEquals(fixture.expectedDspInputEncoding, activeInfo.inputEncoding)
                assertEquals("Steam Audio", activeInfo.engine)
                assertEquals(
                    "Pixel candidate block size is active in the service path",
                    512,
                    activeInfo.frameSize
                )
                assertNull(activeInfo.bypassReason)


                if (fixture.sampleRateHz == 44_100) {
                    await("observed AudioTrack output configuration") {
                        PlaybackDiagnosticsBus.actualOutput.value.isFullyObserved
                    }
                    assertExpectedActiveAudioRouteIfRequested(appContext)
                    val outputInfoBeforeTransition = PlaybackDiagnosticsBus.actualOutput.value
                    await(
                        "same-format playlist transition with active output diagnostics",
                        diagnosticSnapshot = {
                            "player=${onMainThread(instrumentation) {
                                "index=${connectedController.currentMediaItemIndex}, " +
                                    "state=${connectedController.playbackState}, " +
                                    "isPlaying=${connectedController.isPlaying}, " +
                                    "mediaId=${connectedController.currentMediaItem?.mediaId}, " +
                                    "error=${connectedController.playerError}"
                            }}, spatialInfo=${PlaybackDiagnosticsBus.spatial.value}, " +
                                "actualOutput=${PlaybackDiagnosticsBus.actualOutput.value}"
                        }
                    ) {
                        val secondItemIsCurrent = onMainThread(instrumentation) {
                            connectedController.currentMediaItemIndex == 1
                        }
                        val transitionInfo = PlaybackDiagnosticsBus.spatial.value
                        secondItemIsCurrent &&
                            transitionInfo.effectiveMode == SpatialAudioMode.STUDIO &&
                            transitionInfo.inputSampleRateHz == fixture.sampleRateHz &&
                            transitionInfo.inputEncoding == fixture.expectedDspInputEncoding &&
                            transitionInfo.bypassReason == null &&
                            PlaybackDiagnosticsBus.actualOutput.value == outputInfoBeforeTransition
                    }

                    val previousRepeatMode = onMainThread(instrumentation) {
                        connectedController.repeatMode.also {
                            connectedController.repeatMode = Player.REPEAT_MODE_ONE
                            if (!connectedController.isPlaying) connectedController.play()
                        }
                    }
                    try {
                        await("active playback before a Spatial mode change") {
                            onMainThread(instrumentation) { connectedController.isPlaying }
                        }
                        val beforeOff = onMainThread(instrumentation) {
                            connectedController.currentMediaItemIndex to connectedController.currentPosition
                        }
                        val offResult = requestSpatialMode(
                            instrumentation,
                            connectedController,
                            SpatialAudioMode.OFF
                        )
                        assertEquals(SessionResult.RESULT_SUCCESS, offResult.resultCode)
                        await(
                            "Off mode resumes playback at the current item",
                            diagnosticSnapshot = {
                                val snapshot = onMainThread(instrumentation) {
                                    "index=${connectedController.currentMediaItemIndex}, " +
                                        "positionMs=${connectedController.currentPosition}, " +
                                        "state=${connectedController.playbackState}, " +
                                        "playWhenReady=${connectedController.playWhenReady}, " +
                                        "isPlaying=${connectedController.isPlaying}, " +
                                        "suppression=${connectedController.playbackSuppressionReason}, " +
                                        "error=${connectedController.playerError}"
                                }
                                "player={$snapshot}, spatial=${PlaybackDiagnosticsBus.spatial.value}, " +
                                    "actualOutput=${PlaybackDiagnosticsBus.actualOutput.value}"
                            }
                        ) {
                            val state = onMainThread(instrumentation) {
                                Triple(
                                    connectedController.currentMediaItemIndex,
                                    connectedController.currentPosition,
                                    connectedController.isPlaying
                                )
                            }
                            state.first == beforeOff.first &&
                                state.second >= beforeOff.second - 250L &&
                                state.third &&
                                PlaybackDiagnosticsBus.spatial.value.requestedMode == SpatialAudioMode.OFF
                        }

                        val beforeStudio = onMainThread(instrumentation) {
                            connectedController.currentMediaItemIndex to connectedController.currentPosition
                        }
                        val studioResult = requestSpatialMode(
                            instrumentation,
                            connectedController,
                            SpatialAudioMode.STUDIO
                        )
                        assertEquals(SessionResult.RESULT_SUCCESS, studioResult.resultCode)
                        await("Studio mode resumes the existing playlist item") {
                            val state = onMainThread(instrumentation) {
                                Triple(
                                    connectedController.currentMediaItemIndex,
                                    connectedController.currentPosition,
                                    connectedController.isPlaying
                                )
                            }
                            val info = PlaybackDiagnosticsBus.spatial.value
                            state.first == beforeStudio.first &&
                                state.second >= beforeStudio.second - 250L &&
                                state.third &&
                                info.effectiveMode == SpatialAudioMode.STUDIO &&
                                info.inputSampleRateHz == fixture.sampleRateHz &&
                                info.bypassReason == null
                        }
                        assertExpectedActiveAudioRouteIfRequested(appContext)
                    } finally {
                        onMainThread(instrumentation) {
                            connectedController.repeatMode = previousRepeatMode
                        }
                    }
                }

                await(
                    "${fixture.name} playback EOS",
                    diagnosticSnapshot = {
                        val playerSnapshot = onMainThread(instrumentation) {
                            "state=${connectedController.playbackState}, " +
                                "itemIndex=${connectedController.currentMediaItemIndex}, " +
                                "positionMs=${connectedController.currentPosition}, " +
                                "durationMs=${connectedController.duration}, " +
                                "isPlaying=${connectedController.isPlaying}, " +
                                "playWhenReady=${connectedController.playWhenReady}, " +
                                "suppression=${connectedController.playbackSuppressionReason}, " +
                                "error=${connectedController.playerError}"
                        }
                        "player={$playerSnapshot}, spatial=${PlaybackDiagnosticsBus.spatial.value}, " +
                            "actualOutput=${PlaybackDiagnosticsBus.actualOutput.value}, " +
                            "media3AudioSink=${PlaybackDiagnosticsBus.media3AudioSink.value}"
                    }
                ) {
                    onMainThread(instrumentation) {
                        connectedController.playbackState == Player.STATE_ENDED
                    }
                }
            }

            val sustainedFixture = decodeBase64FixtureToAppCache(
                instrumentation.context,
                appContext
            )
            fixtureFiles += sustainedFixture
            runSustainedHighRateFlacPlayback(
                instrumentation,
                connectedController,
                sustainedFixture
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
            if (hadPreviousMode) {
                preferences.edit()
                    .putString(SpatialAudioMode.PREFERENCE_KEY, previousMode)
                    .commit()
            } else {
                preferences.edit().remove(SpatialAudioMode.PREFERENCE_KEY).commit()
            }
            appContext.stopService(Intent(appContext, PlaybackService::class.java))
            fixtureFiles.forEach(File::delete)
            PlaybackDiagnosticsBus.resetAll()
        }
    }

    private fun runSustainedHighRateFlacPlayback(
        instrumentation: Instrumentation,
        controller: MediaController,
        fixtureFile: File
    ) {
        val durationMs = InstrumentationRegistry.getArguments()
            .getString(SUSTAINED_FLAC_DURATION_ARGUMENT)
            ?.toLongOrNull()
            ?: DEFAULT_SUSTAINED_FLAC_DURATION_MS
        require(durationMs >= SUSTAINED_FLAC_DURATION_MS * 2) {
            "Sustained FLAC duration must cover at least two fixture passes"
        }

        val repeatTransitions = AtomicInteger()
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                    repeatTransitions.incrementAndGet()
                }
            }
        }
        onMainThread(instrumentation) { controller.addListener(listener) }

        try {
            PlaybackDiagnosticsBus.reset()
            onMainThread(instrumentation) {
                controller.repeatMode = Player.REPEAT_MODE_ONE
                controller.setMediaItem(MediaItem.fromUri(Uri.fromFile(fixtureFile)))
                controller.prepare()
                controller.play()
            }
            await(
                "long 192 kHz FLAC activates Studio and AudioTrack",
                diagnosticSnapshot = {
                    val spatial = PlaybackDiagnosticsBus.spatial.value
                    val output = PlaybackDiagnosticsBus.actualOutput.value
                    "spatial=$spatial, output=$output, " +
                        onMainThread(instrumentation) {
                            "state=${controller.playbackState}, " +
                                "durationMs=${controller.duration}, " +
                                "positionMs=${controller.currentPosition}, " +
                                "error=${controller.playerError}"
                        }
                }
            ) {
                val spatial = PlaybackDiagnosticsBus.spatial.value
                val output = PlaybackDiagnosticsBus.actualOutput.value
                val playerSnapshot = onMainThread(instrumentation) {
                    SustainedStartSnapshot(
                        isPlaying = controller.isPlaying,
                        playbackState = controller.playbackState,
                        durationMs = controller.duration,
                        error = controller.playerError
                    )
                }
                playerSnapshot.isPlaying &&
                    playerSnapshot.playbackState == Player.STATE_READY &&
                    playerSnapshot.durationMs == SUSTAINED_FLAC_DURATION_MS &&
                    playerSnapshot.error == null &&
                    spatial.effectiveMode == SpatialAudioMode.STUDIO &&
                    spatial.inputSampleRateHz == SUSTAINED_FLAC_RATE_HZ &&
                    spatial.inputEncoding == "Float32" &&
                    spatial.bypassReason == null &&
                    output.isFullyObserved &&
                    output.sampleRateHz == SUSTAINED_FLAC_RATE_HZ &&
                    output.isOffload == false &&
                    output.isTunneling == false
            }

            val seekTargetMs = SUSTAINED_FLAC_DURATION_MS / 3L
            val outputInfoBeforeSeek = PlaybackDiagnosticsBus.actualOutput.value
            onMainThread(instrumentation) { controller.pause() }
            await(
                "192 kHz pause is acknowledged before the service seek",
                diagnosticSnapshot = {
                    val playerSnapshot = onMainThread(instrumentation) {
                        "state=${controller.playbackState}, isPlaying=${controller.isPlaying}, " +
                            "playWhenReady=${controller.playWhenReady}, " +
                            "suppression=${controller.playbackSuppressionReason}, " +
                            "positionMs=${controller.currentPosition}, error=${controller.playerError}"
                    }
                    "player={$playerSnapshot}, actualOutput=${PlaybackDiagnosticsBus.actualOutput.value}"
                }
            ) {
                onMainThread(instrumentation) {
                    !controller.playWhenReady && !controller.isPlaying
                }
            }
            onMainThread(instrumentation) { controller.seekTo(seekTargetMs) }
            await(
                "192 kHz service seek completes with Studio active",
                diagnosticSnapshot = {
                    val playerSnapshot = onMainThread(instrumentation) {
                        "state=${controller.playbackState}, " +
                            "positionMs=${controller.currentPosition}, " +
                            "targetMs=$seekTargetMs, " +
                            "isPlaying=${controller.isPlaying}, " +
                            "error=${controller.playerError}"
                    }
                    "player={$playerSnapshot}, spatial=${PlaybackDiagnosticsBus.spatial.value}, " +
                        "actualOutput=${PlaybackDiagnosticsBus.actualOutput.value}, " +
                        "media3AudioSink=${PlaybackDiagnosticsBus.media3AudioSink.value}"
                }
            ) {
                val playerSnapshot = onMainThread(instrumentation) {
                    Triple(
                        controller.playbackState,
                        controller.currentPosition,
                        controller.playerError
                    )
                }
                val spatial = PlaybackDiagnosticsBus.spatial.value
                playerSnapshot.first == Player.STATE_READY &&
                    playerSnapshot.second in (seekTargetMs - 500L)..(seekTargetMs + 500L) &&
                    playerSnapshot.third == null &&
                    spatial.effectiveMode == SpatialAudioMode.STUDIO &&
                    spatial.inputSampleRateHz == SUSTAINED_FLAC_RATE_HZ &&
                    spatial.inputEncoding == "Float32" &&
                    spatial.bypassReason == null
            }
            onMainThread(instrumentation) { controller.play() }
            await(
                "192 kHz AudioTrack reinitializes after seek",
                diagnosticSnapshot = {
                    val playerSnapshot = onMainThread(instrumentation) {
                        "state=${controller.playbackState}, " +
                            "positionMs=${controller.currentPosition}, " +
                            "isPlaying=${controller.isPlaying}, " +
                            "error=${controller.playerError}"
                    }
                    "player={$playerSnapshot}, spatial=${PlaybackDiagnosticsBus.spatial.value}, " +
                        "actualOutput=${PlaybackDiagnosticsBus.actualOutput.value}"
                }
            ) {
                val playerSnapshot = onMainThread(instrumentation) {
                    SustainedPlayerSnapshot(
                        isPlaying = controller.isPlaying,
                        playbackState = controller.playbackState,
                        playWhenReady = controller.playWhenReady,
                        playbackSuppressionReason = controller.playbackSuppressionReason,
                        currentMediaItemIndex = controller.currentMediaItemIndex,
                        repeatMode = controller.repeatMode,
                        positionMs = controller.currentPosition,
                        error = controller.playerError
                    )
                }
                val output = PlaybackDiagnosticsBus.actualOutput.value
                playerSnapshot.isPlaying &&
                    playerSnapshot.playbackState == Player.STATE_READY &&
                    playerSnapshot.positionMs >= seekTargetMs &&
                    playerSnapshot.error == null &&
                    output.isFullyObserved &&
                    output.sampleRateHz == SUSTAINED_FLAC_RATE_HZ &&
                    output.isOffload == false &&
                    output.isTunneling == false
            }
            assertEquals(outputInfoBeforeSeek, PlaybackDiagnosticsBus.actualOutput.value)

            val playbackDurationMs = onMainThread(instrumentation) { controller.duration }
            val initialMedia3UnderrunEvents = checkNotNull(
                PlaybackDiagnosticsBus.media3AudioSink.value.underrunEventCount
            ) { "Media3 underrun event count was not observed" }
            val startedAtMs = SystemClock.elapsedRealtime()
            val deadlineMs = startedAtMs + durationMs
            val processCpuStartMs = Process.getElapsedCpuTime()
            val runtime = Runtime.getRuntime()
            val initialJavaHeapBytes = usedJavaHeapBytes(runtime)
            var peakJavaHeapBytes = initialJavaHeapBytes
            val initialNativeHeapBytes = Debug.getNativeHeapAllocatedSize()
            var peakNativeHeapBytes = initialNativeHeapBytes
            val thermalService = checkNotNull(
                instrumentation.targetContext.getSystemService(PowerManager::class.java)
            )
            val initialThermalStatus = thermalStatus(thermalService)
            var peakThermalStatus = initialThermalStatus
            var sampleCount = 0
            var playingSamples = 0
            var bufferingSamples = 0
            var readyButNotPlayingSamples = 0
            var playWhenReadyFalseSamples = 0
            var suppressedSamples = 0
            var spatialSamples = 0
            var outputSamples = 0
            var progressSamples = 0
            var positionResetCount = 0
            var previousPositionMs = Long.MIN_VALUE
            var lastSnapshot: SustainedPlayerSnapshot? = null
            while (SystemClock.elapsedRealtime() < deadlineMs) {
                val snapshot = onMainThread(instrumentation) {
                    SustainedPlayerSnapshot(
                        isPlaying = controller.isPlaying,
                        playbackState = controller.playbackState,
                        playWhenReady = controller.playWhenReady,
                        playbackSuppressionReason = controller.playbackSuppressionReason,
                        currentMediaItemIndex = controller.currentMediaItemIndex,
                        repeatMode = controller.repeatMode,
                        positionMs = controller.currentPosition,
                        error = controller.playerError
                    )
                }
                assertNull(
                    "192 kHz FLAC player error at ${snapshot.positionMs} ms: " +
                        snapshot.error?.stackTraceToString(),
                    snapshot.error
                )
                if (snapshot.isPlaying && snapshot.playbackState == Player.STATE_READY) {
                    playingSamples++
                }
                if (snapshot.playbackState == Player.STATE_BUFFERING) bufferingSamples++
                if (snapshot.playbackState == Player.STATE_READY && !snapshot.isPlaying) {
                    readyButNotPlayingSamples++
                }
                if (!snapshot.playWhenReady) playWhenReadyFalseSamples++
                if (snapshot.playbackSuppressionReason != 0) suppressedSamples++
                if (
                    previousPositionMs != Long.MIN_VALUE &&
                    snapshot.positionMs + POSITION_RESET_THRESHOLD_MS < previousPositionMs
                ) {
                    positionResetCount++
                }
                if (snapshot.positionMs != previousPositionMs) {
                    progressSamples++
                    previousPositionMs = snapshot.positionMs
                }
                lastSnapshot = snapshot

                val spatial = PlaybackDiagnosticsBus.spatial.value
                if (
                    spatial.effectiveMode == SpatialAudioMode.STUDIO &&
                    spatial.inputSampleRateHz == SUSTAINED_FLAC_RATE_HZ &&
                    spatial.inputEncoding == "Float32" &&
                    spatial.bypassReason == null
                ) {
                    spatialSamples++
                }
                val output = PlaybackDiagnosticsBus.actualOutput.value
                if (
                    output.sampleRateHz == SUSTAINED_FLAC_RATE_HZ &&
                    output.isOffload == false &&
                    output.isTunneling == false
                ) {
                    outputSamples++
                }
                val javaHeapNowBytes = usedJavaHeapBytes(runtime)
                val nativeHeapNowBytes = Debug.getNativeHeapAllocatedSize()
                peakJavaHeapBytes = maxOf(peakJavaHeapBytes, javaHeapNowBytes)
                peakNativeHeapBytes = maxOf(peakNativeHeapBytes, nativeHeapNowBytes)
                peakThermalStatus = maxOf(peakThermalStatus, thermalStatus(thermalService))
                sampleCount++
                Thread.sleep(SUSTAINED_SAMPLE_INTERVAL_MS)
            }

            val minimumSamples = (sampleCount * 9) / 10
            val minimumRepeatTransitions =
                (durationMs / SUSTAINED_FLAC_DURATION_MS / 2L).coerceAtLeast(1L)
            val finalPlayerSnapshot = onMainThread(instrumentation) {
                SustainedPlayerSnapshot(
                    isPlaying = controller.isPlaying,
                    playbackState = controller.playbackState,
                    playWhenReady = controller.playWhenReady,
                    playbackSuppressionReason = controller.playbackSuppressionReason,
                    currentMediaItemIndex = controller.currentMediaItemIndex,
                    repeatMode = controller.repeatMode,
                    positionMs = controller.currentPosition,
                    error = controller.playerError
                )
            }
            val diagnosticContext =
                "durationMs=$durationMs playbackDurationMs=$playbackDurationMs " +
                    "samples=$sampleCount playing=$playingSamples buffering=$bufferingSamples " +
                    "readyButNotPlaying=$readyButNotPlayingSamples " +
                    "playWhenReadyFalse=$playWhenReadyFalseSamples suppressed=$suppressedSamples " +
                    "spatial=$spatialSamples output=$outputSamples progress=$progressSamples " +
                    "positionResets=$positionResetCount repeatCallbacks=${repeatTransitions.get()} " +
                    "requiredPositionResets=$minimumRepeatTransitions lastSample=$lastSnapshot " +
                    "finalPlayer=$finalPlayerSnapshot spatialInfo=${PlaybackDiagnosticsBus.spatial.value} " +
                    "actualOutput=${PlaybackDiagnosticsBus.actualOutput.value} " +
                    "media3AudioSink=${PlaybackDiagnosticsBus.media3AudioSink.value}"
            android.util.Log.i(SUSTAINED_FLAC_TAG, "soak-check $diagnosticContext")

            assertTrue(
                "PlaybackService was playing for $playingSamples/$sampleCount samples; $diagnosticContext",
                playingSamples >= minimumSamples
            )
            assertTrue(
                "Studio left the 192 kHz path for $spatialSamples/$sampleCount samples; $diagnosticContext",
                spatialSamples >= minimumSamples
            )
            assertTrue(
                "AudioTrack output left 192 kHz for $outputSamples/$sampleCount samples; $diagnosticContext",
                outputSamples >= minimumSamples
            )
            assertTrue(
                "ExoPlayer position progressed for $progressSamples/$sampleCount samples; $diagnosticContext",
                progressSamples >= minimumSamples
            )
            assertTrue(
                "repeat-one sampled position resets=$positionResetCount " +
                    "(callbacks=${repeatTransitions.get()}), required=$minimumRepeatTransitions; " +
                    diagnosticContext,
                positionResetCount.toLong() >= minimumRepeatTransitions
            )
            val elapsedMs = (SystemClock.elapsedRealtime() - startedAtMs).coerceAtLeast(1L)
            val processCpuMs = Process.getElapsedCpuTime() - processCpuStartMs
            val javaHeapEndBytes = usedJavaHeapBytes(runtime)
            val nativeHeapEndBytes = Debug.getNativeHeapAllocatedSize()
            val finalThermalStatus = thermalStatus(thermalService)
            val finalMedia3AudioSink = PlaybackDiagnosticsBus.media3AudioSink.value
            val finalMedia3UnderrunEvents = checkNotNull(finalMedia3AudioSink.underrunEventCount) {
                "Media3 underrun event count became unknown during playback"
            }
            val media3UnderrunEventsDuringSoak =
                finalMedia3UnderrunEvents - initialMedia3UnderrunEvents
            assertTrue(
                "Media3 underrun event count moved backwards: " +
                    "$initialMedia3UnderrunEvents -> $finalMedia3UnderrunEvents",
                media3UnderrunEventsDuringSoak >= 0L
            )
            android.util.Log.i(
                SUSTAINED_FLAC_TAG,
                "sampleRateHz=$SUSTAINED_FLAC_RATE_HZ durationMs=$durationMs " +
                    "playbackDurationMs=$playbackDurationMs samples=$sampleCount " +
                    "playing=$playingSamples spatial=$spatialSamples output=$outputSamples " +
                    "progress=$progressSamples repeatTransitions=${repeatTransitions.get()} " +
                    "processCpuPercent=${processCpuMs * 100.0 / elapsedMs} " +
                    "javaHeapStartBytes=$initialJavaHeapBytes " +
                    "javaHeapEndDeltaBytes=${javaHeapEndBytes - initialJavaHeapBytes} " +
                    "javaHeapPeakDeltaBytes=${peakJavaHeapBytes - initialJavaHeapBytes} " +
                    "nativeHeapStartBytes=$initialNativeHeapBytes " +
                    "nativeHeapEndDeltaBytes=${nativeHeapEndBytes - initialNativeHeapBytes} " +
                    "nativeHeapPeakDeltaBytes=${peakNativeHeapBytes - initialNativeHeapBytes} " +
                    "thermalStatus=$initialThermalStatus->${peakThermalStatus}->${finalThermalStatus} " +
                    "media3UnderrunEvents=$media3UnderrunEventsDuringSoak " +
                    "lastUnderrunBufferSizeBytes=${finalMedia3AudioSink.lastBufferSizeBytes} " +
                    "lastUnderrunBufferDurationMs=${finalMedia3AudioSink.lastBufferDurationMs} " +
                    "lastUnderrunElapsedSinceFeedMs=${finalMedia3AudioSink.lastElapsedSinceLastFeedMs}"
            )
        } finally {
            onMainThread(instrumentation) {
                controller.removeListener(listener)
                controller.repeatMode = Player.REPEAT_MODE_OFF
                controller.pause()
            }
        }
    }

    private fun decodeBase64FixtureToAppCache(
        instrumentationContext: Context,
        appContext: Context
    ): File {
        val encoded = instrumentationContext.assets.open(SUSTAINED_FLAC_ASSET).bufferedReader().use {
            it.readText()
        }
        val flacBytes = Base64.decode(encoded, Base64.DEFAULT)
        require(
            flacBytes.size >= FLAC_MAGIC.size &&
                flacBytes.copyOfRange(0, FLAC_MAGIC.size).contentEquals(FLAC_MAGIC)
        ) { "Sustained fixture is not a FLAC stream" }

        val destination = File(appContext.cacheDir, SUSTAINED_FLAC_FILE_NAME)
        FileOutputStream(destination).use { output -> output.write(flacBytes) }
        return destination
    }

    private fun thermalStatus(powerManager: PowerManager): Int =
        if (android.os.Build.VERSION.SDK_INT >= 29) powerManager.currentThermalStatus else -1

    private fun usedJavaHeapBytes(runtime: Runtime): Long =
        runtime.totalMemory() - runtime.freeMemory()

    private data class SustainedStartSnapshot(
        val isPlaying: Boolean,
        val playbackState: Int,
        val durationMs: Long,
        val error: androidx.media3.common.PlaybackException?
    )

    private data class SustainedPlayerSnapshot(
        val isPlaying: Boolean,
        val playbackState: Int,
        val playWhenReady: Boolean,
        val playbackSuppressionReason: Int,
        val currentMediaItemIndex: Int,
        val repeatMode: Int,
        val positionMs: Long,
        val error: androidx.media3.common.PlaybackException?
    )

    private fun assertExpectedActiveAudioRouteIfRequested(context: Context) {
        val arguments = InstrumentationRegistry.getArguments()
        val requestedType = arguments.getString(EXPECTED_AUDIO_ROUTE_TYPE_ARGUMENT)
        val expectedName = arguments.getString(EXPECTED_AUDIO_ROUTE_NAME_ARGUMENT)
            ?.takeIf { it.isNotBlank() }
        val route = AudioRouteMonitor(context).currentRoute()
        android.util.Log.i(
            ACTIVE_ROUTE_TAG,
            "requestedType=$requestedType expectedName=$expectedName predictedMediaRoute=$route"
        )

        val expectedType = requestedType?.let(AudioRouteType::valueOf) ?: return
        assertTrue(
            "AudioManager attribute routing is prediction, not AudioTrack route evidence: $route",
            route?.confidence != RouteConfidence.ACTIVE_ROUTE
        )
        assertEquals("Unexpected active media output route: $route", expectedType, route?.type)
        if (expectedName != null) {
            assertTrue(
                "Active route name '$route' does not contain '$expectedName'",
                route?.deviceName?.contains(expectedName, ignoreCase = true) == true
            )
        }
    }

    private fun requestSpatialMode(
        instrumentation: Instrumentation,
        controller: MediaController,
        mode: SpatialAudioMode
    ): SessionResult {
        val resultFuture = onMainThread(instrumentation) {
            controller.sendCustomCommand(
                SessionCommand(SpatialAudioMode.SESSION_COMMAND_ACTION, Bundle.EMPTY),
                Bundle().apply {
                    putString(SpatialAudioMode.SESSION_EXTRA_MODE, mode.wireValue)
                }
            )
        }
        return resultFuture.get(CONTROLLER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private fun <T : Any> onMainThread(
        instrumentation: Instrumentation,
        action: () -> T
    ): T {
        lateinit var result: T
        instrumentation.runOnMainSync { result = action() }
        return result
    }

    private fun await(
        label: String,
        diagnosticSnapshot: () -> String = { "" },
        condition: () -> Boolean
    ) {
        val deadline = SystemClock.elapsedRealtime() + PLAYBACK_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            Thread.sleep(POLL_INTERVAL_MS)
        }
        throw AssertionError(
            "Timed out waiting for $label; ${diagnosticSnapshot()}"
        )
    }

    private fun copyFixtureToAppCache(
        instrumentationContext: Context,
        appContext: Context,
        fixtureName: String
    ): File {
        val destination = File(appContext.cacheDir, "spatial-test-$fixtureName")
        instrumentationContext.assets.open("audio/$fixtureName").use { input ->
            FileOutputStream(destination).use(input::copyTo)
        }
        return destination
    }

    private data class Fixture(
        val name: String,
        val sampleRateHz: Int,
        val expectedDspInputEncoding: String
    )

    private companion object {
        const val SETTINGS_FILE = "flactify_settings"
        const val REQUIRE_EXPECTED_AUDIO_ROUTE_ARGUMENT = "requireExpectedAudioRoute"
        const val EXPECTED_AUDIO_ROUTE_TYPE_ARGUMENT = "expectedAudioRouteType"
        const val EXPECTED_AUDIO_ROUTE_NAME_ARGUMENT = "expectedAudioRouteName"
        const val ACTIVE_ROUTE_TAG = "SpatialActiveRoute"
        const val CONTROLLER_TIMEOUT_SECONDS = 30L
        const val PLAYBACK_TIMEOUT_MS = 90_000L
        const val POLL_INTERVAL_MS = 10L
        const val SUSTAINED_FLAC_RATE_HZ = 192_000
        const val SUSTAINED_FLAC_DURATION_MS = 15_000L
        const val SUSTAINED_FLAC_DURATION_ARGUMENT = "spatialFlacPlaybackDurationMs"
        const val DEFAULT_SUSTAINED_FLAC_DURATION_MS = 120_000L
        const val SUSTAINED_SAMPLE_INTERVAL_MS = 1_000L
        const val POSITION_RESET_THRESHOLD_MS = 1_000L
        const val SUSTAINED_FLAC_TAG = "SpatialFlacSustained"
        const val SUSTAINED_FLAC_ASSET = "audio/flac_192000_24_stereo_silence_15s.flac.b64"
        const val SUSTAINED_FLAC_FILE_NAME = "spatial-sustained-192000-24-silence-15s.flac"
        val FLAC_MAGIC = byteArrayOf(
            'f'.code.toByte(), 'L'.code.toByte(), 'a'.code.toByte(), 'C'.code.toByte()
        )
    }
}
