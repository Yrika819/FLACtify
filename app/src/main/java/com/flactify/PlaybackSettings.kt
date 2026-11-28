package com.flactify

import com.flactify.audio.ReplayGainPreferences
import com.flactify.audio.ReplayGainSelection

/** Shared playback preferences read by both the UI and the playback service. */
object PlaybackSettings {
    const val PREFERENCES_NAME = "flactify_settings"
    const val PAUSE_ON_BLUETOOTH_DISCONNECT_KEY = "pause_on_bluetooth_disconnect"
    const val PAUSE_ON_BLUETOOTH_DISCONNECT_DEFAULT = true
    const val REPLAY_GAIN_ENABLED_KEY = "replay_gain_enabled"
    const val REPLAY_GAIN_SELECTION_KEY = "replay_gain_selection"
    const val REPLAY_GAIN_PREAMP_DB_KEY = "replay_gain_preamp_db"
    const val REPLAY_GAIN_PREVENT_CLIPPING_KEY = "replay_gain_prevent_clipping"

    val REPLAY_GAIN_DEFAULTS = ReplayGainPreferences()

    fun readReplayGainPreferences(
        preferences: android.content.SharedPreferences
    ): ReplayGainPreferences = ReplayGainPreferences(
        enabled = preferences.getBoolean(REPLAY_GAIN_ENABLED_KEY, REPLAY_GAIN_DEFAULTS.enabled),
        selection = runCatching {
            ReplayGainSelection.valueOf(
                preferences.getString(REPLAY_GAIN_SELECTION_KEY, null)
                    ?: REPLAY_GAIN_DEFAULTS.selection.name
            )
        }.getOrDefault(REPLAY_GAIN_DEFAULTS.selection),
        preampDb = preferences.getFloat(
            REPLAY_GAIN_PREAMP_DB_KEY,
            REPLAY_GAIN_DEFAULTS.preampDb.toFloat()
        ).toDouble().coerceIn(-12.0, 12.0),
        preventClipping = preferences.getBoolean(
            REPLAY_GAIN_PREVENT_CLIPPING_KEY,
            REPLAY_GAIN_DEFAULTS.preventClipping
        )
    )
}
