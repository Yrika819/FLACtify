# Spatial Audio V2 HRTF rate support

## Selected HRTF data

Steam Audio is pinned to the official v4.8.1 Android SDK release archive. The
archive SHA-256 is
`4a0aa5ec1176f38f0b0993a37c2259d9e86f27e22d5e24f83ec4c3cb9a1d5449`, and
the v4.8.1 release tag resolves to
`0da18255cca520771f363ee01f100572b39a308e`. The separately reviewed source
commit `480dd64f513cc8a6437e7d5b9eb0d3f1d30c2fac` is ten commits newer than
the release tag and is not used as the pinned binary source.

The built-in CIPIC 124 HRTF table covers 24,000, 44,100, and 48,000 Hz. For
88,200, 96,000, 176,400, and 192,000 Hz, FLACtify packages
`core/data/hrtf/cipic_124.sofa` from that pinned release and asks Steam
Audio's SOFA loader to adapt the HRTF to the actual PCM sample rate. This
resamples the HRTF measurements; it does not resample music PCM. Other sample
rates bypass with `HRTF_RATE_UNAVAILABLE`.

The SOFA file SHA-256 is
`c28ff4a874ac889ec0c5885ca524762a70d56984232ff7aadcd9c15d32e1cfb6`.
Gradle downloads it from the immutable v4.8.1 tag, verifies this digest, and
stages it as `assets/hrtf/cipic_124.sofa`. The package includes
`assets/licenses/CIPIC-124-HRTF-NOTICE.md` and Steam Audio notices. The
[CIPIC database publication](https://www.ece.ucdavis.edu/cipic/wp-content/uploads/sites/12/2015/04/cipic_CIPIC_HRTF_Database.pdf)
describes its 45-subject Release 1.0 subset as public domain. Steam Audio's
[v4.8.1 SOFA loader](https://raw.githubusercontent.com/ValveSoftware/steam-audio/v4.8.1/core/src/core/sofa_hrtf_map.cpp)
requires zero `Data.Delay`; the [v4.8.1 guide](https://raw.githubusercontent.com/ValveSoftware/steam-audio/v4.8.1/unity/doc/guide.rst)
documents its supported SOFA subset and automatic HRTF resampling.

## Measured SOFA level calibration

Pixel 7a signal tests found that, before compensation, the fixed-input SOFA
output peak and RMS at high rates increased approximately with the target
sample rate. The native HRTF setup now applies the linear gain
`48,000 / sampleRateHz` through Steam Audio's documented HRTF volume setting
for the SOFA path. This does not alter the music PCM sample rate.

With that compensation enabled, full-scale log sweep and pink-noise output
levels remained within the test's 15% tolerance relative to 48 kHz. This is an
empirical calibration for the pinned Steam Audio runtime and this specific
CIPIC file, not a general claim about arbitrary SOFA data.

## Rate policy and device signal measurements

| Rate | HRTF source | Full-scale sweep peak | Sweep RMS | Pink-noise peak | Pink-noise RMS |
| --- | --- | ---: | ---: | ---: | ---: |
| 44,100 Hz | Built-in CIPIC | 0.8500 | 0.50258 | 0.59303 | 0.13298 |
| 48,000 Hz | Built-in CIPIC | 0.8500 | 0.50402 | 0.68952 | 0.14579 |
| 88,200 Hz | Pinned CIPIC SOFA | 0.8500 | 0.50693 | 0.62392 | 0.14356 |
| 96,000 Hz | Pinned CIPIC SOFA | 0.8500 | 0.50778 | 0.63317 | 0.14338 |
| 176,400 Hz | Pinned CIPIC SOFA | 0.8500 | 0.51143 | 0.62623 | 0.14135 |
| 192,000 Hz | Pinned CIPIC SOFA | 0.8500 | 0.51075 | 0.67658 | 0.14203 |

The Pixel 7a test records sample peak, 48-tap/four-phase estimated true peak,
dBTP, RMS, crest factor, finite-value status, and rendered length for one-second
impulses, full-scale logarithmic sweeps, and deterministic pink noise (0.125
input RMS). The 2026-09-29 Android 17/API 37 run passed 2/2 tests. Measured
impulse sample peaks and estimated dBTP, plus sweep and pink-noise estimated
true peaks and dBTP, were:

| Rate | Impulse sample peak | Impulse dBTP | Sweep estimated true peak | Sweep dBTP | Pink estimated true peak | Pink dBTP |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 44,100 Hz | 0.38157 | -7.636 | 0.87274 | -1.182 | 0.62009 | -4.151 |
| 48,000 Hz | 0.42419 | -7.464 | 0.87174 | -1.192 | 0.70411 | -3.047 |
| 88,200 Hz | 0.20855 | -12.695 | 0.85567 | -1.354 | 0.62663 | -4.060 |
| 96,000 Hz | 0.21578 | -13.357 | 0.85230 | -1.388 | 0.63401 | -3.958 |
| 176,400 Hz | 0.11733 | -18.633 | 0.87189 | -1.191 | 0.62743 | -4.049 |
| 192,000 Hz | 0.10837 | -19.304 | 0.86970 | -1.213 | 0.67917 | -3.360 |

All estimated synthetic-signal peaks remained below 0 dBTP in this run. The
separate PCM16 full-scale sweep passed the 0.85 sample-peak ceiling test after
native conversion. These are offline estimates of native HRTF output; they do
not measure the AudioTrack/system/Bluetooth/DAC path or establish behavior on
music. The limiter applies a 0.85 sample-peak ceiling, one 128-frame lookahead
block, and an 80 ms release. Its transparency and release remain
listening-unvalidated.

## Current engine and Media3 support status

| PCM rate at the SpatialAudioOutput boundary | Steam Audio HRTF data | Studio behavior |
| --- | --- | --- |
| 24,000 / 44,100 / 48,000 Hz | Steam Audio built-in CIPIC 124 | Stereo PCM16 and Float32 are supported when native engine initialization succeeds. |
| 88,200 / 96,000 / 176,400 / 192,000 Hz | Pinned CIPIC 124 SOFA, adapted by Steam Audio | Stereo PCM16 and Float32 are supported when native engine initialization succeeds. |
| Other rates | None | Bypass with `HRTF_RATE_UNAVAILABLE`. |

Media3's high-resolution source conversion presents PCM24/PCM32 source samples
as Float32 at this boundary when Float output is enabled. In Media3 1.11.1,
Float32 output skips the Sonic and silence-skip processor chain. Studio routes
speed and pitch changes through the platform AudioOutput for Float32, while
PCM16 keeps Media3's processor path. This platform behavior is device-dependent
and may support fewer values; boundary tests verify parameter routing but do
not measure the physical AudioTrack speed behavior. If PCM24/PCM32 reaches the
boundary as an integer encoding, it bypasses with `UNSUPPORTED_PCM_ENCODING`.
Mono/multichannel, offloaded, or tunneled output also bypasses with explicit
reasons. The output provider configures the engine from its actual encoding,
rate, and channel mask; source metadata is not used to infer the DSP format.

On 2026-09-29 the Pixel 7a (Android 17/API 37) connected suite passed 27/27
instrumentation tests with no skips. The Media3 float-boundary class passed
3/3, verifying Float32 playback-parameter routing through AudioOutput while
PCM16 retains Media3 processor playback. The PlaybackService integration test
also passed separately after adding the 192 kHz fixture; it observed active
Studio at the actual Media3 output boundary for repository 44.1/16, 96/24,
and 192/24 stereo FLAC. The 44.1 kHz file ran as two same-format playlist
items, and the test confirmed that both Spatial and Actual AudioTrack
configuration diagnostics remained current on item two.

The latest 10-minute 48 kHz PCM16 synthetic AudioTrack run completed final EOS
with the HRTF tail drained, position 600,000,000 µs, zero underruns, zero
stalls, zero no-progress windows, 53.58% process CPU, 53.50% playback-thread
CPU, and thermal status 0 to 0. Java heap start-to-end delta was -2,947,856
bytes and post-warm-up peak delta was 0 bytes. Native heap start-to-end delta
was +10,172,192 bytes and post-warm-up peak delta was +2,832 bytes. These
figures describe the synthetic 48 kHz sink run; they do not establish
high-rate sustained playback or music quality.

## Performance

The latest Pixel 7a run timed 4,000 native Float32 HRTF blocks after 500
warm-up blocks for 128, 256, and 512-frame candidates at all six supported
rates. Full mean, p95, p99, p99.9, and worst values are recorded in the
[Pixel 7a frame benchmark](spatial-audio-v2-pixel7a-frame-benchmark.md).

The 128-frame p99.9 exceeded one block duration at 192 kHz, while the
512-frame candidate stayed within its block duration in this run. These are
direct engine timings, not full-pipeline deadlines. Production remains at 128
frames because it has the lowest inherent latency and completed the actual
service playback test; 512 has not yet been verified through the service.

A 120-second 192 kHz / 24-bit silent-FLAC PlaybackService run on Pixel 7a,
Android 17, with Liberty 4 Pro as the asserted active Bluetooth route, sampled
120/120 seconds in READY and playing state. It observed zero Media3 AudioSink
underrun events, no buffering samples, 48.78% process CPU, and thermal status
1→1. This verifies the current 128-frame configuration with a silent fixture;
it does not establish music quality or performance for other frame sizes.
Emulator timing is not used for device performance claims.

## Known gaps

- No representative licensed music or listening evaluation of the limiter attack/release. Synthetic true-peak estimates exist at six rates, but they measure native HRTF output rather than the downstream device path.
- App-level 44.1/16, 96/24, and 192/24 stereo FLAC playback passed. A ten-minute 192/24 silent-FLAC service run also passed on Pixel 7a; representative music quality remains unverified.
- The Pixel service capture test verified continuous accepted-frame numbering across two compatible 48 kHz items and HRTF tail output only after final EOS. It does not directly introspect convolution-state identity at the playlist boundary.
- Pixel seek reset and the active Liberty 4 Pro route were verified. Bluetooth detach/recreation and subjective listening with representative music remain unverified.
- Direct integer PCM24/PCM32 at the DSP boundary and multichannel rendering remain unsupported.
