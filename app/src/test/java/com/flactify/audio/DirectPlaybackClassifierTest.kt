package com.flactify.audio

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for interpreting `AudioManager.getDirectPlaybackSupport`.
 *
 * The values used here are the ones published in the official reference: the method returns a
 * bitfield, not a single enum, and there is no `DIRECT_PLAYBACK_SUPPORTED` constant. Getting
 * this wrong is the difference between "this route can take PCM straight through" and "this
 * route can decode compressed audio on a DSP", which are entirely different claims.
 */
class DirectPlaybackClassifierTest {

    @Test
    fun `not supported maps to UNAVAILABLE`() {
        assertEquals(
            DirectSupport.UNAVAILABLE,
            DirectPlaybackClassifier.classify(AudioManager.DIRECT_PLAYBACK_NOT_SUPPORTED)
        )
    }

    @Test
    fun `bitstream supported maps to SUPPORTED`() {
        assertEquals(
            DirectSupport.SUPPORTED,
            DirectPlaybackClassifier.classify(AudioManager.DIRECT_PLAYBACK_BITSTREAM_SUPPORTED)
        )
    }

    @Test
    fun `offload alone is not direct playback`() {
        // Offload means the platform decodes on a DSP. FLACtify software-decodes and hands the
        // platform PCM, so this must not be reported as direct support.
        assertEquals(
            DirectSupport.OFFLOAD_ONLY,
            DirectPlaybackClassifier.classify(AudioManager.DIRECT_PLAYBACK_OFFLOAD_SUPPORTED)
        )
    }

    @Test
    fun `offload gapless is not direct playback`() {
        assertEquals(
            DirectSupport.OFFLOAD_ONLY,
            DirectPlaybackClassifier.classify(AudioManager.DIRECT_PLAYBACK_OFFLOAD_GAPLESS_SUPPORTED)
        )
    }

    @Test
    fun `offload combined with bitstream is direct playback`() {
        val combined = AudioManager.DIRECT_PLAYBACK_OFFLOAD_SUPPORTED or
                AudioManager.DIRECT_PLAYBACK_OFFLOAD_GAPLESS_SUPPORTED or
                AudioManager.DIRECT_PLAYBACK_BITSTREAM_SUPPORTED
        assertEquals(DirectSupport.SUPPORTED, DirectPlaybackClassifier.classify(combined))
    }

    @Test
    fun `null result is UNKNOWN rather than UNAVAILABLE`() {
        // An unanswered question must never be rendered as a negative capability claim.
        assertEquals(DirectSupport.UNKNOWN, DirectPlaybackClassifier.classify(null))
    }

    @Test
    fun `unrecognised bitfield is UNKNOWN`() {
        assertEquals(DirectSupport.UNKNOWN, DirectPlaybackClassifier.classify(1 shl 7))
    }

    @Test
    fun `capability exposes one entry per probed encoding`() {
        val capability = DirectPlaybackClassifier.capability(
            probedAtSampleRateHz = 96_000,
            probedAtChannelCount = 2,
            probedAtChannelMask = android.media.AudioFormat.CHANNEL_OUT_STEREO,
            results = mapOf(
                PcmEncoding.PCM_16BIT to AudioManager.DIRECT_PLAYBACK_BITSTREAM_SUPPORTED,
                PcmEncoding.PCM_24BIT_PACKED to AudioManager.DIRECT_PLAYBACK_NOT_SUPPORTED,
                PcmEncoding.PCM_FLOAT to null
            ),
            advertisedProfiles = emptyList()
        )

        assertEquals(PcmEncoding.PROBED.size, capability.perEncoding.size)
        assertTrue(capability.pcm16Supported)
        assertFalse(capability.pcm24Supported)
        assertEquals(DirectSupport.UNKNOWN, capability.supportFor(PcmEncoding.PCM_FLOAT))
    }

    @Test
    fun `unprobed platform reports UNKNOWN for every encoding`() {
        val capability = DirectPlaybackCapability.unknown("Android 13未満のため照会不可")

        assertFalse(capability.isProbed)
        assertNull(capability.probedAtSampleRateHz)
        assertNull(capability.probedAtChannelCount)
        assertEquals(DirectSupport.UNKNOWN, capability.supportFor(PcmEncoding.PCM_16BIT))
        assertEquals(DirectSupport.UNKNOWN, capability.supportFor(PcmEncoding.PCM_24BIT_PACKED))
        assertEquals(DirectSupport.UNKNOWN, capability.supportFor(PcmEncoding.PCM_FLOAT))
        assertTrue(capability.supportedEncodings.isEmpty())
    }

    @Test
    fun `capability is evaluated at the real playback rate not a fixed table`() {
        // The same encoding can be direct-capable at 96 kHz and not at 192 kHz, so the probed
        // rate is carried through and must not be lost.
        val capability = DirectPlaybackClassifier.capability(
            probedAtSampleRateHz = 192_000,
            probedAtChannelCount = 2,
            probedAtChannelMask = android.media.AudioFormat.CHANNEL_OUT_STEREO,
            results = mapOf(PcmEncoding.PCM_16BIT to AudioManager.DIRECT_PLAYBACK_NOT_SUPPORTED),
            advertisedProfiles = emptyList()
        )

        assertTrue(capability.isProbed)
        assertEquals(192_000, capability.probedAtSampleRateHz)
        assertEquals(192_000, capability.capabilityFor(PcmEncoding.PCM_16BIT)?.sampleRateHz)
    }

    @Test
    fun `capability is not considered probed unless both rate and channels are known`() {
        // A direct-playback answer is only meaningful for a concrete (rate, layout) pair.
        val noChannels = DirectPlaybackClassifier.capability(
            probedAtSampleRateHz = 48_000,
            probedAtChannelCount = null,
            probedAtChannelMask = null,
            results = mapOf(PcmEncoding.PCM_16BIT to AudioManager.DIRECT_PLAYBACK_BITSTREAM_SUPPORTED),
            advertisedProfiles = emptyList()
        )
        assertFalse(noChannels.isProbed)
        assertNull(noChannels.conditionLabel)

        val noRate = DirectPlaybackClassifier.capability(
            probedAtSampleRateHz = null,
            probedAtChannelCount = 2,
            probedAtChannelMask = null,
            results = mapOf(PcmEncoding.PCM_16BIT to AudioManager.DIRECT_PLAYBACK_BITSTREAM_SUPPORTED),
            advertisedProfiles = emptyList()
        )
        assertFalse(noRate.isProbed)
        assertNull(noRate.conditionLabel)
    }

    // ── Channel count → output mask ───────────────────────────────────────────

    @Test
    fun `channel count maps to the matching CHANNEL_OUT mask`() {
        assertEquals(android.media.AudioFormat.CHANNEL_OUT_MONO, DirectPlaybackClassifier.channelMaskFor(1))
        assertEquals(android.media.AudioFormat.CHANNEL_OUT_STEREO, DirectPlaybackClassifier.channelMaskFor(2))
        assertEquals(android.media.AudioFormat.CHANNEL_OUT_QUAD, DirectPlaybackClassifier.channelMaskFor(4))
        assertEquals(android.media.AudioFormat.CHANNEL_OUT_5POINT1, DirectPlaybackClassifier.channelMaskFor(6))
        assertEquals(android.media.AudioFormat.CHANNEL_OUT_7POINT1_SURROUND, DirectPlaybackClassifier.channelMaskFor(8))
    }

    @Test
    fun `8 channels uses 7POINT1_SURROUND not the deprecated 7POINT1`() {
        // Android deprecated CHANNEL_OUT_7POINT1 in API 23: "Not the typical 7.1 surround
        // configuration. Use CHANNEL_OUT_7POINT1_SURROUND instead." The official
        // channel-count table also lists 8 -> CHANNEL_OUT_7POINT1_SURROUND.
        val mask = DirectPlaybackClassifier.channelMaskFor(8)
        assertEquals(android.media.AudioFormat.CHANNEL_OUT_7POINT1_SURROUND, mask)
        assertNotEquals(
            "the deprecated CHANNEL_OUT_7POINT1 must not be used for 8 channels",
            android.media.AudioFormat.CHANNEL_OUT_7POINT1,
            mask
        )
        // 7POINT1_SURROUND is still an 8-channel mask, so the channel count is unchanged.
        assertEquals(8, Integer.bitCount(mask!!))
    }

    @Test
    fun `6 channels uses 5POINT1`() {
        val mask = DirectPlaybackClassifier.channelMaskFor(6)
        assertEquals(android.media.AudioFormat.CHANNEL_OUT_5POINT1, mask)
        assertEquals(6, Integer.bitCount(mask!!))
    }

    @Test
    fun `every probed mask constant exists on the minimum supported API`() {
        // CHANNEL_OUT_6POINT1 (7 channels) only exists from API 34, so it cannot be used to
        // build a probe format for a minSdk 26 device. CHANNEL_OUT_7POINT1_SURROUND is API 23,
        // which is within range. This assertion is what stops an API 34-only constant creeping
        // back in, and also pins 8ch to the non-deprecated mask.
        listOf(1, 2, 4, 6, 8).forEach { count ->
            val mask = DirectPlaybackClassifier.channelMaskFor(count)
            assertNotNull("channelCount=$count", mask)
            assertTrue(
                "channelCount=$count must not use an API 34+ or deprecated constant",
                mask in setOf(
                    android.media.AudioFormat.CHANNEL_OUT_MONO,
                    android.media.AudioFormat.CHANNEL_OUT_STEREO,
                    android.media.AudioFormat.CHANNEL_OUT_QUAD,
                    android.media.AudioFormat.CHANNEL_OUT_5POINT1,
                    android.media.AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
                )
            )
        }
        // 7 channels would need a composite the official table does not name, so it stays
        // unprobed rather than guessing.
        assertNull(DirectPlaybackClassifier.channelMaskFor(7))
    }

    @Test
    fun `a 6-channel source is no longer probed as stereo`() {
        // The defect this fixes: a 6-channel file was probed with CHANNEL_OUT_STEREO while the
        // UI displayed "6 ch", so the stated condition contradicted the AudioFormat sent to
        // AudioPolicy.
        val six = DirectPlaybackClassifier.channelMaskFor(6)!!
        assertEquals(android.media.AudioFormat.CHANNEL_OUT_5POINT1, six)
        assertNotEquals(android.media.AudioFormat.CHANNEL_OUT_STEREO, six)
        assertEquals(6, Integer.bitCount(six))
    }

    @Test
    fun `an 8-channel source is probed as 7_1 surround not stereo`() {
        val eight = DirectPlaybackClassifier.channelMaskFor(8)!!
        assertEquals(android.media.AudioFormat.CHANNEL_OUT_7POINT1_SURROUND, eight)
        assertNotEquals(android.media.AudioFormat.CHANNEL_OUT_STEREO, eight)
        assertEquals(8, Integer.bitCount(eight))
    }

    @Test
    fun `a 4-channel source is probed as quad not stereo`() {
        val four = DirectPlaybackClassifier.channelMaskFor(4)!!
        assertEquals(android.media.AudioFormat.CHANNEL_OUT_QUAD, four)
        assertEquals(4, Integer.bitCount(four))
    }

    @Test
    fun `channel counts with no canonical layout return null instead of stereo`() {
        // 3, 5, 9, 10, 11, 12+ and out-of-range values have no single CHANNEL_OUT constant
        // that a bare channel count identifies, so the probe must be skipped rather than
        // silently probing a layout that may not be the content's.
        listOf(0, 3, 5, 7, 9, 10, 11, 12, 16, 24, -1).forEach { count ->
            assertNull("channelCount=$count", DirectPlaybackClassifier.channelMaskFor(count))
        }
    }

    @Test
    fun `an ambiguous channel count never falls back to stereo`() {
        // Explicitly: the old behaviour mapped every non-mono count, including 3, 5 and 9, to
        // CHANNEL_OUT_STEREO. Nothing may reach the probe without a real mask.
        listOf(3, 5, 7, 9, 10, 11, 13).forEach { count ->
            assertNotEquals(
                "channelCount=$count must not be probed as stereo",
                android.media.AudioFormat.CHANNEL_OUT_STEREO,
                DirectPlaybackClassifier.channelMaskFor(count) ?: 0
            )
        }
    }
}
