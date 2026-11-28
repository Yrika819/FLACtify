package com.flactify.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import com.flactify.viewmodel.Playlist
import com.flactify.viewmodel.SmartPlaylistOrder
import com.flactify.viewmodel.SmartPlaylistPreset
import com.flactify.viewmodel.SmartPlaylistQuery

@Composable
fun SmartPlaylistEditorDialog(
    existing: Playlist?,
    onDismiss: () -> Unit,
    onSave: (name: String, query: SmartPlaylistQuery, existingId: String?) -> Unit
) {
    var name by remember(existing) { mutableStateOf(existing?.name ?: "") }
    var title by remember(existing) { mutableStateOf(existing?.smartQuery?.titleContains.orEmpty()) }
    var artist by remember(existing) { mutableStateOf(existing?.smartQuery?.artistContains.orEmpty()) }
    var album by remember(existing) { mutableStateOf(existing?.smartQuery?.albumContains.orEmpty()) }
    var genre by remember(existing) { mutableStateOf(existing?.smartQuery?.genreContains.orEmpty()) }
    var format by remember(existing) { mutableStateOf(existing?.smartQuery?.formatContains.orEmpty()) }
    var limit by remember(existing) { mutableStateOf(existing?.smartQuery?.limit?.toString().orEmpty()) }
    var preset by remember(existing) { mutableStateOf(existing?.smartQuery?.preset ?: SmartPlaylistPreset.NONE) }
    var order by remember(existing) { mutableStateOf(existing?.smartQuery?.order ?: SmartPlaylistOrder.LIBRARY) }
    var presetMenu by remember { mutableStateOf(false) }
    var orderMenu by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "新しいスマートプレイリスト" else "スマートプレイリストを編集") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(name, { name = it }, label = { Text("名前") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("条件（複数指定時はすべて一致）", color = Color.Gray)
                QueryField("曲名", title) { title = it }
                QueryField("アーティスト", artist) { artist = it }
                QueryField("アルバム", album) { album = it }
                QueryField("ジャンル", genre) { genre = it }
                QueryField("形式（例: flac）", format) { format = it }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        OutlinedButton(onClick = { presetMenu = true }, modifier = Modifier.fillMaxWidth()) { Text(presetLabel(preset)) }
                        DropdownMenu(expanded = presetMenu, onDismissRequest = { presetMenu = false }) {
                            SmartPlaylistPreset.entries.forEach { item ->
                                DropdownMenuItem(text = { Text(presetLabel(item)) }, onClick = { preset = item; presetMenu = false })
                            }
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        OutlinedButton(onClick = { orderMenu = true }, modifier = Modifier.fillMaxWidth()) { Text(orderLabel(order)) }
                        DropdownMenu(expanded = orderMenu, onDismissRequest = { orderMenu = false }) {
                            SmartPlaylistOrder.entries.forEach { item ->
                                DropdownMenuItem(text = { Text(orderLabel(item)) }, onClick = { order = item; orderMenu = false })
                            }
                        }
                    }
                }
                OutlinedTextField(limit, { limit = it.filter(Char::isDigit).take(5) }, label = { Text("上限（任意）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("最近聴いた曲は再生統計の日時を使います。追加日時は推測しません。", color = Color.Gray)
            }
        },
        confirmButton = {
            Button(
                enabled = name.isNotBlank(),
                onClick = {
                    onSave(
                        name.trim(),
                        SmartPlaylistQuery(
                            titleContains = title.takeIf(String::isNotBlank),
                            artistContains = artist.takeIf(String::isNotBlank),
                            albumContains = album.takeIf(String::isNotBlank),
                            genreContains = genre.takeIf(String::isNotBlank),
                            formatContains = format.takeIf(String::isNotBlank),
                            preset = preset,
                            order = order,
                            limit = limit.toIntOrNull()?.takeIf { it > 0 }
                        ),
                        existing?.id
                    )
                    onDismiss()
                }
            ) { Text("保存") }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("キャンセル") } }
    )
}

@Composable
private fun QueryField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(value, onValueChange, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
}

private fun presetLabel(value: SmartPlaylistPreset): String = when (value) {
    SmartPlaylistPreset.NONE -> "プリセットなし"
    SmartPlaylistPreset.FREQUENT -> "よく聴く曲"
    SmartPlaylistPreset.RECENT -> "最近聴いた曲"
    SmartPlaylistPreset.FAVORITES -> "お気に入り"
    SmartPlaylistPreset.LOW_PLAY_COUNT -> "再生回数が少ない曲"
    SmartPlaylistPreset.GENRE -> "ジャンル"
    SmartPlaylistPreset.FORMAT -> "形式"
}

private fun orderLabel(value: SmartPlaylistOrder): String = when (value) {
    SmartPlaylistOrder.LIBRARY -> "元の順序"
    SmartPlaylistOrder.TITLE -> "曲名順"
    SmartPlaylistOrder.ARTIST -> "アーティスト順"
    SmartPlaylistOrder.ALBUM -> "アルバム順"
    SmartPlaylistOrder.YEAR -> "年順"
    SmartPlaylistOrder.FORMAT -> "形式順"
    SmartPlaylistOrder.PLAY_COUNT_DESC -> "再生回数順"
    SmartPlaylistOrder.LAST_PLAYED_DESC -> "最終再生順"
}
