package com.flactify.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flactify.audio.PcmEncoding
import com.flactify.audio.PlaybackDiagnostics

internal fun formatTime(ms: Long): String {
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
internal fun AudioDiagnosticsSheet(diagnostics: PlaybackDiagnostics) {
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
