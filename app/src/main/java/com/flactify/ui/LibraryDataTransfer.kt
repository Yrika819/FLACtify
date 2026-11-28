package com.flactify.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.flactify.viewmodel.LibraryBackupCodec
import com.flactify.viewmodel.LibraryBackupPreview
import com.flactify.viewmodel.PlayerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets

private const val MAX_BACKUP_BYTES = 32 * 1024 * 1024

@Composable
fun LibraryBackupTransferSection(viewModel: PlayerViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingJson by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<LibraryBackupPreview?>(null) }
    var pendingRestorePreview by remember { mutableStateOf<LibraryBackupPreview?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(viewModel) {
        try {
            pendingRestorePreview = withContext(Dispatchers.IO) {
                viewModel.pendingLibraryBackupPreview()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            message = "保留中のバックアップ状態を確認できませんでした"
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { destination ->
        if (destination != null) {
            scope.launch {
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(destination, "w")?.use { output ->
                            output.write(viewModel.createLibraryBackup().toByteArray(StandardCharsets.UTF_8))
                        } ?: error("Unable to open selected destination")
                    }
                }
                message = if (result.isSuccess) "バックアップを書き出しました" else "書き出しに失敗しました"
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { source ->
        if (source != null) {
            scope.launch {
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(source)?.use { input -> readSafBytesBounded(input, MAX_BACKUP_BYTES) }
                            ?: error("Unable to open selected backup")
                    }
                }
                val json = result.getOrNull()?.toString(StandardCharsets.UTF_8)
                val decodedPreview = json?.let(LibraryBackupCodec::preview)
                if (decodedPreview != null) {
                    pendingJson = json
                    preview = decodedPreview
                    message = null
                } else {
                    message = "バックアップの形式またはサイズが不正です"
                }
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        pendingRestorePreview?.let { pending ->
            Text(
                "前回の復元が完了しませんでした。保留中のバックアップ: お気に入り ${pending.favoriteCount}件、" +
                    "プレイリスト ${pending.playlistCount}件、再生統計 ${pending.statsCount}件",
                color = Color(0xFFFFCC80), fontSize = 12.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    scope.launch {
                        try {
                            val restored = withContext(Dispatchers.IO) {
                                viewModel.retryPendingLibraryBackupRestore()
                            }
                            message = if (restored) "保留中のバックアップを復元しました" else "復元に失敗しました。元データと保留中のバックアップを確認してください"
                            pendingRestorePreview = withContext(Dispatchers.IO) {
                                viewModel.pendingLibraryBackupPreview()
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            message = "復元に失敗しました。元データと保留中のバックアップを確認してください"
                        }
                    }
                }) { Text("復元を再試行") }
                OutlinedButton(onClick = {
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { viewModel.discardPendingLibraryBackupRestore() }
                            pendingRestorePreview = null
                            message = "保留中のバックアップを破棄しました"
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            message = "保留中のバックアップを破棄できませんでした"
                        }
                    }
                }) { Text("保留を破棄", color = Color.White) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { exportLauncher.launch("flactify-library-backup.json") }) { Text("バックアップを書き出す") }
            OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }) {
                Text("バックアップを読み込む", color = Color.White)
            }
        }
        Text(
            "お気に入り、プレイリスト、再生統計だけを保存します。音声・アートワーク・認証情報は含みません。",
            color = Color(0xCCFFFFFF), fontSize = 12.sp
        )
        message?.let { Text(it, color = Color(0xCCFFFFFF), fontSize = 12.sp) }
    }

    val currentPreview = preview
    if (currentPreview != null && pendingJson != null) {
        AlertDialog(
            onDismissRequest = { preview = null; pendingJson = null },
            title = { Text("バックアップを確認") },
            text = {
                Text(
                    "Version ${currentPreview.version}\nお気に入り: ${currentPreview.favoriteCount}\n" +
                        "プレイリスト: ${currentPreview.playlistCount}\n再生統計: ${currentPreview.statsCount}\n\n" +
                        "既存データとの競合方法を選択してください。"
                )
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(6.dp)) {
                    Button(onClick = {
                        val json = pendingJson
                        if (json != null) {
                            scope.launch {
                                try {
                                    val restored = withContext(Dispatchers.IO) {
                                        viewModel.restoreLibraryBackup(json, merge = true)
                                    }
                                    message = if (restored) "既存データを保持して統合しました" else "復元に失敗しました"
                                    preview = null
                                    pendingJson = null
                                    pendingRestorePreview = withContext(Dispatchers.IO) {
                                        viewModel.pendingLibraryBackupPreview()
                                    }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    message = "復元に失敗しました。既存データと保留中のバックアップを確認してください"
                                }
                            }
                        }
                    }) { Text("統合") }
                    Button(
                        onClick = {
                            val json = pendingJson
                            if (json != null) {
                                scope.launch {
                                    try {
                                        val restored = withContext(Dispatchers.IO) {
                                            viewModel.restoreLibraryBackup(json)
                                        }
                                        message = if (restored) "バックアップを復元しました" else "復元に失敗しました"
                                        preview = null
                                        pendingJson = null
                                        pendingRestorePreview = withContext(Dispatchers.IO) {
                                            viewModel.pendingLibraryBackupPreview()
                                        }
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (_: Exception) {
                                        message = "復元に失敗しました。既存データと保留中のバックアップを確認してください"
                                    }
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB3261E))
                    ) { Text("置き換え") }
                }
            },
            dismissButton = { OutlinedButton(onClick = { preview = null; pendingJson = null }) { Text("キャンセル") } }
        )
    }
}

internal fun readSafBytesBounded(input: java.io.InputStream, maxBytes: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    var total = 0
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        total += count
        require(total <= maxBytes) { "Selected file exceeds the safe size limit" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
