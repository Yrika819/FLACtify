# Spatial Audio V2: Pixel 7a HRTF Frame Benchmark

Measured 2026-09-29 on a Pixel 7a running Android 17.

## Method

The Android instrumentation benchmark warmed each configuration for 500 blocks, then timed 4,000 calls to `NativeSpatialEngine.process` for one Float32 stereo block at a time. It tested frame sizes 128, 256, and 512 at 44.1, 48, 88.2, 96, 176.4, and 192 kHz. The 88.2 kHz and higher configurations used the pinned CIPIC SOFA asset; 44.1 and 48 kHz used Steam Audio's built-in HRTF.

Each timing includes the JNI call and native PCM/frame-adapter/HRTF work for one complete block. It excludes Media3 scheduling, AudioTrack writes, Bluetooth transport, and decoder work. The block deadline is the block's duration at the tested sample rate; the measured worst time can include Android scheduler delays.

## Results

All times are microseconds. Percentiles are nearest-rank over 4,000 measured blocks.

| Rate | Frames | Block duration | Mean | p95 | p99 | p99.9 | Worst |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 44,100 Hz | 128 | 2,902 | 56.739 | 62.134 | 95.785 | 116.455 | 192.139 |
| 44,100 Hz | 256 | 5,805 | 109.036 | 114.014 | 157.552 | 228.068 | 1,730.225 |
| 44,100 Hz | 512 | 11,610 | 174.979 | 198.080 | 228.393 | 299.113 | 1,800.619 |
| 48,000 Hz | 128 | 2,667 | 56.015 | 56.437 | 100.016 | 142.781 | 301.718 |
| 48,000 Hz | 256 | 5,333 | 110.214 | 119.222 | 165.243 | 463.623 | 653.443 |
| 48,000 Hz | 512 | 10,667 | 173.421 | 185.710 | 226.156 | 396.566 | 813.721 |
| 88,200 Hz | 128 | 1,451 | 87.280 | 121.542 | 357.544 | 1,178.548 | 2,283.162 |
| 88,200 Hz | 256 | 2,902 | 214.543 | 414.103 | 2,737.467 | 10,104.655 | 30,175.904 |
| 88,200 Hz | 512 | 5,805 | 246.583 | 289.795 | 442.749 | 1,703.003 | 3,020.508 |
| 96,000 Hz | 128 | 1,333 | 83.397 | 88.501 | 140.951 | 1,353.028 | 1,576.578 |
| 96,000 Hz | 256 | 2,667 | 115.809 | 123.698 | 170.451 | 254.069 | 320.597 |
| 96,000 Hz | 512 | 5,333 | 247.379 | 294.962 | 379.883 | 1,044.841 | 1,627.849 |
| 176,400 Hz | 128 | 726 | 86.113 | 90.657 | 142.008 | 299.113 | 388.550 |
| 176,400 Hz | 256 | 1,451 | 177.932 | 219.523 | 293.864 | 432.617 | 765.178 |
| 176,400 Hz | 512 | 2,902 | 251.273 | 297.607 | 370.280 | 577.840 | 1,125.692 |
| 192,000 Hz | 128 | 667 | 152.876 | 172.282 | 227.661 | 760.498 | 3,224.650 |
| 192,000 Hz | 256 | 1,333 | 192.514 | 234.620 | 260.783 | 583.577 | 2,532.634 |
| 192,000 Hz | 512 | 2,667 | 301.193 | 346.924 | 393.799 | 794.881 | 2,317.505 |

## Decision and limits

Production now uses 512-frame HRTF blocks. The Pixel 7a direct benchmark found 512-frame p99.9 and worst processing times below the block duration at all six target rates. The 128-frame run exceeded its block deadline in several high-rate tail observations; 256 frames had a 30.2 ms worst observation and 10.1 ms p99.9 at 88.2 kHz. The 512-frame choice lowers this observed deadline risk, at the cost of algorithm latency: 11.61 ms at 44.1 kHz and 2.67 ms at 192 kHz.

Before changing the production constant, `PlaybackServiceSpatialFlacDeviceTest` was changed to require 512 frames and run on Pixel 7a. It failed against the old setting with `expected:<512> but was:<128>`. After switching `SpatialPcmEngine.FRAME_SIZE`, the test passed on Pixel 7a / Android 17 with the repository's 44.1, 96, and 192 kHz FLAC fixtures. Its 120-second repeated 192 kHz / 24-bit silence-FLAC phase observed Studio active, 512-frame blocks, Float32 input, 192 kHz PCM_FLOAT AudioTrack output, 120/120 playing samples, 120/120 position-progress samples, and zero Media3 underrun events. The test was invoked with an active Bluetooth route assertion for Liberty 4 Pro. The test reports Media3 underrun events, not `AudioTrack.getUnderrunCount()`.

During that 120-second phase, process CPU was 39.83%; Java heap end delta was +625,376 B and peak delta +8,290,176 B; native heap end delta was +49,232 B and peak delta +77,056 B; thermal status was 0→0→0. These are whole-process observations from one run and do not establish a controlled CPU improvement over the earlier 128-frame run.

The direct block benchmark excludes Media3 scheduling, decoder work, AudioTrack writes, and Bluetooth transport. The 120-second service run used a silent fixture and does not establish sound quality or limiter transparency. A 10-minute service soak with the new 512-frame production setting remains to be run; 512 is a measured service-tested choice, not a claim of globally optimal performance.
