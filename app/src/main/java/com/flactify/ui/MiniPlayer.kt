package com.flactify.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.flactify.viewmodel.ArtworkState
import com.flactify.viewmodel.NowPlayingUiState
import com.flactify.viewmodel.PlayerViewModel
import com.flactify.viewmodel.PlaybackState

/** Internal rollout switch; no user setting or persisted flag is introduced. */
internal object MiniPlayerFeature {
    const val ENABLED = true
}

internal fun shouldShowMiniPlayer(
    enabled: Boolean,
    currentScreen: String,
    hasCurrentTrack: Boolean
): Boolean = enabled && currentScreen != "player" && currentScreen != "splash" && hasCurrentTrack

internal fun miniPlayerArtistLabel(artist: String, playbackState: PlaybackState): String = when (playbackState) {
    PlaybackState.BUFFERING -> "$artist · 読み込み中"
    PlaybackState.ERROR -> "$artist · 再生エラー"
    else -> artist
}

@Composable
internal fun MiniPlayer(
    viewModel: PlayerViewModel,
    nowPlaying: NowPlayingUiState,
    onOpenFullPlayer: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cachedAlbumArt by viewModel.albumArt.collectAsState()
    val current = nowPlaying.current ?: return
    val title = current.title?.takeIf(String::isNotBlank) ?: "不明な曲"
    val artist = current.artist?.takeIf(String::isNotBlank) ?: "不明なアーティスト"
    val artwork = current.artwork as? ArtworkState.Available
    val cachedArtwork = cachedAlbumArt.takeIf {
        artwork?.source == ArtworkState.Available.Source.TRACK_CACHE
    }
    val artworkUri = artwork?.artworkUri?.takeIf {
        artwork.source == ArtworkState.Available.Source.MEDIA3
    }

    Surface(
        modifier = modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF29292D),
        contentColor = Color.White,
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .clickable(role = Role.Button, onClickLabel = "フルプレイヤーを開く", onClick = onOpenFullPlayer)
                .padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            MiniPlayerArtwork(
                bitmap = cachedArtwork,
                artworkUri = artworkUri,
                description = "$title のアルバムアート"
            )
            androidx.compose.foundation.layout.Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = miniPlayerArtistLabel(artist, nowPlaying.playbackState),
                    color = Color(0xFFCCCCD0),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(
                onClick = viewModel::playPause,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = if (nowPlaying.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (nowPlaying.isPlaying) "一時停止" else "再生",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    }
}

@Composable
private fun MiniPlayerArtwork(
    bitmap: Bitmap?,
    artworkUri: android.net.Uri?,
    description: String
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(7.dp)),
        contentAlignment = Alignment.Center
    ) {
        when {
            bitmap != null -> Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = description,
                modifier = Modifier.size(48.dp),
                contentScale = ContentScale.Crop
            )
            artworkUri != null -> AsyncImage(
                model = artworkUri,
                contentDescription = description,
                modifier = Modifier.size(48.dp),
                contentScale = ContentScale.Crop
            )
            else -> {
                Surface(color = Color(0xFF414148), shape = RoundedCornerShape(7.dp)) {
                    Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.MusicNote,
                            contentDescription = description,
                            tint = Color(0xFFDDDEE3),
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }
            }
        }
    }
}
