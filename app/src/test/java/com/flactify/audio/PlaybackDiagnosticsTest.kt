package com.flactify.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the separation between source format, route capability and measured output.
 *
 * The bug these tests exist to prevent: reporting "FLAC 96 kHz 24 bit" on a route whose
 * AudioTrack was actually opened as Float32 at 48 kHz, and calling that bit-perfect.
 */
class PlaybackDiagnosticsTest {

    private fun capability(supported: Boolean, probed: Boolean = true): DirectPlaybackCapability {
        if (!probed) return DirectPlaybackCapability.unknown("未照会")
        val result = if (supported) {
            android.media.AudioManager.DIRECT_PLAYBACK_BITSTREAM_SUPPORTED
        } else {
            android.media.AudioManager.DIRECT_PLAYBACK_NOT_SUPPORTED
        }
        return DirectPlaybackClassifier.capability(
            probedAtSampleRateHz = 96_000,
            probedAtChannelCount = 2,
            probedAtChannelMask = DirectPlaybackClassifier.channelMaskFor(2),
            results = PcmEncoding.PROBED.associateWith { result },
            advertisedProfiles = emptyList()
        )
    }

    @Test
    fun `a 24-bit source does not become a 24-bit output by default`() {
        val diagnostics = PlaybackDiagnostics(
            source = SourceAudioInfo("FLAC", 96_000, 24, 2),
            actualOutput = ActualOutputInfo.UNKNOWN
        )

        assertEquals(24, diagnostics.source.bitDepth)
        // The output was never measured, so it must stay unknown rather than inheriting 24.
        assertNull(diagnostics.actualOutput.pcmEncoding)
        assertFalse(diagnostics.actualOutput.isFullyObserved)
    }

    @Test
    fun `a direct-capable route does not mark the output as direct`() {
        // The DAC advertises direct PCM support, but Media3 opened a Float32 AudioTrack at
        // the mixer's rate. Capability and measurement live in different fields and hold
        // different values; neither is allowed to overwrite the other.
        val diagnostics = PlaybackDiagnostics(
            source = SourceAudioInfo("FLAC", 96_000, 24, 2),
            route = AudioRouteInfo(AudioRouteType.USB, "RME DAC", RouteConfidence.ACTIVE_ROUTE),
            capabilities = capability(supported = true),
            actualOutput = ActualOutputInfo(PcmEncoding.PCM_FLOAT, 48_000, 12)
        )

        assertTrue(diagnostics.capabilities.isProbed)
        assertEquals("Direct supported", diagnostics.directCapabilityLabel)
        // The measured output is Float32 at 48 kHz, not the 24-bit/96 kHz source.
        assertEquals(PcmEncoding.PCM_FLOAT, diagnostics.actualOutput.pcmEncoding)
        assertEquals(48_000, diagnostics.actualOutput.sampleRateHz)
        assertEquals(96_000, diagnostics.source.sampleRateHz)
        assertEquals(24, diagnostics.source.bitDepth)
    }

    @Test
    fun `measured output does not overwrite the source when a capability refresh arrives`() {
        val measured = PlaybackDiagnostics(
            source = SourceAudioInfo("FLAC", 96_000, 24, 2),
            actualOutput = ActualOutputInfo(PcmEncoding.PCM_16BIT, 44_100, 12)
        )
        val afterRefresh = measured.copy(
            route = AudioRouteInfo(AudioRouteType.BLUETOOTH, "WH-1000XM5"),
            capabilities = capability(supported = false)
        )

        assertEquals(PcmEncoding.PCM_16BIT, afterRefresh.actualOutput.pcmEncoding)
        assertEquals(44_100, afterRefresh.actualOutput.sampleRateHz)
        assertEquals(24, afterRefresh.source.bitDepth)
    }

    // ── AudioTrackConfig.channelConfig is a mask, not a count ─────────────────

    @Test
    fun `stereo AudioTrackConfig reports 2 channels not 12`() {
        // Media3 documents channelConfig as "See AudioFormat.CHANNEL_OUT_XXX constants";
        // CHANNEL_OUT_STEREO is 12. Reading the raw value as a count displayed "12 channels".
        val stereo = android.media.AudioFormat.CHANNEL_OUT_STEREO
        val info = ActualOutputInfo(PcmEncoding.PCM_FLOAT, 48_000, stereo)

        assertEquals(12, info.channelMask)
        assertEquals(2, info.channelCount)
        assertTrue(info.isFullyObserved)
    }

    @Test
    fun `mono AudioTrackConfig reports 1 channel not 4`() {
        val mono = android.media.AudioFormat.CHANNEL_OUT_MONO
        assertEquals(4, mono)
        assertEquals(1, ActualOutputInfo(pcmEncoding = PcmEncoding.PCM_16BIT, channelMask = mono).channelCount)
    }

    @Test
    fun `surround masks map to their channel counts`() {
        val cases = mapOf(
            android.media.AudioFormat.CHANNEL_OUT_QUAD to 4,
            android.media.AudioFormat.CHANNEL_OUT_SURROUND to 4,
            android.media.AudioFormat.CHANNEL_OUT_5POINT1 to 6,
            android.media.AudioFormat.CHANNEL_OUT_6POINT1 to 7,
            android.media.AudioFormat.CHANNEL_OUT_7POINT1 to 8
        )
        cases.forEach { (mask, count) ->
            assertEquals("mask=$mask", count, ActualOutputInfo.channelCountForMask(mask))
        }
    }

    @Test
    fun `5_1_2 is 8 channels and 7_1_2 is 10 channels`() {
        // The constant names state the layout as X.Y.Z, so the count is the sum.
        assertEquals(8, ActualOutputInfo.channelCountForMask(android.media.AudioFormat.CHANNEL_OUT_5POINT1POINT2))
        assertEquals(10, ActualOutputInfo.channelCountForMask(android.media.AudioFormat.CHANNEL_OUT_7POINT1POINT2))
    }

    @Test
    fun `5_1_4 is 10 channels`() {
        // Previously mapped to 8. CHANNEL_OUT_5POINT1POINT4 is 5+1+4 = 10 channels.
        val mask = android.media.AudioFormat.CHANNEL_OUT_5POINT1POINT4
        assertEquals(10, ActualOutputInfo.channelCountForMask(mask))
        // The mask is a bit field, so its channel bits corroborate the name.
        assertEquals(10, Integer.bitCount(mask))
    }

    @Test
    fun `7_1_4 is 12 channels`() {
        // Previously mapped to 10. CHANNEL_OUT_7POINT1POINT4 is 7+1+4 = 12 channels.
        val mask = android.media.AudioFormat.CHANNEL_OUT_7POINT1POINT4
        assertEquals(12, ActualOutputInfo.channelCountForMask(mask))
        assertEquals(12, Integer.bitCount(mask))
    }

    @Test
    fun `7_1_surround is 8 channels`() {
        assertEquals(8, ActualOutputInfo.channelCountForMask(android.media.AudioFormat.CHANNEL_OUT_7POINT1_SURROUND))
    }

    @Test
    fun `mask values are not their own channel count`() {
        // Guards the specific mistake this table exists to prevent: reading the raw mask as a
        // count. CHANNEL_OUT_STEREO is 12 but is 2 channels.
        assertEquals(12, android.media.AudioFormat.CHANNEL_OUT_STEREO)
        assertEquals(2, ActualOutputInfo.channelCountForMask(12))
        assertEquals(204, android.media.AudioFormat.CHANNEL_OUT_QUAD)
        assertEquals(4, ActualOutputInfo.channelCountForMask(204))
    }

    @Test
    fun `an unrecognised mask yields null rather than a guessed count`() {
        // A mask with no defined CHANNEL_OUT_* meaning carries no channel count. Guessing one
        // would be fabrication, so it is reported as unknown.
        assertNull(ActualOutputInfo.channelCountForMask(android.media.AudioFormat.CHANNEL_OUT_DEFAULT))
        assertNull(ActualOutputInfo.channelCountForMask(0))
        assertNull(ActualOutputInfo.channelCountForMask(-1))
        assertNull(ActualOutputInfo.channelCountForMask(0x1234))
    }

    @Test
    fun `in and out channel constants share numeric values`() {
        // AudioFormat gives CHANNEL_IN_STEREO and CHANNEL_OUT_STEREO the same value (12), so a
        // raw mask cannot be attributed to an input or an output path. The count is still
        // unambiguous, so it is derived rather than discarded.
        assertEquals(
            android.media.AudioFormat.CHANNEL_IN_STEREO,
            android.media.AudioFormat.CHANNEL_OUT_STEREO
        )
        assertEquals(2, ActualOutputInfo.channelCountForMask(android.media.AudioFormat.CHANNEL_IN_STEREO))
    }

    @Test
    fun `output with an unrecognised mask is not reported as fully observed`() {
        val info = ActualOutputInfo(PcmEncoding.PCM_FLOAT, 48_000, 0x1234)
        assertNull(info.channelCount)
        assertFalse(info.isFullyObserved)
    }

    // ── Direct capability honesty ────────────────────────────────────────────

    @Test
    fun `unknown capability is not rendered as supported`() {
        val unprobed = PlaybackDiagnostics(capabilities = capability(supported = false, probed = false))
        assertEquals("Direct unknown", unprobed.directCapabilityLabel)
        assertTrue(unprobed.capabilities.supportedEncodings.isEmpty())
    }

    @Test
    fun `a probed route with no support renders as unavailable not unknown`() {
        val diagnostics = PlaybackDiagnostics(capabilities = capability(supported = false))
        assertEquals("Direct unavailable", diagnostics.directCapabilityLabel)
    }

    @Test
    fun `a probed route with support renders as supported`() {
        val diagnostics = PlaybackDiagnostics(capabilities = capability(supported = true))
        assertEquals("Direct supported", diagnostics.directCapabilityLabel)
    }

    @Test
    fun `capability records the conditions it was asked about`() {
        val diagnostics = PlaybackDiagnostics(capabilities = capability(supported = true))
        assertEquals(96_000, diagnostics.capabilities.probedAtSampleRateHz)
        assertEquals(2, diagnostics.capabilities.probedAtChannelCount)
        assertTrue(diagnostics.capabilities.conditionLabel!!.contains("2 ch"))
    }

    @Test
    fun `an unprobed capability explains itself rather than showing nothing`() {
        val c = DirectPlaybackCapability.unknown("サンプリングレートが不明")
        assertFalse(c.isProbed)
        assertNull(c.probedAtSampleRateHz)
        assertNull(c.probedAtChannelCount)
        assertEquals("サンプリングレートが不明", c.conditionLabel)
    }

    // ── Source label ──────────────────────────────────────────────────────────

    @Test
    fun `source label omits bit depth when unknown rather than guessing`() {
        val diagnostics = PlaybackDiagnostics(source = SourceAudioInfo("FLAC", 44_100, null, 2))
        assertEquals("FLAC • 44.1 kHz", diagnostics.sourceLabel)
    }

    @Test
    fun `source label omits the rate when unknown rather than assuming a default`() {
        val diagnostics = PlaybackDiagnostics(source = SourceAudioInfo("FLAC", null, null, null))
        assertEquals("FLAC", diagnostics.sourceLabel)
    }

    @Test
    fun `source label includes bit depth only when it was read`() {
        assertEquals(
            "FLAC • 96 kHz • 24 bit",
            PlaybackDiagnostics(source = SourceAudioInfo("FLAC", 96_000, 24, 2)).sourceLabel
        )
    }

    @Test
    fun `a 16-bit 96 kHz file is not mislabelled as 24-bit`() {
        assertEquals(
            "FLAC • 96 kHz • 16 bit",
            PlaybackDiagnostics(source = SourceAudioInfo("FLAC", 96_000, 16, 2)).sourceLabel
        )
    }

    @Test
    fun `a 24-bit 44_1 kHz file is not mislabelled as 16-bit`() {
        assertEquals(
            "FLAC • 44.1 kHz • 24 bit",
            PlaybackDiagnostics(source = SourceAudioInfo("FLAC", 44_100, 24, 2)).sourceLabel
        )
    }

    @Test
    fun `empty source yields an empty label rather than a placeholder`() {
        assertEquals("", PlaybackDiagnostics().sourceLabel)
    }

    // ── Rate formatting ───────────────────────────────────────────────────────

    @Test
    fun `rates format without trailing zeros`() {
        assertEquals("44.1 kHz", SourceAudioInfo.formatRate(44_100))
        assertEquals("48 kHz", SourceAudioInfo.formatRate(48_000))
        assertEquals("88.2 kHz", SourceAudioInfo.formatRate(88_200))
        assertEquals("176.4 kHz", SourceAudioInfo.formatRate(176_400))
        assertEquals("192 kHz", SourceAudioInfo.formatRate(192_000))
    }

    @Test
    fun `rate formatting is locale independent`() {
        val original = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertEquals("44.1 kHz", SourceAudioInfo.formatRate(44_100))
            assertEquals("176.4 kHz", SourceAudioInfo.formatRate(176_400))
        } finally {
            java.util.Locale.setDefault(original)
        }
    }

    // ── Empty state ───────────────────────────────────────────────────────────

    @Test
    fun `default diagnostics is empty and shows nothing`() {
        val diagnostics = PlaybackDiagnostics()
        assertTrue(diagnostics.isEmpty)
        assertEquals("", diagnostics.sourceLabel)
        assertNull(diagnostics.routeLabel)
        assertEquals("Direct unknown", diagnostics.directCapabilityLabel)
    }

    // ── Renderer format is not claimed to be decoder PCM output ───────────────

    @Test
    fun `renderer format makes no PCM claim`() {
        // Media3 documents onAudioInputFormatChanged as the format the renderer processes,
        // not a verified post-decode PCM format, so nothing here may be asserted as PCM.
        val renderer =
            RendererFormatInfo(sampleRateHz = 96_000, channelCount = 2, decoderName = "c2.android.flac.decoder")
        assertEquals("96 kHz", renderer.displayRate)
        assertEquals(2, renderer.channelCount)
        assertEquals("c2.android.flac.decoder", renderer.decoderName)
    }

    @Test
    fun `empty renderer format is unknown, not zero`() {
        val r = RendererFormatInfo.UNKNOWN
        assertNull(r.sampleRateHz)
        assertNull(r.channelCount)
        assertNull(r.decoderName)
        assertNull(r.displayRate)
    }
}
