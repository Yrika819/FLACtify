package com.flactify.viewmodel

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelP2FixTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ============================================================
    // #6: updateAudioOutputRoute() Bluetooth priority
    //
    // These previously asserted that an A2DP route reports the literal string "ldac".
    // A2DP is a transport and does not imply any codec; Android exposes no public API for
    // the negotiated codec, so the route is now reported as a transport plus device name.
    // ============================================================

    @Test
    fun `updateAudioOutputRoute - Bluetooth takes priority over Wired`() {
        val context = mockk<Context>(relaxed = true)
        val audioManager = mockk<AudioManager>(relaxed = true)
        val extractor = TrackMetadataExtractor(context)

        every { context.getSystemService(Context.AUDIO_SERVICE) } returns audioManager

        val bluetoothDevice = mockk<AudioDeviceInfo> {
            every { type } returns AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
            every { productName } returns "Sony WH-1000XM5"
        }
        val wiredDevice = mockk<AudioDeviceInfo> {
            every { type } returns AudioDeviceInfo.TYPE_WIRED_HEADPHONES
            every { productName } returns "Wired Headphones"
        }

        every { audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS) } returns arrayOf(
            bluetoothDevice,
            wiredDevice
        )

        val result = extractor.getBluetoothCodecInfo()
        assertEquals("Bluetooth • Sony WH-1000XM5", result)
    }

    @Test
    fun `updateAudioOutputRoute - Wired only when no Bluetooth`() {
        val context = mockk<Context>(relaxed = true)
        val audioManager = mockk<AudioManager>(relaxed = true)
        val extractor = TrackMetadataExtractor(context)

        every { context.getSystemService(Context.AUDIO_SERVICE) } returns audioManager

        val wiredDevice = mockk<AudioDeviceInfo> {
            every { type } returns AudioDeviceInfo.TYPE_WIRED_HEADPHONES
            every { productName } returns "My Headphones"
        }

        every { audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS) } returns arrayOf(wiredDevice)

        val result = extractor.getBluetoothCodecInfo()
        assertEquals("Wired • My Headphones", result)
    }

    @Test
    fun `updateAudioOutputRoute - USB fallback when productName is blank`() {
        val context = mockk<Context>(relaxed = true)
        val audioManager = mockk<AudioManager>(relaxed = true)
        val extractor = TrackMetadataExtractor(context)

        every { context.getSystemService(Context.AUDIO_SERVICE) } returns audioManager

        val usbDevice = mockk<AudioDeviceInfo> {
            every { type } returns AudioDeviceInfo.TYPE_USB_DEVICE
            every { productName } returns ""
        }

        every { audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS) } returns arrayOf(usbDevice)

        val result = extractor.getBluetoothCodecInfo()
        assertEquals("USB", result)
    }

    @Test
    fun `updateAudioOutputRoute - empty devices results in empty string`() {
        val context = mockk<Context>(relaxed = true)
        val audioManager = mockk<AudioManager>(relaxed = true)
        val extractor = TrackMetadataExtractor(context)

        every { context.getSystemService(Context.AUDIO_SERVICE) } returns audioManager
        every { audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS) } returns arrayOf()

        val result = extractor.getBluetoothCodecInfo()
        assertEquals("", result)
    }

    @Test
    fun `updateAudioOutputRoute - Bluetooth after Wired in array still wins`() {
        val context = mockk<Context>(relaxed = true)
        val audioManager = mockk<AudioManager>(relaxed = true)
        val extractor = TrackMetadataExtractor(context)

        every { context.getSystemService(Context.AUDIO_SERVICE) } returns audioManager

        val wiredDevice = mockk<AudioDeviceInfo> {
            every { type } returns AudioDeviceInfo.TYPE_WIRED_HEADSET
            every { productName } returns "Wired"
        }
        val bluetoothDevice = mockk<AudioDeviceInfo> {
            every { type } returns AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
            every { productName } returns "BT Speaker"
        }

        every { audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS) } returns arrayOf(
            wiredDevice,
            bluetoothDevice
        )

        val result = extractor.getBluetoothCodecInfo()
        assertEquals("Bluetooth • BT Speaker", result)
    }

    // ============================================================
    // #10: preloadHeadersToOfficialCache() cancellation
    // ============================================================

    @Test
    fun `cleanup cancels preload job`() = runTest {
        val viewModel = PlayerViewModel()

        val preloadJobField = PlayerViewModel::class.java.getDeclaredField("preloadJob")
        preloadJobField.isAccessible = true
        val mockJob = mockk<kotlinx.coroutines.Job>(relaxed = true)
        preloadJobField.set(viewModel, mockJob)

        viewModel.cleanup()
        advanceUntilIdle()

        verify { mockJob.cancel() }
    }

    @Test
    fun `preloadHeadersToOfficialCache field is initialized to null`() {
        val viewModel = PlayerViewModel()

        val preloadJobField = PlayerViewModel::class.java.getDeclaredField("preloadJob")
        preloadJobField.isAccessible = true
        val initialValue = preloadJobField.get(viewModel)

        assertNull("preloadJob should be null initially", initialValue)
    }

    // ============================================================
    // #13: resolveTargetIndex() fallback
    // ============================================================

    private fun mockUri(value: String): Uri {
        val uri = mockk<Uri>(relaxed = true)
        every { uri.toString() } returns value
        return uri
    }

    private val trackUri1: Uri by lazy { mockUri("content://track1") }
    private val trackUri2: Uri by lazy { mockUri("content://track2") }
    private val trackUri3: Uri by lazy { mockUri("content://track3") }
    private val nonExistentUri: Uri by lazy { mockUri("content://nonexistent") }

    private fun createTestTracks(): List<TrackData> {
        return listOf(
            TrackData(title = "Track1", artist = "Artist1", album = "Album1", originalIndex = 0, uri = trackUri1),
            TrackData(title = "Track2", artist = "Artist2", album = "Album2", originalIndex = 1, uri = trackUri2),
            TrackData(title = "Track3", artist = "Artist3", album = "Album3", originalIndex = 2, uri = trackUri3)
        )
    }

    @Test
    fun `resolveTargetIndex - returns matching index when currentUri found`() {
        val viewModel = PlayerViewModel()
        val tracks = createTestTracks()

        val result = viewModel.resolveTargetIndex(tracks, trackUri2, false)
        assertEquals(1, result)
    }

    @Test
    fun `resolveTargetIndex - falls back to random when currentUri not found`() {
        val viewModel = PlayerViewModel()
        val tracks = createTestTracks()

        val result = viewModel.resolveTargetIndex(tracks, nonExistentUri, false)
        assertTrue("Index should be within tracks bounds", result in 0 until tracks.size)
    }

    @Test
    fun `resolveTargetIndex - returns random when forceRandom is true`() {
        val viewModel = PlayerViewModel()
        val tracks = listOf(
            TrackData(title = "Track1", artist = "Artist1", album = "Album1", originalIndex = 0, uri = trackUri1),
            TrackData(title = "Track2", artist = "Artist2", album = "Album2", originalIndex = 1, uri = trackUri2)
        )

        val result = viewModel.resolveTargetIndex(tracks, trackUri1, true)
        assertTrue("Index should be within tracks bounds", result in 0 until tracks.size)
    }

    @Test
    fun `resolveTargetIndex - returns random when currentUri is null`() {
        val viewModel = PlayerViewModel()
        val tracks = listOf(
            TrackData(title = "Track1", artist = "Artist1", album = "Album1", originalIndex = 0, uri = trackUri1),
            TrackData(title = "Track2", artist = "Artist2", album = "Album2", originalIndex = 1, uri = trackUri2)
        )

        val result = viewModel.resolveTargetIndex(tracks, null, false)
        assertTrue("Index should be within tracks bounds", result in 0 until tracks.size)
    }

    @Test
    fun `resolveTargetIndex - empty list returns -1`() {
        val viewModel = PlayerViewModel()
        val result = viewModel.resolveTargetIndex(emptyList(), null, true)
        assertEquals("Empty list should return -1", -1, result)
    }

    private fun advanceUntilIdle() {
        testDispatcher.scheduler.advanceUntilIdle()
    }
}
