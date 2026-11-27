package com.flactify

import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.flactify.audio.PlaybackDiagnosticsBus
import com.flactify.audio.spatial.SpatialAudioMode
import com.google.common.util.concurrent.ListenableFuture
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.math.sin

/**
 * Phase 6A R2: limited real-device auto-advance check with synthetic audio.
 *
 * Real WAV files are decoded and played through PlaybackService, so this exercises the same
 * ExoPlayer timeline/auto-advance path as production instead of only inspecting the shuffle order.
 * The queue is intentionally short (three 1.2 s tones) to keep the run bounded.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackAutoAdvanceDeviceTest {

    private val instrumentation: Instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    private lateinit var appContext: Context
    private lateinit var controllerFuture: ListenableFuture<MediaController>
    private lateinit var controller: MediaController
    private lateinit var items: List<MediaItem>
    private val advanced = CopyOnWriteArrayList<Int>()
    private val transitions = CopyOnWriteArrayList<Pair<Int, Int>>()

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val index = controller.currentMediaItemIndex
            if (index !in 0 until TRACK_COUNT) return
            transitions += index to reason
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
            ) {
                advanced += index
            }
        }
    }

    @Before
    fun setUp() {
        appContext = instrumentation.targetContext
        val files = (0 until TRACK_COUNT).map(::ensureTone)
        items = files.mapIndexed { index, file ->
            MediaItem.Builder()
                .setMediaId("auto-advance-$index")
                .setUri(Uri.fromFile(file))
                .build()
        }

        appContext.stopService(Intent(appContext, PlaybackService::class.java))
        val token = SessionToken(
            appContext,
            ComponentName(appContext, PlaybackService::class.java)
        )
        controllerFuture = onMainThread {
            MediaController.Builder(appContext, token).buildAsync()
        }
        controller = controllerFuture.get(CONTROLLER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        onMainThread { controller.addListener(listener) }
        setSpatialOff()
        onMainThread {
            controller.shuffleModeEnabled = true
            controller.repeatMode = Player.REPEAT_MODE_OFF
        }
    }

    @After
    fun tearDown() {
        if (::controller.isInitialized) {
            onMainThread {
                controller.pause()
                controller.release()
            }
        }
        if (::appContext.isInitialized) {
            appContext.stopService(Intent(appContext, PlaybackService::class.java))
        }
    }

    @Test
    fun shuffleRepeatOffPlaysEveryTrackOnceThenStops() {
        val startIndex = 1
        val order = startQueue(startIndex, requestRound = true)
        awaitEnded()

        assertEquals(startIndex, order.first())
        assertEquals(order, advanced)
        assertEquals((0 until TRACK_COUNT).toSet(), advanced.toSet())
        assertEquals(TRACK_COUNT, advanced.size)
        assertFalse(onMainThread { controller.isPlaying })
        val reported = PlaybackDiagnosticsBus.playbackOrder.value
        assertTrue(reported.shuffleModeEnabled)
        assertEquals(order.last(), reported.shuffleOrderLastIndex)
        assertEquals(order.last(), onMainThread { controller.currentMediaItemIndex })
    }

    @Test
    fun shuffleRepeatAllStartsANewRoundAfterTheLastTrack() {
        onMainThread { controller.repeatMode = Player.REPEAT_MODE_ALL }
        val startIndex = 2
        val order = startQueue(startIndex, requestRound = true)

        await("the first item of the next round") {
            advanced.size >= TRACK_COUNT + 1
        }
        onMainThread { controller.pause() }

        assertEquals(startIndex, order.first())
        assertEquals(order, advanced.take(TRACK_COUNT))
        assertEquals(startIndex, advanced[TRACK_COUNT])
        assertEquals(
            Player.REPEAT_MODE_ALL,
            PlaybackDiagnosticsBus.playbackOrder.value.repeatMode
        )
    }

    @Test
    fun shuffleRepeatOneRepeatsTheCurrentTrack() {
        onMainThread { controller.repeatMode = Player.REPEAT_MODE_ONE }
        val startIndex = 1
        startQueue(startIndex, requestRound = true)

        await("repeat transition") {
            transitions.any { it.second == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT }
        }
        onMainThread { controller.pause() }

        val repeats = transitions.filter {
            it.second == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
        }
        assertTrue(repeats.isNotEmpty())
        assertTrue(repeats.all { it.first == startIndex })
        assertTrue(advanced.all { it == startIndex })
    }

    @Test
    fun manualNextAndPreviousFollowTheShuffleOrder() {
        val startIndex = 1
        val order = startQueue(startIndex, requestRound = true)
        val expectedNext = onMainThread { controller.nextMediaItemIndex }
        assertNotEquals(startIndex, expectedNext)
        assertEquals(order[1], expectedNext)

        onMainThread { controller.seekToNextMediaItem() }
        await("manual next to $expectedNext") {
            onMainThread { controller.currentMediaItemIndex == expectedNext }
        }

        onMainThread { controller.seekToPreviousMediaItem() }
        await("manual previous back to $startIndex") {
            onMainThread { controller.currentMediaItemIndex == startIndex }
        }
        assertFalse(onMainThread { controller.hasPreviousMediaItem() })
    }

    @Test
    fun normalPlaybackStillUsesTimelineOrder() {
        onMainThread {
            controller.shuffleModeEnabled = false
            controller.repeatMode = Player.REPEAT_MODE_OFF
        }
        val order = startQueue(0, requestRound = false)
        awaitEnded()

        assertEquals(listOf(0, 1, 2), order)
        assertEquals(order, advanced)
        assertFalse(onMainThread { controller.isPlaying })
        assertFalse(PlaybackDiagnosticsBus.playbackOrder.value.shuffleModeEnabled)
    }

    private fun startQueue(startIndex: Int, requestRound: Boolean): List<Int> {
        advanced.clear()
        transitions.clear()
        onMainThread {
            controller.setMediaItems(items, startIndex, 0L)
            controller.prepare()
        }
        await("queue of $TRACK_COUNT items") {
            onMainThread { controller.mediaItemCount == TRACK_COUNT }
        }
        await("current item $startIndex") {
            onMainThread { controller.currentMediaItemIndex == startIndex }
        }
        val order = if (requestRound) {
            val request = PlaybackShuffleRound.requestFor(
                items.map(MediaItem::mediaId),
                startIndex
            )!!
            val result = sendRound(request)
            assertEquals(SessionResult.RESULT_SUCCESS, result.resultCode)
            // hasPreviousMediaItem() is always true when repeat ALL can wrap, so only repeat
            // OFF/ONE can prove the head through that predicate. The round result plus the
            // second-round assertion cover repeat ALL.
            if (onMainThread { controller.repeatMode } != Player.REPEAT_MODE_ALL) {
                await("round head at item $startIndex") {
                    onMainThread { !controller.hasPreviousMediaItem() }
                }
            }
            readShuffleOrder()
        } else {
            (0 until TRACK_COUNT).toList()
        }
        advanced += startIndex
        onMainThread { controller.play() }
        return order
    }

    private fun sendRound(request: PlaybackShuffleRound.Request): SessionResult =
        onMainThread {
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
                    putInt(
                        PlaybackService.SESSION_EXTRA_SHUFFLE_ROUND_ITEM_COUNT,
                        request.itemCount
                    )
                    putInt(
                        PlaybackService.SESSION_EXTRA_SHUFFLE_ROUND_START_INDEX,
                        request.startIndex
                    )
                }
            )
        }.get(CONTROLLER_TIMEOUT_SECONDS, TimeUnit.SECONDS)

    private fun setSpatialOff() {
        val result = onMainThread {
            controller.sendCustomCommand(
                SessionCommand(SpatialAudioMode.SESSION_COMMAND_ACTION, Bundle()),
                Bundle().apply {
                    putString(SpatialAudioMode.SESSION_EXTRA_MODE, SpatialAudioMode.OFF.wireValue)
                    putInt(SpatialAudioMode.SESSION_EXTRA_HRTF_MIX_PERCENT, 100)
                }
            )
        }.get(CONTROLLER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        assertEquals(SessionResult.RESULT_SUCCESS, result.resultCode)
    }

    private fun awaitEnded() {
        await("playback end") {
            onMainThread { controller.playbackState == Player.STATE_ENDED }
        }
    }

    private fun readShuffleOrder(): List<Int> {
        val startIndex = onMainThread { controller.currentMediaItemIndex }
        val indexes = mutableListOf(startIndex)
        onMainThread { controller.pause() }
        repeat(TRACK_COUNT - 1) {
            val next = onMainThread { controller.nextMediaItemIndex }
            assertNotEquals(C.INDEX_UNSET, next)
            onMainThread { controller.seekToDefaultPosition(next) }
            await("walk to item $next") {
                onMainThread { controller.currentMediaItemIndex == next }
            }
            indexes += next
        }
        onMainThread { controller.seekToDefaultPosition(startIndex) }
        await("walk back to item $startIndex") {
            onMainThread { controller.currentMediaItemIndex == startIndex }
        }
        return indexes
    }

    private fun ensureTone(index: Int): File {
        val file = File(appContext.cacheDir, "auto-advance-tone-$index.wav")
        if (file.length() > 0L) return file
        val sampleRate = 44_100
        val sampleCount = sampleRate * TONE_MILLIS / 1_000
        val dataSize = sampleCount * 2
        val buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        buffer.putInt(36 + dataSize)
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
        buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(16)
        buffer.putShort(1)
        buffer.putShort(1)
        buffer.putInt(sampleRate)
        buffer.putInt(sampleRate * 2)
        buffer.putShort(2)
        buffer.putShort(16)
        buffer.put("data".toByteArray(Charsets.US_ASCII))
        buffer.putInt(dataSize)
        val frequency = 440.0 + index * 110.0
        for (sample in 0 until sampleCount) {
            val fade = minOf(sample, sampleCount - sample, 441) / 441.0
            val value = sin(2.0 * Math.PI * frequency * sample / sampleRate) * 0.08 * fade
            buffer.putShort((value * Short.MAX_VALUE).toInt().toShort())
        }
        FileOutputStream(file).use { it.write(buffer.array()) }
        return file
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
        const val TRACK_COUNT = 3
        const val TONE_MILLIS = 1_200
        const val CONTROLLER_TIMEOUT_SECONDS = 10L
        const val TIMEOUT_MS = 20_000L
        const val POLL_INTERVAL_MS = 40L
    }
}
