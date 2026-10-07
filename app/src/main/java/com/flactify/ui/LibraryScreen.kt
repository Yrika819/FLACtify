package com.flactify.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.flactify.viewmodel.PlayerViewModel
import com.flactify.viewmodel.RecommendedTrack
import com.flactify.viewmodel.TrackData
import com.flactify.viewmodel.Playlist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class TrackSortMode(val label: String) {
    TITLE("曲名"),
    ARTIST("アーティスト"),
    ALBUM("アルバム"),
    TRACK_NUMBER("トラック番号")
}

@Composable
fun rememberAudioAlbumArt(context: Context, uri: Uri): Any? {
    var artBytes by remember(uri) { mutableStateOf<ByteArray?>(null) }
    LaunchedEffect(uri) {
        withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
                    retriever.setDataSource(fd.fileDescriptor)
                    artBytes = retriever.embeddedPicture
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                try { retriever.release() } catch (e: Exception) {}
            }
        }
    }
    return artBytes
}

@Composable
fun ArtistGridIcon(tracks: List<TrackData>, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val distinctUris = remember(tracks) { tracks.map { it.uri }.distinct().take(4) }
    Surface(
        modifier = modifier.size(48.dp), shape = RoundedCornerShape(24.dp), color = Color(0x22FFFFFF)
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (distinctUris.isEmpty()) {
                Icon(Icons.Default.Person, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
            } else if (distinctUris.size == 1) {
                val artData = rememberAudioAlbumArt(context, distinctUris[0])
                if (artData != null) {
                    AsyncImage(model = ImageRequest.Builder(context).data(artData).crossfade(true).build(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Icon(Icons.Default.Person, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                            rememberAudioAlbumArt(context, distinctUris[0])?.let { AsyncImage(model = it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                        }
                        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                            val uri = if (distinctUris.size > 1) distinctUris[1] else distinctUris[0]
                            rememberAudioAlbumArt(context, uri)?.let { AsyncImage(model = it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                        }
                    }
                    Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                            val uri = if (distinctUris.size > 2) distinctUris[2] else distinctUris[0]
                            rememberAudioAlbumArt(context, uri)?.let { AsyncImage(model = it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                        }
                        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                            val uri = if (distinctUris.size > 3) distinctUris[3] else distinctUris[distinctUris.size - 1]
                            rememberAudioAlbumArt(context, uri)?.let { AsyncImage(model = it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: PlayerViewModel,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val artistMap by viewModel.artistMap.collectAsState()
    val albumMap by viewModel.albumMap.collectAsState()
    val isScanning by viewModel.isScanningArtists.collectAsState()
    val scanTotal by viewModel.scanTotal.collectAsState()
    val scanCompleted by viewModel.scanCompleted.collectAsState()
    val scanCurrentFile by viewModel.scanCurrentFile.collectAsState()
    val scanFailures by viewModel.scanFailures.collectAsState()
    val currentAlbumArt by viewModel.albumArt.collectAsState()
    val favorites by viewModel.favorites.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    val recommendations by viewModel.recommendations.collectAsState()
    val isLoadingRecommendations by viewModel.isLoadingRecommendations.collectAsState()
    val recommendationError by viewModel.recommendationError.collectAsState()
    val lastRecommendationUpdate by viewModel.lastRecommendationUpdate.collectAsState()

    val isShuffleMode by viewModel.isShuffleMode.collectAsState()
    val repeatMode by viewModel.repeatMode.collectAsState()

    var selectedTab by remember { mutableStateOf("artists") }
    var selectedArtist by remember { mutableStateOf<String?>(null) }
    var selectedAlbum by remember { mutableStateOf<String?>(null) }

    var editingTrack by remember { mutableStateOf<TrackData?>(null) }
    var showPlaylistPicker by remember { mutableStateOf<TrackData?>(null) }
    var showCreatePlaylist by remember { mutableStateOf(false) }
    var showPlaylistContent by remember { mutableStateOf<String?>(null) }

    var searchQuery by remember { mutableStateOf("") }
    var showFavoritesOnly by remember { mutableStateOf(false) }
    var sortMode by remember { mutableStateOf(TrackSortMode.TITLE) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showScanFailures by remember { mutableStateOf(false) }

    fun sortTracks(tracks: List<TrackData>): List<TrackData> = when (sortMode) {
        TrackSortMode.TITLE -> tracks.sortedBy { it.title.lowercase() }
        TrackSortMode.ARTIST -> tracks.sortedWith(compareBy<TrackData> { it.artist.lowercase() }.thenBy { it.title.lowercase() })
        TrackSortMode.ALBUM -> tracks.sortedWith(
            compareBy<TrackData> { it.album.lowercase() }
                .thenBy { it.discNumber?.substringBefore("/")?.toIntOrNull() ?: 1 }
                .thenBy { it.trackNumber?.substringBefore("/")?.toIntOrNull() ?: Int.MAX_VALUE }
                .thenBy { it.title.lowercase() }
        )
        TrackSortMode.TRACK_NUMBER -> tracks.sortedWith(compareBy<TrackData> { it.trackNumber?.substringBefore("/")?.toIntOrNull() ?: Int.MAX_VALUE }.thenBy { it.title.lowercase() })
    }

    val allSongsList by remember(artistMap, favorites, showFavoritesOnly, searchQuery, sortMode) {
        derivedStateOf {
            var list = sortTracks(artistMap.values.flatten())
            if (showFavoritesOnly) list = list.filter { favorites.contains(it.uri.toString()) }
            if (searchQuery.isNotBlank()) {
                val q = searchQuery.lowercase()
                list = list.filter {
                    it.title.lowercase().contains(q) ||
                    it.artist.lowercase().contains(q) ||
                    it.album.lowercase().contains(q)
                }
            }
            list
        }
    }

    fun filterTracks(tracks: List<TrackData>): List<TrackData> {
        var result = tracks
        if (showFavoritesOnly) result = result.filter { favorites.contains(it.uri.toString()) }
        if (searchQuery.isNotBlank()) {
            val q = searchQuery.lowercase()
            result = result.filter {
                it.title.lowercase().contains(q) ||
                it.artist.lowercase().contains(q) ||
                it.album.lowercase().contains(q)
            }
        }
        return result
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (currentAlbumArt != null) {
            Image(
                bitmap = currentAlbumArt!!.asImageBitmap(), contentDescription = null,
                modifier = Modifier.fillMaxSize().blur(50.dp), contentScale = ContentScale.Crop, alpha = 0.6f
            )
            Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(colors = listOf(Color(0x77121212), Color(0x99121212), Color(0xDD121212)))))
        } else {
            Box(modifier = Modifier.fillMaxSize().background(Color(0xFF121212)))
        }

        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            when { showPlaylistContent != null -> playlists.find { it.id == showPlaylistContent }?.name ?: ""
                                selectedTab == "artists" && selectedArtist != null -> selectedArtist!!
                                selectedTab == "albums" && selectedAlbum != null -> selectedAlbum!!
                                selectedTab == "playlists" -> "プレイリスト"
                                selectedTab == "recommendations" -> "おすすめ"
                                else -> "マイライブラリ"
                            }, color = Color.White, fontWeight = FontWeight.Bold
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            when {
                                showPlaylistContent != null -> showPlaylistContent = null
                                selectedTab == "artists" && selectedArtist != null -> selectedArtist = null
                                selectedTab == "albums" && selectedAlbum != null -> selectedAlbum = null
                                else -> onBackClick()
                            }
                        }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "戻る", tint = Color.White)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )
            }
        ) { paddingValues ->
            Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
                if (selectedArtist == null && selectedAlbum == null && showPlaylistContent == null) {
                    // ── Search bar ──
                    TextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("曲名・アーティスト・アルバム", color = Color.Gray, fontSize = 14.sp) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color.Gray) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Close, contentDescription = null, tint = Color.Gray)
                                }
                            }
                        },
                        colors = TextFieldDefaults.colors(
                            focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                            focusedContainerColor = Color(0x22FFFFFF), unfocusedContainerColor = Color(0x1AFFFFFF),
                            cursorColor = Color(0xFF1DB954),
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent
                        ),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clip(RoundedCornerShape(12.dp)),
                        singleLine = true
                    )

                    // ── Filters row (scrollable) ──
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Heart icon (outside scroll area)
                        IconButton(onClick = { showFavoritesOnly = !showFavoritesOnly }, modifier = Modifier.size(36.dp)) {
                            Icon(
                                Icons.Default.Favorite,
                                contentDescription = "お気に入りのみ",
                                tint = if (showFavoritesOnly) Color(0xFF1DB954) else Color(0x66FFFFFF),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        // Scrollable filter chips
                        Row(
                            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(label = "アーティスト", selected = selectedTab == "artists") { selectedTab = "artists" }
                            FilterChip(label = "曲", selected = selectedTab == "songs") { selectedTab = "songs" }
                            FilterChip(label = "アルバム", selected = selectedTab == "albums") { selectedTab = "albums" }
                            FilterChip(label = "プレイリスト", selected = selectedTab == "playlists") { selectedTab = "playlists" }
                            FilterChip(label = "おすすめ", selected = selectedTab == "recommendations") { selectedTab = "recommendations" }
                        }
                        Box {
                            IconButton(onClick = { showSortMenu = true }, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.Sort, contentDescription = "並び替え", tint = Color.White, modifier = Modifier.size(20.dp))
                            }
                            DropdownMenu(
                                expanded = showSortMenu,
                                onDismissRequest = { showSortMenu = false }
                            ) {
                                TrackSortMode.values().forEach { mode ->
                                    DropdownMenuItem(
                                        text = { Text("${mode.label}${if (sortMode == mode) " ✓" else ""}") },
                                        onClick = { sortMode = mode; showSortMenu = false }
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }

                if (isScanning && artistMap.isEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(color = Color(0xFF1DB954))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            if (scanTotal > 0) "スキャン中: $scanCompleted / $scanTotal" else "音楽ファイルを検索中…",
                            color = Color.White
                        )
                        if (scanCurrentFile.isNotBlank()) {
                            Text(scanCurrentFile, color = Color(0xAAFFFFFF), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedButton(onClick = { viewModel.cancelScan() }) {
                            Text("キャンセル")
                        }
                    }
                } else {
                    if (scanFailures.isNotEmpty() && selectedArtist == null && selectedAlbum == null && showPlaylistContent == null) {
                        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                            Text(
                                "読み込めなかったファイル: ${scanFailures.size}件",
                                color = Color(0xFFFFC107),
                                fontSize = 12.sp,
                                modifier = Modifier.clickable { showScanFailures = !showScanFailures }
                            )
                            if (showScanFailures) {
                                scanFailures.forEach { failedFile ->
                                    Text("• $failedFile", color = Color(0x99FFFFFF), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                    when (selectedTab) {
                        "playlists" -> PlaylistsTab(viewModel, playlists, showPlaylistContent, onShowPlaylist = { showPlaylistContent = it }, onCreatePlaylist = { showCreatePlaylist = true })
                        "recommendations" -> {
                            LaunchedEffect(Unit) { viewModel.loadRecommendations() }
                            RecommendationsTab(
                                recommendations = recommendations,
                                isLoading = isLoadingRecommendations,
                                error = recommendationError,
                                lastUpdated = lastRecommendationUpdate,
                                onRefresh = { viewModel.refreshRecommendations() }
                            )
                        }
                        "artists" -> {
                            if (selectedArtist == null) {
                                val filteredArtists = if (showFavoritesOnly || searchQuery.isNotEmpty()) artistMap.filter { (_, tracks) -> filterTracks(tracks).isNotEmpty() } else artistMap
                                LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(filteredArtists.keys.toList()) { artistName ->
                                        val associatedTracks = filteredArtists[artistName] ?: emptyList()
                                        Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0x12FFFFFF)).clickable { selectedArtist = artistName }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                            ArtistGridIcon(tracks = associatedTracks, modifier = Modifier.size(48.dp))
                                            Spacer(modifier = Modifier.width(16.dp))
                                            Column {
                                                Text(text = artistName, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                                Spacer(modifier = Modifier.height(2.dp))
                                                Text(text = "アーティスト • ${associatedTracks.size}曲", color = Color.LightGray, fontSize = 13.sp)
                                            }
                                        }
                                    }
                                }
                            } else {
                                val filteredSongs = sortTracks(filterTracks(artistMap[selectedArtist] ?: emptyList()))
                                SongListHeader(isShuffleMode, repeatMode, viewModel)
                                SongLazyColumn(songs = filteredSongs, viewModel = viewModel, onBackClick = onBackClick, onTrackLongClick = { editingTrack = it }, onFavoriteClick = { viewModel.toggleFavorite(it.uri.toString()) }, onPlayAction = { ctx, list, idx -> viewModel.playArtistTrack(ctx, list, idx) }, onAddToPlaylist = { showPlaylistPicker = it })
                            }
                        }
                        "albums" -> {
                            if (selectedAlbum == null) {
                                val filteredAlbums = if (showFavoritesOnly || searchQuery.isNotEmpty()) albumMap.filter { (_, tracks) -> filterTracks(tracks).isNotEmpty() } else albumMap
                                LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(filteredAlbums.keys.toList()) { albumName ->
                                        val associatedTracks = filteredAlbums[albumName] ?: emptyList()
                                        val firstTrack = associatedTracks.firstOrNull()
                                        Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0x12FFFFFF)).clickable { selectedAlbum = albumName }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Surface(modifier = Modifier.size(48.dp), shape = RoundedCornerShape(6.dp), color = Color(0x22FFFFFF)) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    val art = firstTrack?.let { rememberAudioAlbumArt(context, it.uri) }
                                                    if (art != null) AsyncImage(model = art, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                                    else Icon(Icons.Default.MusicNote, contentDescription = null, tint = Color.White)
                                                }
                                            }
                                            Spacer(modifier = Modifier.width(16.dp))
                                            Column {
                                                Text(text = albumName, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                Spacer(modifier = Modifier.height(2.dp))
                                                Text(text = "${firstTrack?.artist ?: "不明"} • ${associatedTracks.size}曲", color = Color.LightGray, fontSize = 13.sp)
                                            }
                                        }
                                    }
                                }
                            } else {
                                val filteredSongs = sortTracks(filterTracks(albumMap[selectedAlbum] ?: emptyList()))
                                SongListHeader(isShuffleMode, repeatMode, viewModel)
                                SongLazyColumn(songs = filteredSongs, viewModel = viewModel, onBackClick = onBackClick, onTrackLongClick = { editingTrack = it }, onFavoriteClick = { viewModel.toggleFavorite(it.uri.toString()) }, onPlayAction = { ctx, list, idx -> viewModel.playAlbumTrack(ctx, list, idx) }, onAddToPlaylist = { showPlaylistPicker = it })
                            }
                        }
                        else -> {
                            SongLazyColumn(songs = allSongsList, viewModel = viewModel, onBackClick = onBackClick, onTrackLongClick = { editingTrack = it }, onFavoriteClick = { viewModel.toggleFavorite(it.uri.toString()) }, onPlayAction = { ctx, list, idx -> viewModel.playAllTrack(ctx, list, idx) }, onAddToPlaylist = { showPlaylistPicker = it })
                        }
                    }
                }
            }
        }

        if (editingTrack != null) {
            TrackEditDialog(track = editingTrack!!, viewModel = viewModel, onDismiss = { editingTrack = null })
        }
        if (showPlaylistPicker != null) {
            PlaylistPickerDialog(viewModel = viewModel, track = showPlaylistPicker!!, onDismiss = { showPlaylistPicker = null })
        }
        if (showCreatePlaylist) {
            CreatePlaylistDialog(viewModel = viewModel, onDismiss = { showCreatePlaylist = false })
        }
        if (showPlaylistContent != null) {
            val tracks = viewModel.getPlaylistTracks(showPlaylistContent!!, artistMap.values.flatten())
            SongLazyColumn(songs = tracks, viewModel = viewModel, onBackClick = { showPlaylistContent = null }, onTrackLongClick = { editingTrack = it }, onFavoriteClick = { viewModel.toggleFavorite(it.uri.toString()) }, onPlayAction = { ctx, list, idx -> viewModel.playAllTrack(ctx, list, idx) }, onAddToPlaylist = {})
        }
    }
}

@Composable
private fun RecommendationAlbumArt(url: String?, modifier: Modifier) {
    var bitmap by remember(url) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(url) {
        if (url == null) return@LaunchedEffect
        Log.d("FLACtify", "Downloading album art: $url")
        withContext(Dispatchers.IO) {
            var connection: java.net.HttpURLConnection? = null
            try {
                connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.connect()
                val code = connection.responseCode
                Log.d("FLACtify", "Album art HTTP $code for $url")
                if (code == 200) {
                    val inputStream = connection.inputStream
                    val decoded = BitmapFactory.decodeStream(inputStream)
                    inputStream.close()
                    Log.d("FLACtify", "Album art decoded: ${decoded != null}, ${decoded?.width}x${decoded?.height}")
                    bitmap = decoded
                }
            } catch (e: Exception) {
                Log.e("FLACtify", "Album art download failed: ${e::class.simpleName}: ${e.message}")
            } finally {
                connection?.disconnect()
            }
        }
    }

    Box(contentAlignment = Alignment.Center, modifier = modifier) {
        if (bitmap != null) {
            Image(bitmap = bitmap!!.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(Icons.Default.MusicNote, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
private fun RecommendationsTab(
    recommendations: List<RecommendedTrack>,
    isLoading: Boolean,
    error: String?,
    lastUpdated: Long,
    onRefresh: () -> Unit
) {
    val context = LocalContext.current

    if (isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(color = Color(0xFF1DB954))
                Text("おすすめを取得中…", color = Color.LightGray, fontSize = 13.sp)
            }
        }
        return
    }

    if (error == "NEEDS_MORE_DATA") {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.MusicNote, contentDescription = null, tint = Color(0x44FFFFFF), modifier = Modifier.size(48.dp))
                Text("5曲以上再生すると\nおすすめが表示されます", color = Color.Gray, fontSize = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
        return
    }

    if (error == "NETWORK_ERROR") {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0x66FFFFFF), modifier = Modifier.size(36.dp))
                Text("ネットワークに接続できません\nWiFi/モバイルデータを確認してください", color = Color.LightGray, fontSize = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Button(onClick = onRefresh, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1DB954)), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
                    Text("再試行", color = Color.White)
                }
            }
        }
        return
    }

    if (error != null && recommendations.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.Info, contentDescription = null, tint = Color(0x66FFFFFF), modifier = Modifier.size(36.dp))
                Text(error, color = Color.LightGray, fontSize = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Button(onClick = onRefresh, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1DB954)), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
                    Text("再試行", color = Color.White)
                }
            }
        }
        return
    }

    if (recommendations.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("おすすめが見つかりませんでした", color = Color.Gray, fontSize = 14.sp)
                Button(onClick = onRefresh, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1DB954)), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
                    Text("更新", color = Color.White)
                }
            }
        }
        return
    }

    val grouped = recommendations.groupBy { it.sourceArtist }
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (lastUpdated > 0) {
                    val dateStr = java.text.SimpleDateFormat("MM/dd HH:mm", java.util.Locale.US).format(java.util.Date(lastUpdated))
                    Text("最終更新: $dateStr", color = Color.Gray, fontSize = 11.sp)
                }
                TextButton(onClick = onRefresh, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                    Icon(Icons.Default.Refresh, contentDescription = "更新", tint = Color(0xFF1DB954), modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("更新", color = Color(0xFF1DB954), fontSize = 12.sp)
                }
            }
        }

        grouped.forEach { (sourceArtist, tracks) ->
            item {
                val header = if (sourceArtist.startsWith("genre:")) {
                    val genre = sourceArtist.removePrefix("genre:")
                    "\"$genre\" ジャンルのおすすめ"
                } else {
                    "\"$sourceArtist\" が好きなあなたに"
                }
                Text(
                    text = header,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            items(tracks) { track ->
                Row(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0x12FFFFFF)).padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(modifier = Modifier.size(48.dp), shape = RoundedCornerShape(6.dp), color = Color(0x22FFFFFF)) {
                        RecommendationAlbumArt(url = track.albumArtUrl, modifier = Modifier.fillMaxSize())
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = track.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(text = track.artist, color = Color.LightGray, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistsTab(viewModel: PlayerViewModel, playlists: List<Playlist>, showPlaylistContent: String?, onShowPlaylist: (String) -> Unit, onCreatePlaylist: () -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0x12FFFFFF)).clickable { onCreatePlaylist() }.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Add, contentDescription = null, tint = Color(0xFF1DB954), modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Text("新しいプレイリスト", color = Color(0xFF1DB954), fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
        }
        if (playlists.isEmpty()) {
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text("プレイリストがありません", color = Color.Gray, fontSize = 14.sp)
                }
            }
        } else {
            items(playlists) { playlist ->
                Row(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0x12FFFFFF)).clickable { onShowPlaylist(playlist.id) }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.PlaylistPlay, contentDescription = null, tint = Color.White, modifier = Modifier.size(32.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = playlist.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text(text = "${playlist.trackUris.size}曲", color = Color.LightGray, fontSize = 13.sp)
                    }
                    IconButton(onClick = { viewModel.deletePlaylist(playlist.id) }) {
                        Icon(Icons.Default.Delete, contentDescription = "削除", tint = Color(0x66FFFFFF), modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistPickerDialog(viewModel: PlayerViewModel, track: TrackData, onDismiss: () -> Unit) {
    val playlists by viewModel.playlists.collectAsState()
    var showNewPlaylist by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("プレイリストに追加", color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                if (playlists.isEmpty() && !showNewPlaylist) {
                    Text("プレイリストがありません", color = Color.Gray)
                }
                playlists.forEach { p ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { viewModel.addTrackToPlaylist(p.id, track.uri.toString()); onDismiss() }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.PlaylistPlay, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(p.name, color = Color.White)
                    }
                }
                if (showNewPlaylist) {
                    var name by remember { mutableStateOf("") }
                    OutlinedTextField(
                        value = name, onValueChange = { name = it },
                        label = { Text("プレイリスト名") },
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    Button(onClick = { if (name.isNotBlank()) { viewModel.createPlaylist(name); viewModel.addTrackToPlaylist(viewModel.playlists.value.lastOrNull()?.id ?: "", track.uri.toString()); onDismiss() } }) {
                        Text("作成して追加", color = Color.White)
                    }
                } else {
                    TextButton(onClick = { showNewPlaylist = true }) { Text("新しいプレイリストを作成", color = Color(0xFF1DB954)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる", color = Color.White) } },
        containerColor = Color(0xFF1E1E1E), shape = RoundedCornerShape(16.dp)
    )
}

@Composable
private fun CreatePlaylistDialog(viewModel: PlayerViewModel, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新しいプレイリスト", color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("プレイリスト名") },
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedLabelColor = Color(0xFF1DB954), unfocusedLabelColor = Color.Gray),
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(onClick = { if (name.isNotBlank()) { viewModel.createPlaylist(name); onDismiss() } }, enabled = name.isNotBlank()) {
                Text("作成", color = Color.White)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル", color = Color.White) } },
        containerColor = Color(0xFF1E1E1E), shape = RoundedCornerShape(16.dp)
    )
}

@Composable
private fun SongListHeader(isShuffleMode: Boolean, repeatMode: Int, viewModel: PlayerViewModel) {
    Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { viewModel.toggleShuffle() }) {
            Icon(Icons.Default.Shuffle, contentDescription = "シャッフル", tint = if (isShuffleMode) Color(0xFF1DB954) else Color.White)
        }
        IconButton(onClick = { viewModel.toggleRepeat() }) {
            Icon(Icons.Default.Repeat, contentDescription = "リピート", tint = if (repeatMode != 0) Color(0xFF1DB954) else Color.White)
        }
        Text(
            text = when { isShuffleMode -> "ランダム"; repeatMode == 1 -> "全曲ループ"; repeatMode == 2 -> "1曲キープ"; else -> "通常再生" },
            color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(end = 12.dp, start = 4.dp)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SongLazyColumn(
    songs: List<TrackData>, viewModel: PlayerViewModel, onBackClick: () -> Unit,
    onTrackLongClick: (TrackData) -> Unit, onFavoriteClick: (TrackData) -> Unit,
    onPlayAction: (Context, List<TrackData>, Int) -> Unit, onAddToPlaylist: (TrackData) -> Unit
) {
    val context = LocalContext.current
    val favorites by viewModel.favorites.collectAsState()
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(songs) { idx, trackData ->
            Row(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0x12FFFFFF))
                    .combinedClickable(
                        onClick = { onPlayAction(context, songs, idx); onBackClick() },
                        onLongClick = { onTrackLongClick(trackData) }
                    )
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(modifier = Modifier.size(48.dp), shape = RoundedCornerShape(6.dp), color = Color(0x22FFFFFF)) {
                    Box(contentAlignment = Alignment.Center) {
                        val songArt = rememberAudioAlbumArt(context, trackData.uri)
                        if (songArt != null) AsyncImage(model = songArt, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        else Icon(Icons.Default.MusicNote, contentDescription = null, tint = Color.White)
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = trackData.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(text = trackData.artist, color = Color.LightGray, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                // ── Favorite button ──
                IconButton(onClick = { onFavoriteClick(trackData) }, modifier = Modifier.size(36.dp)) {
                    Icon(
                        if (favorites.contains(trackData.uri.toString())) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = "お気に入り",
                        tint = if (favorites.contains(trackData.uri.toString())) Color(0xFF1DB954) else Color(0x44FFFFFF),
                        modifier = Modifier.size(20.dp)
                    )
                }
                // ── Add to playlist button ──
                IconButton(onClick = { onAddToPlaylist(trackData) }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.PlaylistAdd, contentDescription = "プレイリストに追加", tint = Color(0x44FFFFFF), modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackEditDialog(track: TrackData, viewModel: PlayerViewModel, onDismiss: () -> Unit) {
    val isSavingMetadata by viewModel.isSavingMetadata.collectAsState()
    val canEditMetadata by viewModel.canEditMetadata.collectAsState()
    val context = LocalContext.current
    var title by remember { mutableStateOf(track.title) }
    var artist by remember { mutableStateOf(track.artist) }
    var album by remember { mutableStateOf(track.album) }
    var trackNumber by remember { mutableStateOf(track.trackNumber ?: "") }
    var genre by remember { mutableStateOf(track.genre ?: "") }
    var year by remember { mutableStateOf(track.year ?: "") }
    var composer by remember { mutableStateOf(track.composer ?: "") }
    var albumArtist by remember { mutableStateOf(track.albumArtist ?: "") }
    var discNumber by remember { mutableStateOf(track.discNumber ?: "") }
    var comment by remember { mutableStateOf(track.comment ?: "") }
    var selectBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var saveError by remember { mutableStateOf(false) }

    val imagePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { try { context.contentResolver.openInputStream(it)?.use { stream -> selectBitmap = BitmapFactory.decodeStream(stream) } } catch (e: Exception) { e.printStackTrace() } }
    }

    AlertDialog(
        onDismissRequest = { if (!isSavingMetadata) onDismiss() },
        title = { Text("楽曲情報の編集", color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (saveError) {
                    Text("保存に失敗しました", color = Color(0xFFFF5252), fontSize = 13.sp)
                }
                if (!canEditMetadata) {
                    Text(
                        "このフォルダは読み取り専用です。タグを保存するには、フォルダをもう一度選択して書き込み権限を許可してください。",
                        color = Color(0xFFFFC107),
                        fontSize = 12.sp
                    )
                }
                Box(modifier = Modifier.size(120.dp).clip(RoundedCornerShape(8.dp)).clickable { imagePickerLauncher.launch("image/*") }.background(Color(0x33FFFFFF)), contentAlignment = Alignment.Center) {
                    val bitmapToShow = selectBitmap ?: track.albumArtBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                    if (bitmapToShow != null) Image(bitmap = bitmapToShow.asImageBitmap(), contentDescription = "ジャケット写真", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    else Text("画像をタップして変更", color = Color.White, fontSize = 12.sp, style = androidx.compose.ui.text.TextStyle(textAlign = androidx.compose.ui.text.style.TextAlign.Center))
                }
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("曲名") }, colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedLabelColor = Color(0xFF1DB954), unfocusedLabelColor = Color.Gray), modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = artist, onValueChange = { artist = it }, label = { Text("アーティスト") }, colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedLabelColor = Color(0xFF1DB954), unfocusedLabelColor = Color.Gray), modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = album, onValueChange = { album = it }, label = { Text("アルバム") }, colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedLabelColor = Color(0xFF1DB954), unfocusedLabelColor = Color.Gray), modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = albumArtist, onValueChange = { albumArtist = it }, label = { Text("アルバムアーティスト") }, colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedLabelColor = Color(0xFF1DB954), unfocusedLabelColor = Color.Gray), modifier = Modifier.fillMaxWidth(), singleLine = true)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = trackNumber, onValueChange = { trackNumber = it }, label = { Text("トラック番号") }, colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedLabelColor = Color(0xFF1DB954), unfocusedLabelColor = Color.Gray), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(150.dp), singleLine = true)
                    OutlinedTextField(value = discNumber, onValueChange = { discNumber = it }, label = { Text("ディスク番号") }, colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedLabelColor = Color(0xFF1DB954), unfocusedLabelColor = Color.Gray), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f), singleLine = true)
                }
                OutlinedTextField(value = genre, onValueChange = { genre = it }, label = { Text("ジャンル") }, colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedLabelColor = Color(0xFF1DB954), unfocusedLabelColor = Color.Gray), modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = year, onValueChange = { year = it }, label = { Text("年") }, colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedLabelColor = Color(0xFF1DB954), unfocusedLabelColor = Color.Gray), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = composer, onValueChange = { composer = it }, label = { Text("作曲者") }, colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedLabelColor = Color(0xFF1DB954), unfocusedLabelColor = Color.Gray), modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = comment, onValueChange = { comment = it }, label = { Text("コメント") }, colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedLabelColor = Color(0xFF1DB954), unfocusedLabelColor = Color.Gray), modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 3)
            }
        },
        confirmButton = {
            Button(
                onClick = { saveError = false; viewModel.updateTrackTags(context = context, trackUri = track.uri, title = title, artist = artist, album = album, trackNumber = trackNumber, genre = genre.takeIf { it.isNotBlank() }, year = year.takeIf { it.isNotBlank() }, composer = composer.takeIf { it.isNotBlank() }, albumArtist = albumArtist.takeIf { it.isNotBlank() }, discNumber = discNumber.takeIf { it.isNotBlank() }, comment = comment.takeIf { it.isNotBlank() }, artworkBitmap = selectBitmap) { if (it) { saveError = false; onDismiss() } else { saveError = true } } },
                enabled = !isSavingMetadata && canEditMetadata,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1DB954))
            ) {
                if (isSavingMetadata) CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                else Text("保存", color = Color.White)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !isSavingMetadata) { Text("キャンセル", color = Color.White) } },
        containerColor = Color(0xFF1E1E1E), shape = RoundedCornerShape(16.dp)
    )
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) Color(0xFF1DB954) else Color(0x33FFFFFF)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = label, color = Color.White, fontWeight = FontWeight.Medium, fontSize = 13.sp,
            maxLines = 1, softWrap = false
        )
    }
}
