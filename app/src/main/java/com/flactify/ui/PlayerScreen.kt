package com.flactify.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import com.flactify.audio.PlaybackDiagnostics
import com.flactify.viewmodel.PlayerViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel,
    onSelectFolder: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenSettings: () -> Unit = {}
) {
    val trackName by viewModel.currentTrackName.collectAsState()
    val artist by viewModel.currentArtist.collectAsState()
    val albumArt by viewModel.albumArt.collectAsState()
    val lyrics by viewModel.lyrics.collectAsState()
    val audioInfo by viewModel.audioInfo.collectAsState()
    val bluetoothInfo by viewModel.bluetoothCodecInfo.collectAsState()
    val diagnostics by viewModel.diagnostics.collectAsState()
    val trackList by viewModel.trackList.collectAsState()
    val isScanning by viewModel.isScanningArtists.collectAsState()
    var dragDistance by remember { mutableFloatStateOf(0f) }
    var showLyrics by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(viewModel) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        when {
                            dragDistance > 150f -> viewModel.previous()
                            dragDistance < -150f -> viewModel.next()
                        }
                        dragDistance = 0f
                    },
                    onHorizontalDrag = { _, delta -> dragDistance += delta }
                )
            }
    ) {
        ArtworkBackground(albumArt)
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {},
                    navigationIcon = {
                        IconButton(onClick = onOpenLibrary) {
                            Icon(Icons.AutoMirrored.Filled.List, "マイライブラリを開く", tint = Color.White)
                        }
                    },
                    actions = {
                        Box {
                            IconButton(onClick = { showMenu = true }) {
                                Icon(Icons.Default.MoreVert, "その他の操作", tint = Color.White)
                            }
                            DropdownMenu(showMenu, onDismissRequest = { showMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("再生診断") },
                                    leadingIcon = { Icon(Icons.Default.Equalizer, null) },
                                    onClick = { showMenu = false; showDiagnostics = true }
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("ライブラリ") },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.List, null) },
                                    onClick = { showMenu = false; onOpenLibrary() }
                                )
                                DropdownMenuItem(
                                    text = { Text("設定") },
                                    leadingIcon = { Icon(Icons.Default.Settings, null) },
                                    onClick = { showMenu = false; onOpenSettings() }
                                )
                                DropdownMenuItem(
                                    text = { Text("音楽フォルダを選択") },
                                    leadingIcon = { Icon(Icons.Default.FolderOpen, null) },
                                    onClick = { showMenu = false; onSelectFolder() }
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )
            },
            containerColor = Color.Transparent
        ) { insets ->
            BoxWithConstraints(
                Modifier.fillMaxSize().padding(insets).padding(horizontal = 24.dp)
            ) {
                val artworkSize = constrainedArtworkSize(maxWidth, maxHeight)
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(Modifier.height(8.dp))
                    if (trackList.isEmpty() && !isScanning) {
                        EmptyLibraryPrompt(onSelectFolder)
                    } else {
                        ArtworkCover(albumArt, artworkSize)
                    }
                    Spacer(Modifier.height(16.dp))
                    TrackInformation(trackName, artist)
                    Spacer(Modifier.height(6.dp))
                    QualityBadges(diagnostics, audioInfo, bluetoothInfo) { showDiagnostics = true }
                    Spacer(Modifier.height(8.dp))
                    PlaybackProgress(viewModel)
                    PlaybackControls(viewModel)
                    LyricsPreview(viewModel, lyrics) { showLyrics = true }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
        if (showDiagnostics) {
            ModalBottomSheet(
                onDismissRequest = { showDiagnostics = false },
                containerColor = Color(0xFF1A1A1A)
            ) { AudioDiagnosticsSheet(diagnostics) }
        }
        if (showLyrics) {
            LyricsSheet(viewModel, lyrics, onDismiss = { showLyrics = false })
        }
    }
}

@Composable
private fun ArtworkBackground(albumArt: android.graphics.Bitmap?) {
    if (albumArt == null) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF30303A), Color(0xFF121212)))))
    } else {
        Image(
            bitmap = albumArt.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize().blur(40.dp),
            contentScale = ContentScale.Crop
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color(0x40000000), Color(0xAA121212), Color(0xFF121212)))
            )
        )
    }
}

@Composable
private fun ArtworkCover(albumArt: android.graphics.Bitmap?, size: Dp) {
    Box(Modifier.fillMaxWidth().height(size), contentAlignment = Alignment.Center) {
        if (albumArt == null) {
            Icon(Icons.Default.MusicNote, "アルバムアートなし", tint = Color(0xDDFFFFFF), modifier = Modifier.size(82.dp))
        } else {
            Crossfade(albumArt, label = "album artwork") { bitmap ->
                Image(
                    bitmap.asImageBitmap(),
                    contentDescription = "アルバムアート",
                    modifier = Modifier.size(size).clip(RoundedCornerShape(4.dp)),
                    contentScale = ContentScale.Fit
                )
            }
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(size * 0.28f)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xFF121212))))
            )
        }
    }
}

@Composable
private fun EmptyLibraryPrompt(onSelectFolder: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().heightIn(min = 220.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.FolderOpen, null, tint = Color.White, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(12.dp))
        Text("音楽フォルダを選択してください", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text("FLAC / WAV / M4A / MP3 に対応", color = Color(0xAAFFFFFF), fontSize = 13.sp)
        Button(onClick = onSelectFolder, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1DB954))) {
            Icon(Icons.Default.FolderOpen, null)
            Spacer(Modifier.width(8.dp))
            Text("フォルダを選択")
        }
    }
}

@Composable
private fun TrackInformation(trackName: String, artist: String) {
    Column(Modifier.fillMaxWidth()) {
        Text(trackName, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(artist, color = Color(0xDDFFFFFF), fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QualityBadges(
    diagnostics: PlaybackDiagnostics,
    audioInfo: String,
    bluetoothInfo: String,
    onOpenDiagnostics: () -> Unit
) {
    val source = diagnostics.sourceLabel.ifEmpty { audioInfo.replace(" | ", " • ") }
    val route = diagnostics.routeLabel ?: bluetoothInfo.takeIf(String::isNotEmpty)
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (source.isNotEmpty()) QualityBadge(source)
        if (route != null) QualityBadge(route)
        if (diagnostics.capabilities.isProbed) {
            Surface(onClick = onOpenDiagnostics, color = Color(0x22FFFFFF), shape = RoundedCornerShape(20.dp)) {
                Text(diagnostics.directCapabilityLabel, color = Color(0xDDEEFFEE), fontSize = 11.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
            }
        }
        if (source.isNotEmpty() || route != null) {
            IconButton(onClick = onOpenDiagnostics, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Info, "音質の詳細", tint = Color(0xDDFFFFFF), modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun QualityBadge(label: String) {
    Surface(color = Color(0x33FFFFFF), shape = RoundedCornerShape(20.dp)) {
        Text(label, color = Color.White, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 210.dp).padding(horizontal = 10.dp, vertical = 5.dp))
    }
}

@Composable
private fun PlaybackProgress(viewModel: PlayerViewModel) {
    val position by viewModel.currentPosition.collectAsState()
    val duration by viewModel.duration.collectAsState()
    Column(Modifier.fillMaxWidth()) {
        Slider(
            value = if (duration > 0L) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
            onValueChange = { viewModel.seekTo((it * duration).toLong()) },
            enabled = duration > 0L,
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color(0x55FFFFFF)),
            modifier = Modifier.fillMaxWidth().height(28.dp)
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(position), color = Color(0xCCFFFFFF), fontSize = 12.sp)
            Text(formatTime(duration), color = Color(0xCCFFFFFF), fontSize = 12.sp)
        }
    }
}

@Composable
private fun PlaybackControls(viewModel: PlayerViewModel) {
    val isPlaying by viewModel.isPlaying.collectAsState()
    val shuffle by viewModel.isShuffleMode.collectAsState()
    val repeat by viewModel.repeatMode.collectAsState()
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = viewModel::toggleShuffle, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Default.Shuffle, if (shuffle) "シャッフル オン" else "シャッフル オフ", tint = if (shuffle) Color(0xFF1DB954) else Color.White, modifier = Modifier.size(22.dp))
        }
        IconButton(onClick = viewModel::previous, modifier = Modifier.size(52.dp)) {
            Icon(Icons.Default.SkipPrevious, "前の曲", tint = Color.White, modifier = Modifier.size(34.dp))
        }
        Surface(onClick = viewModel::playPause, shape = CircleShape, color = Color.White, modifier = Modifier.size(76.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, if (isPlaying) "一時停止" else "再生", tint = Color.Black, modifier = Modifier.size(42.dp))
            }
        }
        IconButton(onClick = viewModel::next, modifier = Modifier.size(52.dp)) {
            Icon(Icons.Default.SkipNext, "次の曲", tint = Color.White, modifier = Modifier.size(34.dp))
        }
        IconButton(onClick = viewModel::toggleRepeat, modifier = Modifier.size(48.dp)) {
            Icon(
                if (repeat == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                when (repeat) { Player.REPEAT_MODE_ONE -> "1曲リピート オン"; Player.REPEAT_MODE_ALL -> "全曲リピート オン"; else -> "リピート オフ" },
                tint = if (repeat != Player.REPEAT_MODE_OFF) Color(0xFF1DB954) else Color.White,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
private fun LyricsPreview(viewModel: PlayerViewModel, lyrics: List<Pair<Long, String>>, onOpenLyrics: () -> Unit) {
    val preview by viewModel.currentLyrics.collectAsState()
    Box(
        Modifier.fillMaxWidth().heightIn(min = 40.dp)
            .clickable(enabled = preview.isNotEmpty() || lyrics.isNotEmpty(), onClick = onOpenLyrics)
            .padding(top = 8.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        when {
            preview.isNotEmpty() -> Text(preview, color = Color(0xFF8FE3A2), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            lyrics.isNotEmpty() -> Text("歌詞を表示", color = Color(0xBBFFFFFF), fontSize = 13.sp)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LyricsSheet(viewModel: PlayerViewModel, lyrics: List<Pair<Long, String>>, onDismiss: () -> Unit) {
    val position by viewModel.currentPosition.collectAsState()
    val activeIndex = lyrics.indexOfLast { it.first <= position }
    val listState = rememberLazyListState()
    LaunchedEffect(activeIndex) {
        if (activeIndex >= 0 && activeIndex < lyrics.size) listState.animateScrollToItem(activeIndex)
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color(0xFF1A1A1A)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.8f).padding(horizontal = 20.dp)) {
            Text("歌詞", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                itemsIndexed(lyrics) { index, (_, line) ->
                    Text(line, color = if (index == activeIndex) Color(0xFF1DB954) else Color(0xCCFFFFFF), fontSize = if (index == activeIndex) 22.sp else 18.sp, fontWeight = if (index == activeIndex) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}
