package com.flactify.viewmodel

import android.net.Uri
import androidx.media3.session.MediaController
import androidx.media3.common.Player
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.SettableFuture
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Job
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackControllerTest {
    @Test
    fun `playTracks builds media items and starts playback`() {
        val player = mockk<MediaController>(relaxed = true)
        every { player.currentMediaItem } returns null
        val controller = PlaybackController(
            scope = CoroutineScope(Dispatchers.Unconfined),
            onPlayingChanged = {},
            onMetadataChanged = { _, _ -> },
            onTracksChanged = { _, _ -> },
            onShuffleChanged = {},
            onRepeatChanged = {},
            onProgress = { _, _ -> }
        )
        setPlayer(controller, player)
        val tracks = listOf(track("One", "content://one"), track("Two", "content://two"))

        controller.playTracks(tracks, 1, { "flac" }, { "audio/flac" })

        verify(exactly = 1) { player.setMediaItems(any<List<androidx.media3.common.MediaItem>>(), 1, 0L) }
        verify(exactly = 1) { player.prepare() }
        verify(exactly = 1) { player.play() }
    }

    @Test
    fun `cleanup releases player reference`() {
        val player = mockk<MediaController>(relaxed = true)
        val listener = mockk<Player.Listener>()
        val progressJob = mockk<Job>(relaxed = true)
        val future = Futures.immediateFuture(player)
        val controller = PlaybackController(
            scope = CoroutineScope(Dispatchers.Unconfined),
            onPlayingChanged = {},
            onMetadataChanged = { _, _ -> },
            onTracksChanged = { _, _ -> },
            onShuffleChanged = {},
            onRepeatChanged = {},
            onProgress = { _, _ -> }
        )
        setPlayer(controller, player)
        setPrivateField(controller, "listener", listener)
        setPrivateField(controller, "progressJob", progressJob)
        setPrivateField(controller, "controllerFuture", future)
        mockkStatic(MediaController::class)

        try {
            controller.cleanup()
            controller.cleanup()

            assertNull(controller.player)
            verify(exactly = 1) { player.removeListener(listener) }
            verify(exactly = 1) { progressJob.cancel() }
            verify(exactly = 1) { MediaController.releaseFuture(future) }
        } finally {
            unmockkStatic(MediaController::class)
        }
    }

    @Test
    fun `cleanup cancels an in-flight controller future`() {
        val future = SettableFuture.create<MediaController>()
        val controller = PlaybackController(
            scope = CoroutineScope(Dispatchers.Unconfined),
            onPlayingChanged = {},
            onMetadataChanged = { _, _ -> },
            onTracksChanged = { _, _ -> },
            onShuffleChanged = {},
            onRepeatChanged = {},
            onProgress = { _, _ -> }
        )
        setPrivateField(controller, "controllerFuture", future)
        mockkStatic(MediaController::class)

        try {
            controller.cleanup()

            assertTrue(future.isCancelled)
            verify(exactly = 1) { MediaController.releaseFuture(future) }
        } finally {
            unmockkStatic(MediaController::class)
        }
    }

    @Test
    fun `syncState reports idle state when queue is empty`() {
        val player = mockk<MediaController>(relaxed = true)
        every { player.currentMediaItem } returns null
        every { player.isPlaying } returns false
        val controller = PlaybackController(
            scope = CoroutineScope(Dispatchers.Unconfined),
            onPlayingChanged = {},
            onMetadataChanged = { _, _ -> error("metadata must not be emitted while idle") },
            onTracksChanged = { _, _ -> },
            onShuffleChanged = {},
            onRepeatChanged = {},
            onProgress = { _, _ -> }
        )
        setPlayer(controller, player)
        var idleReported = false

        controller.syncState { idleReported = true }

        assertTrue(idleReported)
    }

    private fun setPlayer(controller: PlaybackController, player: MediaController) {
        setPrivateField(controller, "player", player)
    }

    private fun setPrivateField(controller: PlaybackController, name: String, value: Any?) {
        val field = PlaybackController::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(controller, value)
    }

    private fun track(title: String, uri: String) = TrackData(
        title = title,
        artist = "Artist",
        album = "Album",
        originalIndex = 0,
        uri = mockk<Uri>(relaxed = true).also { every { it.toString() } returns uri }
    )
}
