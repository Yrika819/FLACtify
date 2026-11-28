package com.flactify.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.flactify.viewmodel.PlaybackState

class MiniPlayerTest {
    @Test
    fun `mini player is hidden when disabled or no track is current`() {
        assertFalse(shouldShowMiniPlayer(enabled = false, currentScreen = "library", hasCurrentTrack = true))
        assertFalse(shouldShowMiniPlayer(enabled = true, currentScreen = "library", hasCurrentTrack = false))
    }

    @Test
    fun `mini player is hidden on full player and splash screens`() {
        assertFalse(shouldShowMiniPlayer(enabled = true, currentScreen = "player", hasCurrentTrack = true))
        assertFalse(shouldShowMiniPlayer(enabled = true, currentScreen = "splash", hasCurrentTrack = true))
    }

    @Test
    fun `mini player remains visible on browsing screens including paused tracks`() {
        // Visibility depends on current-item presence, not isPlaying.
        assertTrue(shouldShowMiniPlayer(enabled = true, currentScreen = "library", hasCurrentTrack = true))
        assertTrue(shouldShowMiniPlayer(enabled = true, currentScreen = "settings", hasCurrentTrack = true))
    }

    @Test
    fun `buffering and playback errors are shown alongside artist metadata`() {
        assertTrue(miniPlayerArtistLabel("Artist", PlaybackState.BUFFERING).contains("読み込み中"))
        assertTrue(miniPlayerArtistLabel("Artist", PlaybackState.ERROR).contains("再生エラー"))
        assertTrue(miniPlayerArtistLabel("Artist", PlaybackState.READY) == "Artist")
    }
}
