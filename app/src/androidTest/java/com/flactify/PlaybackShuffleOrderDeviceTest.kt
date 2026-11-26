package com.flactify

import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.util.concurrent.ListenableFuture
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * Phase 6A shuffle regression on a real ExoPlayer: after the service-owned reshuffle command, a
 * round that starts at an arbitrary queue item visits every item exactly once and then stops.
 *
 * The queue is not prepared and the items are never opened, so no audio is decoded or played; the
 * test only exercises the playlist order that media3 keeps for auto-advance.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackShuffleOrderDeviceTest {

    private val instrumentation: Instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun shuffleRoundFromAnyQueueItemCoversTheWholeQueueOnce() {
        val appContext = instrumentation.targetContext
        var controllerFuture: ListenableFuture<MediaController>? = null
        var controller: MediaController? = null
        try {
            appContext.stopService(Intent(appContext, PlaybackService::class.java))
            val token = SessionToken(
                appContext,
                ComponentName(appContext, PlaybackService::class.java)
            )
            controllerFuture = onMainThread {
                MediaController.Builder(appContext, token).buildAsync()
            }
            val connected = controllerFuture.get(CONTROLLER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            controller = connected
            onMainThread { connected.setShuffleModeEnabled(true) }

            for (startIndex in 0 until QUEUE_SIZE) {
                val items = (0 until QUEUE_SIZE).map { index ->
                    MediaItem.Builder()
                        .setMediaId("shuffle-order-$index")
                        .setUri(Uri.parse("content://com.flactify.shuffle-test/item-$index"))
                        .build()
                }
                onMainThread { connected.setMediaItems(items, startIndex, 0L) }
                await("queue of $QUEUE_SIZE items") {
                    onMainThread { connected.mediaItemCount == QUEUE_SIZE }
                }
                await("current item $startIndex") {
                    onMainThread { connected.currentMediaItemIndex == startIndex }
                }

                val result = onMainThread {
                    connected.sendCustomCommand(
                        SessionCommand(
                            PlaybackService.SESSION_COMMAND_START_SHUFFLE_ROUND,
                            Bundle()
                        ),
                        Bundle()
                    )
                }.get(CONTROLLER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                assertEquals(SessionResult.RESULT_SUCCESS, result.resultCode)

                await("round head at item $startIndex") {
                    onMainThread { !connected.hasPreviousMediaItem() }
                }

                val visited = mutableListOf(onMainThread { connected.currentMediaItemIndex })
                while (true) {
                    val next = onMainThread { connected.nextMediaItemIndex }
                    if (next == C.INDEX_UNSET) break
                    onMainThread { connected.seekToDefaultPosition(next) }
                    await("advance to item $next") {
                        onMainThread { connected.currentMediaItemIndex == next }
                    }
                    visited += next
                }

                assertEquals(
                    "round from $startIndex must start at the item playback started from",
                    startIndex,
                    visited.first()
                )
                assertEquals(
                    "round from $startIndex must visit every item exactly once",
                    (0 until QUEUE_SIZE).toList(),
                    visited.sorted()
                )
                assertEquals(QUEUE_SIZE, visited.size)
            }
        } finally {
            controller?.let { connected -> onMainThread { connected.release() } }
        }
    }

    private fun <T : Any> onMainThread(action: () -> T): T {
        lateinit var result: T
        instrumentation.runOnMainSync { result = action() }
        return result
    }

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            Thread.sleep(POLL_INTERVAL_MS)
        }
        throw AssertionError("Timed out waiting for $label")
    }

    private companion object {
        const val QUEUE_SIZE = 5
        const val CONTROLLER_TIMEOUT_SECONDS = 10L
        const val TIMEOUT_MS = 20_000L
        const val POLL_INTERVAL_MS = 50L
    }
}
