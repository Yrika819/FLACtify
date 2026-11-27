package com.flactify.viewmodel

import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Phase 6A playback-time contract: an unresolved duration is explicitly unknown (never a fake
 * 00:00 or the previous item's length), a genuine zero length stays zero, and seeking is only
 * allowed once the duration is known.
 */
class PlayerViewModelPlaybackTimeTest {

    @Test
    fun `duration is unknown until a report resolves it`() {
        val viewModel = PlayerViewModel()
        try {
            assertNull(viewModel.duration.value)

            viewModel.applyPlaybackProgress(0L, null)
            assertNull(viewModel.duration.value)
            assertEquals(0L, viewModel.currentPosition.value)

            viewModel.applyPlaybackProgress(4_000L, 212_000L)
            assertEquals(212_000L, viewModel.duration.value ?: -1L)
            assertEquals(4_000L, viewModel.currentPosition.value)
        } finally {
            viewModel.cleanup()
        }
    }

    @Test
    fun `a transient unresolved duration keeps the last resolved value within a track`() {
        val viewModel = PlayerViewModel()
        try {
            viewModel.applyPlaybackProgress(1_000L, 212_000L)

            // Media3 can briefly report C.TIME_UNSET while the same item rebuffers or
            // re-prepares. The last authoritative length must survive inside that item.
            viewModel.applyPlaybackProgress(2_000L, null)

            assertEquals(212_000L, viewModel.duration.value ?: -1L)
            assertEquals(2_000L, viewModel.currentPosition.value)
        } finally {
            viewModel.cleanup()
        }
    }

    @Test
    fun `a media item transition drops the previous item's resolved duration`() {
        val viewModel = PlayerViewModel()
        try {
            viewModel.applyPlaybackProgress(4_000L, 212_000L)

            viewModel.handlePlaybackMediaItemTransition(
                mockk(relaxed = true),
                androidx.media3.common.MediaMetadata.EMPTY,
                null
            )

            assertNull(viewModel.duration.value)
            assertEquals(0L, viewModel.currentPosition.value)
        } finally {
            viewModel.cleanup()
        }
    }

    @Test
    fun `idle playback drops the resolved duration`() {
        val viewModel = PlayerViewModel()
        try {
            viewModel.applyPlaybackProgress(4_000L, 212_000L)

            viewModel.onPlaybackIdle()

            assertNull(viewModel.duration.value)
            assertEquals(0L, viewModel.currentPosition.value)
        } finally {
            viewModel.cleanup()
        }
    }

    @Test
    fun `a zero duration is a resolved length and not unknown`() {
        val viewModel = PlayerViewModel()
        try {
            viewModel.applyPlaybackProgress(0L, 0L)

            assertEquals(0L, viewModel.duration.value ?: -1L)
        } finally {
            viewModel.cleanup()
        }
    }

    @Test
    fun `position is clamped and never negative`() {
        val viewModel = PlayerViewModel()
        try {
            viewModel.applyPlaybackProgress(-1_000L, null)

            assertEquals(0L, viewModel.currentPosition.value)
        } finally {
            viewModel.cleanup()
        }
    }

    @Test
    fun `seek is ignored while the duration is unknown and clamped once known`() {
        val viewModel = PlayerViewModel()
        val controller = mockk<PlaybackController>(relaxed = true)
        setField(viewModel, "playbackController", controller)
        try {
            viewModel.seekTo(30_000L)
            verify(exactly = 0) { controller.seekTo(any()) }

            (getField(viewModel, "_duration") as MutableStateFlow<Long?>).value = 120_000L
            viewModel.seekTo(150_000L)
            verify(exactly = 1) { controller.seekTo(120_000L) }
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
}
