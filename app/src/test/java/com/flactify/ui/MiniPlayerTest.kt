package com.flactify.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.flactify.viewmodel.PlaybackState

class MiniPlayerTest {
    @Test
    fun `mini player artwork colors are blended into a persistently dark background`() {
        val brightArtworkColor = miniPlayerBackgroundColor(0xFFFFFFFF.toInt(), hasArtwork = true)
        val redArtworkColor = miniPlayerBackgroundColor(0xFFEB2A5C.toInt(), hasArtwork = true)
        val greenArtworkColor = miniPlayerBackgroundColor(0xFF3CD264.toInt(), hasArtwork = true)

        assertTrue(brightArtworkColor.red < 0.4f)
        assertTrue(brightArtworkColor.green < 0.4f)
        assertTrue(brightArtworkColor.blue < 0.4f)
        assertTrue(redArtworkColor.red > redArtworkColor.blue)
        assertTrue(greenArtworkColor.green > greenArtworkColor.red)
        assertTrue(contrastRatio(Color.White, brightArtworkColor) >= 4.5)
        assertTrue(contrastRatio(Color.White, redArtworkColor) >= 4.5)
        assertTrue(contrastRatio(Color.White, greenArtworkColor) >= 4.5)
        assertTrue(contrastRatio(composite(Color.White.copy(alpha = 0.76f), brightArtworkColor), brightArtworkColor) >= 4.5)
        assertTrue(contrastRatio(composite(Color.White.copy(alpha = 0.76f), redArtworkColor), redArtworkColor) >= 4.5)
        assertTrue(contrastRatio(composite(Color.White.copy(alpha = 0.76f), greenArtworkColor), greenArtworkColor) >= 4.5)
    }

    @Test
    fun `mini player uses the fixed dark gray fallback until current artwork is available`() {
        val fallback = miniPlayerBackgroundColor(0xFFFF0000.toInt(), hasArtwork = false)

        assertTrue(fallback.toArgb() == 0xFF29292D.toInt())
        assertTrue(miniPlayerBackgroundColor(null, hasArtwork = true).toArgb() == 0xFF29292D.toInt())
    }

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

    private fun contrastRatio(foreground: Color, background: Color): Double {
        val lighter = maxOf(relativeLuminance(foreground), relativeLuminance(background))
        val darker = minOf(relativeLuminance(foreground), relativeLuminance(background))
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun composite(foreground: Color, background: Color): Color = Color(
        red = foreground.red * foreground.alpha + background.red * (1f - foreground.alpha),
        green = foreground.green * foreground.alpha + background.green * (1f - foreground.alpha),
        blue = foreground.blue * foreground.alpha + background.blue * (1f - foreground.alpha)
    )

    private fun relativeLuminance(color: Color): Double {
        fun linear(channel: Float): Double {
            val value = channel.toDouble()
            return if (value <= 0.04045) value / 12.92 else Math.pow((value + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * linear(color.red) + 0.7152 * linear(color.green) + 0.0722 * linear(color.blue)
    }
}
