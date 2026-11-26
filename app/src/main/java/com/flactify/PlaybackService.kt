package com.flactify

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.flactify.audio.AudioRouteMonitor
import com.flactify.audio.PlaybackAudioObserver
import com.flactify.audio.PlaybackDiagnosticsBus
import com.flactify.audio.PlaybackOrderInfo
import com.flactify.audio.spatial.FlactifyRenderersFactory
import com.flactify.audio.spatial.NativeSpatialEngine
import com.flactify.audio.spatial.SpatialAudioMode
import com.flactify.audio.spatial.SpatialAudioOutputProvider
import com.flactify.audio.spatial.SpatialPcmOutputObserverFactory
import com.flactify.audio.spatial.SpatialEosDrainState
import com.flactify.audio.spatial.SpatialHrtfAsset
import com.flactify.audio.spatial.SpatialHrtfRateSupport
import com.flactify.audio.spatial.SpatialHrtfSource
import com.flactify.audio.spatial.SpatialGainRamp
import com.flactify.audio.spatial.SpatialHrtfMixPercent
import com.flactify.audio.spatial.SpatialPcmEngine
import com.flactify.audio.spatial.SpatialPipelineInfo
import com.flactify.audio.spatial.SpatialPipelineState
import com.flactify.audio.spatial.SpatialPlaybackQueueState
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture

@OptIn(UnstableApi::class)
open class PlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private var mediaSession: MediaSession? = null
    private var bluetoothDisconnectMonitor: AudioRouteMonitor? = null
    private var audioObserver: AnalyticsListener? = null
    private var spatialAudioMode = SpatialAudioMode.OFF
    private var spatialAudioHrtfMixPercent = SpatialHrtfMixPercent.DEFAULT_PERCENT
    @Volatile
    private var spatialPipelineGeneration: Long = 0L
    private var nextSpatialPipelineGeneration: Long = 0L
    private val spatialPlaybackQueueState = SpatialPlaybackQueueState()
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val spatialGainRamp by lazy {
        SpatialGainRamp(
            scheduleDelayed = { task, delayMs -> mainHandler.postDelayed(task, delayMs) },
            cancelScheduled = mainHandler::removeCallbacks
        )
    }
    private var spatialModeTransitionInProgress = false
    private var spatialModeTransitionCompletion: ((Boolean) -> Unit)? = null
    private var spatialModeTransitionVolume: Float? = null
    private var activeSpatialGainRamp: SpatialGainRamp.Transition? = null
    private var pendingFadeInListener: Player.Listener? = null

    companion object {
        /**
         * Trusted-controller command: rebuild the shuffle order so the current item starts the
         * round. MediaController cannot call ExoPlayer.setShuffleOrder, so the service owns it.
         */
        const val SESSION_COMMAND_START_SHUFFLE_ROUND = "com.flactify.START_SHUFFLE_ROUND"

        /** The queue and start item the controller intended when it sent the round command. */
        const val SESSION_EXTRA_SHUFFLE_ROUND_QUEUE_FINGERPRINT =
            "com.flactify.SHUFFLE_ROUND_QUEUE_FINGERPRINT"
        const val SESSION_EXTRA_SHUFFLE_ROUND_ITEM_COUNT = "com.flactify.SHUFFLE_ROUND_ITEM_COUNT"
        const val SESSION_EXTRA_SHUFFLE_ROUND_START_INDEX = "com.flactify.SHUFFLE_ROUND_START_INDEX"

        /** True when this command actually rebuilt the order (false for a deduplicated no-op). */
        const val SESSION_RESULT_EXTRA_SHUFFLE_ROUND_REBUILT =
            "com.flactify.SHUFFLE_ROUND_REBUILT"
        /** Why a round command was skipped, when it was not applied. */
        const val SESSION_RESULT_EXTRA_SHUFFLE_ROUND_SKIP_REASON =
            "com.flactify.SHUFFLE_ROUND_SKIP_REASON"

        /** Read local SAF tracks directly; duplicating them can waste storage and reuse stale
         * spans after an in-place metadata edit changes the FLAC frame offset. */
        fun getPlaybackDataSourceFactory(service: Context): DefaultDataSource.Factory =
            DefaultDataSource.Factory(service.applicationContext)
    }

    private val spatialSessionCommand by lazy {
        SessionCommand(SpatialAudioMode.SESSION_COMMAND_ACTION, Bundle.EMPTY)
    }

    private val shuffleRoundSessionCommand by lazy {
        SessionCommand(SESSION_COMMAND_START_SHUFFLE_ROUND, Bundle.EMPTY)
    }

    private val spatialSessionCallback = object : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val builder = MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
            if (controller.isTrusted) {
                builder.setAvailableSessionCommands(
                    MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                        .buildUpon()
                        .add(spatialSessionCommand)
                        .add(shuffleRoundSessionCommand)
                        .build()
                )
            }
            return builder.build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == SESSION_COMMAND_START_SHUFFLE_ROUND) {
                // The service owns the order. Apply synchronously against the authoritative player
                // so the returned result describes what actually happened, and reject a command
                // whose queue/current item is no longer the one the controller intended.
                if (!args.containsKey(SESSION_EXTRA_SHUFFLE_ROUND_QUEUE_FINGERPRINT) ||
                    !args.containsKey(SESSION_EXTRA_SHUFFLE_ROUND_ITEM_COUNT) ||
                    !args.containsKey(SESSION_EXTRA_SHUFFLE_ROUND_START_INDEX)
                ) {
                    return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                }
                val request = PlaybackShuffleRound.Request(
                    queueFingerprint = args.getLong(SESSION_EXTRA_SHUFFLE_ROUND_QUEUE_FINGERPRINT),
                    itemCount = args.getInt(SESSION_EXTRA_SHUFFLE_ROUND_ITEM_COUNT),
                    startIndex = args.getInt(SESSION_EXTRA_SHUFFLE_ROUND_START_INDEX)
                )
                return Futures.immediateFuture(shuffleRoundResult(applyShuffleRound(request)))
            }

            if (customCommand.customAction != SpatialAudioMode.SESSION_COMMAND_ACTION) {
                return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            }

            val requestedMode = SpatialAudioMode.fromWireValue(
                args.getString(SpatialAudioMode.SESSION_EXTRA_MODE)
            )
            if (args.getString(SpatialAudioMode.SESSION_EXTRA_MODE) != requestedMode.wireValue) {
                return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
            }
            val requestedHrtfMixPercent = args.getInt(
                SpatialAudioMode.SESSION_EXTRA_HRTF_MIX_PERCENT,
                SpatialHrtfMixPercent.DEFAULT_PERCENT
            )
            if (!SpatialHrtfMixPercent.isValidPercent(requestedHrtfMixPercent)) {
                return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
            }

            val result = SettableFuture.create<SessionResult>()
            mainHandler.post {
                try {
                    replacePlayerForSpatialConfig(
                        requestedMode,
                        requestedHrtfMixPercent
                    ) { switched ->
                        result.set(
                            SessionResult(
                                if (switched) SessionResult.RESULT_SUCCESS
                                else SessionError.ERROR_UNKNOWN
                            )
                        )
                    }
                } catch (_: RuntimeException) {
                    result.set(SessionResult(SessionError.ERROR_UNKNOWN))
                }
            }
            return result
        }
    }

    override fun onCreate() {
        super.onCreate()

        spatialAudioMode = SpatialAudioMode.fromWireValue(
            getSharedPreferences(PlaybackSettings.PREFERENCES_NAME, Context.MODE_PRIVATE)
                .getString(SpatialAudioMode.PREFERENCE_KEY, SpatialAudioMode.OFF.wireValue)
        )
        spatialAudioHrtfMixPercent = getSharedPreferences(
            PlaybackSettings.PREFERENCES_NAME,
            Context.MODE_PRIVATE
        ).getInt(
            SpatialAudioMode.PREFERENCE_KEY_HRTF_MIX_PERCENT,
            SpatialHrtfMixPercent.DEFAULT_PERCENT
        ).coerceIn(
            SpatialHrtfMixPercent.MIN_PERCENT,
            SpatialHrtfMixPercent.MAX_PERCENT
        )
        player = buildPlayer(spatialAudioMode, spatialAudioHrtfMixPercent)
        attachPlaybackObservers(player)
        bluetoothDisconnectMonitor = AudioRouteMonitor(this).also { monitor ->
            monitor.startObserving(
                onRouteChanged = {},
                onBluetoothOutputRemoved = ::pauseForBluetoothDisconnectIfEnabled
            )
        }
        publishRequestedSpatialMode(spatialAudioMode, spatialAudioHrtfMixPercent)

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        val pendingIntent = android.app.PendingIntent.getActivity(
            this,
            0,
            intent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )

        setMediaNotificationProvider(CustomMediaNotificationProvider(this))
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(pendingIntent)
            .setCallback(spatialSessionCallback)
            .build()
    }

    internal open fun createSpatialPcmOutputObserverFactory(): SpatialPcmOutputObserverFactory? = null

    private fun buildPlayer(
        mode: SpatialAudioMode,
        hrtfMixPercent: Int = spatialAudioHrtfMixPercent,
        pipelineGeneration: Long = spatialPipelineGeneration
    ): ExoPlayer {
        val pipelineState = SpatialPipelineState(mode, hrtfMixPercent) { info ->
            if (pipelineGeneration == spatialPipelineGeneration) {
                PlaybackDiagnosticsBus.publishSpatial(info)
            }
        }
        pipelineState.setRequestedMode(mode, hrtfMixPercent)
        val eosDrainState = SpatialEosDrainState()
        var spatialHrtfData: java.nio.ByteBuffer? = null
        val spatialEngineFactory: ((Int) -> SpatialPcmEngine)? =
            if (mode == SpatialAudioMode.STUDIO) {
                { sampleRateHz ->
                    val hrtfData =
                        if (
                            SpatialHrtfRateSupport.sourceFor(sampleRateHz) ==
                                SpatialHrtfSource.PINNED_CIPIC_SOFA
                        ) {
                            spatialHrtfData ?: SpatialHrtfAsset.load(this).also {
                                spatialHrtfData = it
                            }
                        } else {
                            null
                        }
                    NativeSpatialEngine(
                        sampleRateHz,
                        SpatialPcmEngine.FRAME_SIZE,
                        hrtfData,
                        hrtfMixPercent
                    )
                }
            } else {
                null
            }
        val defaultAudioOutputProvider = AudioTrackAudioOutputProvider.Builder(this).build()
        val customLoadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(60_000, 120_000, 500, 1_000)
            .build()

        return ExoPlayer.Builder(
            this,
            FlactifyRenderersFactory(this, eosDrainState, mode, spatialPlaybackQueueState)
        )
            .setAudioOutputProvider(
                SpatialAudioOutputProvider(
                    defaultAudioOutputProvider,
                    requireDecodedPcm = mode == SpatialAudioMode.STUDIO,
                    spatialPipelineState = pipelineState,
                    eosDrainState = eosDrainState,
                    spatialEngineFactory = spatialEngineFactory,
                    outputObserverFactory = createSpatialPcmOutputObserverFactory(),
                    hrtfMixPercent = hrtfMixPercent
                )
            )
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(getPlaybackDataSourceFactory(this))
            )
            .setLoadControl(customLoadControl)
            .build()
    }

    private fun attachPlaybackObservers(target: ExoPlayer) {
        audioObserver = PlaybackAudioObserver.attach(
            target,
            PlaybackAudioObserver(
                onRendererFormat = { PlaybackDiagnosticsBus.publishRendererFormat(it) },
                onDecoderName = { PlaybackDiagnosticsBus.publishDecoderName(it) },
                onOutputFormat = { PlaybackDiagnosticsBus.publishOutput(it) },
                onMedia3AudioSink = { PlaybackDiagnosticsBus.publishMedia3AudioSink(it) }
            )
        )
        publishPlaybackOrder(target)
        target.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                PlaybackDiagnosticsBus.resetForMediaItemTransition()
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                publishPlaybackOrder(target)
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                publishPlaybackOrder(target)
                // Shuffle can also be switched on from outside the app UI (notification, Bluetooth,
                // another controller). Starting the round here keeps that path identical.
                if (shuffleModeEnabled) startShuffleRoundFromCurrentItem()
            }

            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                // Queue mutations clone/insert into the shuffle order, so the terminal index of
                // the active order can move without a shuffle-mode change.
                publishPlaybackOrder(target)
            }
        })
    }

    private fun publishPlaybackOrder(target: ExoPlayer) {
        val order = PlaybackOrderInfo(
            repeatMode = target.repeatMode,
            shuffleModeEnabled = target.shuffleModeEnabled,
            shuffleOrderLastIndex = target.shuffleOrder.lastIndex
        )
        spatialPlaybackQueueState.updatePlaybackOrder(
            order.repeatMode,
            order.shuffleModeEnabled,
            order.shuffleOrderLastIndex
        )
        PlaybackDiagnosticsBus.publishPlaybackOrder(order)
    }

    /**
     * Rebuilds the active shuffle order so the current item is the first item of the round.
     *
     * Media3's default shuffle order can start anywhere in the permutation. When the user starts
     * from an arbitrary item, auto-advance then stops at the end of the permutation and the items
     * before the start position are never played. Making the current item the head keeps repeat
     * OFF to exactly one full pass, repeat ALL to a new round after that pass, and repeat ONE
     * untouched.
     */
    private fun startShuffleRoundFromCurrentItem() {
        applyShuffleRound(request = null)
    }

    /**
     * Applies one round decision against the authoritative player. Returns null while the service
     * player is not initialized; otherwise the decision describes whether a new order was built.
     */
    private fun applyShuffleRound(
        request: PlaybackShuffleRound.Request?
    ): PlaybackShuffleRound.Decision? {
        if (!::player.isInitialized) return null
        val state = PlaybackShuffleRound.State(
            itemCount = player.mediaItemCount,
            currentIndex = player.currentMediaItemIndex,
            queueFingerprint = PlaybackShuffleRound.queueFingerprint(
                (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
            ),
            activeOrderLength = player.shuffleOrder.length,
            activeOrderFirstIndex = player.shuffleOrder.firstIndex
        )
        val decision = PlaybackShuffleRound.decide(
            request = request,
            shuffleModeEnabled = player.shuffleModeEnabled,
            state = state
        )
        when (decision) {
            PlaybackShuffleRound.Decision.APPLY -> {
                val order = PlaybackShuffleRound.orderStartingAt(
                    state.itemCount,
                    state.currentIndex
                ) ?: return PlaybackShuffleRound.Decision.NOTHING_TO_REORDER
                player.setShuffleOrder(
                    ShuffleOrder.DefaultShuffleOrder(order, kotlin.random.Random.nextLong())
                )
                publishPlaybackOrder(player)
            }
            PlaybackShuffleRound.Decision.ALREADY_APPLIED,
            PlaybackShuffleRound.Decision.NOTHING_TO_REORDER -> publishPlaybackOrder(player)
            else -> Unit
        }
        return decision
    }

    private fun shuffleRoundResult(decision: PlaybackShuffleRound.Decision?): SessionResult {
        if (decision == null) return SessionResult(SessionError.ERROR_INVALID_STATE)
        val extras = Bundle()
        val applied = decision == PlaybackShuffleRound.Decision.APPLY
        extras.putBoolean(SESSION_RESULT_EXTRA_SHUFFLE_ROUND_REBUILT, applied)
        val success = applied ||
            decision == PlaybackShuffleRound.Decision.ALREADY_APPLIED ||
            decision == PlaybackShuffleRound.Decision.NOTHING_TO_REORDER
        if (!success) {
            extras.putString(SESSION_RESULT_EXTRA_SHUFFLE_ROUND_SKIP_REASON, decision.name)
        }
        return SessionResult(
            if (success) {
                SessionResult.RESULT_SUCCESS
            } else {
                // RESULT_INFO_SKIPPED is not a valid custom-command result code. A skipped round
                // is a state refusal, not a success, so encode it as ERROR_INVALID_STATE and keep
                // the reason in extras.
                SessionError.ERROR_INVALID_STATE
            },
            extras
        )
    }

    private fun replacePlayerForSpatialConfig(
        mode: SpatialAudioMode,
        hrtfMixPercent: Int,
        completion: (Boolean) -> Unit
    ) {
        if (!SpatialHrtfMixPercent.isValidPercent(hrtfMixPercent)) {
            completion(false)
            return
        }
        if (
            mode == spatialAudioMode &&
            (mode == SpatialAudioMode.OFF || hrtfMixPercent == spatialAudioHrtfMixPercent)
        ) {
            if (mode == SpatialAudioMode.OFF) {
                spatialAudioHrtfMixPercent = hrtfMixPercent
                persistSpatialAudioSettings(mode, hrtfMixPercent)
            }
            completion(true)
            return
        }
        if (spatialModeTransitionInProgress) {
            completion(false)
            return
        }

        cancelPendingFadeIn()
        spatialModeTransitionInProgress = true
        spatialModeTransitionCompletion = completion

        val previousPlayer = player
        val previousSpatialInfo = PlaybackDiagnosticsBus.spatial.value
        val targetVolume = spatialModeTransitionVolume
            ?: previousPlayer.volume.coerceIn(0f, 1f).also { spatialModeTransitionVolume = it }
        val previousPipelineGeneration = spatialPipelineGeneration
        val replacementPipelineGeneration = ++nextSpatialPipelineGeneration
        val replacement = try {
            buildPlayer(
                mode,
                hrtfMixPercent,
                replacementPipelineGeneration
            )
        } catch (_: RuntimeException) {
            restorePlayerAfterFailedModeSwitch(previousPlayer, targetVolume)
            return
        }

        try {
            replacement.trackSelectionParameters = previousPlayer.trackSelectionParameters
            replacement.playbackParameters = previousPlayer.playbackParameters
            replacement.repeatMode = previousPlayer.repeatMode
            replacement.shuffleModeEnabled = previousPlayer.shuffleModeEnabled
        } catch (_: RuntimeException) {
            replacement.release()
            restorePlayerAfterFailedModeSwitch(previousPlayer, targetVolume)
            return
        }

        val session = mediaSession
        if (session == null) {
            replacement.release()
            restorePlayerAfterFailedModeSwitch(previousPlayer, targetVolume)
            return
        }

        fun swapPlayersAtSilence() {
            val items = (0 until previousPlayer.mediaItemCount)
                .map(previousPlayer::getMediaItemAt)
            val currentIndex = previousPlayer.currentMediaItemIndex
                .takeIf { it in items.indices } ?: 0
            val currentPosition = previousPlayer.currentPosition.coerceAtLeast(0L)
            val wasPrepared = previousPlayer.playbackState != Player.STATE_IDLE
            val shouldResume = previousPlayer.playWhenReady

            val replacementSpatialInfo = try {
                // Prepare the replacement muted; ramp it in only after it starts rendering.
                replacement.volume = if (shouldResume && targetVolume > 0f) 0f else targetVolume
                if (items.isNotEmpty()) {
                    replacement.setMediaItems(items, currentIndex, currentPosition)
                    replacement.shuffleOrder = previousPlayer.shuffleOrder
                }
                spatialPipelineGeneration = replacementPipelineGeneration
                publishRequestedSpatialMode(mode, hrtfMixPercent)
                if (wasPrepared) replacement.prepare()
                PlaybackDiagnosticsBus.spatial.value
            } catch (_: RuntimeException) {
                spatialPipelineGeneration = previousPipelineGeneration
                rollbackSpatialPlayerReplacement(
                    previousPlayer,
                    session,
                    replacement,
                    targetVolume,
                    previousSpatialInfo
                )
                return
            }

            try {
                session.setPlayer(replacement)
                if (shouldResume) replacement.play()
            } catch (_: RuntimeException) {
                spatialPipelineGeneration = previousPipelineGeneration
                rollbackSpatialPlayerReplacement(
                    previousPlayer,
                    session,
                    replacement,
                    targetVolume,
                    previousSpatialInfo
                )
                return
            }

            audioObserver?.let(previousPlayer::removeAnalyticsListener)
            audioObserver = null
            player = replacement
            spatialAudioMode = mode
            spatialAudioHrtfMixPercent = hrtfMixPercent
            persistSpatialAudioSettings(mode, hrtfMixPercent)

            PlaybackDiagnosticsBus.reset()
            PlaybackDiagnosticsBus.publishSpatial(replacementSpatialInfo)
            attachPlaybackObservers(replacement)
            previousPlayer.release()
            if (shouldResume && targetVolume > 0f) {
                installFadeInOnFirstPlayback(replacement, targetVolume)
            } else {
                spatialModeTransitionVolume = null
            }
            finishSpatialModeTransition(true)
        }

        // Separate players cannot share a sample-aligned crossfade. Fade the old track to silence
        // before detaching its AudioTrack, then start the replacement muted and fade it in.
        if (previousPlayer.isPlaying && targetVolume > 0f) {
            startGainRamp(previousPlayer, 0f, ::swapPlayersAtSilence)
        } else {
            swapPlayersAtSilence()
        }
    }

    private fun installFadeInOnFirstPlayback(target: ExoPlayer, targetVolume: Float) {
        val listener = object : Player.Listener {
            private var fadeStarted = false

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying || fadeStarted || target !== player) return
                fadeStarted = true
                target.removeListener(this)
                if (pendingFadeInListener === this) pendingFadeInListener = null
                startGainRamp(target, targetVolume) {
                    if (target === player) spatialModeTransitionVolume = null
                }
            }
        }
        pendingFadeInListener?.let(player::removeListener)
        pendingFadeInListener = listener
        target.addListener(listener)
        if (target.isPlaying) listener.onIsPlayingChanged(true)
    }

    private fun startGainRamp(
        target: ExoPlayer,
        targetVolume: Float,
        onComplete: () -> Unit
    ) {
        activeSpatialGainRamp?.cancel()
        val from = target.volume.coerceIn(0f, 1f)
        val to = targetVolume.coerceIn(0f, 1f)
        if (from == to) {
            activeSpatialGainRamp = null
            onComplete()
            return
        }

        lateinit var transition: SpatialGainRamp.Transition
        transition = spatialGainRamp.start(
            from = from,
            to = to,
            setGain = { gain -> target.volume = gain },
            onComplete = {
                if (activeSpatialGainRamp === transition) activeSpatialGainRamp = null
                onComplete()
            }
        )
        activeSpatialGainRamp = transition
    }

    private fun cancelPendingFadeIn() {
        pendingFadeInListener?.let { listener ->
            if (::player.isInitialized) player.removeListener(listener)
        }
        pendingFadeInListener = null
        activeSpatialGainRamp?.cancel()
        activeSpatialGainRamp = null
    }

    private fun restorePlayerAfterFailedModeSwitch(
        previousPlayer: ExoPlayer,
        targetVolume: Float
    ) {
        startGainRamp(previousPlayer, targetVolume) {
            spatialModeTransitionVolume = null
            finishSpatialModeTransition(false)
        }
    }

    private fun rollbackSpatialPlayerReplacement(
        previousPlayer: ExoPlayer,
        session: MediaSession,
        replacement: ExoPlayer,
        targetVolume: Float,
        previousSpatialInfo: SpatialPipelineInfo
    ) {
        runCatching { session.setPlayer(previousPlayer) }
        runCatching { replacement.release() }
        PlaybackDiagnosticsBus.publishSpatial(previousSpatialInfo)
        startGainRamp(previousPlayer, targetVolume) {
            spatialModeTransitionVolume = null
            finishSpatialModeTransition(false)
        }
    }

    private fun finishSpatialModeTransition(success: Boolean) {
        spatialModeTransitionInProgress = false
        val completion = spatialModeTransitionCompletion
        spatialModeTransitionCompletion = null
        completion?.invoke(success)
    }

    private fun persistSpatialAudioSettings(
        mode: SpatialAudioMode,
        hrtfMixPercent: Int
    ) {
        getSharedPreferences(PlaybackSettings.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(SpatialAudioMode.PREFERENCE_KEY, mode.wireValue)
            .putInt(SpatialAudioMode.PREFERENCE_KEY_HRTF_MIX_PERCENT, hrtfMixPercent)
            .apply()
    }

    private fun publishRequestedSpatialMode(
        mode: SpatialAudioMode,
        hrtfMixPercent: Int
    ) {
        PlaybackDiagnosticsBus.publishSpatial(
            if (mode == SpatialAudioMode.OFF) {
                SpatialPipelineInfo(
                    requestedMode = SpatialAudioMode.OFF,
                    hrtfMixPercent = hrtfMixPercent
                )
            } else {
                SpatialPipelineInfo(
                    requestedMode = SpatialAudioMode.STUDIO,
                    hrtfMixPercent = hrtfMixPercent,
                    bypassReason = "AWAITING_PCM_FORMAT"
                )
            }
        )
    }

    private class CustomMediaNotificationProvider(private val context: Context) :
        MediaNotification.Provider {
        private val defaultProvider = DefaultMediaNotificationProvider.Builder(context).build()

        override fun createNotification(
            mediaSession: MediaSession,
            customLayout: ImmutableList<CommandButton>,
            actionFactory: MediaNotification.ActionFactory,
            callback: MediaNotification.Provider.Callback
        ): MediaNotification {
            val mediaNotification = defaultProvider.createNotification(
                mediaSession, customLayout, actionFactory, callback
            )

            val artworkData = mediaSession.player.mediaMetadata.artworkData
            if (artworkData != null) {
                val bitmap = BitmapFactory.decodeByteArray(artworkData, 0, artworkData.size)
                if (bitmap != null) {
                    val builder = NotificationCompat.Builder(context, mediaNotification.notification)
                        .setLargeIcon(bitmap)
                    return MediaNotification(mediaNotification.notificationId, builder.build())
                }
            }
            return mediaNotification
        }

        override fun handleCustomCommand(
            session: MediaSession,
            action: String,
            extras: Bundle
        ): Boolean = defaultProvider.handleCustomCommand(session, action, extras)

        override fun getNotificationChannelInfo():
            MediaNotification.Provider.NotificationChannelInfo =
            defaultProvider.notificationChannelInfo
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    private fun pauseForBluetoothDisconnectIfEnabled() {
        if (!::player.isInitialized || !player.playWhenReady) return
        val shouldPause = getSharedPreferences(
            PlaybackSettings.PREFERENCES_NAME,
            Context.MODE_PRIVATE
        ).getBoolean(
            PlaybackSettings.PAUSE_ON_BLUETOOTH_DISCONNECT_KEY,
            PlaybackSettings.PAUSE_ON_BLUETOOTH_DISCONNECT_DEFAULT
        )
        if (shouldPause) player.pause()
    }

    private fun persistCurrentPosition() {
        if (!::player.isInitialized) return
        val uri = player.currentMediaItem?.localConfiguration?.uri ?: return
        getSharedPreferences(PlaybackSettings.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("playback_uri", uri.toString())
            .putLong("playback_position", player.currentPosition.coerceAtLeast(0L))
            .apply()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (::player.isInitialized) {
            persistCurrentPosition()
            player.pause()
        }
        super.onTaskRemoved(rootIntent)
        stopSelf()
    }

    override fun onDestroy() {
        bluetoothDisconnectMonitor?.stopObserving()
        bluetoothDisconnectMonitor = null
        cancelPendingFadeIn()
        spatialModeTransitionVolume = null
        finishSpatialModeTransition(false)
        PlaybackDiagnosticsBus.resetAll()
        audioObserver = null
        mediaSession?.release()
        mediaSession = null
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }
}
