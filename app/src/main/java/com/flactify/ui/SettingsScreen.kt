package com.flactify.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flactify.viewmodel.PlayerViewModel
import com.flactify.viewmodel.TagRecoveryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: PlayerViewModel,
    onBackClick: () -> Unit
) {
    val sleepTimerMinutes by viewModel.sleepTimerMinutes.collectAsState()
    val pauseOnBluetoothDisconnect by viewModel.pauseOnBluetoothDisconnect.collectAsState()
    val cacheSize by viewModel.cacheSize.collectAsState()
    val spatialAudioMode by viewModel.spatialAudioMode.collectAsState()
    val spatialHrtfMixPercent by viewModel.spatialHrtfMixPercent.collectAsState()
    val isChangingSpatialAudioMode by viewModel.isChangingSpatialAudioMode.collectAsState()
    val spatialAudioModeError by viewModel.spatialAudioModeError.collectAsState()
    var hrtfMixDraft by remember(
        spatialHrtfMixPercent,
        isChangingSpatialAudioMode,
        spatialAudioModeError
    ) {
        mutableStateOf(spatialHrtfMixPercent.toFloat())
    }
    val context = LocalContext.current
    val jaudiotaggerLicense = remember(context) {
        runCatching {
            context.assets.open("licenses/LGPL-2.1.txt").bufferedReader().use { it.readText() }
        }.getOrElse { "The LGPL-2.1 license text is unavailable." }
    }
    val apacheLicense = remember(context) {
        runCatching {
            context.assets.open("licenses/Apache-2.0.txt").bufferedReader().use { it.readText() }
        }.getOrElse { "The Apache-2.0 license text is unavailable." }
    }
    val mplLicense = remember(context) {
        runCatching {
            context.assets.open("licenses/MPL-2.0.txt").bufferedReader().use { it.readText() }
        }.getOrElse { "The MPL-2.0 license text is unavailable." }
    }

    var showLicenseDialog by remember { mutableStateOf(false) }
    var showCacheDialog by remember { mutableStateOf(false) }
    var recoveryEntries by remember { mutableStateOf(emptyList<TagRecoveryManager.Entry>()) }
    var pendingExport by remember { mutableStateOf<TagRecoveryManager.Entry?>(null) }
    var pendingDelete by remember { mutableStateOf<TagRecoveryManager.Entry?>(null) }
    var recoveryMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val recoveryManager = remember(context) { TagRecoveryManager(context.applicationContext) }
    val exportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { destination ->
            val entry = pendingExport
            pendingExport = null
            if (destination != null && entry != null) {
                scope.launch {
                    val result =
                        runCatching { withContext(Dispatchers.IO) { recoveryManager.export(entry, destination) } }
                    recoveryMessage = if (result.isSuccess) "Recovery copy exported successfully." else
                        "Export failed: ${result.exceptionOrNull()?.message ?: "Unable to write destination"}"
                }
            }
        }

    LaunchedEffect(Unit) {
        viewModel.refreshCacheSize(context)
        withContext(Dispatchers.IO) { recoveryManager.entries() }.also { recoveryEntries = it }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("設定", color = Color.White, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "戻る", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF121212))
            )
        },
        containerColor = Color(0xFF121212)
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            item {
                SettingsSectionHeader("Bluetooth")
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF1E1E1E)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Bluetooth切断時に一時停止",
                                color = Color.White,
                                fontSize = 14.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "実際の再生ルートでBluetoothから別の出力への切り替わりを確認できた場合に一時停止します。予測や接続機器一覧だけの場合は誤停止を防ぐため一時停止しません。",
                                color = Color(0xCCFFFFFF),
                                fontSize = 12.sp,
                                lineHeight = 18.sp
                            )
                        }
                        Switch(
                            checked = pauseOnBluetoothDisconnect,
                            onCheckedChange = viewModel::setPauseOnBluetoothDisconnect,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = Color(0xFF1DB954)
                            )
                        )
                    }
                }
            }

            item {
                SettingsSectionHeader("空間オーディオ")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = spatialAudioMode == com.flactify.audio.spatial.SpatialAudioMode.OFF,
                        onClick = {
                            viewModel.setSpatialAudioMode(
                                com.flactify.audio.spatial.SpatialAudioMode.OFF
                            )
                        },
                        enabled = !isChangingSpatialAudioMode,
                        label = { Text("オフ", fontSize = 13.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFF1DB954),
                            selectedLabelColor = Color.Black,
                            containerColor = Color(0x22FFFFFF),
                            labelColor = Color.White
                        )
                    )
                    FilterChip(
                        selected = spatialAudioMode == com.flactify.audio.spatial.SpatialAudioMode.STUDIO,
                        onClick = {
                            viewModel.setSpatialAudioMode(
                                com.flactify.audio.spatial.SpatialAudioMode.STUDIO
                            )
                        },
                        enabled = !isChangingSpatialAudioMode,
                        label = { Text("Studio / HRTF", fontSize = 13.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFF1DB954),
                            selectedLabelColor = Color.Black,
                            containerColor = Color(0x22FFFFFF),
                            labelColor = Color.White
                        )
                    )
                }
                Text(
                    "Studioでは、Media3のPCM出力境界で受け取ったステレオPCMにHRTF処理を行います。対応できないPCM形式や出力条件では空間処理をバイパスし、診断情報に理由を表示します。イヤホン側の空間効果は二重処理を避けるためオフを推奨します。",
                    color = Color(0xCCFFFFFF),
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("HRTF混合量", color = Color.White, fontSize = 14.sp)
                    Text(
                        "${hrtfMixDraft.roundToInt()}%",
                        color = Color(0xFF1DB954),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Slider(
                    value = hrtfMixDraft,
                    onValueChange = { hrtfMixDraft = it },
                    onValueChangeFinished = {
                        viewModel.setSpatialHrtfMixPercent(hrtfMixDraft.roundToInt())
                    },
                    valueRange = 0f..100f,
                    steps = 19,
                    enabled = spatialAudioMode ==
                            com.flactify.audio.spatial.SpatialAudioMode.STUDIO &&
                            !isChangingSpatialAudioMode,
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFF1DB954),
                        activeTrackColor = Color(0xFF1DB954),
                        inactiveTrackColor = Color(0x55FFFFFF)
                    )
                )
                Text(
                    "0%はHRTF成分なし、100%はStudioのHRTF処理全体です。変更は保存され、再生中は短いフェードを挟んで反映します。0%でもStudioのPCM再生経路を使用します。",
                    color = Color(0xCCFFFFFF),
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
                if (isChangingSpatialAudioMode) {
                    Text("空間オーディオ設定を切り替えています…", color = Color(0xFF1DB954), fontSize = 12.sp)
                }
                spatialAudioModeError?.let { error ->
                    Text(error, color = Color(0xFFFF7070), fontSize = 12.sp)
                }
            }

            item {
                SettingsSectionHeader("スリープタイマー")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(15, 30, 60).forEach { minutes ->
                        FilterChip(
                            selected = sleepTimerMinutes == minutes,
                            onClick = { viewModel.setSleepTimer(minutes) },
                            label = { Text("${minutes}分", fontSize = 13.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF1DB954),
                                selectedLabelColor = Color.Black,
                                containerColor = Color(0x22FFFFFF),
                                labelColor = Color.White
                            )
                        )
                    }
                    if (sleepTimerMinutes > 0) {
                        FilterChip(
                            selected = false,
                            onClick = { viewModel.cancelSleepTimer() },
                            label = { Text("解除", fontSize = 13.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = Color(0x33FF5252),
                                labelColor = Color(0xFFFF5252)
                            )
                        )
                    }
                }
            }

            item {
                SettingsSectionHeader("タグ編集の復旧データ")
                if (recoveryEntries.isEmpty()) {
                    Text("復旧データはありません", color = Color(0x88FFFFFF), fontSize = 13.sp)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        recoveryEntries.forEach { entry ->
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                color = Color(0xFF1E1E1E)
                            ) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Text(entry.originalIdentifier, color = Color.White, fontSize = 12.sp)
                                    Text(
                                        "作成: ${
                                            entry.createdAt?.let {
                                                java.text.SimpleDateFormat(
                                                    "yyyy/MM/dd HH:mm",
                                                    java.util.Locale.getDefault()
                                                ).format(java.util.Date(it))
                                            } ?: "不明"
                                        } · サイズ: ${formatBytes(entry.sizeBytes)}",
                                        color = Color(0xCCFFFFFF), fontSize = 11.sp
                                    )
                                    Text(entry.failureReason, color = Color(0xCCFFFFFF), fontSize = 11.sp)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(
                                            onClick = {
                                                pendingExport = entry
                                                exportLauncher.launch(entry.audioFile?.name ?: "recovery-audio.bin")
                                            },
                                            enabled = entry.audioFile?.isFile == true
                                        ) { Text("復旧データを書き出す", fontSize = 11.sp) }
                                        TextButton(onClick = { pendingDelete = entry }) {
                                            Text("削除", color = Color(0xFFFF7070), fontSize = 11.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                recoveryMessage?.let { Text(it, color = Color(0xCCFFFFFF), fontSize = 12.sp) }
            }

            item {
                SettingsSectionHeader("キャッシュ管理")
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF1E1E1E)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("ライブラリキャッシュサイズ", color = Color.White, fontSize = 14.sp)
                            Text(
                                text = formatBytes(cacheSize),
                                color = Color(0xCCFFFFFF),
                                fontSize = 12.sp
                            )
                        }
                        Button(
                            onClick = { showCacheDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5252)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("削除", color = Color.White, fontSize = 13.sp)
                        }
                    }
                }
            }

            item {
                SettingsSectionHeader("再生統計")
                val statsVersion by viewModel.statsVersion.collectAsState()
                val summary by remember(statsVersion) { mutableStateOf(viewModel.getStatsSummary()) }
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF1E1E1E)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            StatItem(label = "再生曲数", value = "${summary.totalTracks}")
                            StatItem(label = "再生回数", value = "${summary.totalPlayCount}")
                            StatItem(label = "スキップ", value = "${summary.totalSkipCount}")
                        }
                        if (summary.lastPlayed > 0) {
                            Spacer(modifier = Modifier.height(8.dp))
                            val dateStr = java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", java.util.Locale.US)
                                .format(java.util.Date(summary.lastPlayed))
                            Text(
                                "最終再生: $dateStr",
                                color = Color(0x88FFFFFF),
                                fontSize = 11.sp,
                                modifier = Modifier.align(Alignment.CenterHorizontally)
                            )
                        }
                    }
                }
            }

            item {
                SettingsSectionHeader("おすすめとプライバシー")
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF1E1E1E)
                ) {
                    Text(
                        "おすすめ機能では、ライブラリ内のアーティスト名と再生統計を使ってLast.fmから候補を取得します。音声ファイル本体やタグ編集内容は送信しません。ネットワーク通信を使わない場合は、おすすめタブを開かないでください。",
                        color = Color(0xCCFFFFFF),
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }

            item {
                SettingsSectionHeader("最も聴いた曲")
                val statsVersion by viewModel.statsVersion.collectAsState()
                val topTracks by remember(statsVersion) { mutableStateOf(viewModel.getTopPlayedTracks()) }
                if (topTracks.isEmpty()) {
                    Text(
                        "データがありません",
                        color = Color(0x88FFFFFF),
                        fontSize = 13.sp,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                } else {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF1E1E1E)
                    ) {
                        Column(modifier = Modifier.padding(vertical = 8.dp)) {
                            topTracks.forEachIndexed { index, track ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "${index + 1}",
                                        color = Color(0xFF1DB954),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        modifier = Modifier.width(24.dp)
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(track.title, color = Color.White, fontSize = 13.sp, maxLines = 1)
                                        Text(track.artist, color = Color(0xAAFFFFFF), fontSize = 11.sp, maxLines = 1)
                                    }
                                    Text(
                                        "${track.playCount}回",
                                        color = Color(0x88FFFFFF),
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                SettingsSectionHeader("About & licenses")
                TextButton(onClick = { showLicenseDialog = true }) {
                    Text("Open-source licenses", color = Color.White)
                }
            }
        }
    }

    if (showLicenseDialog) {
        AlertDialog(
            onDismissRequest = { showLicenseDialog = false },
            containerColor = Color(0xFF1E1E1E),
            title = { Text("Third-party licenses", color = Color.White) },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 440.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        "jaudiotagger 3.0.1\nCopyright (C) 2015 Paul Taylor\nLicensed under the GNU Lesser General Public License, version 2.1 or (at your option) any later version.\n\n",
                        color = Color.White
                    )
                    Text(jaudiotaggerLicense, color = Color(0xCCFFFFFF), fontSize = 12.sp)
                    Spacer(Modifier.height(20.dp))
                    Text(
                        "AndroidX, Jetpack Compose, Media3, Coil, OkHttp/Okio, Kotlin runtime, Guava and related runtime libraries use Apache License 2.0.",
                        color = Color.White
                    )
                    Text(apacheLicense, color = Color(0xCCFFFFFF), fontSize = 12.sp)
                    Spacer(Modifier.height(20.dp))
                    Text(
                        "OkHttp ships Public Suffix List data from the Public Suffix List project under the Mozilla Public License 2.0. FLACtify does not modify that data. Its corresponding source is the public list at https://publicsuffix.org/list/public_suffix_list.dat.",
                        color = Color.White
                    )
                    Text(mplLicense, color = Color(0xCCFFFFFF), fontSize = 12.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = { showLicenseDialog = false }) {
                    Text("Close", color = Color(0xFF1DB954))
                }
            }
        )
    }

    pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("復旧データを削除しますか？") },
            text = { Text("音声コピーと対応するメタデータを削除します。この操作は取り消せません。") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    scope.launch {
                        val deleted = withContext(Dispatchers.IO) { recoveryManager.delete(entry) }
                        recoveryEntries = withContext(Dispatchers.IO) { recoveryManager.entries() }
                        recoveryMessage =
                            if (deleted) "Recovery copy deleted." else "Some recovery files could not be deleted."
                    }
                }) { Text("削除", color = Color(0xFFFF7070)) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("キャンセル") } }
        )
    }

    if (showCacheDialog) {
        AlertDialog(
            onDismissRequest = { showCacheDialog = false },
            containerColor = Color(0xFF1E1E1E),
            title = { Text("キャッシュ削除", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "再生成可能なライブラリキャッシュのみ削除します。スキャンやタグ編集の作業ファイル、復旧データ、再生中の音声には影響しません。",
                    color = Color(0xCCFFFFFF)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearCache(context)
                    viewModel.refreshCacheSize(context)
                    showCacheDialog = false
                }) {
                    Text("削除", color = Color(0xFFFF5252))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCacheDialog = false }) {
                    Text("キャンセル", color = Color.White)
                }
            }
        )
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        color = Color(0xFF1DB954),
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(vertical = 4.dp)
    )
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(label, color = Color(0xAAFFFFFF), fontSize = 11.sp)
    }
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
        else -> "${bytes / (1024 * 1024 * 1024)} GB"
    }
}
