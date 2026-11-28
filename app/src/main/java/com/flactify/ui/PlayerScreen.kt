package com.flactify.ui

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.flactify.audio.PcmEncoding
import com.flactify.audio.PlaybackDiagnostics
import com.flactify.viewmodel.PlayerViewModel
import com.flactify.viewmodel.NowPlayingUiState
import com.flactify.viewmodel.PositionState
import com.flactify.viewmodel.ArtworkState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.media3.common.Player
import com.flactify.PlaybackSettings
import com.flactify.viewmodel.PlaybackQueueSnapshot

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel,
    onSelectFolder: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenSettings: () -> Unit = {}
) {
    val nowPlaying by viewModel.nowPlayingContentState.collectAsState(initial = NowPlayingUiState())
    val isPlaying = nowPlaying.isPlaying
    val currentTrackName = nowPlaying.current?.title?.takeIf { it.isNotBlank() }
        ?: if (nowPlaying.current == null) "未再生" else "不明"
    val currentArtist = nowPlaying.current?.artist?.takeIf { it.isNotBlank() } ?: "不明なアーティスト"
    val artwork = nowPlaying.current?.artwork as? ArtworkState.Available
    val cachedAlbumArt by viewModel.albumArt.collectAsState()
    val albumArt = cachedAlbumArt.takeIf { artwork?.source == ArtworkState.Available.Source.TRACK_CACHE }
    val artworkUri = artwork?.artworkUri?.takeIf { artwork?.source == ArtworkState.Available.Source.MEDIA3 }
    val lyrics by viewModel.lyrics.collectAsState()
    val context = LocalContext.current
    val selectedTreeUri = context
        .getSharedPreferences(PlaybackSettings.PREFERENCES_NAME, android.content.Context.MODE_PRIVATE)
        .getString("last_uri", null)
        ?.let(Uri::parse)
    val track = nowPlaying.current
    val externalLyricsKey = track?.let {
        ExternalLyricsCacheKey(
            trackKey = it.identity.stableKey,
            title = it.title,
            artist = it.artist,
            album = it.album
        )
    }
    val externalLyrics by produceState<List<Pair<Long, String>>?>(
        initialValue = null,
        key1 = externalLyricsKey,
        key2 = selectedTreeUri
    ) {
        value = track?.let { currentTrack ->
            val cacheKey = externalLyricsKey ?: return@let null
            withContext(Dispatchers.IO) {
                ExternalLyricsCache.getOrLoad(cacheKey) {
                    val sibling = externalLyricsUriFor(currentTrack.identity.uri, selectedTreeUri)
                        ?: return@getOrLoad null
                    context.contentResolver.openInputStream(sibling)?.use(::readExternalLyrics)
                }
            }
        }
    }
    val trackLyricsKey = track?.identity?.stableKey
    var useExternalLyrics by remember(trackLyricsKey) { mutableStateOf(false) }
    var lyricOffsetMs by remember(trackLyricsKey) { mutableLongStateOf(0L) }
    val selectedLyrics = when {
        useExternalLyrics && !externalLyrics.isNullOrEmpty() -> externalLyrics.orEmpty()
        lyrics.isNotEmpty() -> lyrics
        else -> externalLyrics.orEmpty()
    }
    val isShuffleMode = nowPlaying.shuffleEnabled
    val repeatMode = nowPlaying.repeatMode
    val audioInfo by viewModel.audioInfo.collectAsState()
    val bluetoothCodecInfo by viewModel.bluetoothCodecInfo.collectAsState()
    val diagnostics by viewModel.diagnostics.collectAsState()
    val trackList by viewModel.trackList.collectAsState()
    val isScanning by viewModel.isScanningArtists.collectAsState()
    val playbackQueue by viewModel.playbackQueue.collectAsState()

    var offsetX by remember { mutableStateOf(0f) }
    var showLyricsSheet by remember { mutableStateOf(false) }
    var showDiagnosticsSheet by remember { mutableStateOf(false) }
    var showQueueSheet by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (offsetX > 150) {
                            viewModel.previous()
                        } else if (offsetX < -150) {
                            viewModel.next()
                        }
                        offsetX = 0f
                    },
                    onHorizontalDrag = { _, dragAmount ->
                        offsetX += dragAmount
                    }
                )
            }
    ) {
        if (albumArt != null || artworkUri != null) {
            if (albumArt != null) {
            Image(
                bitmap = albumArt!!.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(40.dp),
                contentScale = ContentScale.Crop
            )
            } else {
                AsyncImage(
                    model = artworkUri,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().blur(40.dp),
                    contentScale = ContentScale.Crop,
                    onError = {
                        viewModel.setArtworkState(
                            nowPlaying.current?.identity,
                            nowPlaying.transitionGeneration,
                            ArtworkState.Unavailable
                        )
                    }
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color(0x40000000),
                                Color(0x55121212),
                                Color(0x80121212)
                            )
                        )
                    )
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color(0xFF2C2C35), Color(0xFF121212))
                        )
                    )
            )
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("FLACTify", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp) },
                    navigationIcon = {
                        IconButton(onClick = onOpenLibrary) {
                            Icon(Icons.Default.List, contentDescription = "マイライブラリを開く", tint = Color.White)
                        }
                    },
                    actions = {
                        IconButton(onClick = { showQueueSheet = true }) {
                            Icon(Icons.Default.QueueMusic, contentDescription = "再生キュー", tint = Color.White)
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Default.Settings, contentDescription = "設定", tint = Color.White)
                        }
                        IconButton(onClick = onSelectFolder) {
                            Icon(Icons.Default.FolderOpen, contentDescription = "フォルダ選択", tint = Color.White)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )
            },
            containerColor = Color.Transparent
        ) { paddingValues ->
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 24.dp)
            ) {
                val artworkSize = constrainedArtworkSize(maxWidth, maxHeight)

                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Spacer(modifier = Modifier.height(32.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(artworkSize),
                            contentAlignment = Alignment.Center
                        ) {

                    if (trackList.isEmpty() && !isScanning) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                Icons.Default.FolderOpen,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(64.dp)
                            )
                            Text(
                                "音楽フォルダを選択してください",
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text("FLAC / WAV / M4A / MP3 に対応", color = Color(0xAAFFFFFF), fontSize = 13.sp)
                            Button(
                                onClick = onSelectFolder,
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1DB954))
                            ) {
                                Icon(Icons.Default.FolderOpen, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("フォルダを選択", color = Color.White)
                            }
                        }
                    } else if (albumArt != null || artworkUri != null) {
                        Card(
                            modifier = Modifier.size(artworkSize),
                            shape = RoundedCornerShape(12.dp),
                            elevation = CardDefaults.cardElevation(defaultElevation = 12.dp)
                        ) {
                            if (albumArt != null) Image(
                                bitmap = albumArt!!.asImageBitmap(),
                                contentDescription = "アルバムアート",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            ) else AsyncImage(
                                model = artworkUri,
                                contentDescription = "アルバムアート",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                                onError = {
                                    viewModel.setArtworkState(
                                        nowPlaying.current?.identity,
                                        nowPlaying.transitionGeneration,
                                        ArtworkState.Unavailable
                                    )
                                }
                            )
                        }
                    } else {
                        Surface(
                            modifier = Modifier.size(artworkSize),
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0x33FFFFFF)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.MusicNote,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(80.dp)
                                )
                            }
                        }
                    }
                        }

                        Spacer(modifier = Modifier.height(32.dp))
                        Column(modifier = Modifier.fillMaxWidth()) {
                    // 曲名・アーティスト名
                    Text(
                        text = currentTrackName,
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = currentArtist,
                        color = Color(0xCCFFFFFF),
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    // 音質情報バッジ
                    Spacer(modifier = Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 1. ファイルが申告する情報（例: FLAC • 96 kHz • 24 bit）
                        //    不明な項目は推測せず省略する。
                        val sourceLabel = diagnostics.sourceLabel.ifEmpty {
                            audioInfo.replace(" | ", " • ")
                        }
                        if (sourceLabel.isNotEmpty()) {
                            Surface(
                                color = Color(0x33FFFFFF),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = sourceLabel,
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    modifier = Modifier
                                        .widthIn(max = 220.dp)
                                        .padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }

                        // 2. 出力先（Bluetooth • <name> / USB • <name> / Speaker）
                        //    协商済みBluetooth codecは公開APIから取得できないため
                        //    codec名は一切表示しない。
                        val routeLabel = diagnostics.routeLabel
                            ?: bluetoothCodecInfo.takeIf { it.isNotEmpty() }
                        if (routeLabel != null) {
                            Surface(
                                color = Color(0x33FFFFFF),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = routeLabel,
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    modifier = Modifier
                                        .widthIn(max = 220.dp)
                                        .padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }

                        // 3. Direct再生「能力」。実際にDirect経路で再生中であるとは
                        //    主张していない。tapで詳細診断を開く。
                        if (diagnostics.capabilities.isProbed) {
                            Surface(
                                color = Color(0x22FFFFFF),
                                shape = RoundedCornerShape(4.dp),
                                onClick = { showDiagnosticsSheet = true }
                            ) {
                                Text(
                                    text = diagnostics.directCapabilityLabel,
                                    color = Color(0xCCFFFFFF),
                                    fontSize = 11.sp,
                                    modifier = Modifier
                                        .widthIn(max = 220.dp)
                                        .padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }

                    // 歌詞エリア
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                            .height(44.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        CurrentLyricLabel(
                            viewModel = viewModel,
                            lyrics = applyLyricsOffset(selectedLyrics, lyricOffsetMs),
                            hasLyrics = selectedLyrics.isNotEmpty()
                        ) { showLyricsSheet = true }
                    }

                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 60.dp)
                    ) {
                        PlayerProgress(viewModel)

                    // 操作ボタン
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { viewModel.toggleShuffle() }) {
                            Icon(
                                Icons.Default.Shuffle,
                                contentDescription = "シャッフル",
                                tint = if (isShuffleMode) Color(0xFF1DB954) else Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        IconButton(onClick = { viewModel.previous() }) {
                            Icon(
                                Icons.Default.SkipPrevious,
                                contentDescription = "前へ",
                                tint = Color.White,
                                modifier = Modifier.size(38.dp)
                            )
                        }

                        Surface(
                            onClick = { viewModel.playPause() },
                            shape = CircleShape,
                            color = Color.White,
                            modifier = Modifier.size(88.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = "再生・一時停止",
                                    tint = Color.Black,
                                    modifier = Modifier.size(44.dp)
                                )
                            }
                        }

                        IconButton(onClick = { viewModel.next() }) {
                            Icon(
                                Icons.Default.SkipNext,
                                contentDescription = "次へ",
                                tint = Color.White,
                                modifier = Modifier.size(38.dp)
                            )
                        }

                        IconButton(onClick = { viewModel.toggleRepeat() }) {
                            Icon(
                                imageVector = when (repeatMode) {
                                    Player.REPEAT_MODE_ONE -> Icons.Default.RepeatOne
                                    else -> Icons.Default.Repeat
                                },
                                contentDescription = "リピート",
                                tint = if (repeatMode != Player.REPEAT_MODE_OFF) Color(0xFF1DB954) else Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                        }
                }
            }
        }

        if (showDiagnosticsSheet) {
            ModalBottomSheet(
                onDismissRequest = { showDiagnosticsSheet = false },
                containerColor = Color(0xFF1A1A1A)
            ) {
                AudioDiagnosticsSheet(diagnostics)
            }
        }

        if (showLyricsSheet) {
            LyricsBottomSheet(
                viewModel = viewModel,
                embeddedLyrics = lyrics,
                externalLyrics = externalLyrics.orEmpty(),
                useExternalLyrics = useExternalLyrics,
                onUseExternalLyricsChanged = { useExternalLyrics = it },
                manualOffsetMs = lyricOffsetMs,
                onManualOffsetChanged = { lyricOffsetMs = it },
                onDismiss = { showLyricsSheet = false }
            )
        }
        if (showQueueSheet) {
            QueueBottomSheet(
                viewModel = viewModel,
                queue = playbackQueue,
                onDismiss = { showQueueSheet = false }
            )
        }
    }
}

@Composable
private fun PlayerProgress(viewModel: PlayerViewModel) {
    val position by viewModel.nowPlayingPosition.collectAsState(initial = PositionState())
    val seekableDuration = position.durationMs?.takeIf { position.isSeekable && it > 0L }
    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = if (seekableDuration != null) {
                (position.positionMs.toFloat() / seekableDuration).coerceIn(0f, 1f)
            } else 0f,
            onValueChange = { fraction ->
                seekableDuration?.let { viewModel.seekTo((fraction * it).toLong()) }
            },
            enabled = seekableDuration != null,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = Color(0x40FFFFFF)
            ),
            modifier = Modifier.height(20.dp)
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = formatTime(position.positionMs), color = Color(0xBBFFFFFF), fontSize = 12.sp)
            Text(
                text = position.durationMs?.let(::formatTime) ?: "--:--",
                color = Color(0xBBFFFFFF),
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun CurrentLyricLabel(
    viewModel: PlayerViewModel,
    lyrics: List<Pair<Long, String>>,
    hasLyrics: Boolean,
    onOpenLyrics: () -> Unit
) {
    val position by viewModel.nowPlayingPosition.collectAsState(initial = PositionState())
    val currentLyrics = if (lyrics.any { it.first >= 0L }) {
        val index = activeLyricsIndex(lyrics, position.positionMs)
        if (index >= 0) lyrics[index].second else ""
    } else {
        lyrics.joinToString("\n") { it.second }
    }
    if (currentLyrics.isNotEmpty()) {
        Text(
            text = currentLyrics,
            color = Color(0xFF1DB954),
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clickable(onClick = onOpenLyrics)
        )
    } else if (hasLyrics) {
        Text(
            text = "歌詞を表示",
            color = Color(0x99FFFFFF),
            fontSize = 13.sp,
            modifier = Modifier.clickable(onClick = onOpenLyrics)
        )
    } else {
        Text(
            text = "歌詞なし",
            color = Color(0x66FFFFFF),
            fontSize = 13.sp,
            modifier = Modifier.clickable(onClick = onOpenLyrics)
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun LyricsBottomSheet(
    viewModel: PlayerViewModel,
    embeddedLyrics: List<Pair<Long, String>>,
    externalLyrics: List<Pair<Long, String>>,
    useExternalLyrics: Boolean,
    onUseExternalLyricsChanged: (Boolean) -> Unit,
    manualOffsetMs: Long,
    onManualOffsetChanged: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val position by viewModel.nowPlayingPosition.collectAsState(initial = PositionState())
    val lyrics = when {
        useExternalLyrics && externalLyrics.isNotEmpty() -> externalLyrics
        embeddedLyrics.isNotEmpty() -> embeddedLyrics
        else -> externalLyrics
    }
    val displayedLyrics = remember(lyrics, manualOffsetMs) {
        applyLyricsOffset(lyrics, manualOffsetMs)
    }
    val activeLyricIndex = activeLyricsIndex(displayedLyrics, position.positionMs)
    val lyricsListState = rememberLazyListState()
    val isDragged by lyricsListState.interactionSource.collectIsDraggedAsState()
    var manualScroll by remember { mutableStateOf(false) }

    LaunchedEffect(isDragged) {
        if (isDragged) manualScroll = true
    }

    LaunchedEffect(activeLyricIndex, manualScroll) {
        if (!manualScroll && activeLyricIndex >= 0 && activeLyricIndex < displayedLyrics.size) {
            lyricsListState.animateScrollToItem(activeLyricIndex)
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color(0xFF1A1A1A)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.8f)
                .padding(horizontal = 20.dp)
        ) {
            Text("歌詞", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(12.dp))
            if (embeddedLyrics.isNotEmpty() && externalLyrics.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = !useExternalLyrics,
                        onClick = { onUseExternalLyricsChanged(false) },
                        label = { Text("埋め込み") }
                    )
                    FilterChip(
                        selected = useExternalLyrics,
                        onClick = { onUseExternalLyricsChanged(true) },
                        label = { Text("外部 .lrc") }
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "オフセット ${formatOffset(manualOffsetMs)}",
                    color = Color(0xCCFFFFFF),
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { onManualOffsetChanged((manualOffsetMs - 100L).coerceAtLeast(-30_000L)) }) {
                    Text("−0.1s")
                }
                TextButton(onClick = { onManualOffsetChanged((manualOffsetMs + 100L).coerceAtMost(30_000L)) }) {
                    Text("+0.1s")
                }
            }
            if (manualScroll) {
                TextButton(onClick = { manualScroll = false }) {
                    Text("現在の歌詞へ追従")
                }
            }
            LazyColumn(
                state = lyricsListState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (displayedLyrics.isEmpty()) {
                    item {
                        Text(
                            text = "この曲には歌詞がありません",
                            color = Color(0x99FFFFFF),
                            fontSize = 16.sp,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                } else {
                    itemsIndexed(displayedLyrics) { index, (timestamp, line) ->
                        Text(
                            text = line,
                            color = if (index == activeLyricIndex) Color(0xFF1DB954) else Color(0xCCFFFFFF),
                            fontSize = if (index == activeLyricIndex) 22.sp else 18.sp,
                            fontWeight = if (index == activeLyricIndex) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = timestamp >= 0L) {
                                    // Offset changes lyric display timing; seek the source timestamp.
                                    viewModel.seekTo(lyrics[index].first)
                                }
                        )
                    }
                }
            }
        }
    }
}

private fun formatOffset(offsetMs: Long): String = when {
    offsetMs == 0L -> "0.0s"
    offsetMs < 0L -> "−${String.format(java.util.Locale.US, "%.1f", -offsetMs / 1000.0)}s"
    else -> "+${String.format(java.util.Locale.US, "%.1f", offsetMs / 1000.0)}s"
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun QueueBottomSheet(
    viewModel: PlayerViewModel,
    queue: PlaybackQueueSnapshot,
    onDismiss: () -> Unit
) {
    var showSaveDialog by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color(0xFF1A1A1A)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.82f)
                .padding(horizontal = 20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("再生キュー", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                TextButton(
                    onClick = { showSaveDialog = true },
                    enabled = queue.items.isNotEmpty()
                ) {
                    Text("プレイリストに保存", color = Color(0xFF1DB954))
                }
            }
            if (queue.shuffleEnabled) {
                Text(
                    "シャッフル中は「次に再生」と並び替えを無効にしています。",
                    color = Color(0x99FFFFFF),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            if (queue.items.isEmpty()) {
                Text("キューは空です", color = Color(0x99FFFFFF), modifier = Modifier.padding(vertical = 24.dp))
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    itemsIndexed(queue.items) { index, item ->
                        val isCurrent = index == queue.currentIndex
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (isCurrent) Color(0x2233CC66) else Color.Transparent,
                                    RoundedCornerShape(8.dp)
                                )
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.title.ifEmpty { "不明" },
                                    color = if (isCurrent) Color(0xFF1DB954) else Color.White,
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = item.artist.ifEmpty { "不明なアーティスト" },
                                    color = Color(0x99FFFFFF),
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(
                                onClick = { viewModel.moveQueueItem(index, index - 1) },
                                enabled = !queue.shuffleEnabled && index > 0
                            ) {
                                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "上へ", tint = Color.White)
                            }
                            IconButton(
                                onClick = { viewModel.moveQueueItem(index, index + 1) },
                                enabled = !queue.shuffleEnabled && index < queue.items.lastIndex
                            ) {
                                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "下へ", tint = Color.White)
                            }
                            IconButton(
                                onClick = { viewModel.removeQueueItem(index) },
                                enabled = !isCurrent
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = if (isCurrent) "再生中の曲は削除できません" else "キューから削除",
                                    tint = Color.White
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    if (showSaveDialog) {
        SaveQueueDialog(
            onDismiss = { showSaveDialog = false },
            onSave = { name ->
                val saved = viewModel.savePlaybackQueueAsPlaylist(name)
                if (saved) showSaveDialog = false
            }
        )
    }
}

@Composable
private fun SaveQueueDialog(
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("キューを保存") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("プレイリスト名") },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(name) }, enabled = name.trim().isNotEmpty()) {
                Text("保存")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } }
    )
}

private fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(java.util.Locale.US, "%02d:%02d", minutes, seconds)
}

/**
 * Detailed playback diagnostics.
 *
 * The four sections are deliberately kept apart and labelled, because the most common audio
 * bug in this app's history was showing one of these values in another's place. Anything not
 * measured is rendered as "Unknown" rather than filled in from a neighbouring field.
 */
@Composable
private fun AudioDiagnosticsSheet(diagnostics: PlaybackDiagnostics) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp)
    ) {
        Text(
            text = "再生診断",
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "能力と実際の出力を分けて表示します。Direct対応は「再生中」の意味ではありません。underrun数はMedia3通知イベントで、AudioTrackの生カウンタではありません。",
            color = Color(0x88FFFFFF),
            fontSize = 11.sp
        )
        Spacer(modifier = Modifier.height(16.dp))

        DiagnosticsSection("ソース（ファイル申告）") {
            DiagnosticsRow("フォーマット", diagnostics.source.container ?: "Unknown")
            DiagnosticsRow("サンプリングレート", diagnostics.source.displayRate ?: "Unknown")
            // Never inferred from sample rate; 44.1k/24bit and 96k/16bit are both legal.
            DiagnosticsRow("ビット深度", diagnostics.source.bitDepth?.let { "$it bit" } ?: "Unknown")
            DiagnosticsRow("チャンネル数", diagnostics.source.channelCount?.toString() ?: "Unknown")
            DiagnosticsRow("ビットレート", diagnostics.source.bitrateKbps?.let { "$it kbps" } ?: "Unknown")
        }

        Spacer(modifier = Modifier.height(12.dp))
        DiagnosticsSection("レンダラ入力（実測）") {
            // Media3 documents this as the format the renderer is processing, not a verified
            // post-decode PCM format, so no PCM claim is made here.
            DiagnosticsRow("デコーダ", diagnostics.renderer.decoderName ?: "Unknown")
            DiagnosticsRow("サンプリングレート", diagnostics.renderer.displayRate ?: "Unknown")
            DiagnosticsRow("チャンネル数", diagnostics.renderer.channelCount?.toString() ?: "Unknown")
        }

        Spacer(modifier = Modifier.height(12.dp))
        DiagnosticsSection("出力先") {
            DiagnosticsRow("種別", diagnostics.route?.type?.name ?: "Unknown")
            DiagnosticsRow("デバイス", diagnostics.route?.deviceName ?: "Unknown")
            DiagnosticsRow("判定根拠", diagnostics.route?.confidence?.name ?: "Unknown")
        }

        Spacer(modifier = Modifier.height(12.dp))
        DiagnosticsSection("Direct再生能力（API ${if (diagnostics.capabilities.isProbed) "33+" else "非対応"}）") {
            DiagnosticsRow("判定", diagnostics.directCapabilityLabel)
            DiagnosticsRow("照会条件", diagnostics.capabilities.conditionLabel ?: "未照会")
            DiagnosticsRow(
                "Float32",
                diagnostics.capabilities.supportFor(PcmEncoding.PCM_FLOAT).name
            )
            DiagnosticsRow(
                "PCM24",
                diagnostics.capabilities.supportFor(PcmEncoding.PCM_24BIT_PACKED).name
            )
            DiagnosticsRow(
                "PCM16",
                diagnostics.capabilities.supportFor(PcmEncoding.PCM_16BIT).name
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        DiagnosticsSection("Spatial DSP") {
            DiagnosticsRow("要求モード", diagnostics.spatial.requestedMode.name)
            DiagnosticsRow("有効モード", diagnostics.spatial.effectiveMode.name)
            DiagnosticsRow("エンジン", diagnostics.spatial.engine ?: "Unknown")
            DiagnosticsRow("エンジン版", diagnostics.spatial.engineVersion ?: "Unknown")
            DiagnosticsRow("入力PCM", diagnostics.spatial.inputEncoding ?: "Unknown")
            DiagnosticsRow(
                "入力形式",
                listOfNotNull(
                    diagnostics.spatial.inputSampleRateHz?.let { "$it Hz" },
                    diagnostics.spatial.inputChannelCount?.let { "$it ch" }
                ).joinToString(" • ").ifEmpty { "Unknown" }
            )
            DiagnosticsRow("内部形式", diagnostics.spatial.internalFormat ?: "Unknown")
            DiagnosticsRow("HRTFプロファイル", diagnostics.spatial.hrtfProfile ?: "Unknown")
            DiagnosticsRow(
                "HRTF混合量",
                diagnostics.spatial.hrtfMixPercent?.let { "$it%" } ?: "Unknown"
            )
            DiagnosticsRow("仮想配置", diagnostics.spatial.speakerLayout ?: "Unknown")
            DiagnosticsRow(
                "処理ブロック",
                diagnostics.spatial.frameSize?.let { "$it frames" } ?: "Unknown"
            )
            DiagnosticsRow(
                "アルゴリズム遅延",
                diagnostics.spatial.algorithmLatencyFrames?.let { "$it frames" } ?: "未計測"
            )
            DiagnosticsRow("バイパス理由", diagnostics.spatial.bypassReason ?: "なし")
            DiagnosticsRow("エラー", diagnostics.spatial.error ?: "なし")
        }

        Spacer(modifier = Modifier.height(12.dp))
        DiagnosticsSection("実際の出力（実測）") {
            DiagnosticsRow("PCM", diagnostics.actualOutput.pcmEncoding?.name ?: "Unknown")
            DiagnosticsRow("サンプリングレート", diagnostics.actualOutput.displayRate ?: "Unknown")
            DiagnosticsRow("チャンネル数", diagnostics.actualOutput.channelCount?.toString() ?: "Unknown")
            DiagnosticsRow("Offload", diagnostics.actualOutput.isOffload?.toString() ?: "Unknown")
        }

        Spacer(modifier = Modifier.height(12.dp))
        DiagnosticsSection("Media3 Audio Sinkイベント") {
            DiagnosticsRow(
                "Underrun通知数",
                diagnostics.media3AudioSink.underrunEventCount?.toString() ?: "Unknown"
            )
            DiagnosticsRow(
                "最後のバッファ",
                diagnostics.media3AudioSink.lastBufferSizeBytes?.let { "$it bytes" } ?: "Unknown"
            )
            DiagnosticsRow(
                "バッファ時間",
                diagnostics.media3AudioSink.lastBufferDurationMs?.let { "$it ms" } ?: "Unknown"
            )
            DiagnosticsRow(
                "最後のfeedから",
                diagnostics.media3AudioSink.lastElapsedSinceLastFeedMs?.let { "$it ms" } ?: "Unknown"
            )
        }
    }
}

@Composable
private fun DiagnosticsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(text = title, color = Color(0xFF1DB954), fontSize = 12.sp, fontWeight = FontWeight.Bold)
    Spacer(modifier = Modifier.height(4.dp))
    Column(modifier = Modifier.fillMaxWidth(), content = content)
}

@Composable
private fun DiagnosticsRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, color = Color(0x99FFFFFF), fontSize = 12.sp)
        Text(text = value, color = Color.White, fontSize = 12.sp)
    }
}
