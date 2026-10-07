package com.flactify.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioRouteMonitorTest {

    private fun device(type: Int, name: String = ""): AudioDeviceInfo = mockk<AudioDeviceInfo> {
        every { this@mockk.type } returns type
        every { this@mockk.productName } returns name
    }

    // ── Transport → route type ────────────────────────────────────────────────

    @Test
    fun `A2DP is a Bluetooth route and never a codec name`() {
        val route = AudioRouteMonitor.selectByPrecedence(
            listOf(device(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "WH-1000XM5"))
        )
        assertEquals(AudioRouteType.BLUETOOTH, route?.type)
        // A2DP is a transport. Nothing in the public API says whether LDAC/aptX/AAC/SBC was
        // negotiated, so no codec name may appear anywhere in the label.
        val label = route!!.label
        assertEquals("Bluetooth • WH-1000XM5", label)
        assertTrue(
            "Label must not name a codec: $label",
            listOf("LDAC", "ldac", "aptX", "AAC", "SBC", "Lossless").none { label.contains(it) }
        )
    }

    @Test
    fun `Bluetooth SCO is still a Bluetooth route`() {
        val route = AudioRouteMonitor.selectByPrecedence(
            listOf(device(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "Car Audio"))
        )
        assertEquals(AudioRouteType.BLUETOOTH, route?.type)
    }

    @Test
    fun `BLE devices are Bluetooth routes`() {
        listOf(
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST,
            AudioDeviceInfo.TYPE_HEARING_AID
        ).forEach { type ->
            val route = AudioRouteMonitor.selectByPrecedence(listOf(device(type, "LE")))
            assertEquals("type $type", AudioRouteType.BLUETOOTH, route?.type)
        }
    }

    @Test
    fun `USB device types map to USB`() {
        listOf(
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_ACCESSORY
        ).forEach { type ->
            val route = AudioRouteMonitor.selectByPrecedence(listOf(device(type, "RME DAC")))
            assertEquals("type $type", AudioRouteType.USB, route?.type)
            assertEquals("USB • RME DAC", route?.label)
        }
    }

    @Test
    fun `wired types map to Wired`() {
        listOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_LINE_DIGITAL
        ).forEach { type ->
            val route = AudioRouteMonitor.selectByPrecedence(listOf(device(type, "Earphones")))
            assertEquals("type $type", AudioRouteType.WIRED, route?.type)
        }
    }

    @Test
    fun `HDMI types map to HDMI`() {
        listOf(
            AudioDeviceInfo.TYPE_HDMI,
            AudioDeviceInfo.TYPE_HDMI_ARC,
            AudioDeviceInfo.TYPE_HDMI_EARC
        ).forEach { type ->
            val route = AudioRouteMonitor.selectByPrecedence(listOf(device(type, "AV Receiver")))
            assertEquals("type $type", AudioRouteType.HDMI, route?.type)
        }
    }

    @Test
    fun `unrecognised device type falls back to OTHER_UNKNOWN rather than being dropped`() {
        val route = AudioRouteMonitor.selectByPrecedence(listOf(device(9999, "Mystery")))
        assertEquals(AudioRouteType.OTHER_UNKNOWN, route?.type)
    }

    // ── Device naming ─────────────────────────────────────────────────────────

    @Test
    fun `built-in speaker drops the product name because it is the phone model`() {
        // AudioDeviceInfo.productName is the handset model for the built-in speaker, which is
        // not what the listener is hearing through.
        val route = AudioRouteMonitor.selectByPrecedence(
            listOf(device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "Pixel 7a"))
        )
        assertEquals(AudioRouteType.BUILTIN_SPEAKER, route?.type)
        assertNull(route?.deviceName)
        assertEquals("Speaker", route?.label)
    }

    @Test
    fun `blank peripheral name yields a bare route label`() {
        val route = AudioRouteMonitor.selectByPrecedence(listOf(device(AudioDeviceInfo.TYPE_USB_DEVICE, "   ")))
        assertEquals("USB", route?.label)
    }

    // ── Priority ──────────────────────────────────────────────────────────────

    @Test
    fun `Bluetooth outranks wired regardless of array order`() {
        val bluetooth = device(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "BT")
        val wired = device(AudioDeviceInfo.TYPE_WIRED_HEADSET, "Wired")

        assertEquals(
            AudioRouteType.BLUETOOTH,
            AudioRouteMonitor.selectByPrecedence(listOf(bluetooth, wired))?.type
        )
        assertEquals(
            AudioRouteType.BLUETOOTH,
            AudioRouteMonitor.selectByPrecedence(listOf(wired, bluetooth))?.type
        )
    }

    @Test
    fun `USB outranks wired`() {
        val route = AudioRouteMonitor.selectByPrecedence(
            listOf(
                device(AudioDeviceInfo.TYPE_WIRED_HEADSET, "Wired"),
                device(AudioDeviceInfo.TYPE_USB_DEVICE, "DAC")
            )
        )
        assertEquals(AudioRouteType.USB, route?.type)
    }

    @Test
    fun `empty device list yields no route`() {
        assertNull(AudioRouteMonitor.selectByPrecedence(emptyList()))
    }

    // ── Confidence: a connected-device list is not the active route ───────────

    @Test
    fun `attribute-based routing prediction is never labeled active`() {
        val route = AudioRouteMonitor.routingPrediction(
            device(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "Headphones")
        )
        assertEquals(RouteConfidence.ROUTING_PREDICTION, route.confidence)
        assertFalse(route.confidence == RouteConfidence.ACTIVE_ROUTE)
    }

    @Test
    fun `connected-device fallback is explicitly marked as lower confidence`() {
        // getDevices() reports every connected output. Any precedence applied on top is this
        // app's heuristic, so the result must never be presented as the active route.
        val route = AudioRouteMonitor.selectByPrecedence(
            listOf(device(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, "Cable"))
        )
        assertEquals(RouteConfidence.CONNECTED_DEVICES_ONLY, route?.confidence)
    }

    @Test
    fun `an undetermined route is labelled as unknown rather than guessing a device`() {
        val route = AudioRouteInfo(AudioRouteType.UNDETERMINED, null, RouteConfidence.NONE)
        assertEquals("Output • unknown", route.label)
        assertNull(route.deviceName)
    }

    @Test
    fun `route observation can be started and stopped without throwing`() {
        val context = mockk<Context>(relaxed = true)
        val audioManager = mockk<AudioManager>(relaxed = true)
        every { context.getSystemService(Context.AUDIO_SERVICE) } returns audioManager

        val monitor = AudioRouteMonitor(context)
        // A mid-track plug must be able to re-read the route; registering the callback is what
        // keeps the diagnostics from showing the previous track's device until the next track.
        assertTrue(monitor.startObserving { })
        monitor.stopObserving()
        // Stopping twice must be safe, since cleanup can run more than once.
        monitor.stopObserving()
    }

    @Test
    fun `route observation reports failure when no AudioManager is available`() {
        val context = mockk<Context>(relaxed = true)
        every { context.getSystemService(Context.AUDIO_SERVICE) } returns null

        assertFalse(AudioRouteMonitor(context).startObserving { })
    }

    @Test
    fun `confirmed active Bluetooth removal followed by speaker route is pause eligible`() {
        val previous = AudioRouteInfo(AudioRouteType.BLUETOOTH, "Headphones", RouteConfidence.ACTIVE_ROUTE)
        val current = AudioRouteInfo(AudioRouteType.BUILTIN_SPEAKER, null, RouteConfidence.ACTIVE_ROUTE)

        assertTrue(AudioRouteMonitor.shouldPauseForBluetoothRemoval(previous, current, true))
    }

    @Test
    fun `inactive Bluetooth removal while USB is active does not pause`() {
        val previous = AudioRouteInfo(AudioRouteType.USB, "DAC", RouteConfidence.ACTIVE_ROUTE)
        val current = AudioRouteInfo(AudioRouteType.USB, "DAC", RouteConfidence.ACTIVE_ROUTE)

        assertFalse(AudioRouteMonitor.shouldPauseForBluetoothRemoval(previous, current, true))
    }

    @Test
    fun `routing prediction cannot establish Bluetooth disconnect`() {
        val previous = AudioRouteInfo(AudioRouteType.BLUETOOTH, "Headphones", RouteConfidence.ROUTING_PREDICTION)
        val current = AudioRouteInfo(AudioRouteType.BUILTIN_SPEAKER, null, RouteConfidence.ROUTING_PREDICTION)

        assertFalse(AudioRouteMonitor.shouldPauseForBluetoothRemoval(previous, current, true))
    }

    @Test
    fun `connected device fallback cannot establish Bluetooth disconnect`() {
        val previous = AudioRouteInfo(AudioRouteType.BLUETOOTH, "Headphones", RouteConfidence.CONNECTED_DEVICES_ONLY)
        val current = AudioRouteInfo(AudioRouteType.BUILTIN_SPEAKER, null, RouteConfidence.CONNECTED_DEVICES_ONLY)

        assertFalse(AudioRouteMonitor.shouldPauseForBluetoothRemoval(previous, current, true))
    }

    @Test
    fun `remaining active Bluetooth route does not pause after one Bluetooth device is removed`() {
        val bluetooth = AudioRouteInfo(AudioRouteType.BLUETOOTH, "Second headset", RouteConfidence.ACTIVE_ROUTE)

        assertFalse(AudioRouteMonitor.shouldPauseForBluetoothRemoval(bluetooth, bluetooth, true))
    }

    @Test
    fun `unrelated device removal does not pause`() {
        val bluetooth = AudioRouteInfo(AudioRouteType.BLUETOOTH, "Headphones", RouteConfidence.ACTIVE_ROUTE)
        val speaker = AudioRouteInfo(AudioRouteType.BUILTIN_SPEAKER, null, RouteConfidence.ACTIVE_ROUTE)

        assertFalse(AudioRouteMonitor.shouldPauseForBluetoothRemoval(bluetooth, speaker, false))
    }
}
