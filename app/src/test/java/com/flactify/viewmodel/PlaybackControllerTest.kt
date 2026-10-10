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

    @Test
    fun `setPlaylist skips rebuild when the requested queue is already loaded identically`() {
        val tracks = listOf(track("One", "content://one"), track("Two", "content://two"))
        val player = mediaControllerWithQueue(tracks)
        val controller = newController(player)

        controller.setPlaylist(tracks, { "flac" }, { "audio/flac" }, forceRandom = false, resolveTargetIndex = { _, _, _ -> 0 })

        verify(exactly = 0) { player.clearMediaItems() }
        verify(exactly = 0) { player.addMediaItems(any<List<androidx.media3.common.MediaItem>>()) }
        verify(exactly = 0) { player.prepare() }
        verify(exactly = 0) { player.seekTo(any<Int>(), any<Long>()) }
    }

    @Test
    fun `setPlaylist still applies explicit shuffle and repeat on an identical queue`() {
        val tracks = listOf(track("One", "content://one"))
        val player = mediaControllerWithQueue(tracks)
        val controller = newController(player)

        controller.setPlaylist(
            tracks, { "flac" }, { "audio/flac" }, forceRandom = false,
            resolveTargetIndex = { _, _, _ -> 0 },
            restoreShuffle = true, restoreRepeat = Player.REPEAT_MODE_ALL
        )

        verify(exactly = 1) { player.shuffleModeEnabled = true }
        verify(exactly = 1) { player.repeatMode = Player.REPEAT_MODE_ALL }
        verify(exactly = 0) { player.clearMediaItems() }
        verify(exactly = 0) { player.prepare() }
    }

    @Test
    fun `setPlaylist rebuilds once when a track is added`() {
        val initial = listOf(track("One", "content://one"))
        val player = mediaControllerWithQueue(initial)
        val controller = newController(player)

        controller.setPlaylist(
            initial + track("Two", "content://two"),
            { "flac" }, { "audio/flac" }, forceRandom = false, resolveTargetIndex = { _, _, _ -> 0 }
        )

        verify(exactly = 1) { player.clearMediaItems() }
        verify(exactly = 1) { player.addMediaItems(any<List<androidx.media3.common.MediaItem>>()) }
        verify(exactly = 1) { player.prepare() }
    }

    @Test
    fun `setPlaylist rebuilds when only the metadata changes at the same count`() {
        val loaded = listOf(track("One", "content://one"), track("Two", "content://two"))
        val player = mediaControllerWithQueue(loaded)
        val controller = newController(player)

        // Same URIs and order, but the first title was edited in place.
        val relabelled = listOf(track("One (remastered)", "content://one"), track("Two", "content://two"))
        controller.setPlaylist(relabelled, { "flac" }, { "audio/flac" }, forceRandom = false, resolveTargetIndex = { _, _, _ -> 0 })

        verify(exactly = 1) { player.clearMediaItems() }
        verify(exactly = 1) { player.prepare() }
    }

    @Test
    fun `setPlaylist rebuilds when a single track is swapped at the same count`() {
        val loaded = listOf(track("One", "content://one"), track("Two", "content://two"))
        val player = mediaControllerWithQueue(loaded)
        val controller = newController(player)

        val swapped = listOf(track("One", "content://one"), track("Elsewhere", "content://elsewhere"))
        controller.setPlaylist(swapped, { "flac" }, { "audio/flac" }, forceRandom = false, resolveTargetIndex = { _, _, _ -> 0 })

        verify(exactly = 1) { player.clearMediaItems() }
        verify(exactly = 1) { player.prepare() }
    }

    private fun newController(player: MediaController): PlaybackController {
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
        return controller
    }

    private fun mediaControllerWithQueue(tracks: List<TrackData>): MediaController {
        val player = mockk<MediaController>(relaxed = true)
        every { player.currentMediaItem } returns null
        every { player.isPlaying } returns false
        every { player.mediaItemCount } returns tracks.size
        tracks.forEachIndexed { index, data ->
            every { player.getMediaItemAt(index) } returns mediaItemFor(data)
        }
        return player
    }

    private fun mediaItemFor(data: TrackData): androidx.media3.common.MediaItem =
        androidx.media3.common.MediaItem.Builder()
            .setMediaId(data.uri.toString())
            .setUri(data.uri)
            .setMimeType("audio/flac")
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(data.title)
                    .setArtist(data.artist)
                    .setAlbumTitle(data.album)
                    .build()
            )
            .build()

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
