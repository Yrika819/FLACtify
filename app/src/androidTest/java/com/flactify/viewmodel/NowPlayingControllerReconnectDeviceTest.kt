package com.flactify.viewmodel

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.flactify.PlaybackService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Gate 3 reconnect check against the real FoundationClosure PlaybackService and
 * PlayerViewModel. It invokes the disconnect callback registered on the live
 * MediaController, leaving the session/player running so reconnect can validate
 * the authoritative paused snapshot instead of testing service recovery.
 */
@RunWith(AndroidJUnit4::class)
class NowPlayingControllerReconnectDeviceTest {

    private var viewModel: PlayerViewModel? = null
    private var scope: CoroutineScope? = null
    private var toneFile: File? = null

    @After
    fun tearDown() {
        scope?.cancel()
        viewModel?.let { model -> onMain { model.cleanup() } }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.stopService(Intent(context, PlaybackService::class.java))
        toneFile?.delete()
    }

    @Test
    fun reconnectPublishesCurrentSnapshotAndRejectsOldControllerCallbacks() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val model = PlayerViewModel()
        viewModel = model
        onMain { model.initController(context) }
        await("initial Media3 controller") {
            model.nowPlayingUiState.value.session == SessionConnectionState.CONNECTED
        }

        val tone = File(context.cacheDir, "gate3-controller-reconnect.wav")
        toneFile = tone
        writeSyntheticTone(tone, durationSeconds = 12)
        val uri = Uri.fromFile(tone)
        val extractorField = PlayerViewModel::class.java.getDeclaredField("metadataExtractor")
            .apply { isAccessible = true }
        val extractor = extractorField.get(model) as TrackMetadataExtractor
        extractor.fileExtensionCache[uri] = "wav"
        val track = TrackData(
            title = "Reconnect Synthetic Tone",
            artist = "Gate 3 Fixture",
            album = "Synthetic Test Audio",
            originalIndex = 0,
            uri = uri
        )

        onMain { model.playAllTrack(context, listOf(track), 0) }
        await("prepared synthetic track with seekable duration") {
            val state = model.nowPlayingUiState.value
            state.current?.identity?.uri == uri &&
                state.playbackState == PlaybackState.READY &&
                state.position.durationMs != null &&
                state.position.isSeekable &&
                state.isPlaying
        }
        onMain {
            model.seekTo(2_000L)
            model.playPause()
        }
        await("paused seek position") {
            val state = model.nowPlayingUiState.value
            !state.isPlaying && state.position.positionMs >= 2_000L
        }

        val controllerField = PlayerViewModel::class.java.getDeclaredField("playbackController")
            .apply { isAccessible = true }
        val playbackController = controllerField.get(model) as PlaybackController
        val oldController = requireNotNull(playbackController.player)
        val oldPlayerListener = PlaybackController::class.java.getDeclaredField("listener")
            .apply { isAccessible = true }
            .get(playbackController) as Player.Listener
        val oldSessionGeneration = model.nowPlayingUiState.value.controllerGeneration

        val snapshots = CopyOnWriteArrayList<NowPlayingUiState>()
        val collectorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = collectorScope
        collectorScope.launch { model.nowPlayingUiState.collect(snapshots::add) }

        // MediaController.Listener is the callback installed by PlaybackController's
        // MediaController.Builder. Invoking it drives the production reconnect path while
        // preserving the real Media3 session and paused ExoPlayer state.
        val disconnectCallback = MediaController::class.java.getDeclaredField("listener")
            .apply { isAccessible = true }
            .get(oldController) as MediaController.Listener
        onMain { disconnectCallback.onDisconnected(oldController) }

        await("RECONNECTING publication") {
            model.nowPlayingUiState.value.session == SessionConnectionState.RECONNECTING
        }
        await("new controller with synchronized snapshot") {
            val state = model.nowPlayingUiState.value
            state.session == SessionConnectionState.CONNECTED &&
                state.controllerGeneration > oldSessionGeneration
        }

        val connectedSnapshot = snapshots.firstOrNull {
            it.session == SessionConnectionState.CONNECTED &&
                it.controllerGeneration > oldSessionGeneration
        }
        assertNotNull("CONNECTED must be emitted after the resynchronized snapshot", connectedSnapshot)
        val connected = requireNotNull(connectedSnapshot)
        assertEquals(uri, connected.current?.identity?.uri)
        assertEquals("Reconnect Synthetic Tone", connected.current?.title)
        assertEquals("Gate 3 Fixture", connected.current?.artist)
        assertEquals(PlaybackState.READY, connected.playbackState)
        assertTrue("paused state must be retained", !connected.isPlaying)
        assertTrue("duration must be resolved", (connected.position.durationMs ?: 0L) > 0L)
        assertTrue("track must remain seekable", connected.position.isSeekable)
        assertTrue(
            "reconnect position must retain the paused seek",
            connected.position.positionMs in 2_000L..2_250L
        )

        val stateBeforeStaleCallback = model.nowPlayingUiState.value
        oldPlayerListener.onMediaItemTransition(
            MediaItem.Builder()
                .setMediaId("stale-old-controller-item")
                .setUri(Uri.parse("file:///stale-old-controller-item.wav"))
                .build(),
            Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED
        )
        assertEquals(stateBeforeStaleCallback, model.nowPlayingUiState.value)
    }

    private fun await(label: String, timeoutMs: Long = 20_000L, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(50L)
        }
        throw AssertionError("Timed out waiting for $label")
    }

    private fun writeSyntheticTone(file: File, durationSeconds: Int) {
        val sampleRate = 44_100
        val channels = 2
        val frames = sampleRate * durationSeconds
        val pcmBytes = frames * channels * Short.SIZE_BYTES
        val data = ByteBuffer.allocate(44 + pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
        data.put("RIFF".toByteArray(Charsets.US_ASCII))
        data.putInt(36 + pcmBytes)
        data.put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
        data.putInt(16)
        data.putShort(1)
        data.putShort(channels.toShort())
        data.putInt(sampleRate)
        data.putInt(sampleRate * channels * Short.SIZE_BYTES)
        data.putShort((channels * Short.SIZE_BYTES).toShort())
        data.putShort(16)
        data.put("data".toByteArray(Charsets.US_ASCII))
        data.putInt(pcmBytes)
        repeat(frames) { frame ->
            val phase = 2.0 * Math.PI * 440.0 * frame / sampleRate
            val sample = (Short.MAX_VALUE * 0.2 * kotlin.math.sin(phase)).toInt().toShort()
            data.putShort(sample)
            data.putShort(sample)
        }
        file.writeBytes(data.array())
    }

    private fun onMain(block: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    }
}
