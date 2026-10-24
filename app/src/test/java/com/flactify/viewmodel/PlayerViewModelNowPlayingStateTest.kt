package com.flactify.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
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
            Uri::class.java
        ).apply {
            isAccessible = true
            invoke(viewModel, mockk<Context>(relaxed = true), newUri)
        }

        assertNull(viewModel.albumArt.value)
        assertEquals(emptyList<Pair<Long, String>>(), viewModel.lyrics.value)
        assertEquals("", viewModel.audioInfo.value)
        assertEquals("", viewModel.bluetoothCodecInfo.value)

        viewModel.cleanup()
    }

    private fun setField(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true; set(target, value) }
    }

    private fun getField(target: Any, name: String): Any =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
            ?: error("$name was null")
}
