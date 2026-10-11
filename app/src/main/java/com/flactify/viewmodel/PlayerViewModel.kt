package com.flactify.viewmodel

import android.util.Log
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.net.Uri
import androidx.annotation.OptIn
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionResult
import com.flactify.PlaybackSettings
import com.flactify.audio.spatial.SpatialAudioMode
import com.flactify.audio.spatial.SpatialHrtfMixPercent
import com.flactify.audio.AudioRouteMonitor
import com.flactify.audio.AudioSourceReader
import com.flactify.audio.DirectPlaybackProbe
import com.flactify.audio.PlaybackDiagnostics
import com.flactify.audio.PlaybackDiagnosticsBus
import com.flactify.audio.SourceAudioInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.common.util.concurrent.MoreExecutors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Startup lifecycle for the cache-first library restore.
 *
 * The library is only authoritative once [Ready] is reached, after a validation scan completes.
 * [CacheRestored] means the previous library is being shown immediately from cache so the splash
 * can end, but it is still provisional until validation confirms the folder and the files.
 * [Validating] is the first-launch / no-cache path where there is nothing to show yet.
 * [Error] keeps whatever provisional library is on screen and surfaces the failure.
 */
sealed interface StartupState {
    data object Initializing : StartupState
    data class Validating(val provisionalTrackCount: Int) : StartupState
    data class CacheRestored(val provisionalTrackCount: Int) : StartupState
    data object Ready : StartupState
    data class Error(val message: String, val keptProvisionalLibrary: Boolean) : StartupState
}

// 🚀 【データクラス】重複比較ができるように equals/hashCode を明示的にオーバーライド
data class TrackData(
    val title: String,
    val artist: String,
    val album: String = "不明なアルバム",
    val trackNumber: String? = null,
    val genre: String? = null,
    val year: String? = null,
    val composer: String? = null,
    val albumArtist: String? = null,
    val discNumber: String? = null,
    val comment: String? = null,
    val originalIndex: Int,
    val uri: Uri,
    val albumArtBytes: ByteArray? = null,
    val lastModified: Long = 0L
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as TrackData
        return uri == other.uri
    }

    override fun hashCode(): Int = uri.hashCode()
}

/** Guards ViewModel-owned services from being installed again after Activity recreation. */
internal class ViewModelInitializationGate {
    private val started = AtomicBoolean(false)
    fun begin(): Boolean = started.compareAndSet(false, true)
}

@OptIn(UnstableApi::class)
class PlayerViewModel : ViewModel() {
    // Kept as a compatibility mirror for existing tests and tag-edit integration.
    private var controller: MediaController? = null
    private var playbackController: PlaybackController? = null
    private var sharedPreferences: SharedPreferences? = null
    private var userLibraryController: UserLibraryController? = null
    private var recommendationController: RecommendationController? = null
    private var metadataExtractor: TrackMetadataExtractor? = null
    private var tagEditor: TagEditor? = null
    private var libraryScanner: LibraryScanner? = null

    private val _preloadedCache = CompletableDeferred<LibraryCacheSnapshot?>()
    @Volatile
    private var currentCacheSnapshot: LibraryCacheSnapshot? = null

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady

    private val _startupState = MutableStateFlow<StartupState>(StartupState.Initializing)
    val startupState: StateFlow<StartupState> = _startupState

    // Every library intention (startup restore, folder switch) takes a generation. A result that
    // arrives under an older generation must never overwrite the library, the saved cache or the
    // player queue of a newer intention.
    private val libraryGeneration = AtomicLong(0L)
    // Reference count so a cancelled scan's finally block cannot clear the indicator of a newer
    // scan that is still running.
    private val activeScanCount = AtomicInteger(0)
    private var validateJob: Job? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _pauseOnBluetoothDisconnect = MutableStateFlow(
        PlaybackSettings.PAUSE_ON_BLUETOOTH_DISCONNECT_DEFAULT
    )
    val pauseOnBluetoothDisconnect: StateFlow<Boolean> = _pauseOnBluetoothDisconnect

    private val _currentTrackName = MutableStateFlow("未再生")
    val currentTrackName: StateFlow<String> = _currentTrackName

    private val _currentArtist = MutableStateFlow("")
    val currentArtist: StateFlow<String> = _currentArtist

    private val _albumArt = MutableStateFlow<Bitmap?>(null)
    val albumArt: StateFlow<Bitmap?> = _albumArt

    private val _themeColor = MutableStateFlow(android.graphics.Color.parseColor("#121212"))
    val themeColor: StateFlow<Int> = _themeColor

    private val _lyrics = MutableStateFlow<List<Pair<Long, String>>>(emptyList())
    val lyrics: StateFlow<List<Pair<Long, String>>> = _lyrics

    private val _trackList = MutableStateFlow<List<String>>(emptyList())
    val trackList: StateFlow<List<String>> = _trackList

    private val _artistMap = MutableStateFlow<Map<String, List<TrackData>>>(emptyMap())
    val artistMap: StateFlow<Map<String, List<TrackData>>> = _artistMap

    private val _albumMap = MutableStateFlow<Map<String, List<TrackData>>>(emptyMap())
    val albumMap: StateFlow<Map<String, List<TrackData>>> = _albumMap

    private val _isScanningArtists = MutableStateFlow(false)
    val isScanningArtists: StateFlow<Boolean> = _isScanningArtists
    private val _scanTotal = MutableStateFlow(0)
    val scanTotal: StateFlow<Int> = _scanTotal
    private val _scanCompleted = MutableStateFlow(0)
    val scanCompleted: StateFlow<Int> = _scanCompleted
    private val _scanCurrentFile = MutableStateFlow("")
    val scanCurrentFile: StateFlow<String> = _scanCurrentFile
    private val _scanFailures = MutableStateFlow<List<String>>(emptyList())
    val scanFailures: StateFlow<List<String>> = _scanFailures

    // Tag editing writes back to files through the selected tree URI.
    // Keep this explicit so the UI can explain why editing is unavailable
    // when an older/read-only grant is restored after process death.
    private val _canEditMetadata = MutableStateFlow(false)
    val canEditMetadata: StateFlow<Boolean> = _canEditMetadata

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration

    private val _isShuffleMode = MutableStateFlow(false)
    val isShuffleMode: StateFlow<Boolean> = _isShuffleMode

    private val _repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF)
    val repeatMode: StateFlow<Int> = _repeatMode

    private val _audioInfo = MutableStateFlow("")
    val audioInfo: StateFlow<String> = _audioInfo

    private val _bluetoothCodecInfo = MutableStateFlow("")
    val bluetoothCodecInfo: StateFlow<String> = _bluetoothCodecInfo

    // ── Audio diagnostics (V1) ──
    // Source / route / capability / measured output are kept in separate fields of
    // PlaybackDiagnostics so a capability can never be presented as a measurement.
    private val _diagnostics = MutableStateFlow(PlaybackDiagnostics())
    val diagnostics: StateFlow<PlaybackDiagnostics> = _diagnostics

    private val _spatialAudioMode = MutableStateFlow(SpatialAudioMode.OFF)
    val spatialAudioMode: StateFlow<SpatialAudioMode> = _spatialAudioMode
    private val _spatialHrtfMixPercent =
        MutableStateFlow(SpatialHrtfMixPercent.DEFAULT_PERCENT)
    val spatialHrtfMixPercent: StateFlow<Int> = _spatialHrtfMixPercent
    private val _isChangingSpatialAudioMode = MutableStateFlow(false)
    val isChangingSpatialAudioMode: StateFlow<Boolean> = _isChangingSpatialAudioMode
    private val _spatialAudioModeError = MutableStateFlow<String?>(null)
    val spatialAudioModeError: StateFlow<String?> = _spatialAudioModeError

    private var audioSourceReader: AudioSourceReader? = null
    private var directPlaybackProbe: DirectPlaybackProbe? = null
    private var audioRouteMonitor: AudioRouteMonitor? = null
    private var diagnosticsJob: Job? = null
    private var positionPersister = PlaybackPositionPersister()

    /** URI whose details are currently displayed, used to skip duplicate extractions. */
    private var detailsUri: Uri? = null

    /** True once [detailsUri] finished loading, so a re-announce can be skipped outright. */
    private var detailsSettled = false

    private var metadataJob: Job? = null
    private var scanJob: Job? = null
    private var preloadJob: Job? = null

    private val _isSavingMetadata = MutableStateFlow(false)
    val isSavingMetadata: StateFlow<Boolean> = _isSavingMetadata
    private val isSavingMetadataGuard = AtomicBoolean(false)
    private var shouldRestorePlaybackState = true

    // ── Favorites ──
    private val _favorites = MutableStateFlow<Set<String>>(emptySet())
    val favorites: StateFlow<Set<String>> = _favorites

    // ── Playlists ──
    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists

    // ── Sleep Timer ──
    private val _sleepTimerMinutes = MutableStateFlow(0)
    val sleepTimerMinutes: StateFlow<Int> = _sleepTimerMinutes
    private var sleepTimerController: SleepTimerController? = null
    private val initializationGate = ViewModelInitializationGate()

    // ── Recommendations ──
    private val _recommendations = MutableStateFlow<List<RecommendedTrack>>(emptyList())
    val recommendations: StateFlow<List<RecommendedTrack>> = _recommendations

    private val _isLoadingRecommendations = MutableStateFlow(false)
    val isLoadingRecommendations: StateFlow<Boolean> = _isLoadingRecommendations

    private val _recommendationError = MutableStateFlow<String?>(null)
    val recommendationError: StateFlow<String?> = _recommendationError

    private val _lastRecommendationUpdate = MutableStateFlow(0L)
    val lastRecommendationUpdate: StateFlow<Long> = _lastRecommendationUpdate

    // ── Cache ──
    private val _cacheSize = MutableStateFlow(0L)
    val cacheSize: StateFlow<Long> = _cacheSize

    val currentLyrics: StateFlow<String> = combine(currentPosition, lyrics) { position, lyricList ->
        if (lyricList.isEmpty()) return@combine ""
        val timedLyrics = lyricList.filter { it.first >= 0L }
        if (timedLyrics.isEmpty()) {
            lyricList.joinToString("\n") { it.second }
        } else {
            timedLyrics.lastOrNull { it.first <= position }?.second ?: ""
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ""
    )

    fun initController(context: Context) {
        if (!initializationGate.begin()) return
        // The ViewModel can outlive an Activity after configuration changes. Resources it owns
        // must keep the application context rather than retaining the Activity.
        val appContext = context.applicationContext
        sharedPreferences = appContext.getSharedPreferences(
            PlaybackSettings.PREFERENCES_NAME,
            Context.MODE_PRIVATE
        )
        _pauseOnBluetoothDisconnect.value = sharedPreferences?.getBoolean(
            PlaybackSettings.PAUSE_ON_BLUETOOTH_DISCONNECT_KEY,
            PlaybackSettings.PAUSE_ON_BLUETOOTH_DISCONNECT_DEFAULT
        ) ?: PlaybackSettings.PAUSE_ON_BLUETOOTH_DISCONNECT_DEFAULT
        _spatialAudioMode.value = SpatialAudioMode.fromWireValue(
            sharedPreferences?.getString(SpatialAudioMode.PREFERENCE_KEY, null)
        )
        _spatialHrtfMixPercent.value = sharedPreferences
            ?.getInt(
                SpatialAudioMode.PREFERENCE_KEY_HRTF_MIX_PERCENT,
                SpatialHrtfMixPercent.DEFAULT_PERCENT
            )
            ?.takeIf(SpatialHrtfMixPercent::isValidPercent)
            ?: SpatialHrtfMixPercent.DEFAULT_PERCENT
        userLibraryController = UserLibraryController(appContext)
        recommendationController = RecommendationController(appContext)
        metadataExtractor = TrackMetadataExtractor(appContext)
        tagEditor = TagEditor()
        audioSourceReader = AudioSourceReader(appContext)
        directPlaybackProbe = DirectPlaybackProbe(appContext)
        audioRouteMonitor = AudioRouteMonitor(appContext)
        // Follow connect/disconnect events so plugging in a DAC or a headset mid-track updates
        // the route immediately instead of leaving the previous track's route on screen.
        audioRouteMonitor?.startObserving { onRouteChanged() }
        positionPersister = PlaybackPositionPersister()
        observeMeasuredOutput()
        libraryScanner = LibraryScanner(metadataExtractor)
        sleepTimerController = SleepTimerController(viewModelScope) {
            playbackController?.pause()
            _sleepTimerMinutes.value = 0
        }
        _favorites.value = userLibraryController?.getFavorites() ?: emptySet()
        _playlists.value = userLibraryController?.getPlaylists() ?: emptyList()

        sharedPreferences?.getString("last_uri", null)?.let { folderUriString ->
            _canEditMetadata.value = hasPersistedWritePermission(appContext, Uri.parse(folderUriString))
        }

        preloadJob = viewModelScope.launch(Dispatchers.IO) {
            val lastUriStr = sharedPreferences?.getString("last_uri", null)
            val lastUri = lastUriStr?.let { Uri.parse(it) }
            val cached = try {
                scanner().loadCacheSnapshot(appContext)
            } catch (e: Exception) {
                null
            }
            currentCacheSnapshot = cached
            _preloadedCache.complete(cached)
        }

        viewModelScope.launch(Dispatchers.IO) {
            val size = userLibraryController?.getCacheDirSize() ?: 0L
            withContext(Dispatchers.Main) { _cacheSize.value = size }
        }

        playbackController = PlaybackController(
            scope = viewModelScope,
            onPlayingChanged = { isPlaying ->
                _isPlaying.value = isPlaying
                if (!isPlaying) persistPlaybackPosition()
            },
            onMetadataChanged = { metadata, uri ->
                // Flush before the item changes so a transition cannot lose the last position.
                persistPlaybackPosition()
                _currentTrackName.value = metadata.title?.toString() ?: "不明"
                _currentArtist.value = metadata.artist?.toString() ?: "不明なアーティスト"
                updateTrackDetails(appContext, uri)
            },
            onTracksChanged = { _, uri ->
                persistPlaybackPosition()
                updateTrackDetails(appContext, uri)
            },
            onShuffleChanged = { enabled ->
                _isShuffleMode.value = enabled
                sharedPreferences?.edit()?.putBoolean("shuffle", enabled)?.apply()
            },
            onRepeatChanged = { mode ->
                _repeatMode.value = mode
                sharedPreferences?.edit()?.putInt("repeat", mode)?.apply()
            },
            onProgress = { position, duration ->
                if (duration > 0) {
                    _currentPosition.value = position
                    _duration.value = duration
                    // UI updates stay at the 250 ms poll rate; only the persister decides
                    // whether this tick is worth a disk write.
                    positionPersister.onProgress(playbackController?.currentUri()?.toString(), position)
                        ?.let { checkpoint ->
                            sharedPreferences?.edit()
                                ?.putString("playback_uri", checkpoint.uri)
                                ?.putLong("playback_position", checkpoint.positionMs)
                                ?.apply()
                        }
                }
            }
        )
        playbackController?.initialize(
            context = appContext,
            onReady = { readyController ->
                controller = readyController
                playbackController?.syncState {
                    _currentTrackName.value = "未再生"
                    _currentArtist.value = ""
                    updateTrackDetails(appContext, null)
                }
                val lastUriStr = sharedPreferences?.getString("last_uri", null)
                if (lastUriStr != null) {
                    // Startup restore: show the cached library immediately, then validate.
                    restoreStartupLibrary(appContext, Uri.parse(lastUriStr))
                } else {
                    // No folder has ever been selected; nothing to scan or restore.
                    _startupState.value = StartupState.Ready
                    _isReady.value = true
                }
            },
            onFailure = {
                // The player is unavailable, so the user only sees local library state.
                _startupState.value = StartupState.Error("プレイヤーに接続できませんでした", false)
                _isReady.value = true
            }
        )
    }

    fun setPauseOnBluetoothDisconnect(enabled: Boolean) {
        _pauseOnBluetoothDisconnect.value = enabled
        sharedPreferences?.edit()
            ?.putBoolean(PlaybackSettings.PAUSE_ON_BLUETOOTH_DISCONNECT_KEY, enabled)
            ?.apply()
    }

    private fun updateTrackDetails(context: Context, uri: Uri?) {
        if (uri == null) {
            _albumArt.value = null
            _themeColor.value = android.graphics.Color.parseColor("#121212")
            _lyrics.value = emptyList()
            _audioInfo.value = ""
            _bluetoothCodecInfo.value = ""
            _diagnostics.value = PlaybackDiagnostics()
            detailsUri = null
            detailsSettled = false
            return
        }

        // onMediaMetadataChanged and onTracksChanged both fire for a single track change, and
        // onTracksChanged can fire more than once. Re-reading the file each time cancelled
        // and restarted the extraction, so skip while this track is already current or in
        // flight. A failed load leaves the state unsettled so a later re-announce retries.
        if (uri == detailsUri && (detailsSettled || metadataJob?.isActive == true)) return
        detailsUri = uri
        detailsSettled = false
        metadataJob?.cancel()
        diagnosticsJob?.cancel()
        // Clear the previous track's figures immediately so the screen never shows one
        // track's sample rate against another track's route.
        _diagnostics.value = PlaybackDiagnostics()

        val extractor = metadataExtractor ?: return
        val cachedMetadata = extractor.getCachedMetadata(uri)
        if (cachedMetadata != null) {
            _albumArt.value = cachedMetadata.albumArt
            _themeColor.value = cachedMetadata.themeColor
            _lyrics.value = cachedMetadata.lyrics
            _audioInfo.value = cachedMetadata.audioInfo
            _bluetoothCodecInfo.value = cachedMetadata.bluetoothCodecInfo
            detailsSettled = true
        } else {
            metadataJob = viewModelScope.launch(Dispatchers.IO) {
                val metadata = extractor.extractMetadata(uri)
                withContext(Dispatchers.Main) {
                    // Only settle if this job still describes the displayed track; a newer
                    // track may have superseded it while the read was in flight.
                    if (detailsUri == uri) {
                        _albumArt.value = metadata.albumArt
                        _themeColor.value = metadata.themeColor
                        _lyrics.value = metadata.lyrics
                        _audioInfo.value = metadata.audioInfo
                        _bluetoothCodecInfo.value = metadata.bluetoothCodecInfo
                        detailsSettled = true
                    }
                }
            }
        }

        refreshDiagnostics(uri)
    }

    /**
     * Refreshes route and direct-playback capability for the current item.
     *
     * The measured decoder/output figures are not written here: those are owned by
     * `PlaybackDiagnosticsBus` and arrive from the playback service, so a capability refresh
     * can never overwrite a measurement.
     */
    private fun refreshDiagnostics(uri: Uri) {
        val probe = directPlaybackProbe
        val monitor = audioRouteMonitor
        val reader = audioSourceReader
        val extension = metadataExtractor?.fileExtensionCache?.get(uri)
        val container = extension?.let { ext ->
            when (ext) {
                "flac" -> "FLAC"
                "wav" -> "WAV"
                "m4a" -> "ALAC/AAC"
                "mp3" -> "MP3"
                else -> ext.uppercase()
            }
        }

        diagnosticsJob = viewModelScope.launch(Dispatchers.IO) {
            val source = if (reader != null) {
                reader.read(uri, container)
            } else {
                SourceAudioInfo(container, null, null, null)
            }
            val route = monitor?.currentRoute()
            // No defaults: an unknown rate or channel count means the probe cannot be asked
            // a meaningful question, so the capability stays unknown rather than assuming
            // 48 kHz stereo and reporting "Direct supported" for a format nobody played.
            val capability = probe?.probe(source.sampleRateHz, source.channelCount)
                ?: com.flactify.audio.DirectPlaybackCapability.unknown("probe を初期化できていません")
            withContext(Dispatchers.Main) {
                _diagnostics.value = _diagnostics.value.copy(
                    source = source,
                    route = route,
                    capabilities = capability
                )
            }
        }
    }

    /**
     * Re-reads only the route and direct capability.
     *
     * Split from [refreshDiagnostics] because a device change says nothing new about the file:
     * re-running the metadata and STREAMINFO reads on every plug event would be wasted I/O.
     */
    private fun onRouteChanged() {
        val uri = detailsUri ?: return
        val monitor = audioRouteMonitor ?: return
        val probe = directPlaybackProbe
        val source = _diagnostics.value.source
        diagnosticsJob?.cancel()
        diagnosticsJob = viewModelScope.launch(Dispatchers.IO) {
            val route = monitor.currentRoute()
            val capability = probe?.probe(source.sampleRateHz, source.channelCount)
                ?: com.flactify.audio.DirectPlaybackCapability.unknown("probe を初期化できていません")
            withContext(Dispatchers.Main) {
                // Do not resurrect a route for a track the user has already left.
                if (detailsUri != uri) return@withContext
                _diagnostics.value = _diagnostics.value.copy(route = route, capabilities = capability)
            }
        }
    }

    /** Forces a position write if one is outstanding. Safe to call from any state. */
    private fun persistPlaybackPosition() {
        val checkpoint = positionPersister.flush() ?: return
        sharedPreferences?.edit()
            ?.putString("playback_uri", checkpoint.uri)
            ?.putLong("playback_position", checkpoint.positionMs)
            ?.apply()
    }

    /**
     * Merges the playback service's measurements into [diagnostics].
     *
     * Kept in one place so the measured renderer/output fields are written only from measured
     * data. Capability refreshes in [refreshDiagnostics] never touch them, and so cannot
     * overwrite a real observation with a prediction.
     */
    private fun observeMeasuredOutput() {
        viewModelScope.launch {
            combine(
                PlaybackDiagnosticsBus.renderer,
                PlaybackDiagnosticsBus.actualOutput,
                PlaybackDiagnosticsBus.spatial,
                PlaybackDiagnosticsBus.media3AudioSink
            ) { renderer, output, spatial, media3AudioSink ->
                _diagnostics.value.copy(
                    renderer = renderer,
                    actualOutput = output,
                    spatial = spatial,
                    media3AudioSink = media3AudioSink
                )
            }.collect { diagnostics ->
                _diagnostics.value = diagnostics
            }
        }
    }


    // 🏎💨 フォルダ切り替え（ユーザー操作）。正式なライブラリはこのスキャンが成功して初めて切り替わる。
    fun loadDirectory(context: Context, uri: Uri) {
        // A new folder intention supersedes any in-flight folder switch or startup validation.
        validateJob?.cancel()
        validateJob = null
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            performDirectoryLoad(context.applicationContext, uri)
        }
    }

    /**
     * The folder-switch body, extracted so it can be driven deterministically from tests without a
     * leaked [viewModelScope] coroutine (which leaves an active SupervisorJob that [runTest] waits
     * on). [loadDirectory] is a thin launcher around this.
     */
    internal suspend fun performDirectoryLoad(appContext: Context, uri: Uri) {
        val generation = libraryGeneration.incrementAndGet()
        beginScanIndicator()
        try {
            _scanTotal.value = 0
            _scanCompleted.value = 0
            _scanCurrentFile.value = ""
            _scanFailures.value = emptyList()

            // Requesting a persistable grant is not a folder switch. Keep the previous
            // library, queue, URI and edit capability until the requested tree scans.
            persistFolderPermission(appContext, uri)
            val cachedSnapshot = currentCacheSnapshot ?: _preloadedCache.await()
            val cachedTracks = cachedSnapshot
                ?.takeIf { LibraryScanner.ownsFolder(it, uri) }
                ?.tracks
                .orEmpty()
            val result = scanner().scan(
                appContext, uri, cachedTracks,
                onProgress = { progress ->
                    if (libraryGeneration.get() == generation) applyScanProgress(progress)
                },
                shouldPersist = { libraryGeneration.get() == generation }
            )
            if (libraryGeneration.get() != generation) return
            if (!result.scanSucceeded) {
                _scanFailures.value = result.failedFiles.ifEmpty {
                    listOfNotNull(result.failure?.message ?: "フォルダを読み取れませんでした")
                }
                _isReady.value = true
                return
            }

            val tracks = result.tracks
            updateLibraryState(tracks)
            withContext(Dispatchers.Main) {
                setupPlayerPlaylist(tracks, forceRandom = cachedTracks.isEmpty())
            }
            sharedPreferences?.edit()?.putString("last_uri", uri.toString())?.apply()
            _canEditMetadata.value = hasPersistedWritePermission(appContext, uri)
            currentCacheSnapshot = LibraryCacheSnapshot(uri, tracks)
            _preloadedCache.complete(currentCacheSnapshot)
            _scanFailures.value = result.failedFiles
            _isReady.value = true
        } finally {
            endScanIndicator()
        }
    }

    /**
     * Cache-first startup restore for the persisted folder. Unlike [loadDirectory] this never
     * treats the result as a folder switch: it first shows the cached library so the splash can
     * end, then validates the folder in the background and commits only the validated result.
     *
     * The cached library is only shown when the cache owns the exact folder URI and a read grant
     * for it is still persisted; it is provisional until [StartupState.Ready]. A validation
     * failure keeps whatever provisional library is on screen and surfaces the failure instead of
     * clearing the library the user is looking at.
     */
    private fun restoreStartupLibrary(appContext: Context, uri: Uri) {
        scanJob?.cancel()
        scanJob = null
        validateJob?.cancel()
        validateJob = viewModelScope.launch {
            performRestoreStartupLibrary(appContext, uri)
        }
    }

    /**
     * The cache-first startup body, extracted for the same reason as [performDirectoryLoad]: tests
     * drive it directly to avoid a leaked [viewModelScope] coroutine.
     */
    internal suspend fun performRestoreStartupLibrary(appContext: Context, uri: Uri) {
        val generation = libraryGeneration.incrementAndGet()
        var restoredProvisional = false
        beginScanIndicator()
        try {
            _scanTotal.value = 0
            _scanCompleted.value = 0
            _scanCurrentFile.value = ""
            _scanFailures.value = emptyList()

            val cachedSnapshot = currentCacheSnapshot ?: _preloadedCache.await()
            val cachedTracks = cachedSnapshot
                ?.takeIf { LibraryScanner.ownsFolder(it, uri) }
                ?.tracks
                .orEmpty()
            val canRead = hasPersistedReadPermission(appContext, uri)

            if (cachedTracks.isNotEmpty() && canRead && libraryGeneration.get() == generation) {
                // Provisional restore from cache: show the last library now and keep validating.
                restoredProvisional = true
                updateLibraryState(cachedTracks)
                _isReady.value = true
                _startupState.value = StartupState.CacheRestored(cachedTracks.size)
                withContext(Dispatchers.Main) {
                    setupPlayerPlaylist(cachedTracks, forceRandom = false)
                }
            } else if (libraryGeneration.get() == generation) {
                // No usable cache: surface a validating state instead of blocking the splash.
                _startupState.value = StartupState.Validating(0)
            }

            persistFolderPermission(appContext, uri)

            val result = scanner().scan(
                appContext, uri, cachedTracks,
                onProgress = { progress ->
                    if (libraryGeneration.get() == generation) applyScanProgress(progress)
                },
                shouldPersist = { libraryGeneration.get() == generation }
            )
            if (libraryGeneration.get() != generation) return

            if (!result.scanSucceeded) {
                val message = result.failure?.message ?: "フォルダを読み取れませんでした"
                _scanFailures.value = result.failedFiles.ifEmpty { listOfNotNull(message) }
                _isReady.value = true
                _startupState.value = StartupState.Error(message, restoredProvisional)
                return
            }

            val tracks = result.tracks
            updateLibraryState(tracks)
            withContext(Dispatchers.Main) {
                setupPlayerPlaylist(tracks, forceRandom = cachedTracks.isEmpty())
            }
            _canEditMetadata.value = hasPersistedWritePermission(appContext, uri)
            currentCacheSnapshot = LibraryCacheSnapshot(uri, tracks)
            _preloadedCache.complete(currentCacheSnapshot)
            _scanFailures.value = result.failedFiles
            _isReady.value = true
            _startupState.value = StartupState.Ready
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (libraryGeneration.get() == generation) {
                val message = e.message ?: "ライブラリの読み込みに失敗しました"
                _scanFailures.value = listOfNotNull(message)
                _isReady.value = true
                _startupState.value = StartupState.Error(message, restoredProvisional)
            }
        } finally {
            endScanIndicator()
        }
    }

    private fun applyScanProgress(progress: LibraryScanProgress) {
        _scanTotal.value = progress.total
        _scanCompleted.value = progress.completed
        _scanCurrentFile.value = progress.currentFile
        _scanFailures.value = progress.failedFiles
    }

    private fun beginScanIndicator() {
        activeScanCount.incrementAndGet()
        _isScanningArtists.value = true
    }

    private fun endScanIndicator() {
        if (activeScanCount.decrementAndGet() <= 0) {
            activeScanCount.set(0)
            _isScanningArtists.value = false
        }
    }

    fun cancelScan() {
        scanJob?.cancel()
        scanJob = null
        validateJob?.cancel()
        validateJob = null
    }

    private fun persistFolderPermission(context: Context, uri: Uri): Boolean {
        return try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            hasPersistedWritePermission(context, uri)
        } catch (_: SecurityException) {
            hasPersistedWritePermission(context, uri)
        } catch (_: UnsupportedOperationException) {
            false
        }
    }

    private fun hasPersistedWritePermission(context: Context, uri: Uri): Boolean {
        return context.contentResolver.persistedUriPermissions.any { permission ->
            permission.uri == uri && permission.isWritePermission
        }
    }

    private fun hasPersistedReadPermission(context: Context, uri: Uri): Boolean {
        return context.contentResolver.persistedUriPermissions.any { permission ->
            permission.uri == uri && permission.isReadPermission
        }
    }

    private suspend fun updateLibraryState(tracks: List<TrackData>) {
        val distinctTracks = tracks.distinctBy { it.uri.toString() }
        val tempArtistMap = mutableMapOf<String, MutableList<TrackData>>()
        val tempAlbumMap = mutableMapOf<String, MutableList<TrackData>>()

        distinctTracks.forEach { track ->
            val artistKey = track.artist
            val albumKey = track.album

            val existingArtistKey = tempArtistMap.keys.find { it.equals(artistKey, ignoreCase = true) }
            tempArtistMap.getOrPut(existingArtistKey ?: artistKey) { mutableListOf() }.add(track)

            val existingAlbumKey = tempAlbumMap.keys.find { it.equals(albumKey, ignoreCase = true) }
            tempAlbumMap.getOrPut(existingAlbumKey ?: albumKey) { mutableListOf() }.add(track)
        }

        withContext(Dispatchers.Main) {
            _trackList.value = distinctTracks.map { it.title }
            _artistMap.value = tempArtistMap.toSortedMap(compareBy { it.lowercase() })
            _albumMap.value = tempAlbumMap.toSortedMap(compareBy { it.lowercase() })
        }
    }

    private fun setupPlayerPlaylist(tracks: List<TrackData>, forceRandom: Boolean = false) {
        val restoreUri = if (shouldRestorePlaybackState) {
            sharedPreferences?.getString("playback_uri", null)?.let(Uri::parse)
        } else null
        val restorePosition = if (shouldRestorePlaybackState) {
            sharedPreferences?.getLong("playback_position", 0L) ?: 0L
        } else 0L
        val restoreShuffle = if (shouldRestorePlaybackState) {
            sharedPreferences?.getBoolean("shuffle", false)
        } else null
        val restoreRepeat = if (shouldRestorePlaybackState) {
            sharedPreferences?.getInt("repeat", Player.REPEAT_MODE_OFF)
        } else null
        playbackController?.setPlaylist(
            tracks = tracks,
            fileExtension = { metadataExtractor?.fileExtensionCache[it] ?: "flac" },
            mimeType = ::getMimeTypeForExtension,
            forceRandom = forceRandom && restoreUri == null,
            resolveTargetIndex = ::resolveTargetIndex,
            restoreUri = restoreUri,
            restorePosition = restorePosition,
            restoreShuffle = restoreShuffle,
            restoreRepeat = restoreRepeat
        )
        shouldRestorePlaybackState = false
    }

    internal fun resolveTargetIndex(tracks: List<TrackData>, currentUri: Uri?, forceRandom: Boolean): Int {
        if (tracks.isEmpty()) return -1
        if (forceRandom || currentUri == null) {
            return (0 until tracks.size).random()
        }
        val newIndex = tracks.indexOfFirst { it.uri == currentUri }
        return if (newIndex != -1) newIndex else (0 until tracks.size).random()
    }

    private fun scanner(): LibraryScanner {
        return libraryScanner ?: LibraryScanner(metadataExtractor).also { libraryScanner = it }
    }

    // Compatibility delegates kept while UI and regression tests migrate to LibraryScanner.
    private fun saveCache(context: Context, uri: Uri, tracks: List<TrackData>) {
        scanner().saveCache(context, uri, tracks)
    }

    private fun loadCache(context: Context, uri: Uri): List<TrackData> {
        return scanner().loadCache(context, uri)
    }

    fun playArtistTrack(context: Context, artistTracks: List<TrackData>, initialIndex: Int) {
        userLibraryController?.recordPlay(artistTracks[initialIndex].uri.toString())
        notifyStatsChanged()
        playTrackListCommon(artistTracks, initialIndex)
    }

    fun playAlbumTrack(context: Context, albumTracks: List<TrackData>, initialIndex: Int) {
        userLibraryController?.recordPlay(albumTracks[initialIndex].uri.toString())
        notifyStatsChanged()
        playTrackListCommon(albumTracks, initialIndex)
    }

    fun playAllTrack(context: Context, allTracks: List<TrackData>, initialIndex: Int) {
        userLibraryController?.recordPlay(allTracks[initialIndex].uri.toString())
        notifyStatsChanged()
        playTrackListCommon(allTracks, initialIndex)
    }

    private fun playTrackListCommon(tracks: List<TrackData>, initialIndex: Int) {
        playbackController?.playTracks(
            tracks = tracks,
            initialIndex = initialIndex,
            fileExtension = { metadataExtractor?.fileExtensionCache[it] ?: "flac" },
            mimeType = ::getMimeTypeForExtension
        )
    }

    fun playPause() {
        playbackController?.playPause()
    }

    fun seekTo(pos: Long) {
        playbackController?.seekTo(pos)
    }

    fun next() {
        playbackController?.currentUri()?.toString()?.let {
            userLibraryController?.recordSkip(it)
            notifyStatsChanged()
        }
        playbackController?.seekToNext()
    }

    fun previous() {
        playbackController?.currentUri()?.toString()?.let {
            userLibraryController?.recordSkip(it)
            notifyStatsChanged()
        }
        playbackController?.seekToPrevious()
    }

    fun toggleShuffle() {
        playbackController?.setShuffle(!_isShuffleMode.value)
    }

    fun toggleRepeat() {
        playbackController?.setRepeat(
            when (_repeatMode.value) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        )
    }

    fun updateTrackTags(
        context: Context,
        trackUri: Uri,
        title: String,
        artist: String,
        album: String,
        trackNumber: String,
        genre: String?,
        year: String?,
        composer: String?,
        albumArtist: String?,
        discNumber: String?,
        comment: String?,
        artworkBitmap: Bitmap?,
        onComplete: (Boolean) -> Unit
    ) {
        val appContext = context.applicationContext
        if (!isSavingMetadataGuard.compareAndSet(false, true)) {
            onComplete(false); return
        }
        viewModelScope.launch {
            performUpdateTrackTags(
                appContext, trackUri, title, artist, album, trackNumber,
                genre, year, composer, albumArtist, discNumber, comment, artworkBitmap, onComplete
            )
        }
    }

    /** Extracted tag-edit body so it can be driven directly by tests without a viewModelScope leak. */
    internal suspend fun performUpdateTrackTags(
        appContext: Context,
        trackUri: Uri,
        title: String,
        artist: String,
        album: String,
        trackNumber: String,
        genre: String?,
        year: String?,
        composer: String?,
        albumArtist: String?,
        discNumber: String?,
        comment: String?,
        artworkBitmap: Bitmap?,
        onComplete: (Boolean) -> Unit
    ) {
        _isSavingMetadata.value = true
        try {
            val fileExt = metadataExtractor?.fileExtensionCache[trackUri]
                ?: trackUri.lastPathSegment?.substringAfterLast(".", "tmp")
                ?: "tmp"
            val result = tagEditor?.updateTrackTags(
                context = appContext,
                trackUri = trackUri,
                request = TagEditRequest(
                    title = title,
                    artist = artist,
                    album = album,
                    trackNumber = trackNumber,
                    genre = genre,
                    year = year,
                    composer = composer,
                    albumArtist = albumArtist,
                    discNumber = discNumber,
                    comment = comment,
                    artworkBitmap = artworkBitmap
                ),
                fileExtension = fileExt
            )
            val success = result?.isSuccess == true
            if (success) {
                val artBytes = result.getOrNull()?.albumArtBytes
                refreshTrackAfterEdit(
                    appContext, trackUri, title, artist, album, trackNumber,
                    genre, year, composer, albumArtist, discNumber, comment,
                    artBytes
                )
            }
            onComplete(success)
        } finally {
            _isSavingMetadata.value = false
            isSavingMetadataGuard.set(false)
        }
    }

    private suspend fun refreshTrackAfterEdit(
        context: Context,
        trackUri: Uri,
        title: String,
        artist: String,
        album: String,
        trackNumber: String,
        genre: String?,
        year: String?,
        composer: String?,
        albumArtist: String?,
        discNumber: String?,
        comment: String?,
        artBytes: ByteArray?
    ) {
        withContext(Dispatchers.IO) {
            // A committed tag edit is the newest truth about this library. Invalidate any in-flight
            // scan so a stale result cannot overwrite the freshly edited metadata or persist a
            // cache file built from pre-edit data.
            libraryGeneration.incrementAndGet()
            scanJob?.cancel()
            validateJob?.cancel()

            val oldTrack = _artistMap.value.values.flatten().find { it.uri == trackUri }
            val originalIndex = oldTrack?.originalIndex ?: 0
            val updatedTrack = TrackData(
                title = title, artist = artist, album = album,
                trackNumber = trackNumber.takeIf { it.isNotBlank() },
                genre = genre, year = year, composer = composer,
                albumArtist = albumArtist, discNumber = discNumber, comment = comment,
                originalIndex = originalIndex, uri = trackUri,
                albumArtBytes = compactArtworkBytes(artBytes), lastModified = System.currentTimeMillis()
            )

            // Rebuild library state with the updated track
            val allTracks = _artistMap.value.values.flatten().toMutableList()
            val idx = allTracks.indexOfFirst { it.uri == trackUri }
            if (idx != -1) allTracks[idx] = updatedTrack else allTracks.add(updatedTrack)

            val distinctTracks = allTracks.distinctBy { it.uri.toString() }
            val tempArtistMap = mutableMapOf<String, MutableList<TrackData>>()
            val tempAlbumMap = mutableMapOf<String, MutableList<TrackData>>()
            distinctTracks.forEach { track ->
                val aKey = tempArtistMap.keys.find { it.equals(track.artist, ignoreCase = true) } ?: track.artist
                tempArtistMap.getOrPut(aKey) { mutableListOf() }.add(track)
                val alKey = tempAlbumMap.keys.find { it.equals(track.album, ignoreCase = true) } ?: track.album
                tempAlbumMap.getOrPut(alKey) { mutableListOf() }.add(track)
            }

            withContext(Dispatchers.Main) {
                _trackList.value = distinctTracks.map { it.title }
                _artistMap.value = tempArtistMap.toSortedMap(compareBy { it.lowercase() })
                _albumMap.value = tempAlbumMap.toSortedMap(compareBy { it.lowercase() })
            }

            val folderUriStr = sharedPreferences?.getString("last_uri", null)
            if (!folderUriStr.isNullOrEmpty()) {
                saveCache(context, Uri.parse(folderUriStr), distinctTracks)
            }

            // Update player metadata for the currently playing track
            withContext(Dispatchers.Main) {
                // The extractor caches still describe the file as it was before this edit,
                // so a later cache hit would resurrect the old artwork and theme colour.
                metadataExtractor?.invalidate(trackUri)
                if (detailsUri == trackUri) {
                    detailsUri = null
                    detailsSettled = false
                }
                playbackController?.replaceMediaItem(trackUri, title, artist, album)
            }
        }
    }

    // ── Favorites ──
    fun toggleFavorite(uri: String) {
        userLibraryController?.toggleFavorite(uri)
        _favorites.value = userLibraryController?.getFavorites() ?: emptySet()
    }

    fun isFavorite(uri: String): Boolean = _favorites.value.contains(uri)

    // ── Playlists ──
    fun refreshPlaylists() {
        _playlists.value = userLibraryController?.getPlaylists() ?: emptyList()
    }

    fun createPlaylist(name: String) {
        userLibraryController?.savePlaylist(Playlist(name = name))
        refreshPlaylists()
    }

    fun deletePlaylist(id: String) {
        userLibraryController?.deletePlaylist(id)
        refreshPlaylists()
    }

    fun addTrackToPlaylist(playlistId: String, uri: String) {
        userLibraryController?.addTrackToPlaylist(playlistId, uri)
        refreshPlaylists()
    }

    fun getPlaylistTracks(playlistId: String, allTracks: List<TrackData>): List<TrackData> {
        return userLibraryController?.getPlaylistTracks(playlistId, allTracks) ?: emptyList()
    }

    fun setSpatialAudioMode(mode: SpatialAudioMode) {
        requestSpatialAudioConfig(mode, _spatialHrtfMixPercent.value)
    }

    fun setSpatialHrtfMixPercent(percent: Int) {
        if (!SpatialHrtfMixPercent.isValidPercent(percent)) {
            _spatialAudioModeError.value = "HRTF混合量は0〜100%で指定してください"
            return
        }
        requestSpatialAudioConfig(_spatialAudioMode.value, percent)
    }

    private fun requestSpatialAudioConfig(mode: SpatialAudioMode, hrtfMixPercent: Int) {
        if (_isChangingSpatialAudioMode.value) return
        if (mode == _spatialAudioMode.value && hrtfMixPercent == _spatialHrtfMixPercent.value) {
            _spatialAudioModeError.value = null
            return
        }

        val future = playbackController?.setSpatialAudioMode(mode, hrtfMixPercent)
        if (future == null) {
            _spatialAudioModeError.value = "再生サービスに接続できません"
            return
        }

        _isChangingSpatialAudioMode.value = true
        _spatialAudioModeError.value = null
        future.addListener({
            val succeeded = runCatching { future.get().resultCode == SessionResult.RESULT_SUCCESS }
                .getOrDefault(false)
            if (succeeded) {
                _spatialAudioMode.value = mode
                _spatialHrtfMixPercent.value = hrtfMixPercent
            } else {
                _spatialAudioModeError.value = "空間オーディオ設定を切り替えられませんでした"
            }
            _isChangingSpatialAudioMode.value = false
        }, MoreExecutors.directExecutor())
    }

    // ── Sleep Timer ──
    fun setSleepTimer(minutes: Int) {
        _sleepTimerMinutes.value = minutes
        sleepTimerController = sleepTimerController ?: SleepTimerController(viewModelScope) {
            playbackController?.pause()
            _sleepTimerMinutes.value = 0
        }
        sleepTimerController?.setSleepTimer(minutes)
    }

    fun cancelSleepTimer() {
        sleepTimerController?.cancel()
        _sleepTimerMinutes.value = 0
    }

    // ── Recommendations ──
    private var recommendationsJob: Job? = null

    fun loadRecommendations(forceRefresh: Boolean = false) {
        if (_isLoadingRecommendations.value) return
        if (!forceRefresh && _recommendations.value.isNotEmpty()) return

        recommendationsJob?.cancel()
        recommendationsJob = viewModelScope.launch {
            try {
                _isLoadingRecommendations.value = true
                _recommendationError.value = null

                if (forceRefresh) recommendationController?.clearCache()

                val result = recommendationController?.getRecommendations(
                    artistMap = _artistMap.value,
                    allStats = userLibraryController?.getAllStats() ?: emptyMap()
                )

                if (result != null) {
                    result.fold(
                        onSuccess = { tracks ->
                            _recommendations.value = tracks
                            _lastRecommendationUpdate.value = System.currentTimeMillis()
                            _recommendationError.value = null
                        },
                        onFailure = { error ->
                            when (error.message) {
                                "API_KEY_NOT_SET" -> _recommendationError.value = "Last.fm APIキーが設定されていません"
                                "NEEDS_MORE_DATA" -> _recommendationError.value = "NEEDS_MORE_DATA"
                                "NO_ARTISTS_FOUND" -> _recommendationError.value =
                                    "再生履歴とライブラリのアーティスト情報が一致しません"

                                "NETWORK_ERROR" -> _recommendationError.value = "NETWORK_ERROR"
                                else -> {
                                    Log.e("FLACtify", "Recommendation error: ${error.message}", error)
                                    _recommendationError.value = "おすすめの取得に失敗しました"
                                }
                            }
                        }
                    )
                }
            } catch (e: Exception) {
                Log.e("FLACtify", "Recommendation crash: ${e.message}", e)
                _recommendationError.value = "おすすめの取得に失敗しました"
            } finally {
                _isLoadingRecommendations.value = false
            }
        }
    }

    fun refreshRecommendations() {
        loadRecommendations(forceRefresh = true)
    }

    // ── Cache ──
    fun refreshCacheSize(context: Context) {
        userLibraryController = userLibraryController ?: UserLibraryController(context)
        _cacheSize.value = userLibraryController?.getCacheDirSize() ?: 0L
    }

    fun clearCache(context: Context) {
        userLibraryController = userLibraryController ?: UserLibraryController(context)
        userLibraryController?.clearCache()
        _cacheSize.value = 0L
    }

    // ── Stats ──
    private val _statsVersion = MutableStateFlow(0L)
    val statsVersion: StateFlow<Long> = _statsVersion

    fun notifyStatsChanged() {
        _statsVersion.value = _statsVersion.value + 1
    }

    fun getStatsFor(uri: String): LibraryManager.PlayStats =
        userLibraryController?.getStatsFor(uri) ?: LibraryManager.PlayStats()

    fun getStatsForDisplay(uri: String): String {
        val s = getStatsFor(uri)
        val parts = mutableListOf<String>()
        if (s.playCount > 0) parts.add("${s.playCount}回再生")
        if (s.skipCount > 0) parts.add("${s.skipCount}回スキップ")
        if (s.lastPlayed > 0) {
            val date =
                java.text.SimpleDateFormat("yyyy/MM/dd", java.util.Locale.US).format(java.util.Date(s.lastPlayed))
            parts.add("最終再生: $date")
        }
        return parts.joinToString(" · ")
    }

    data class StatsSummary(
        val totalTracks: Int,
        val totalPlayCount: Int,
        val totalSkipCount: Int,
        val lastPlayed: Long
    )

    data class TopTrack(
        val title: String,
        val artist: String,
        val playCount: Int,
        val skipCount: Int,
        val lastPlayed: Long
    )

    fun getStatsSummary(): StatsSummary {
        val allStats = userLibraryController?.getAllStats() ?: emptyMap()
        if (allStats.isEmpty()) return StatsSummary(0, 0, 0, 0L)
        return StatsSummary(
            totalTracks = allStats.size,
            totalPlayCount = allStats.values.sumOf { it.playCount },
            totalSkipCount = allStats.values.sumOf { it.skipCount },
            lastPlayed = allStats.values.maxOf { it.lastPlayed }
        )
    }

    fun getTopPlayedTracks(topN: Int = 10): List<TopTrack> {
        val allStats = userLibraryController?.getAllStats() ?: emptyMap()
        if (allStats.isEmpty()) return emptyList()

        val uriMap = _artistMap.value.values.flatten().associateBy { it.uri.toString() }

        return allStats.entries
            .sortedByDescending { it.value.playCount }
            .take(topN)
            .map { (uri, s) ->
                val track = uriMap[uri]
                TopTrack(
                    title = track?.title ?: "不明",
                    artist = track?.artist ?: "不明",
                    playCount = s.playCount,
                    skipCount = s.skipCount,
                    lastPlayed = s.lastPlayed
                )
            }
    }

    private fun collectAudioFiles(dir: DocumentFile): List<DocumentFile> {
        return scanner().collectAudioFiles(dir)
    }

    private fun getMimeTypeForExtension(ext: String): String = when (ext) {
        "flac" -> "audio/flac"
        "wav" -> "audio/wav"
        "m4a" -> "audio/mp4"
        "mp3" -> "audio/mpeg"
        else -> "audio/mpeg"
    }

    override fun onCleared() {
        super.onCleared()
        cleanup()
    }

    internal fun cleanup() {
        persistPlaybackPosition()
        audioRouteMonitor?.stopObserving()
        metadataJob?.cancel()
        diagnosticsJob?.cancel()
        scanJob?.cancel()
        validateJob?.cancel()
        sleepTimerController?.cancel()
        recommendationsJob?.cancel()
        preloadJob?.cancel()
        playbackController?.cleanup()
        playbackController = null
        controller = null
        isSavingMetadataGuard.set(false)
    }

    private companion object {
    }
}
