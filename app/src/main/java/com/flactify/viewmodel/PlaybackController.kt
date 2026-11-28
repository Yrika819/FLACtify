package com.flactify.viewmodel

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.flactify.PlaybackService
import com.flactify.PlaybackShuffleRound
import com.flactify.audio.spatial.SpatialAudioMode
import com.flactify.audio.spatial.SpatialHrtfMixPercent
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One item in the service-owned Media3 playlist, projected for queue UI. */
data class PlaybackQueueItem(
    val mediaId: String,
    val uri: Uri?,
    val title: String,
    val artist: String,
    val album: String
)

/** Authoritative queue snapshot read from [MediaController], never maintained independently. */
data class PlaybackQueueSnapshot(
    val items: List<PlaybackQueueItem> = emptyList(),
    val currentIndex: Int = -1,
    val shuffleEnabled: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF
)

enum class QueueActionResult {
    SUCCESS,
    NO_CONTROLLER,
    INVALID_INDEX,
    CURRENT_ITEM_PROTECTED,
    SHUFFLE_NEXT_UNAVAILABLE,
    SHUFFLE_REORDER_UNAVAILABLE
}

@OptIn(UnstableApi::class)
class PlaybackController(
    private val scope: CoroutineScope,
    private val onPlayingChanged: (Boolean) -> Unit,
    private val onMetadataChanged: (MediaMetadata, Uri?) -> Unit,
    private val onTracksChanged: (Tracks, Uri?) -> Unit,
    private val onShuffleChanged: (Boolean) -> Unit,
    private val onRepeatChanged: (Int) -> Unit,
    /** Reports the position and the duration in milliseconds. The duration is null while the
     *  player has not resolved it yet (Media3 [androidx.media3.common.C.TIME_UNSET]), so an
     *  unknown duration never looks like a genuine zero-length item. */
    private val onProgress: (Long, Long?) -> Unit,
    private val onMediaItemTransition: (MediaMetadata, Uri?) -> Unit = { _, _ -> },
    /** The queue vanished or the player became idle; the current item no longer owns its time. */
    private val onPlaybackIdle: () -> Unit = {},
    /** Full Media3 item snapshot used by the shared now-playing state. */
    private val onCurrentMediaItemChanged: (MediaItem?, Boolean, MediaMetadata?) -> Unit = { _, _, _ -> },
    private val onPlaybackStateChanged: (Int, Boolean) -> Unit = { _, _ -> },
    private val onPlaybackError: () -> Unit = {},
    private val onSeekabilityChanged: (Boolean) -> Unit = {},
    private val onSessionConnectionChanged: (SessionConnectionState, Long) -> Unit = { _, _ -> },
    private val onAuthoritativeSnapshot: (MediaController, Long) -> Unit = { _, _ -> },
    private val onQueueChanged: (PlaybackQueueSnapshot) -> Unit = {}
) {
    var player: MediaController? = null
        private set

    private var listener: Player.Listener? = null
    private var progressJob: Job? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var reconnectJob: Job? = null
    private var appContext: Context? = null
    private var readyCallback: ((MediaController) -> Unit)? = null
    private var failureCallback: (() -> Unit)? = null
    private var reconnecting = false
    private var reconnectDelayMs = 500L
    private var controllerGeneration = 0L
    private var initialized = false
    private var cleanedUp = false

    fun initialize(context: Context, onReady: (MediaController) -> Unit, onFailure: () -> Unit) {
        if (initialized || cleanedUp) return
        initialized = true
        appContext = context.applicationContext
        readyCallback = onReady
        failureCallback = onFailure
        connectController()
    }

    private fun connectController() {
        val context = appContext ?: return
        if (cleanedUp || controllerFuture != null || player != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val mediaControllerListener = object : MediaController.Listener {
            override fun onDisconnected(controller: MediaController) {
                if (player !== controller || cleanedUp) return
                listener?.let(controller::removeListener)
                listener = null
                player = null
                progressJob?.cancel()
                progressJob = null
                controllerGeneration++
                reconnecting = true
                onSessionConnectionChanged(SessionConnectionState.RECONNECTING, controllerGeneration)
                controllerFuture?.let(MediaController::releaseFuture)
                controllerFuture = null
                scheduleReconnect()
            }
        }
        val future = MediaController.Builder(context, token)
            .setListener(mediaControllerListener)
            .buildAsync()
        controllerFuture = future
        future.addListener({
            try {
                val mediaController = future.get()
                if (cleanedUp) {
                    return@addListener
                }
                player = mediaController
                reconnectDelayMs = 500L
                val wasReconnect = reconnecting
                reconnectJob?.cancel()
                reconnectJob = null
                val controllerListener = createControllerListener(mediaController)
                listener = controllerListener
                mediaController.addListener(controllerListener)
                controllerGeneration++
                reconnecting = false
                // syncState publishes one authoritative snapshot with CONNECTED atomically. The
                // ready callback performs the same sync on first connection before publishing it.
                if (wasReconnect) syncState() else readyCallback?.invoke(mediaController)
                onSessionConnectionChanged(SessionConnectionState.CONNECTED, controllerGeneration)
            } catch (_: Exception) {
                controllerFuture = null
                if (!cleanedUp && reconnecting) {
                    scheduleReconnect()
                } else if (!cleanedUp) {
                    controllerGeneration++
                    onSessionConnectionChanged(SessionConnectionState.DISCONNECTED, controllerGeneration)
                    failureCallback?.invoke()
                }
            }
        }, MoreExecutors.directExecutor())
    }

    private fun scheduleReconnect() {
        if (cleanedUp || reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            delay(reconnectDelayMs)
            reconnectDelayMs = (reconnectDelayMs * 2L).coerceAtMost(8_000L)
            reconnectJob = null
            connectController()
        }
    }

    /**
     * The controller listener is a separate factory so the reporting contract (transition,
     * discontinuity, READY/ENDED duration publication) can be exercised without a live session.
     */
    internal fun createControllerListener(mediaController: MediaController): Player.Listener =
        object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                if (player !== mediaController) return
                onPlayingChanged(playing)
                if (playing) startProgressUpdates()
            }

            override fun onMediaMetadataChanged(metadata: MediaMetadata) {
                if (player !== mediaController) return
                onMetadataChanged(metadata, currentUri())
                onCurrentMediaItemChanged(mediaController.currentMediaItem, false, metadata)
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (player !== mediaController) return
                onCurrentMediaItemChanged(mediaItem, true, mediaController.mediaMetadata)
                handleMediaItemTransition(mediaItem)
                publishPositionAndDuration(mediaController)
                publishQueue(mediaController)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (player !== mediaController) return
                this@PlaybackController.onPlaybackStateChanged.invoke(
                    playbackState,
                    mediaController.playerError != null
                )
                // A prepared (READY) or finished (ENDED) item has an authoritative duration even
                // while playback is paused, so the UI must not wait for the 250 ms playing loop.
                when (playbackState) {
                    Player.STATE_READY, Player.STATE_ENDED -> publishPositionAndDuration(mediaController)
                    // IDLE means the item was stopped or removed; keep no duration for it.
                    Player.STATE_IDLE -> {
                        if (mediaController.currentMediaItem == null) {
                            onCurrentMediaItemChanged(null, false, mediaController.mediaMetadata)
                        }
                        onPlaybackIdle()
                    }
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                if (player === mediaController) this@PlaybackController.onPlaybackError()
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                if (player !== mediaController) return
                publishPositionAndDuration(mediaController, newPosition.positionMs)
            }

            override fun onTracksChanged(tracks: Tracks) {
                if (player !== mediaController) return
                onTracksChanged(tracks, currentUri())
            }

            override fun onTimelineChanged(
                timeline: androidx.media3.common.Timeline,
                reason: Int
            ) {
                if (player !== mediaController) return
                publishQueue(mediaController)
            }

            override fun onShuffleModeEnabledChanged(enabled: Boolean) {
                if (player !== mediaController) return
                onShuffleChanged(enabled)
            }

            override fun onRepeatModeChanged(mode: Int) {
                if (player !== mediaController) return
                onRepeatChanged(mode)
            }
        }

    fun syncState(onIdle: () -> Unit = {}) {
        player?.let { mediaController ->
            onAuthoritativeSnapshot(mediaController, controllerGeneration)
            onPlayingChanged(mediaController.isPlaying)
            onPlaybackStateChanged(mediaController.playbackState, mediaController.playerError != null)
            onSeekabilityChanged(mediaController.isCurrentMediaItemSeekable)
            if (mediaController.currentMediaItem == null) {
                onCurrentMediaItemChanged(null, false, mediaController.mediaMetadata)
                onPlaybackIdle()
                onIdle()
            } else {
                onCurrentMediaItemChanged(mediaController.currentMediaItem, false, mediaController.mediaMetadata)
                onMetadataChanged(mediaController.mediaMetadata, currentUri())
            }
            publishPositionAndDuration(mediaController)
            onShuffleChanged(mediaController.shuffleModeEnabled)
            onRepeatChanged(mediaController.repeatMode)
            publishQueue(mediaController)
            if (mediaController.isPlaying) startProgressUpdates()
        }
    }

    private fun publishQueue(mediaController: MediaController) {
        onQueueChanged(queueSnapshot(mediaController))
    }

    private fun queueSnapshot(mediaController: MediaController): PlaybackQueueSnapshot {
        val items = (0 until mediaController.mediaItemCount).map { index ->
            val item = mediaController.getMediaItemAt(index)
            val metadata = item.mediaMetadata
            PlaybackQueueItem(
                mediaId = item.mediaId,
                uri = item.localConfiguration?.uri,
                title = metadata.title?.toString().orEmpty(),
                artist = metadata.artist?.toString().orEmpty(),
                album = metadata.albumTitle?.toString().orEmpty()
            )
        }
        return PlaybackQueueSnapshot(
            items = items,
            currentIndex = mediaController.currentMediaItemIndex,
            shuffleEnabled = mediaController.shuffleModeEnabled,
            repeatMode = mediaController.repeatMode
        )
    }

    /** Returns the queue directly from MediaController, or an empty snapshot if offline. */
    fun queueSnapshot(): PlaybackQueueSnapshot = player?.let(::queueSnapshot) ?: PlaybackQueueSnapshot()

    fun setPlaylist(
        tracks: List<TrackData>,
        fileExtension: (Uri) -> String,
        mimeType: (String) -> String,
        forceRandom: Boolean,
        resolveTargetIndex: (List<TrackData>, Uri?, Boolean) -> Int,
        restoreUri: Uri? = null,
        restorePosition: Long = 0L,
        restoreShuffle: Boolean? = null,
        restoreRepeat: Int? = null
    ) {
        val mediaController = player ?: return
        val desiredItems = buildMediaItems(tracks, fileExtension, mimeType)

        // The startup restore loads the cached queue first and then re-applies the validated
        // queue. When nothing material changed the requested queue is already loaded in the same
        // order with the same metadata, so tearing it down and calling prepare() again would only
        // discard the prepared window and the restored playback position. Playlist identity is
        // compared by URI, order and metadata, never by count alone.
        if (mediaController.hasMediaItems(desiredItems)) {
            restoreShuffle?.let { mediaController.shuffleModeEnabled = it }
            restoreRepeat?.let { mediaController.repeatMode = it }
            // Only an explicit restore needs a round on an unchanged queue. A library refresh
            // with no queue change must leave the active round alone; enabling shuffle is already
            // handled by the service listener.
            if (restoreShuffle == true) {
                startShuffleRoundFromCurrentItem(
                    mediaController,
                    PlaybackShuffleRound.requestFor(
                        desiredItems.map(MediaItem::mediaId),
                        mediaController.currentMediaItemIndex
                    )
                )
            }
            return
        }

        val currentUri = currentUri()
        val wasPlaying = mediaController.isPlaying
        val currentPosition = mediaController.currentPosition
        mediaController.clearMediaItems()
        mediaController.addMediaItems(desiredItems)
        mediaController.prepare()
        restoreShuffle?.let { mediaController.shuffleModeEnabled = it }
        restoreRepeat?.let { mediaController.repeatMode = it }
        if (tracks.isNotEmpty()) {
            val resumeIndex = restoreUri?.let { uri -> tracks.indexOfFirst { it.uri == uri } } ?: -1
            val targetIndex = if (resumeIndex >= 0) resumeIndex else resolveTargetIndex(tracks, currentUri, forceRandom)
            val effectiveIndex = if (resumeIndex >= 0) {
                mediaController.seekTo(targetIndex, restorePosition.coerceAtLeast(0L))
                targetIndex
            } else if (forceRandom || currentUri == null) {
                mediaController.seekTo(targetIndex, 0L)
                targetIndex
            } else {
                val newIndex = tracks.indexOfFirst { it.uri == currentUri }
                (
                    if (newIndex != -1) {
                        mediaController.seekTo(newIndex, currentPosition)
                        newIndex
                    } else {
                        mediaController.seekTo(targetIndex, 0L)
                        targetIndex
                    }
                ).also {
                    if (wasPlaying) mediaController.play()
                }
            }
            // The queue was rebuilt: make sure a shuffle round starts at the restored/selected item
            // instead of somewhere inside Media3's previous permutation. The request carries the
            // intended index because MediaController reflects session state asynchronously.
            if (restoreShuffle != false) {
                startShuffleRoundFromCurrentItem(
                    mediaController,
                    PlaybackShuffleRound.requestFor(
                        desiredItems.map(MediaItem::mediaId),
                        effectiveIndex
                    )
                )
            }
        }
    }

    private fun buildMediaItems(
        tracks: List<TrackData>,
        fileExtension: (Uri) -> String,
        mimeType: (String) -> String
    ): List<MediaItem> = tracks.map { track ->
        MediaItem.Builder()
            .setMediaId(track.uri.toString())
            .setUri(track.uri)
            .setMimeType(mimeType(fileExtension(track.uri)))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setArtist(track.artist)
                    .setAlbumTitle(track.album)
                    .build()
            )
            .build()
    }

    private fun buildMediaItem(
        track: TrackData,
        fileExtension: (Uri) -> String,
        mimeType: (String) -> String
    ): MediaItem = buildMediaItems(listOf(track), fileExtension, mimeType).single()

    /**
     * True when the controller already holds exactly [desired] in the same order and with the
     * same identity (URI, MIME type, title, artist, album). Comparing only the number of items is
     * not enough: a folder can swap one track for another without changing the count.
     */
    private fun androidx.media3.common.Player.hasMediaItems(desired: List<MediaItem>): Boolean {
        if (mediaItemCount != desired.size) return false
        for (index in 0 until mediaItemCount) {
            if (!getMediaItemAt(index).hasSameIdentityAs(desired[index])) return false
        }
        return true
    }

    private fun MediaItem.hasSameIdentityAs(other: MediaItem): Boolean {
        if ((localConfiguration?.uri?.toString() ?: mediaId) !=
            (other.localConfiguration?.uri?.toString() ?: other.mediaId)
        ) return false
        if (localConfiguration?.mimeType != other.localConfiguration?.mimeType) return false
        val a = mediaMetadata
        val b = other.mediaMetadata
        return a.title?.toString() == b.title?.toString() &&
            a.artist?.toString() == b.artist?.toString() &&
            a.albumTitle?.toString() == b.albumTitle?.toString()
    }

    fun playTracks(
        tracks: List<TrackData>,
        initialIndex: Int,
        fileExtension: (Uri) -> String,
        mimeType: (String) -> String
    ) {
        val mediaController = player ?: return
        val items = buildMediaItems(tracks, fileExtension, mimeType)
        mediaController.setMediaItems(items, initialIndex, 0L)
        mediaController.prepare()
        mediaController.play()
        startShuffleRoundFromCurrentItem(
            mediaController,
            PlaybackShuffleRound.requestFor(items.map(MediaItem::mediaId), initialIndex)
        )
    }

    fun playPause() { player?.let { if (it.playWhenReady) it.pause() else it.play() } }

    fun enqueueNext(
        track: TrackData,
        fileExtension: (Uri) -> String,
        mimeType: (String) -> String
    ): QueueActionResult {
        val mediaController = player ?: return QueueActionResult.NO_CONTROLLER
        if (mediaController.shuffleModeEnabled) return QueueActionResult.SHUFFLE_NEXT_UNAVAILABLE
        val currentIndex = mediaController.currentMediaItemIndex
        if (currentIndex !in 0 until mediaController.mediaItemCount) {
            return QueueActionResult.INVALID_INDEX
        }
        mediaController.addMediaItem(
            currentIndex + 1,
            buildMediaItem(track, fileExtension, mimeType)
        )
        return QueueActionResult.SUCCESS
    }

    fun enqueueAtEnd(
        track: TrackData,
        fileExtension: (Uri) -> String,
        mimeType: (String) -> String
    ): QueueActionResult {
        val mediaController = player ?: return QueueActionResult.NO_CONTROLLER
        mediaController.addMediaItem(
            mediaController.mediaItemCount,
            buildMediaItem(track, fileExtension, mimeType)
        )
        return QueueActionResult.SUCCESS
    }

    /** Removing the current item is blocked so this action cannot discard active playback. */
    fun removeQueueItem(index: Int): QueueActionResult {
        val mediaController = player ?: return QueueActionResult.NO_CONTROLLER
        if (index !in 0 until mediaController.mediaItemCount) return QueueActionResult.INVALID_INDEX
        if (index == mediaController.currentMediaItemIndex) {
            return QueueActionResult.CURRENT_ITEM_PROTECTED
        }
        mediaController.removeMediaItem(index)
        return QueueActionResult.SUCCESS
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int): QueueActionResult {
        val mediaController = player ?: return QueueActionResult.NO_CONTROLLER
        if (mediaController.shuffleModeEnabled) return QueueActionResult.SHUFFLE_REORDER_UNAVAILABLE
        if (fromIndex !in 0 until mediaController.mediaItemCount ||
            toIndex !in 0 until mediaController.mediaItemCount
        ) {
            return QueueActionResult.INVALID_INDEX
        }
        if (fromIndex == toIndex) return QueueActionResult.SUCCESS
        mediaController.moveMediaItem(fromIndex, toIndex)
        return QueueActionResult.SUCCESS
    }

    fun queueMediaIds(): List<String> = queueSnapshot().items.map { it.uri?.toString() ?: it.mediaId }
    fun seekTo(position: Long) {
        player?.let { mediaController ->
            // Unknown duration is not a zero-length item: only clamp when the duration is known.
            val duration = durationOrNull(mediaController.duration)
            val target = position.coerceAtLeast(0L).let { requested ->
                duration?.let { requested.coerceAtMost(it) } ?: requested
            }
            mediaController.seekTo(target)
            onProgress(target, duration)
        }
    }
    fun seekToNext() { player?.seekToNext() }
    fun seekToPrevious() { player?.seekToPrevious() }
    fun hasNextMediaItem(): Boolean = player?.hasNextMediaItem() == true
    fun hasPreviousMediaItem(): Boolean = player?.hasPreviousMediaItem() == true
    fun setShuffle(enabled: Boolean) {
        // The service starts the round from onShuffleModeEnabledChanged, which also covers
        // notification/Bluetooth/other-controller changes. Sending a command here as well made
        // the service rebuild the same round twice.
        player?.shuffleModeEnabled = enabled
    }

    /**
     * Asks the service to rebuild the active shuffle order with the current item first. Only the
     * service owns the ExoPlayer, and MediaController exposes no setShuffleOrder(), so this rides
     * on the same trusted-session-command pattern as the spatial audio command.
     */
    private fun startShuffleRoundFromCurrentItem(
        mediaController: MediaController,
        request: PlaybackShuffleRound.Request?
    ) {
        // The request describes the queue the app just loaded, not the asynchronously reflected
        // controller state. The service compares it with its authoritative queue and current item.
        if (request == null) return
        mediaController.sendCustomCommand(
            SessionCommand(PlaybackService.SESSION_COMMAND_START_SHUFFLE_ROUND, Bundle()),
            Bundle().apply {
                putLong(
                    PlaybackService.SESSION_EXTRA_SHUFFLE_ROUND_QUEUE_FINGERPRINT,
                    request.queueFingerprint
                )
                putInt(PlaybackService.SESSION_EXTRA_SHUFFLE_ROUND_ITEM_COUNT, request.itemCount)
                putInt(PlaybackService.SESSION_EXTRA_SHUFFLE_ROUND_START_INDEX, request.startIndex)
            }
        )
    }
    fun setRepeat(mode: Int) { player?.repeatMode = mode }
    fun pause() { player?.pause() }

    fun setSpatialAudioMode(
        mode: SpatialAudioMode,
        hrtfMixPercent: Int = SpatialHrtfMixPercent.DEFAULT_PERCENT
    ) = player?.sendCustomCommand(
        SessionCommand(SpatialAudioMode.SESSION_COMMAND_ACTION, Bundle.EMPTY),
        Bundle().apply {
            putString(SpatialAudioMode.SESSION_EXTRA_MODE, mode.wireValue)
            putInt(SpatialAudioMode.SESSION_EXTRA_HRTF_MIX_PERCENT, hrtfMixPercent)
        }
    )

    fun replaceMediaItem(uri: Uri, title: String, artist: String, album: String) {
        player?.let { mediaController ->
            for (index in 0 until mediaController.mediaItemCount) {
                val item = mediaController.getMediaItemAt(index)
                if (item.localConfiguration?.uri == uri) {
                    mediaController.replaceMediaItem(
                        index,
                        MediaItem.Builder()
                            .setMediaId(uri.toString())
                            .setUri(uri)
                            .setMimeType(item.localConfiguration?.mimeType ?: "audio/flac")
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setTitle(title)
                                    .setArtist(artist)
                                    .setAlbumTitle(album)
                                    .build()
                            )
                            .build()
                    )
                    return
                }
            }
        }
    }

    fun currentUri(): Uri? = player?.currentMediaItem?.localConfiguration?.uri

    internal fun handleMediaItemTransition(mediaItem: MediaItem?) {
        val metadata = mediaItem?.mediaMetadata ?: MediaMetadata.Builder().build()
        val uri = mediaItem?.localConfiguration?.uri ?: currentUri()
        onMediaItemTransition(metadata, uri)
    }

    private fun publishPositionAndDuration(mediaController: MediaController) {
        publishPositionAndDuration(mediaController, mediaController.currentPosition)
    }

    private fun publishPositionAndDuration(mediaController: MediaController, positionMs: Long) {
        onSeekabilityChanged(mediaController.isCurrentMediaItemSeekable)
        onProgress(positionMs.coerceAtLeast(0L), durationOrNull(mediaController.duration))
    }

    /** Media3 reports an unresolved duration as C.TIME_UNSET; a genuine zero stays zero. */
    private fun durationOrNull(durationMs: Long): Long? = durationMs.takeIf { it >= 0L }

    private fun startProgressUpdates() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (player?.isPlaying == true) {
                player?.let { publishPositionAndDuration(it) }
                delay(250)
            }
        }
    }

    fun cleanup() {
        if (cleanedUp) return
        cleanedUp = true
        progressJob?.cancel()
        progressJob = null
        listener?.let { player?.removeListener(it) }
        listener = null
        controllerFuture?.let(MediaController::releaseFuture)
        controllerFuture = null
        player = null
    }
}
