package com.flactify.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/** The output route evidence available from public platform APIs. */
enum class AudioRouteType {
    BUILTIN_SPEAKER,
    WIRED,
    USB,
    BLUETOOTH,
    HDMI,
    OTHER_UNKNOWN,

    /**
     * The platform version or available APIs cannot identify which device is actually
     * carrying the media stream. Distinct from [OTHER_UNKNOWN], which means a device was
     * identified but is of an unrecognised type.
     */
    UNDETERMINED
}

/** How confident [AudioRouteInfo] is. Never upgraded beyond what was actually observed. */
enum class RouteConfidence {
    /** Device returned by `getAudioDevicesForAttributes`; this is a routing prediction, not playback evidence. */
    ROUTING_PREDICTION,

    /** Reserved for a route observed on the actual routed playback object (for example AudioTrack). */
    ACTIVE_ROUTE,

    /** Only a connected-device list was available, so this is a guess about precedence. */
    CONNECTED_DEVICES_ONLY,

    /** Nothing could be read. */
    NONE
}

/**
 * The active output route.
 *
 * @property deviceName nullable on purpose. Android does not expose which Bluetooth codec is
 *   negotiated, and it reports the *phone model* as `productName` for the built-in speaker
 *   and the 3.5 mm jack, so a name is only meaningful for self-named peripherals.
 */
data class AudioRouteInfo(
    val type: AudioRouteType,
    val deviceName: String? = null,
    val confidence: RouteConfidence = RouteConfidence.NONE
) {
    /**
     * Short label for the player screen, e.g. `Bluetooth • WH-1000XM5`.
     *
     * Never includes a codec name. `TYPE_BLUETOOTH_A2DP` says nothing about whether LDAC,
     * aptX, AAC or SBC was negotiated, and the public API does not expose which.
     */
    val label: String
        get() = when (type) {
            AudioRouteType.BUILTIN_SPEAKER -> "Speaker"
            AudioRouteType.BLUETOOTH -> withName("Bluetooth")
            AudioRouteType.USB -> withName("USB")
            AudioRouteType.WIRED -> withName("Wired")
            AudioRouteType.HDMI -> withName("HDMI")
            AudioRouteType.OTHER_UNKNOWN -> withName("Output")
            // Not a route, so a neutral label rather than a guessed device.
            AudioRouteType.UNDETERMINED -> "Output • unknown"
        }

    private fun withName(prefix: String): String =
        deviceName?.takeIf { it.isNotBlank() }?.let { "$prefix • $it" } ?: prefix
}

/**
 * Reports the best publicly available output-device information.
 *
 * `AudioManager.getDevices(GET_DEVICES_OUTPUTS)` returns every *connected* output, which is not
 * the same thing as the active route: with a headset and a Bluetooth headset both plugged in
 * it reports both, and any precedence applied on top is this app's invention, not a fact.
 *
 * On API 33+ this uses `getAudioDevicesForAttributes(USAGE_MEDIA / CONTENT_TYPE_MUSIC)`, a
 * routing prediction for those attributes. It is not evidence from FLACtify's routed AudioTrack.
 * Below API 33 the connected-device list is used as a heuristic. Neither signal can trigger
 * pause-on-disconnect.
 */
class AudioRouteMonitor(private val context: Context) {

    /**
     * Blocking; call from [kotlinx.coroutines.Dispatchers.IO].
     *
     * @return the best route the platform can confirm, or null if nothing could be read.
     */
    fun currentRoute(): AudioRouteInfo? {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activeRouteOnApi33(audioManager)?.let { return it }
        }
        return connectedDeviceFallback(audioManager)
    }

    /**
     * Starts reporting route changes so a headphone or USB DAC connected mid-track does not
     * leave the diagnostics showing the previous route until the next track.
     * If supplied, [onBluetoothOutputRemoved] is called when a Bluetooth audio output disappears.
     *
     * @return false if the callback could not be registered; the caller then just polls.
     */
    fun startObserving(
        onBluetoothOutputRemoved: (() -> Unit)? = null,
        onRouteChanged: () -> Unit
    ): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return false
        if (deviceCallback != null) return true
        var previousRoute = currentRoute()
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                previousRoute = currentRoute()
                onRouteChanged()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                val bluetoothOutputRemoved = removedDevices?.any { device ->
                    AudioRouteMonitor.isBluetoothOutput(device)
                } == true
                val routeAfterRemoval = currentRoute()
                if (shouldPauseForBluetoothRemoval(previousRoute, routeAfterRemoval, bluetoothOutputRemoved)) {
                    onBluetoothOutputRemoved?.invoke()
                }
                previousRoute = routeAfterRemoval
                onRouteChanged()
            }
        }
        return try {
            audioManager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
            deviceCallback = callback
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Stops route observation. Safe to call when not observing. */
    fun stopObserving() {
        val callback = deviceCallback ?: return
        deviceCallback = null
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            audioManager.unregisterAudioDeviceCallback(callback)
        } catch (e: Exception) {
            // Already unregistered, or the process is tearing down.
        }
    }

    private var deviceCallback: AudioDeviceCallback? = null

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun activeRouteOnApi33(audioManager: AudioManager): AudioRouteInfo? = try {
        val devices = audioManager.getAudioDevicesForAttributes(mediaAttributes())
        if (devices.isNullOrEmpty()) {
            null
        } else {
            // The platform may legitimately report more than one device for duplicated paths;
            // the first entry is the one the stream will be opened on.
            routingPrediction(devices[0])
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Pre-API-33 path. A connected-device list cannot identify the active route, so this is
     * reported as a lower confidence and the UI says so rather than implying certainty.
     */
    private fun connectedDeviceFallback(audioManager: AudioManager): AudioRouteInfo? = try {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        selectByPrecedence(devices?.toList().orEmpty())
    } catch (e: Exception) {
        null
    }

    companion object {
        /**
         * Precedence used only when the platform cannot tell us the active route. Ordered by how
         * commonly the device wins the real media route; this is an explicit heuristic, not a
         * measurement, and the result is downgraded to [RouteConfidence.CONNECTED_DEVICES_ONLY].
         */
        private val PRECEDENCE = listOf(
            AudioRouteType.BLUETOOTH,
            AudioRouteType.USB,
            AudioRouteType.HDMI,
            AudioRouteType.WIRED,
            AudioRouteType.BUILTIN_SPEAKER,
            AudioRouteType.OTHER_UNKNOWN
        )

        internal fun mediaAttributes(): AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        /** Pause only for a Bluetooth-to-non-Bluetooth transition observed on actual playback routing.
         * Predictions and connected-device heuristics must never pause playback. */
        internal fun shouldPauseForBluetoothRemoval(
            previous: AudioRouteInfo?,
            current: AudioRouteInfo?,
            bluetoothOutputRemoved: Boolean
        ): Boolean = bluetoothOutputRemoved &&
            previous?.type == AudioRouteType.BLUETOOTH &&
            previous.confidence == RouteConfidence.ACTIVE_ROUTE &&
            current != null &&
            current.confidence == RouteConfidence.ACTIVE_ROUTE &&
            current.type != AudioRouteType.BLUETOOTH

        private fun describe(device: AudioDeviceInfo, confidence: RouteConfidence): AudioRouteInfo {
            val type = classify(device.type) ?: AudioRouteType.OTHER_UNKNOWN
            return AudioRouteInfo(type = type, deviceName = device.nameFor(type), confidence = confidence)
        }

        internal fun routingPrediction(device: AudioDeviceInfo): AudioRouteInfo =
            describe(device, RouteConfidence.ROUTING_PREDICTION)

        internal fun selectByPrecedence(devices: List<AudioDeviceInfo>): AudioRouteInfo? {
            if (devices.isEmpty()) return null
            val byType = LinkedHashMap<AudioRouteType, AudioDeviceInfo>()
            for (device in devices) {
                val type = classify(device.type) ?: continue
                byType.getOrPut(type) { device }
            }
            for (type in PRECEDENCE) {
                val device = byType[type] ?: continue
                return describe(device, RouteConfidence.CONNECTED_DEVICES_ONLY)
            }
            return null
        }

        /**
         * Maps an `AudioDeviceInfo.TYPE_*` constant to a route type.
         *
         * Returns null for input-only or otherwise non-routable types so they are skipped
         * rather than being mislabelled as an output route.
         */
        internal fun classify(deviceType: Int): AudioRouteType? = when (deviceType) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST,
            AudioDeviceInfo.TYPE_HEARING_AID -> AudioRouteType.BLUETOOTH

            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> AudioRouteType.USB

            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_LINE_DIGITAL,
            AudioDeviceInfo.TYPE_AUX_LINE -> AudioRouteType.WIRED

            AudioDeviceInfo.TYPE_HDMI,
            AudioDeviceInfo.TYPE_HDMI_ARC,
            AudioDeviceInfo.TYPE_HDMI_EARC -> AudioRouteType.HDMI

            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE,
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> AudioRouteType.BUILTIN_SPEAKER

            // TYPE_DOCK, TYPE_FM, TYPE_IP, TYPE_BUS, TYPE_TELEPHONY, TYPE_REMOTE_SUBMIX,
            // TYPE_MULTICHANNEL_GROUP and anything unrecognised.
            else -> AudioRouteType.OTHER_UNKNOWN
        }

        internal fun isBluetoothOutput(device: AudioDeviceInfo): Boolean =
            device.isSink && classify(device.type) == AudioRouteType.BLUETOOTH

        /**
         * `productName` is only trustworthy for peripherals that name themselves. For the
         * built-in speaker it is the phone model, which is not what the user is listening
         * through, so the name is dropped.
         */
        private fun AudioDeviceInfo.nameFor(type: AudioRouteType): String? {
            if (type == AudioRouteType.BUILTIN_SPEAKER) return null
            return try {
                productName?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            } catch (e: Exception) {
                null
            }
        }
    }
}
