package com.flactify

/** Shared playback preferences read by both the UI and the playback service. */
object PlaybackSettings {
    const val PREFERENCES_NAME = "flactify_settings"
    const val PAUSE_ON_BLUETOOTH_DISCONNECT_KEY = "pause_on_bluetooth_disconnect"
    const val PAUSE_ON_BLUETOOTH_DISCONNECT_DEFAULT = true
}
