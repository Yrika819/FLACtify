package com.flactify.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerViewModelNowPlayingStateTest {
    @Test
    fun `changing track URI clears previous artwork lyrics and audio details immediately`() {
        val viewModel = PlayerViewModel()
        val oldUri = mockk<Uri>()
        val newUri = mockk<Uri>()
        val oldArtwork = mockk<Bitmap>()
        setField(viewModel, "detailsUri", oldUri)
        (getField(viewModel, "_albumArt") as MutableStateFlow<Bitmap?>).value = oldArtwork
        (getField(viewModel, "_lyrics") as MutableStateFlow<List<Pair<Long, String>>>).value =
            listOf(1_000L to "old lyric")
        (getField(viewModel, "_audioInfo") as MutableStateFlow<String>).value = "FLAC | 96kHz"
        (getField(viewModel, "_bluetoothCodecInfo") as MutableStateFlow<String>).value = "Old route"

        PlayerViewModel::class.java.getDeclaredMethod(
            "updateTrackDetails",
            Context::class.java,
            Uri::class.java,
            Boolean::class.javaPrimitiveType
        ).apply {
            isAccessible = true
            invoke(viewModel, mockk<Context>(relaxed = true), newUri, false)
        }

        assertNull(viewModel.albumArt.value)
        assertEquals(emptyList<Pair<Long, String>>(), viewModel.lyrics.value)
        assertEquals("", viewModel.audioInfo.value)
        assertEquals("", viewModel.bluetoothCodecInfo.value)

        viewModel.cleanup()
    }

    @Test
    fun `track identity uses media id and URI rather than display metadata`() {
        val first = TrackIdentity("one", uri("content://library/one"))
        val sameTextDifferentUri = TrackIdentity("two", uri("content://library/two"))
        val sameMediaIdDifferentUri = TrackIdentity("one", uri("content://library/one-copy"))
        assertNotEquals(first, sameTextDifferentUri)
        assertNotEquals(first, sameMediaIdDifferentUri)
        assertNotEquals(first.stableKey, sameTextDifferentUri.stableKey)
    }

    @Test
    fun `A to B to A advances a fresh transition generation each time`() {
        val viewModel = PlayerViewModel()
        val context = mockk<Context>(relaxed = true)
        val trackA = uri("content://library/a")
        val trackB = uri("content://library/b")
        try {
            viewModel.handlePlaybackMediaItemTransition(
                context, androidx.media3.common.MediaMetadata.Builder().setTitle("A").build(), trackA
            )
            val firstAGeneration = viewModel.nowPlayingUiState.value.transitionGeneration
            viewModel.handlePlaybackMediaItemTransition(
                context, androidx.media3.common.MediaMetadata.Builder().setTitle("B").build(), trackB
            )
            viewModel.handlePlaybackMediaItemTransition(
                context, androidx.media3.common.MediaMetadata.Builder().setTitle("A").build(), trackA
            )

            assertEquals(firstAGeneration + 2L, viewModel.nowPlayingUiState.value.transitionGeneration)
            assertSame(trackA, viewModel.nowPlayingUiState.value.current?.identity?.uri)
            assertEquals("A", viewModel.nowPlayingUiState.value.current?.title)
        } finally {
            viewModel.cleanup()
        }
    }

    @Test
    fun `artwork completion from an earlier generation is ignored after a track change`() {
        val viewModel = PlayerViewModel()
        val context = mockk<Context>(relaxed = true)
        val trackA = uri("content://library/a")
        val trackB = uri("content://library/b")
        try {
            viewModel.handlePlaybackMediaItemTransition(
                context, androidx.media3.common.MediaMetadata.EMPTY, trackA
            )
            val oldIdentity = viewModel.nowPlayingUiState.value.current!!.identity
            val oldGeneration = viewModel.nowPlayingUiState.value.transitionGeneration
            viewModel.handlePlaybackMediaItemTransition(
                context, androidx.media3.common.MediaMetadata.EMPTY, trackB
            )
            val currentArtwork = viewModel.nowPlayingUiState.value.current!!.artwork

            viewModel.setArtworkState(
                oldIdentity,
                oldGeneration,
                ArtworkState.Available(uri("content://art/old"), 1L)
            )

            assertEquals(currentArtwork, viewModel.nowPlayingUiState.value.current?.artwork)
            assertSame(trackB, viewModel.nowPlayingUiState.value.current?.identity?.uri)
        } finally {
            viewModel.cleanup()
        }
    }

    @Test
    fun `pausing retains current identity and metadata`() {
        val viewModel = PlayerViewModel()
        val uri = uri("content://library/paused")
        try {
            viewModel.handlePlaybackMediaItemTransition(
                mockk(relaxed = true),
                androidx.media3.common.MediaMetadata.Builder().setTitle("Paused track").build(),
                uri
            )
            viewModel.applyPlaybackPlayingState(false)

            assertSame(uri, viewModel.nowPlayingUiState.value.current?.identity?.uri)
            assertEquals("Paused track", viewModel.nowPlayingUiState.value.current?.title)
            assertEquals(false, viewModel.nowPlayingUiState.value.isPlaying)
        } finally {
            viewModel.cleanup()
        }
    }

    @Test
    fun `empty queue is represented by a null current track`() {
        val viewModel = PlayerViewModel()
        try {
            assertNull(viewModel.nowPlayingUiState.value.current)
            viewModel.handlePlaybackMediaItemTransition(
                mockk(relaxed = true), androidx.media3.common.MediaMetadata.EMPTY, null
            )

            assertNull(viewModel.nowPlayingUiState.value.current)
            assertEquals(1L, viewModel.nowPlayingUiState.value.transitionGeneration)
        } finally {
            viewModel.cleanup()
        }
    }

    @Test
    fun `controller reconnection advances its generation and resynchronizes session status`() {
        val viewModel = PlayerViewModel()
        try {
            viewModel.updateSessionConnection(SessionConnectionState.RECONNECTING, 1L)
            viewModel.updateSessionConnection(SessionConnectionState.CONNECTED, 2L)

            assertEquals(SessionConnectionState.CONNECTED, viewModel.nowPlayingUiState.value.session)
            assertEquals(2L, viewModel.nowPlayingUiState.value.controllerGeneration)
        } finally {
            viewModel.cleanup()
        }
    }

    @Test
    fun `A to B to A rejects first A delayed completion`() {
        val viewModel = PlayerViewModel()
        val context = mockk<Context>(relaxed = true)
        val trackA = uri("content://library/a")
        try {
            viewModel.handlePlaybackMediaItemTransition(context, MediaMetadata.EMPTY, trackA)
            val firstIdentity = viewModel.nowPlayingUiState.value.current!!.identity
            val firstGeneration = viewModel.nowPlayingUiState.value.transitionGeneration
            viewModel.handlePlaybackMediaItemTransition(context, MediaMetadata.EMPTY, uri("content://library/b"))
            viewModel.handlePlaybackMediaItemTransition(context, MediaMetadata.EMPTY, trackA)

            val latest = viewModel.nowPlayingUiState.value
            assertEquals(firstGeneration + 2, latest.transitionGeneration)
            val currentArtwork = latest.current?.artwork
            viewModel.setArtworkState(
                firstIdentity,
                firstGeneration,
                ArtworkState.Available(uri("content://art/stale"), firstGeneration)
            )
            assertEquals(currentArtwork, viewModel.nowPlayingUiState.value.current?.artwork)
        } finally {
            viewModel.cleanup()
        }
    }

    @Test
    fun `reconnecting invalidates pending results and publishes a complete connected snapshot`() {
        val viewModel = PlayerViewModel()
        val itemUri = uri("content://library/current")
        val item = mockMediaItem("current", itemUri, MediaMetadata.Builder().setTitle("item title").build())
        val controller = mockk<androidx.media3.session.MediaController>(relaxed = true)
        every { controller.currentMediaItem } returns item
        every { controller.mediaMetadata } returns MediaMetadata.Builder()
            .setTitle("session title").setArtist("artist").setAlbumTitle("album")
            .setArtworkUri(uri("content://art/current")).build()
        every { controller.playbackState } returns Player.STATE_READY
        every { controller.playerError } returns null
        every { controller.isPlaying } returns false
        every { controller.currentPosition } returns 42_000L
        every { controller.duration } returns 240_000L
        every { controller.isCurrentMediaItemSeekable } returns true
        every { controller.shuffleModeEnabled } returns true
        every { controller.repeatMode } returns Player.REPEAT_MODE_ALL
        try {
            viewModel.handlePlaybackMediaItemTransition(mockk(relaxed = true), MediaMetadata.EMPTY, itemUri)
            val beforeDisconnectGeneration = viewModel.nowPlayingUiState.value.transitionGeneration
            viewModel.updateSessionConnection(SessionConnectionState.RECONNECTING, 1L)
            assertTrue(viewModel.nowPlayingUiState.value.transitionGeneration > beforeDisconnectGeneration)
            viewModel.resynchronizeNowPlayingState(controller, 2L)

            val snapshot = viewModel.nowPlayingUiState.value
            assertEquals(SessionConnectionState.CONNECTED, snapshot.session)
            assertEquals(PlaybackState.READY, snapshot.playbackState)
            assertEquals("session title", snapshot.current?.title)
            assertEquals("artist", snapshot.current?.artist)
            assertEquals("album", snapshot.current?.album)
            assertEquals(42_000L, snapshot.position.positionMs)
            assertEquals(240_000L, snapshot.position.durationMs)
            assertEquals(true, snapshot.position.isSeekable)
            assertEquals(true, snapshot.shuffleEnabled)
            assertEquals(Player.REPEAT_MODE_ALL, snapshot.repeatMode)
        } finally {
            viewModel.cleanup()
        }
    }

    @Test
    fun `media metadata notification is reflected instead of stale item metadata`() {
        val viewModel = PlayerViewModel()
        val trackUri = uri("content://library/current")
        val item = mockMediaItem(
            "current",
            trackUri,
            MediaMetadata.Builder().setTitle("stale title").setArtist("stale artist").build()
        )
        val notified = MediaMetadata.Builder().setTitle("fresh title").setArtist("fresh artist")
            .setAlbumTitle("fresh album").setArtworkUri(uri("content://art/fresh")).build()
        try {
            viewModel.handleCurrentMediaItemChanged(mockk(relaxed = true), item, false, notified)
            viewModel.applyPlaybackState(PlaybackState.ERROR)
            viewModel.handleCurrentMediaItemChanged(mockk(relaxed = true), item, false, notified)

            val track = viewModel.nowPlayingUiState.value.current!!
            assertEquals("fresh title", track.title)
            assertEquals("fresh artist", track.artist)
            assertEquals("fresh album", track.album)
            assertEquals("fresh title", viewModel.currentTrackName.value)
            assertEquals(PlaybackState.ERROR, viewModel.nowPlayingUiState.value.playbackState)
            val artUri = uri("content://art/fresh")
            viewModel.setArtworkState(
                track.identity,
                viewModel.nowPlayingUiState.value.transitionGeneration,
                ArtworkState.Available(artUri, 1L)
            )
            val artwork = viewModel.nowPlayingUiState.value.current?.artwork as ArtworkState.Available
            assertSame(artUri, artwork.artworkUri)
            assertEquals(ArtworkState.Available.Source.MEDIA3, artwork.source)
        } finally {
            viewModel.cleanup()
        }
    }

    @Test
    fun `position ticks do not emit from content projection`() = kotlinx.coroutines.runBlocking {
        val viewModel = PlayerViewModel()
        val nextEmission = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeoutOrNull(50L) { viewModel.nowPlayingContentState.drop(1).first() }
        }
        viewModel.applyPlaybackProgress(250L, null)
        try {
            assertNull(nextEmission.await())
        } finally {
            viewModel.cleanup()
        }
    }

    private fun setField(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true; set(target, value) }
    }

    private fun getField(target: Any, name: String): Any =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
            ?: error("$name was null")

    private fun uri(value: String): Uri {
        val uri = mockk<Uri>(relaxed = true)
        every { uri.toString() } returns value
        every { uri.equals(any()) } answers { firstArg<Any>() === uri }
        every { uri.hashCode() } returns value.hashCode()
        return uri
    }

    private fun mockMediaItem(mediaId: String, uri: Uri, metadata: MediaMetadata): MediaItem {
        return MediaItem.Builder().setMediaId(mediaId).setUri(uri).setMediaMetadata(metadata).build()
    }
}
