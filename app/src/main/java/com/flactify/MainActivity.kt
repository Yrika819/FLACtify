package com.flactify

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.flactify.ui.LibraryScreen
import com.flactify.ui.PlayerScreen
import com.flactify.ui.SettingsScreen
import com.flactify.viewmodel.PlayerViewModel
import com.flactify.viewmodel.StartupState
import androidx.compose.animation.core.animateFloatAsState
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val viewModel: PlayerViewModel by viewModels()

    private val selectFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let { viewModel.loadDirectory(this, it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 🚀 【全画面拡張】画面を上下のシステムバーの裏側まで広げて透明化
        enableEdgeToEdge()

        viewModel.initController(this)

        setContent {
            val isDataReady by viewModel.isReady.collectAsState()
            val startupState by viewModel.startupState.collectAsState()
            val currentThemeColor by viewModel.themeColor.collectAsState()

            var currentScreen by remember { mutableStateOf("splash") }

            // 🚀 【サクサク起動】キャッシュから復元できた時点／検証が始まった時点でスプラッシュを終える。
            // 以前はライブラリ全走査の完了(isReady)までスプラッシュを引っ張っていたが、キャッシュ復元後は
            // 即座にプレイヤーを表示し、フォルダ検証はバックグラウンドで続行する。キャッシュが無い初回も
            // ロゴのまま全走査を待たせず、読み込み中のプレイヤーを先に出す。
            val leaveSplash = isDataReady || startupState !is StartupState.Initializing
            LaunchedEffect(leaveSplash) {
                if (leaveSplash) {
                    delay(100)
                    currentScreen = "player"
                }
            }

            // 🚀 【システムバー制御】画面に合わせて時計やバーの文字色（白・黒）を自動反転
            val view = LocalView.current
            if (!view.isInEditMode) {
                SideEffect {
                    val window = (view.context as android.app.Activity).window

                    // スプラッシュ画面の時はダーク固定、プレイヤー時はアルバムアートの色で判定
                    val activeColor = if (currentScreen == "splash") 0xFF121212.toInt() else currentThemeColor

                    val r = android.graphics.Color.red(activeColor)
                    val g = android.graphics.Color.green(activeColor)
                    val b = android.graphics.Color.blue(activeColor)
                    val luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255
                    val isLightColor = luminance > 0.5

                    val insetsController = WindowCompat.getInsetsController(window, view)
                    insetsController.isAppearanceLightStatusBars = isLightColor
                    insetsController.isAppearanceLightNavigationBars = isLightColor
                }
            }

            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF121212)
                ) {
                    // 🚀 【素早く切り替え】150ms でプレイヤー画面へ移行
                    Crossfade(
                        targetState = currentScreen,
                        animationSpec = tween(durationMillis = 150)
                    ) { screen ->
                        when (screen) {
                            "splash" -> {
                                AnimatedSplashScreen()
                            }
                            "player" -> {
                                PlayerScreen(
                                    viewModel = viewModel,
                                    onSelectFolder = { selectFolderLauncher.launch(null) },
                                    onOpenLibrary = { currentScreen = "library" },
                                    onOpenSettings = { currentScreen = "settings" }
                                )
                            }
                            "library" -> {
                                LibraryScreen(
                                    viewModel = viewModel,
                                    onBackClick = { currentScreen = "player" }
                                )
                            }
                            "settings" -> {
                                SettingsScreen(
                                    viewModel = viewModel,
                                    onBackClick = { currentScreen = "player" }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
fun AnimatedSplashScreen() {
    var startAnimation by remember { mutableStateOf(false) }

    LaunchedEffect(key1 = true) {
        startAnimation = true
    }

    val logoAlpha by animateFloatAsState(
        targetValue = if (startAnimation) 1f else 0f,
        animationSpec = tween(durationMillis = 200)
    )

    val logoScale by animateFloatAsState(
        targetValue = if (startAnimation) 1.0f else 0.9f,
        animationSpec = tween(durationMillis = 200)
    )

    val splashGradient = Brush.verticalGradient(
        colors = listOf(
            Color(0xFF1E1E24),
            Color(0xFF0F0F11),
        )
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(splashGradient),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .scale(logoScale)
                .alpha(logoAlpha),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "FLACtify",
                fontSize = 42.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 4.sp,
                color = Color.White
            )
        }
    }
}