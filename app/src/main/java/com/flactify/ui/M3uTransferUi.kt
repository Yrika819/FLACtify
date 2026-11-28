package com.flactify.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.flactify.viewmodel.M3uPlaylistCodec
import com.flactify.viewmodel.Playlist
import com.flactify.viewmodel.TrackData
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

private data class M3uEncoding(val label: String, val charset: Charset)
private val encodings = listOf(
    M3uEncoding("UTF-8", StandardCharsets.UTF_8),
    M3uEncoding("UTF-16", StandardCharsets.UTF_16),
    M3uEncoding("ISO-8859-1", StandardCharsets.ISO_8859_1)
)

@Composable
fun M3uImportDialog(
    bytes: ByteArray,
    fileName: String,
    tracks: List<TrackData>,
    playlists: List<Playlist>,
    onDismiss: () -> Unit,
    onImport: (name: String, uris: List<String>, replaceId: String?) -> Unit
) {
    var encoding by remember(bytes) { mutableStateOf(encodings.first()) }
    var name by remember(fileName) { mutableStateOf(fileName.substringBeforeLast('.').ifBlank { "Imported playlist" }) }
    var encodingMenu by remember { mutableStateOf(false) }
    var replaceExisting by remember { mutableStateOf(false) }
    val parsed = remember(bytes, encoding) {
        M3uPlaylistCodec.parse(bytes.toString(encoding.charset))
    }
    // SAF library entries are content:// URIs. Restrict imports to those already present in the
    // current grant-backed library; never open a path, file:// URI, or network URL from M3U text.
    val known = remember(tracks) {
        tracks.map { it.uri.toString() }
            .filter { it.startsWith("content://", ignoreCase = true) }
            .associateWith { value -> tracks.first { it.uri.toString() == value } }
    }
    val resolvedUris = parsed.entries.mapNotNull { known[it.location]?.uri?.toString() }.distinct()
    val unresolved = parsed.entries.count { !known.containsKey(it.location) }
    val existing = playlists.firstOrNull { it.name == name.trim() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("M3Uプレイリストを確認") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(name, { name = it }, label = { Text("プレイリスト名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { encodingMenu = true }) { Text(encoding.label) }
                    DropdownMenu(expanded = encodingMenu, onDismissRequest = { encodingMenu = false }) {
                        encodings.forEach { item ->
                            DropdownMenuItem(text = { Text(item.label) }, onClick = { encoding = item; encodingMenu = false })
                        }
                    }
                    Text("${parsed.entries.size}件", modifier = Modifier.padding(top = 12.dp))
                }
                Text("解決済み: ${resolvedUris.size}件 / 未解決: ${unresolved}件", color = if (unresolved == 0) Color.White else Color(0xFFFFB74D))
                if (parsed.warnings.isNotEmpty()) {
                    Text("注意: ${parsed.warnings.take(3).joinToString("；")}", color = Color(0xFFFFB74D))
                }
                if (existing != null) {
                    Text("同名プレイリストがあります", color = Color(0xFFFFB74D))
                    OutlinedButton(onClick = { replaceExisting = !replaceExisting }) {
                        Text(if (replaceExisting) "既存を置き換える" else "新しい名前として追加")
                    }
                }
                Text("選択したSAFファイルと現在のライブラリに一致するURIだけを取り込みます。外部パスやネットワークURLは開きません。", color = Color.Gray)
            }
        },
        confirmButton = {
            Button(
                enabled = name.isNotBlank() && resolvedUris.isNotEmpty(),
                onClick = onClick@{
                    onImport(name.trim(), resolvedUris, if (replaceExisting) existing?.id else null)
                    onDismiss()
                }
            ) { Text("取り込む") }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("キャンセル") } }
    )
}
