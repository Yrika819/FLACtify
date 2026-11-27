package com.flactify.viewmodel

import android.net.Uri
import android.graphics.Bitmap
import androidx.media3.common.Player

/** Immutable, Media3-backed state shared by the full player and future mini player. */
data class NowPlayingUiState(
    val current: CurrentTrack? = null,
    val transitionGeneration: Long = 0L,
    val playbackState: PlaybackState = PlaybackState.IDLE,
    val isPlaying: Boolean = false,
    val position: PositionState = PositionState(),
    val shuffleEnabled: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val session: SessionConnectionState = SessionConnectionState.CONNECTING,
    val controllerGeneration: Long = 0L
)

data class CurrentTrack(
    val identity: TrackIdentity,
    val title: String?,
    val artist: String?,
    val album: String?,
    val artwork: ArtworkState = ArtworkState.Loading
)

/** The media id and source URI jointly identify a track; display metadata is deliberately excluded. */
data class TrackIdentity(val mediaId: String, val uri: Uri) {
    val stableKey: String get() = "$mediaId\n${uri}"
}

data class PositionState(
    val positionMs: Long = 0L,
    val durationMs: Long? = null,
    val isSeekable: Boolean = false
)

enum class PlaybackState { IDLE, BUFFERING, READY, ENDED, ERROR }

sealed interface ArtworkState {
    data object Loading : ArtworkState

    /** A null URI represents artwork extracted from the track itself and held by the legacy image pipeline. */
    data class Available(val artworkUri: Uri?, val version: Long, val bitmap: Bitmap? = null) : ArtworkState

    data object Unavailable : ArtworkState
}

enum class SessionConnectionState { CONNECTING, CONNECTED, RECONNECTING, DISCONNECTED }

internal fun playbackStateOf(state: Int): PlaybackState = when (state) {
    Player.STATE_BUFFERING -> PlaybackState.BUFFERING
    Player.STATE_READY -> PlaybackState.READY
    Player.STATE_ENDED -> PlaybackState.ENDED
    Player.STATE_IDLE -> PlaybackState.IDLE
    else -> PlaybackState.ERROR
}
