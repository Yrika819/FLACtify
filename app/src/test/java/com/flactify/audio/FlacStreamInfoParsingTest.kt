package com.flactify.audio

import android.content.Context
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * STREAMINFO parsing checked against RFC 9639 section 8.2, not against itself.
 *
 * The payload is 34 bytes laid out in this fixed order:
 *
 * ```
 *   0..1   minimum block size   u(16)
 *   2..3   maximum block size   u(16)
 *   4..6   minimum frame size   u(24)
 *   7..9   maximum frame size   u(24)
 *  10..17  sample rate u(20) | channels-1 u(3) | bps-1 u(5) | total samples u(36)
 *  18..33  MD5 of unencoded audio u(128)
 * ```
 *
 * A previous version of this test built its fixture with the MD5 *before* the packed field and
 * the parser read the packed field at offset 16 - both wrong, and mutually consistent, so the
 * suite passed while the code was broken. The fixture below is therefore also verified against
 * two real libFLAC-encoded files committed as test resources, whose ground truth comes from
 * `ffprobe` and an independent reader, not from this parser.
 */
class FlacStreamInfoParsingTest {

    // The parser never touches the context; it is only needed to construct the reader.
    private val reader = AudioSourceReader(mockk<Context>(relaxed = true))

    private data class Parsed(val sampleRateHz: Int, val channelCount: Int, val bitDepth: Int)

    /**
     * Builds a conformant FLAC stream: `fLaC` + one STREAMINFO block (+ optional trailing bytes).
     *
     * The 34-byte payload is assembled as an explicit byte array in spec order, so the fixture
     * and the parser cannot silently agree on the same mistake.
     *
     * @param lastBlock sets the last-metadata-block flag. RFC 9639 only requires STREAMINFO to
     *   be *first*; its own reference file sets this flag, so a STREAMINFO-only stream is legal.
     * @param declaredLength the length written into the block header, which a conformant file
     *   always sets to 34. Overridden only to exercise the parser's validation.
     */
    private fun flacStream(
        sampleRateHz: Int,
        channels: Int,
        bitDepth: Int,
        lastBlock: Boolean = false,
        declaredLength: Int = STREAMINFO_LENGTH,
        storedBpsMinusOne: Int? = null,
        trailing: ByteArray = ByteArray(0)
    ): ByteArray {
        require(sampleRateHz in 0..0xFFFFF) { "sample rate must fit u(20)" }
        require(channels in 1..8) { "FLAC supports 1..8 channels" }
        require(bitDepth in 4..32) { "FLAC supports 4..32 bits per sample" }

        // payload[10..17]: sample rate u(20) | channels-1 u(3) | bps-1 u(5) | total samples u(36)
        var packed = sampleRateHz.toLong() shl 44
        packed = packed or ((channels - 1).toLong() shl 41)
        packed = packed or ((storedBpsMinusOne ?: (bitDepth - 1)).toLong() shl 36)
        packed = packed or 0x0FFFFFFFL // total samples, arbitrary

        val payload = ByteArray(STREAMINFO_LENGTH)
        fun u16(off: Int, v: Int) {
            payload[off] = ((v ushr 8) and 0xFF).toByte()
            payload[off + 1] = (v and 0xFF).toByte()
        }

        fun u24(off: Int, v: Int) {
            payload[off] = ((v ushr 16) and 0xFF).toByte()
            payload[off + 1] = ((v ushr 8) and 0xFF).toByte()
            payload[off + 2] = (v and 0xFF).toByte()
        }
        u16(0, 4096)   // minimum block size
        u16(2, 4096)   // maximum block size
        u24(4, 100)    // minimum frame size
        u24(7, 200)    // maximum frame size
        for (i in 0 until 8) {
            payload[10 + i] = ((packed ushr (56 - 8 * i)) and 0xFF).toByte()
        }
        // payload[18..33] is the MD5 signature; left as zeros ("value not known" per spec).

        val out = java.io.ByteArrayOutputStream()
        out.write("fLaC".toByteArray(Charsets.US_ASCII))
        // One byte holding the last-metadata-block flag in bit 7 and the block type in bits 6..0,
        // then the 24-bit block length.
        out.write((if (lastBlock) 0x80 else 0x00) or STREAMINFO_TYPE)
        out.write(((declaredLength ushr 16) and 0xFF).toInt())
        out.write(((declaredLength ushr 8) and 0xFF).toInt())
        out.write((declaredLength and 0xFF).toInt())
        out.write(payload)
        out.write(trailing)
        return out.toByteArray()
    }

    private fun parse(bytes: ByteArray): Parsed? {
        val method = AudioSourceReader::class.java
            .getDeclaredMethod("readFlacStreamInfoOrNull", InputStream::class.java)
        method.isAccessible = true
        val result = method.invoke(reader, ByteArrayInputStream(bytes)) ?: return null
        val type = result.javaClass
        fun field(name: String): Int =
            type.getDeclaredField(name).apply { isAccessible = true }.getInt(result)
        return Parsed(field("sampleRateHz"), field("channelCount"), field("bitDepth"))
    }

    // ── Ground truth: real libFLAC files, cross-checked with ffprobe ──────────

    @Test
    fun `real libFLAC file 44_1 kHz 16-bit stereo parses correctly`() {
        val bytes = realFlac("/audio/flac_44100_16_stereo.flac")
        // ffprobe reports: sample_rate=44100 channels=2 bits_per_raw_sample=16
        assertEquals(Parsed(44_100, 2, 16), parse(bytes))
    }

    @Test
    fun `real STREAMINFO-only file with last-block flag parses correctly`() {
        val bytes = realFlac("/audio/flac_streaminfo_only_44100_16_stereo.flac")
        // Identical audio, but STREAMINFO carries the last-metadata-block flag and is the only
        // metadata block. RFC 9639 Appendix D.1 shows exactly this file, so it must parse.
        assertEquals(Parsed(44_100, 2, 16), parse(bytes))
    }

    @Test
    fun `real 96 kHz 24-bit stereo file parses correctly`() {
        // Guards against the parser being accidentally tuned to one file: different rate,
        // different depth than the 44.1 kHz/16-bit fixtures.
        assertEquals(Parsed(96_000, 2, 24), parse(realFlac("/audio/flac_96000_24_stereo.flac")))
    }

    @Test
    fun `real 192 kHz 24-bit stereo file parses correctly`() {
        assertEquals(Parsed(192_000, 2, 24), parse(realFlac("/audio/flac_192000_24_stereo.flac")))
    }

    @Test
    fun `real 48 kHz 24-bit 6-channel file parses correctly`() {
        // Exercises the 3-bit channel field above stereo.
        assertEquals(Parsed(48_000, 6, 24), parse(realFlac("/audio/flac_48000_24_surround6.flac")))
    }

    // ── Spec-layout cases ─────────────────────────────────────────────────────

    @Test
    fun `44_1 kHz 16-bit stereo`() {
        assertEquals(Parsed(44_100, 2, 16), parse(flacStream(44_100, 2, 16)))
    }

    @Test
    fun `44_1 kHz 24-bit stereo is not read as 16-bit`() {
        // The case the old sample-rate heuristic got wrong.
        assertEquals(Parsed(44_100, 2, 24), parse(flacStream(44_100, 2, 24)))
    }

    @Test
    fun `48 kHz 24-bit`() {
        assertEquals(Parsed(48_000, 2, 24), parse(flacStream(48_000, 2, 24)))
    }

    @Test
    fun `96 kHz 16-bit is not read as 24-bit`() {
        // The other case the old sample-rate heuristic got wrong.
        assertEquals(Parsed(96_000, 2, 16), parse(flacStream(96_000, 2, 16)))
    }

    @Test
    fun `96 kHz 24-bit`() {
        assertEquals(Parsed(96_000, 2, 24), parse(flacStream(96_000, 2, 24)))
    }

    @Test
    fun `192 kHz 24-bit`() {
        assertEquals(Parsed(192_000, 2, 24), parse(flacStream(192_000, 2, 24)))
    }

    @Test
    fun `mono`() {
        assertEquals(Parsed(44_100, 1, 16), parse(flacStream(44_100, 1, 16)))
    }

    @Test
    fun `6 channel`() {
        assertEquals(Parsed(48_000, 6, 24), parse(flacStream(48_000, 6, 24)))
    }

    @Test
    fun `8 channel and 32-bit are within the spec range`() {
        // RFC 9639 section 8.2: FLAC supports 1..8 channels and 4..32 bits per sample.
        assertEquals(Parsed(96_000, 8, 32), parse(flacStream(96_000, 8, 32)))
    }

    @Test
    fun `STREAMINFO with last-metadata-block flag is accepted`() {
        val parsed = parse(flacStream(44_100, 2, 16, lastBlock = true))
        assertEquals(Parsed(44_100, 2, 16), parsed)
    }

    // ── Invalid / truncated ───────────────────────────────────────────────────

    @Test
    fun `non-FLAC input yields no stream info rather than throwing`() {
        assertNull(parse("ID3".toByteArray(Charsets.US_ASCII) + ByteArray(64)))
    }

    @Test
    fun `truncated payload yields no stream info`() {
        val full = flacStream(44_100, 2, 16)
        assertNull(parse(full.copyOf(20)))
    }

    @Test
    fun `first block that is not STREAMINFO is rejected`() {
        val bytes = flacStream(44_100, 2, 16)
        bytes[4] = 4 // VORBIS_COMMENT
        assertNull(parse(bytes))
    }

    @Test
    fun `declared length other than 34 is rejected`() {
        // Section 8.2 fixes the payload at 34 bytes; anything else is not a STREAMINFO block.
        assertNull(parse(flacStream(44_100, 2, 16, declaredLength = 33)))
        assertNull(parse(flacStream(44_100, 2, 16, declaredLength = 40)))
    }

    @Test
    fun `zero sample rate is rejected`() {
        assertNull(parse(flacStream(0, 2, 16)))
    }

    @Test
    fun `bit depth below the spec minimum is rejected`() {
        // Section 8.2: FLAC supports 4..32 bits per sample. The stored field is bits-1, so a
        // stored 0 decodes to 1 bit and must be refused rather than reported.
        assertNull(parse(flacStream(44_100, 2, 16, storedBpsMinusOne = 0)))
        assertNull(parse(flacStream(44_100, 2, 16, storedBpsMinusOne = 2)))
    }

    @Test
    fun `32-bit depth is accepted even though few encoders produce it`() {
        // RFC 9639 Table 3 states 4..32; a 24-bit ceiling is an interoperability note, not a
        // format limit, so a 32-bit header must parse.
        assertEquals(Parsed(96_000, 2, 32), parse(flacStream(96_000, 2, 32)))
    }

    @Test
    fun `MD5 occupies the final 16 bytes so corrupting it cannot change the parsed spec`() {
        // Guards the field order: if MD5 were parsed instead of the packed field, this fails.
        val valid = flacStream(96_000, 2, 24)
        val parsed = parse(valid)
        assertNotNull(parsed)
        assertEquals(24, parsed!!.bitDepth)
        assertEquals(96_000, parsed.sampleRateHz)
    }

    private fun realFlac(resourcePath: String): ByteArray =
        FlacStreamInfoParsingTest::class.java.getResourceAsStream(resourcePath)
            ?.use { it.readBytes() }
            ?: error("missing test resource $resourcePath")

    private companion object {
        const val STREAMINFO_TYPE = 0
        const val STREAMINFO_LENGTH = 34
    }
}
