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
import com.flactify.PlaybackShuffleRound
import com.flactify.audio.PlaybackDiagnosticsBus
import com.google.common.util.concurrent.ListenableFuture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
                        roundArguments(items.map(MediaItem::mediaId), startIndex)
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
                // SpatialPlaybackQueueState drains the final HRTF tail only at
                // shuffleOrder.lastIndex. The service publishes the player's actual terminal
                // index; it must be the last item of this round, not an interior boundary.
                val reported = PlaybackDiagnosticsBus.playbackOrder.value
                assertTrue(reported.shuffleModeEnabled)
                assertEquals(visited.last(), reported.shuffleOrderLastIndex)
            }
        } finally {
            controller?.let { connected -> onMainThread { connected.release() } }
        }
    }

    @Test
    fun duplicateStaleAndMalformedRoundCommandsDoNotRebuildTheOrder() {
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

            val items = (0 until QUEUE_SIZE).map { index ->
                MediaItem.Builder()
                    .setMediaId("shuffle-dedupe-$index")
                    .setUri(Uri.parse("content://com.flactify.shuffle-test/dedupe-$index"))
                    .build()
            }
            val startIndex = 1
            onMainThread { connected.setMediaItems(items, startIndex, 0L) }
            await("queue of $QUEUE_SIZE items") {
                onMainThread { connected.mediaItemCount == QUEUE_SIZE }
            }
            await("current item $startIndex") {
                onMainThread { connected.currentMediaItemIndex == startIndex }
            }

            val request = PlaybackShuffleRound.requestFor(
                items.map(MediaItem::mediaId),
                startIndex
            )!!
            val first = sendRound(connected, request)
            assertEquals(SessionResult.RESULT_SUCCESS, first.resultCode)
            await("round head at item $startIndex") {
                onMainThread { !connected.hasPreviousMediaItem() }
            }

            // A duplicate of the command that is already applied must report success without
            // rebuilding the order (the extras say rebuilt=false).
            val duplicate = sendRound(connected, request)
            assertEquals(SessionResult.RESULT_SUCCESS, duplicate.resultCode)
            assertFalse(
                duplicate.extras.getBoolean(
                    PlaybackService.SESSION_RESULT_EXTRA_SHUFFLE_ROUND_REBUILT
                )
            )
            assertEquals(startIndex, onMainThread { connected.currentMediaItemIndex })

            // Advance once, then replay the original (now stale) command. It must not pull the
            // round head back to the old start item.
            val next = onMainThread { connected.nextMediaItemIndex }
            onMainThread { connected.seekToDefaultPosition(next) }
            await("advance to item $next") {
                onMainThread { connected.currentMediaItemIndex == next }
            }
            val staleItem = sendRound(connected, request)
            assertEquals(SessionResult.RESULT_ERROR_INVALID_STATE, staleItem.resultCode)
            assertFalse(
                staleItem.extras.getBoolean(
                    PlaybackService.SESSION_RESULT_EXTRA_SHUFFLE_ROUND_REBUILT
                )
            )
            assertEquals(
                PlaybackShuffleRound.Decision.STALE_ITEM.name,
                staleItem.extras.getString(
                    PlaybackService.SESSION_RESULT_EXTRA_SHUFFLE_ROUND_SKIP_REASON
                )
            )
            assertTrue(onMainThread { connected.hasPreviousMediaItem() })
            assertEquals(next, onMainThread { connected.currentMediaItemIndex })

            // A request for a different queue must not rebuild the order either.
            val staleQueue = sendRound(
                connected,
                request.copy(queueFingerprint = request.queueFingerprint + 1)
            )
            assertEquals(SessionResult.RESULT_ERROR_INVALID_STATE, staleQueue.resultCode)
            assertEquals(
                PlaybackShuffleRound.Decision.STALE_QUEUE.name,
                staleQueue.extras.getString(
                    PlaybackService.SESSION_RESULT_EXTRA_SHUFFLE_ROUND_SKIP_REASON
                )
            )
            assertTrue(onMainThread { connected.hasPreviousMediaItem() })

            // The service checks shuffle mode itself instead of trusting the controller.
            onMainThread { connected.setShuffleModeEnabled(false) }
            val disabled = sendRound(connected, request)
            assertEquals(SessionResult.RESULT_ERROR_INVALID_STATE, disabled.resultCode)
            assertEquals(
                PlaybackShuffleRound.Decision.SHUFFLE_DISABLED.name,
                disabled.extras.getString(
                    PlaybackService.SESSION_RESULT_EXTRA_SHUFFLE_ROUND_SKIP_REASON
                )
            )

            // Commands from an older client without the request identity are rejected rather
            // than silently reshuffling at the current item.
            val malformed = onMainThread {
                connected.sendCustomCommand(
                    SessionCommand(
                        PlaybackService.SESSION_COMMAND_START_SHUFFLE_ROUND,
                        Bundle()
                    ),
                    Bundle()
                )
            }.get(CONTROLLER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            assertEquals(SessionResult.RESULT_ERROR_BAD_VALUE, malformed.resultCode)
        } finally {
            controller?.let { connected -> onMainThread { connected.release() } }
        }
    }

    @Test
    fun externalControllerEnablingShuffleStartsTheRoundAtTheCurrentItem() {
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
            onMainThread {
                connected.shuffleModeEnabled = false
                connected.repeatMode = androidx.media3.common.Player.REPEAT_MODE_OFF
            }

            val startIndex = 2
            val items = (0 until QUEUE_SIZE).map { index ->
                MediaItem.Builder()
                    .setMediaId("shuffle-external-$index")
                    .setUri(Uri.parse("content://com.flactify.shuffle-test/external-$index"))
                    .build()
            }
            onMainThread { connected.setMediaItems(items, startIndex, 0L) }
            await("queue of $QUEUE_SIZE items") {
                onMainThread { connected.mediaItemCount == QUEUE_SIZE }
            }
            await("current item $startIndex") {
                onMainThread { connected.currentMediaItemIndex == startIndex }
            }

            // This is the notification/Bluetooth/other-controller path: no app command is sent,
            // the service listener must start the round itself.
            onMainThread { connected.setShuffleModeEnabled(true) }
            await("external shuffle round head at item $startIndex") {
                onMainThread { !connected.hasPreviousMediaItem() }
            }

            val visited = mutableListOf(startIndex)
            while (true) {
                val next = onMainThread { connected.nextMediaItemIndex }
                if (next == C.INDEX_UNSET) break
                onMainThread { connected.seekToDefaultPosition(next) }
                await("advance to item $next") {
                    onMainThread { connected.currentMediaItemIndex == next }
                }
                visited += next
            }
            assertEquals(startIndex, visited.first())
            assertEquals((0 until QUEUE_SIZE).toList(), visited.sorted())
            assertEquals(
                visited.last(),
                PlaybackDiagnosticsBus.playbackOrder.value.shuffleOrderLastIndex
            )
        } finally {
            controller?.let { connected -> onMainThread { connected.release() } }
        }
    }

    private fun roundArguments(mediaIds: List<String>, startIndex: Int): Bundle {
        val request = PlaybackShuffleRound.requestFor(mediaIds, startIndex)!!
        return Bundle().apply {
            putLong(
                PlaybackService.SESSION_EXTRA_SHUFFLE_ROUND_QUEUE_FINGERPRINT,
                request.queueFingerprint
            )
            putInt(PlaybackService.SESSION_EXTRA_SHUFFLE_ROUND_ITEM_COUNT, request.itemCount)
            putInt(PlaybackService.SESSION_EXTRA_SHUFFLE_ROUND_START_INDEX, request.startIndex)
        }
    }

    private fun sendRound(
        controller: MediaController,
        request: PlaybackShuffleRound.Request
    ): SessionResult = onMainThread {
        controller.sendCustomCommand(
            SessionCommand(
                PlaybackService.SESSION_COMMAND_START_SHUFFLE_ROUND,
                Bundle()
            ),
            Bundle().apply {
                putLong(
                    PlaybackService.SESSION_EXTRA_SHUFFLE_ROUND_QUEUE_FINGERPRINT,
                    request.queueFingerprint
                )
                putInt(PlaybackService.SESSION_EXTRA_SHUFFLE_ROUND_ITEM_COUNT, request.itemCount)
                putInt(PlaybackService.SESSION_EXTRA_SHUFFLE_ROUND_START_INDEX, request.startIndex)
            }
        )
    }.get(CONTROLLER_TIMEOUT_SECONDS, TimeUnit.SECONDS)

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
