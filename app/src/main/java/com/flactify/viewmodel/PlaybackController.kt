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
import com.flactify.audio.spatial.SpatialAudioMode
import com.flactify.audio.spatial.SpatialHrtfMixPercent
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
class PlaybackController(
    private val scope: CoroutineScope,
    private val onPlayingChanged: (Boolean) -> Unit,
    private val onMetadataChanged: (MediaMetadata, Uri?) -> Unit,
    private val onTracksChanged: (Tracks, Uri?) -> Unit,
    private val onShuffleChanged: (Boolean) -> Unit,
    private val onRepeatChanged: (Int) -> Unit,
    private val onProgress: (Long, Long) -> Unit
) {
    var player: MediaController? = null
        private set

    private var listener: Player.Listener? = null
    private var progressJob: Job? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var initialized = false
    private var cleanedUp = false

    fun initialize(context: Context, onReady: (MediaController) -> Unit, onFailure: () -> Unit) {
        if (initialized || cleanedUp) return
        initialized = true
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        controllerFuture = future
        future.addListener({
            try {
                val mediaController = future.get()
                if (cleanedUp) {
                    return@addListener
                }
                player = mediaController
                val controllerListener = object : Player.Listener {
                    override fun onIsPlayingChanged(playing: Boolean) {
                        onPlayingChanged(playing)
                        if (playing) startProgressUpdates()
                    }

                    override fun onMediaMetadataChanged(metadata: MediaMetadata) {
                        onMetadataChanged(metadata, currentUri())
                    }

                    override fun onTracksChanged(tracks: Tracks) {
                        onTracksChanged(tracks, currentUri())
                    }

                    override fun onShuffleModeEnabledChanged(enabled: Boolean) {
                        onShuffleChanged(enabled)
                    }

                    override fun onRepeatModeChanged(mode: Int) {
                        onRepeatChanged(mode)
                    }
                }
                listener = controllerListener
                mediaController.addListener(controllerListener)
                onReady(mediaController)
            } catch (_: Exception) {
                if (!cleanedUp) onFailure()
            }
        }, MoreExecutors.directExecutor())
    }

    fun syncState(onIdle: () -> Unit = {}) {
        player?.let { mediaController ->
            onPlayingChanged(mediaController.isPlaying)
            if (mediaController.currentMediaItem == null) {
                onIdle()
            } else {
                onMetadataChanged(mediaController.mediaMetadata, currentUri())
            }
            onProgress(mediaController.currentPosition, mediaController.duration.coerceAtLeast(0L))
            onShuffleChanged(mediaController.shuffleModeEnabled)
            onRepeatChanged(mediaController.repeatMode)
            if (mediaController.isPlaying) startProgressUpdates()
        }
    }

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
            if (resumeIndex >= 0) {
                mediaController.seekTo(targetIndex, restorePosition.coerceAtLeast(0L))
            } else if (forceRandom || currentUri == null) {
                mediaController.seekTo(targetIndex, 0L)
            } else {
                val newIndex = tracks.indexOfFirst { it.uri == currentUri }
                if (newIndex != -1) mediaController.seekTo(newIndex, currentPosition)
                else mediaController.seekTo(targetIndex, 0L)
                if (wasPlaying) mediaController.play()
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
        mediaController.setMediaItems(tracks.map { track ->
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
        }, initialIndex, 0L)
        mediaController.prepare()
        mediaController.play()
    }

    fun playPause() { player?.let { if (it.isPlaying) it.pause() else it.play() } }
    fun seekTo(position: Long) { player?.seekTo(position) }
    fun seekToNext() { player?.seekToNext() }
    fun seekToPrevious() { player?.seekToPrevious() }
    fun setShuffle(enabled: Boolean) { player?.shuffleModeEnabled = enabled }
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

    private fun startProgressUpdates() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (player?.isPlaying == true) {
                player?.let { onProgress(it.currentPosition, it.duration) }
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
