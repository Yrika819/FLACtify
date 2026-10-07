# Spatial Audio V2 foundation

Date: 2026-09-29

Branch: `feat/spatial-audio-v2-foundation`

## Integration

PlaybackService owns the authoritative mode and ExoPlayer. Settings sends Off/Studio through PlaybackController and MediaController. Studio mode is persisted by the service and survives Activity recreation.

Production Studio uses Media3 1.11.1's post-conversion AudioOutput boundary:

- Spatial Off: the existing Media3 selection and PCM path → identity retry wrapper → platform AudioOutput. The wrapper adds no PCM conversion or processing.
- Studio: decoder → Media3 trim/channel mapping and PCM conversion → SpatialAudioOutputProvider using the actual OutputConfig → SpatialAudioOutput → NativeSpatialEngine → AudioTrackAudioOutput → AudioTrack.

Studio enables Media3 Float32 output for high-resolution source PCM. Ordinary PCM16 remains PCM16. Media3 1.11.1 skips its Sonic and silence-skip processor chain for Float32 output. Studio routes playback speed and pitch changes through the platform AudioOutput only for Float32; PCM16 retains Media3's processor path. The platform route is device-dependent and may support fewer values. A boundary test verifies parameter routing, but actual AudioTrack speed behavior has not been measured. The provider only enables HRTF for stereo PCM16 or Float32 at a supported rate after native engine creation succeeds. Media3-converted PCM24/PCM32 sources therefore reach the HRTF as Float32; if an integer PCM24/PCM32 encoding reaches the output boundary directly, it bypasses with `UNSUPPORTED_PCM_ENCODING`. Unsupported channel masks, rates, tunneling configurations, and native initialization failures publish explicit bypass reasons. Studio rejects compressed format support so passthrough and offload cannot skip decoded PCM processing.

The HRTF engine is configured from the actual Media3 OutputConfig, not source-file metadata. It accepts arbitrary input chunk sizes through its bounded fixed-frame FIFO and sends 128-frame blocks to Steam Audio. Music PCM is not resampled. The built-in Steam Audio HRTF is used through 48 kHz; pinned CIPIC SOFA data is loaded only for higher supported rates and adapted to the stream rate.

SpatialAudioOutput uses a preallocated direct scratch buffer. If downstream writes are partial, it retries the same processed buffer with the same timestamp and access-unit count before accepting more input. The input position advances only for frames accepted into the spatial FIFO. Output timestamps are derived from the first accepted PCM timestamp and emitted-frame count. Pause and play are forwarded without resetting HRTF state. Media3's handleDiscontinuity signal marks a possible discontinuity: continuous next-buffer timestamps preserve HRTF state, while an unknown timestamp or real timestamp jump clears pending PCM and resets the engine. DefaultAudioSink flush immediately clears pending output and resets the engine.

SpatialAudioSink distinguishes final renderer EOS from internal Media3 drains. At final EOS, AudioOutput.stop is deferred while the output wrapper drains the engine tail; an ordinary stop does not insert a tail. Compatible AudioOutput reuse retains one engine across the boundary. Media3 1.11.1 source review confirms that `BaseRenderer.replaceStream()` reports a stream replacement through `onStreamChanged`; `MediaCodecRenderer` applies queued stream changes after processing the prior output and calls `onProcessedStreamChange`. `MediaCodecAudioRenderer` reaches `AudioSink.playToEndOfStream()` through its `renderToEndOfStream()` hook after renderer EOS with no codec drain action. ExoPlayer marks the old stream final when its renderer must be disabled or reconfigured, so this static evidence applies specifically to compatible, same-renderer-configuration transitions ([BaseRenderer](https://github.com/androidx/media/blob/1.11.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/BaseRenderer.java#L158-L175), [MediaCodecRenderer stream changes](https://github.com/androidx/media/blob/1.11.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/mediacodec/MediaCodecRenderer.java#L705-L758), [output EOS](https://github.com/androidx/media/blob/1.11.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/mediacodec/MediaCodecRenderer.java#L2302-L2321), [audio renderer EOS](https://github.com/androidx/media/blob/1.11.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/MediaCodecAudioRenderer.java#L947-L963)). A Pixel 7a PlaybackService test plays a same-format two-item FLAC playlist and reaches item two with Spatial diagnostics active; unit tests verify that a potential stream discontinuity with continuous timestamps preserves HRTF state. The service test still does not capture PCM or tail counts across the real playlist boundary. The 10-minute Pixel run reached final EOS with the tail complete.

The native engine still uses a 128-frame lookahead limiter with a 0.85 sample-peak ceiling and 80 ms release. It adds 128 frames of algorithm latency. Prior synthetic measurements found a 2.71 sample peak before protection for a full-scale sweep at 48 kHz. True-peak measurement, representative music, and listening validation remain outstanding.

The Media3 AudioProcessor implementation remains only as a legacy processor-path test harness. FlactifyRenderersFactory no longer installs it in production Studio playback.

Mode changes replace the service-owned player and reconnect it to the existing MediaSession. Queue, current item and position, play state, repeat/shuffle, volume, playback parameters, and track selection are restored. This remains a controlled restart; seamless audio continuity is not claimed.

## Steam Audio dependency

The app links the official Steam Audio Android SDK v4.8.1 release archive, SHA-256 `4a0aa5ec1176f38f0b0993a37c2259d9e86f27e22d5e24f83ec4c3cb9a1d5449`. The release tag resolves to commit `0da18255cca520771f363ee01f100572b39a308e`. The separately reviewed source commit `480dd64f513cc8a6437e7d5b9eb0d3f1d30c2fac` is ten commits newer than the release tag and is not the source of the pinned binary archive. The app links upstream `libphonon.so` for armv7, arm64, x86, and x86_64; its JNI adapter is built from repository source with CMake. Steam Audio Apache and third-party notices are packaged with the app.

Studio uses a custom two-speaker layout: left `(-0.5, 0, -0.8660254)`, right `(0.5, 0, -0.8660254)`. It uses CIPIC subject 124 data shipped in the Steam Audio v4.8.1 source release. The SOFA license notice and asset digest are recorded in [HRTF rate support](spatial-audio-v2-hrtf-rate-support.md).

## Diagnostics

The separate Spatial DSP section reports requested/effective mode, engine/version, the actual AudioOutput PCM encoding/rate/channel mask, Float32 internal processing, block size, HRTF profile, speaker layout, algorithm latency, bypass reason, and error. On a media-item transition, the service clears per-item renderer facts while retaining the observed AudioTrack and Spatial output configurations. Media3 may reuse the same AudioOutput and AudioTrack; their public lifecycle callbacks update these values when they are released or reconfigured. AudioTrack output and route diagnostics remain separate. Source metadata, renderer input, output route, Direct capability, and actual device output are not treated as evidence for each other. Processing percentiles and true peak are not yet reported.

## Verification evidence

Current implementation checks (2026-09-29):

- `env -u ANDROID_PREFS_ROOT ANDROID_HOME=$ANDROID_SDK_ROOT ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`: passed.
- `env -u ANDROID_PREFS_ROOT ANDROID_HOME=$ANDROID_SDK_ROOT ./gradlew :app:connectedDebugAndroidTest`: latest run passed 28/28 tests on Pixel 7a Android 17/API 37, with no skips. It includes native HRTF/headroom, AudioOutput lifecycle, FLAC playback, and the 10-minute AudioTrack run.
- `env -u ANDROID_PREFS_ROOT ANDROID_HOME=$ANDROID_SDK_ROOT ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.flactify.audio.spatial.PlaybackServiceSpatialFlacDeviceTest`: passed 1/1 after adding the 192 kHz fixture. PlaybackService activated Studio at the observed output boundary for 44.1/16, 96/24, and 192/24 FLAC; the 44.1 kHz fixture also ran as a same-format two-item playlist.
- `env -u ANDROID_PREFS_ROOT ANDROID_HOME=$ANDROID_SDK_ROOT ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.flactify.audio.spatial.Media3FloatOutputBoundaryDeviceTest`: passed 3/3 on Pixel 7a. It verifies that Float32 routes non-default playback parameters through AudioOutput while PCM16 retains Media3 processor playback.
- `env -u ANDROID_PREFS_ROOT ANDROID_HOME=$ANDROID_SDK_ROOT ./gradlew :app:testDebugUnitTest --tests com.flactify.audio.spatial.SpatialAudioOutputPcmTest`: passed.
- A 10-minute synthetic 48 kHz PCM16 run reported 600,000,000 µs position, active Studio HRTF, completed final tail, an initialized AudioTrack, zero underruns/stalls/no-progress windows, and thermal status 0 to 0. Its CPU and memory measurements are below; the separate 192 kHz run is recorded later.

### Previous implementation evidence (before output-boundary migration)

The following measurements were collected on commit 5c75dea before Studio moved from the AudioProcessor path to SpatialAudioOutput. They remain evidence about the pinned native engine and the previous PCM16 sink path, but do not verify the current production integration.

On a Pixel 7a running Android 17/API 37:

- The focused native, processor, and Media3 EOS suite passed 17/17 tests after the limiter change, covering PCM conversion, non-finite samples, arbitrary chunking, reset, backpressure, gapless-compatible period flushes, and final EOS handling.
- The synthetic headroom suite passed 2/2 tests. It rendered impulse, full-scale logarithmic sweep, and deterministic pink noise through the native engine at 44.1, 48, 88.2, 96, 176.4, and 192 kHz. A separate PCM16 full-scale sweep test confirmed output remains under the sample-peak ceiling after conversion.
- A full-scale sweep remained at a measured sample peak of 0.8500001 or below at all six tested rates. At 48 kHz it measured RMS 0.53185 and 4.07 dB crest factor after limiting. Pink noise at 0.125 input RMS measured 0.6895 peak / 0.14579 RMS at 48 kHz. The limiter changed level only when the HRTF output exceeded the sample-peak ceiling.
- Before the SOFA rate scalar was added, fixed-input sweep and pink-noise output levels rose approximately in proportion to the high target sample rate. Multiplying the SOFA HRTF volume by `48,000 / streamRate` brought measured peak and RMS within the 15% rate-tolerance assertion on the Pixel 7a. This is an empirical compensation for the pinned runtime path; it is not a claim that every SOFA HRTF behaves the same way.
- The final connected Pixel 7a suite passed 26/26, including a 10-minute synthetic 48 kHz PCM16 Studio run through Media3 `DefaultAudioSink` and a real platform `AudioTrack`. It asserted active HRTF, matching 48 kHz processor/track rates, an initialized track, zero underruns, zero no-progress windows after warm-up, no more than one stalled window, at least 9 minutes of reported position, and at least 28.8 million accepted frames.
- After one isolated 10-minute run timed out during the final 30-second EOS drain (without a captured state snapshot), two later standalone 10-minute runs and the final full-suite run completed. Their EOS logs reported `sinkEnded=true`, `sinkPending=false`, `tailDrained=true`, and position `600,010,666` µs. The earlier timeout did not recur, but its cause remains uncharacterized.
- The latest 10-minute full-suite run reported 32.90% process CPU and 32.84% playback-thread CPU, zero underruns/stalls/no-progress windows, and thermal status `0 -> 0` (NONE -> NONE). Successful runs varied from 15.68% to 37.22% CPU, so these measurements are observational rather than a stable benchmark.
- In that latest run, Java heap after the first sample past 5 seconds had a peak delta of 0 bytes and an end delta of -1,025,536 bytes. Native heap had a post-warm-up peak delta of 4,240 bytes and end delta of -17,504 bytes, while the initial-to-end delta was +10,250,144 bytes. The large initial change occurred before the warm-up baseline and has not been attributed; the post-warm-up results show no continuing growth in this run.
- The post-limiter Pixel 7a benchmark processed 5,000 blocks per frame size after 200 warm-up blocks, across 128/256/512-frame candidates at all six rates. At 128 frames, the measurements were:

  | Rate | Mean µs | p95 µs | p99 µs | p99.9 µs | Worst µs | Block budget µs |
  | --- | ---: | ---: | ---: | ---: | ---: | ---: |
  | 44.1 kHz | 54.975 | 59.285 | 96.517 | 329.468 | 424.194 | 2902.494 |
  | 48 kHz | 53.241 | 54.688 | 97.046 | 109.741 | 189.535 | 2666.666 |
  | 88.2 kHz | 73.756 | 76.742 | 118.653 | 157.634 | 2459.960 | 1451.247 |
  | 96 kHz | 76.683 | 81.788 | 126.099 | 167.318 | 1186.157 | 1333.333 |
  | 176.4 kHz | 75.632 | 79.264 | 121.826 | 134.440 | 168.131 | 725.623 |
  | 192 kHz | 122.640 | 130.208 | 171.875 | 192.139 | 470.541 | 666.666 |

  The 128-frame choice has the lowest mean processing time and latency among the tested candidates. Its p99.9 stayed within every block budget; one 88.2 kHz worst block exceeded its 1.45 ms budget. The separate 192 kHz 10-minute synthetic AudioTrack run is recorded above; neither synthetic run replaces sustained ExoPlayer FLAC verification.

These are synthetic signal and integration measurements. They do not demonstrate music quality, Bluetooth codec, headphone DSP state, or complete ExoPlayer route behavior.

### 10-minute Pixel 7a 48 kHz AudioTrack run

The latest 2026-09-29 full connected suite ran the current Media3 OutputProvider path at 48 kHz with synthetic PCM16 and Steam Audio active on a Pixel 7a (Android 17/API 37). At final EOS it reported `sinkEnded=true`, no pending output, the HRTF tail complete, and position `600,000,000` µs. Before EOS, the AudioTrack was PLAYING and its playback head reached `28,776,000` frames (about 599.5 seconds). AudioTrack underruns, stalled windows, Spatial no-progress windows, and AudioTrack playback-head no-progress windows were zero. The test collected 600 heap samples through 600,000 ms. Thermal status was `0 -> 0`.

| Measurement | Result |
| --- | ---: |
| Process CPU | 47.68% |
| Playback-thread CPU | 47.58% |
| Java heap start-to-end | -3,619,968 bytes |
| Java heap peak above start | +139,072 bytes |
| Java heap post-warm-up end delta | -3,759,040 bytes |
| Java heap post-warm-up peak delta | 0 bytes |
| Native heap start-to-end | +10,177,552 bytes |
| Native heap peak above start | +10,267,600 bytes |
| Native heap post-warm-up end delta | -79,696 bytes |
| Native heap post-warm-up peak delta | +10,352 bytes |
| Thermal status | 0 -> 0 |

The approximately 10.3 MB native-heap peak above the starting value occurred before the warm-up baseline and has not been attributed. This single run showed only about 10 KB of post-warm-up peak increase. It remains a 48 kHz synthetic sink result; sustained ExoPlayer FLAC playback has not been measured. App-level FLAC playback at 44.1, 96, and 192 kHz has been tested separately.

## 10-minute Pixel 7a 192 kHz AudioTrack run

Command: `env -u ANDROID_PREFS_ROOT ANDROID_HOME=$ANDROID_SDK_ROOT ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.flactify.audio.spatial.SpatialAudioSustainedPlaybackDeviceTest -Pandroid.testInstrumentationRunnerArguments.spatialSampleRateHz=192000 -Pandroid.testInstrumentationRunnerArguments.spatialPlaybackDurationMs=600000` — passed 1/1 on Pixel 7a, Android 17/API 37.

This used synthetic PCM16 through Media3 `DefaultAudioSink`, Studio HRTF, and a platform `AudioTrack` configured at 192 kHz. The test measured the platform playback head before final EOS, then confirmed final tail drain. It is not a sustained ExoPlayer FLAC playback run.

- Studio stayed active with Steam Audio 4.8.1, CIPIC subject 124 SOFA, Float32 internal processing, and 128-frame blocks.
- Final spatial position was 600,000,000 µs; EOS completed with no pending output and the HRTF tail complete.
- The AudioTrack playback head reached 115,104,000 frames (about 599.5 seconds of 192 kHz playback) before EOS. It was in PLAYING state at that measurement.
- Underruns, AudioTrack playback-head no-progress windows, Spatial position no-progress windows, and stalled windows were all zero.
- The test recorded 601 one-second heap samples; its last sample was at 601,000 ms.

| Measurement | Result |
| --- | ---: |
| Process CPU | 58.80% |
| Playback-thread CPU | 58.73% |
| Java heap start-to-end | +2,379,776 bytes |
| Java heap peak above start | +10,964,032 bytes |
| Java heap post-warm-up end delta | -8,551,488 bytes |
| Java heap post-warm-up peak delta | +32,768 bytes |
| Native heap start-to-end | +49,213,728 bytes |
| Native heap peak above start | +49,222,800 bytes |
| Native heap post-warm-up end delta | +16,448 bytes |
| Native heap post-warm-up peak delta | +17,072 bytes |
| Thermal status | 0 -> 0 |

The approximately 49.2 MB native-heap increase was present in the startup peak, before the five-second warm-up baseline. This run showed only about 17 KB of post-warm-up native-heap peak increase, so it did not show continuing native-heap growth. The startup allocation's exact owner has not been attributed. This is one device run and does not establish performance on other devices or prove sustained playback of real FLAC files through ExoPlayer.

## Pixel 7a native block-time benchmark

Command: `env -u ANDROID_PREFS_ROOT ANDROID_HOME=$ANDROID_SDK_ROOT ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.flactify.audio.spatial.NativeSpatialEnginePerformanceDeviceTest` — passed 1/1 on Pixel 7a, Android 17/API 37. Each candidate used 200 warm-up and 5,000 measured direct `NativeSpatialEngine.process()` calls with Float32 PCM. These are native-engine call timings; they exclude the Media3 FIFO, AudioOutput writes, and device scheduling.

| Rate | Frames | Mean µs | p95 µs | p99 µs | p99.9 µs | Worst µs | Block budget µs |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 44.1 kHz | 128 | 55.180 | 57.658 | 92.164 | 111.451 | 129.313 | 2902.494 |
| 44.1 kHz | 256 | 104.547 | 110.474 | 144.857 | 180.216 | 221.029 | 5804.988 |
| 44.1 kHz | 512 | 166.553 | 174.235 | 215.617 | 292.399 | 587.524 | 11609.977 |
| 48 kHz | 128 | 53.119 | 54.688 | 83.984 | 101.726 | 144.124 | 2666.666 |
| 48 kHz | 256 | 104.434 | 110.881 | 148.275 | 179.403 | 492.187 | 5333.333 |
| 48 kHz | 512 | 166.171 | 174.967 | 205.078 | 213.827 | 581.543 | 10666.666 |
| 88.2 kHz | 128 | 72.960 | 76.213 | 107.178 | 120.239 | 147.665 | 1451.247 |
| 88.2 kHz | 256 | 104.144 | 111.044 | 142.293 | 157.552 | 208.659 | 2902.494 |
| 88.2 kHz | 512 | 215.918 | 242.025 | 256.225 | 521.078 | 8270.793 | 5804.988 |
| 96 kHz | 128 | 73.208 | 78.450 | 114.136 | 159.871 | 327.108 | 1333.333 |
| 96 kHz | 256 | 105.013 | 110.595 | 142.660 | 188.924 | 3021.566 | 2666.666 |
| 96 kHz | 512 | 213.344 | 240.193 | 253.947 | 294.840 | 455.769 | 5333.333 |
| 176.4 kHz | 128 | 72.833 | 75.846 | 108.642 | 121.989 | 230.591 | 725.623 |
| 176.4 kHz | 256 | 151.337 | 157.471 | 191.610 | 253.988 | 419.231 | 1451.247 |
| 176.4 kHz | 512 | 212.373 | 239.217 | 253.418 | 260.702 | 264.364 | 2902.494 |
| 192 kHz | 128 | 117.973 | 123.739 | 158.325 | 167.888 | 175.171 | 666.666 |
| 192 kHz | 256 | 150.013 | 156.616 | 191.976 | 200.806 | 210.612 | 1333.333 |
| 192 kHz | 512 | 212.447 | 245.158 | 264.689 | 303.019 | 390.340 | 2666.666 |

In this single run, 128 frames had the lowest mean among candidates at every rate; its p99.9 and worst block stayed below the block budget at all six rates. The 512-frame 88.2 kHz worst block (8.27 ms vs 5.80 ms) and 256-frame 96 kHz worst block (3.02 ms vs 2.67 ms) exceeded their budgets. This supports retaining the current 128-frame value provisionally; it does not prove end-to-end high-rate AudioTrack performance or establish a long-term performance-optimal value.

## Limitations

- Ten-minute synthetic PCM16 runs have passed on the Pixel 7a at 48 kHz and 192 kHz through Media3 DefaultAudioSink and a real AudioTrack. PlaybackService sustained playback is now verified on Pixel 7a using the generated silent 192 kHz FLAC fixture: a 608.7-second repeat run passed with Studio active and zero Media3 AudioSink underrun events (this is not a raw AudioTrack underrun count). This does not establish music quality. The checked-in diagnostics fixtures are only 20 ms at 96/192 kHz and 30 ms at 44.1 kHz; looping the 20 ms 192 kHz file produced a Media3 `StuckPlayerException` after 10 seconds without position progress, so that experiment is not representative sustained playback evidence.
- Added a generated 15-second 192 kHz / 24-bit stereo digital-silence FLAC fixture for service-level playback. It is stored as Base64 text and decoded to app cache by the device test. FFmpeg 9.0.1 emitted zero STREAMINFO total samples to its non-seekable stdout; the fixture header records the known 2,880,000 samples (15,000 ms). Its MD5 field remains unset, which FLAC defines as unknown ([RFC 9639 §8.2](https://www.rfc-editor.org/rfc/rfc9639.html#section-8.2)). I read the persisted fixture through Android Studio and decoded it with host FFmpeg 9.0.1: it emitted exactly 17,280,000 packed s24le bytes, matching 15 seconds of 192 kHz stereo PCM. A 60-second Pixel smoke against the initial unknown-duration stream showed 60/60 playing, Spatial-active, output-active, and progressing samples with one repeat. The corrected finite-duration fixture now has a compile-checked test that requires 15,000 ms duration and multiple repeats; its Pixel 7a / Android 17 run passed 1/1 on 2026-09-29; the 30-second repeat phase observed 30/30 playing samples with Studio and output diagnostics active, two position resets, and one repeat callback. This does not capture sample-level PCM continuity.
- No representative licensed music track has been measured through the HRTF path.
- Synthetic native HRTF outputs have offline 48-tap / four-phase estimated true-peak measurements documented for six rates; all tested signals were below 0 dBTP. The 0.85 sample-peak ceiling does not guarantee downstream true-peak safety or music behavior.
- The limiter's release is provisional and has not been assessed by listening.
- No Liberty 4 Pro listening comparison, Bluetooth route recreation, mode-change listening check, or user-level click/pop assessment has been run.
- The Pixel 7a block-time table above uses direct native engine calls, not an emulator or end-to-end AudioTrack writes. No 128-frame call exceeded its budget in this run, but the 88.2 kHz 512-frame and 96 kHz 256-frame candidates each had one over-budget worst block. This does not establish end-to-end high-rate underrun behavior.
- The same-format playlist test confirms active Spatial diagnostics on its second item, but does not capture a sample-level real-track boundary or prove the convolution state is unchanged.
- Multichannel rendering, direct integer PCM24/PCM32 at the DSP boundary, SOFA user-file import, and custom user HRTF selection remain out of scope.

## Next verification step

Reconnect the Pixel 7a and run the finite-duration 192 kHz FLAC PlaybackService test for ten minutes. Capture repeat count, playback progress, AudioTrack underruns where publicly observable, processing latency, memory, and thermal behavior. Then perform the Liberty 4 Pro comparison with its vendor spatial effect disabled. Keep Bluetooth codec and complete system output claims separate from the measured app pipeline.
