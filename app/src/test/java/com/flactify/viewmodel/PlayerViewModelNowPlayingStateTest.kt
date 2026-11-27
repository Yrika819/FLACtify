package com.flactify.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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
            assertEquals(trackA, viewModel.nowPlayingUiState.value.current?.identity?.uri)
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
                ArtworkState.Available(null, 1L)
            )

            assertEquals(currentArtwork, viewModel.nowPlayingUiState.value.current?.artwork)
            assertEquals(trackB, viewModel.nowPlayingUiState.value.current?.identity?.uri)
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

            assertEquals(uri, viewModel.nowPlayingUiState.value.current?.identity?.uri)
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

    private fun setField(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true; set(target, value) }
    }

    private fun getField(target: Any, name: String): Any =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
            ?: error("$name was null")

    private fun uri(@Suppress("UNUSED_PARAMETER") value: String): Uri = mockk(relaxed = true)
}
