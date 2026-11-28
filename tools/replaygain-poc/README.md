# ReplayGain / R128 diagnostic PoC

This directory is an isolated host-side proof of concept. It is not included in the Android
source set, does not change the playback path, and does not open or write an audio file.

## Run

```sh
python3 -m unittest discover -s tools/replaygain-poc -p 'test_*.py'
tools/replaygain-poc/run_synthetic_ebur128.sh
```

The C probe generates ten seconds of a 1 kHz, 0.5-amplitude stereo sine in memory and sends it
to the host's `libebur128`. It reports integrated LUFS, sample peak, and true peak. A host without
the library exits with status 2; it is not a product build requirement.

## Metadata rules captured here

ReplayGain 1.0 tags use dB text and a linear peak ratio:

* `REPLAYGAIN_TRACK_GAIN` / `REPLAYGAIN_ALBUM_GAIN` are independent choices; album gain must not
  silently replace track gain.
* `REPLAYGAIN_TRACK_PEAK` / `REPLAYGAIN_ALBUM_PEAK` are ratios, not dBFS. A value above 1.0 is
  valid evidence that the encoded signal already exceeds sample full scale.
* An optional `REPLAYGAIN_REFERENCE_LOUDNESS` is retained as metadata, but this PoC does not infer
  a gain when a track-gain tag is missing.

Opus `R128_TRACK_GAIN` is a different convention from RFC 7845: a signed 16-bit Q7.8 integer in
1/256 dB steps relative to the R128 target of -23 LUFS. It is intentionally parsed by a separate
function and rejects ReplayGain-style decimals/suffixes. It must not be treated as a
`REPLAYGAIN_TRACK_GAIN` string.

## Clipping guard

`clipping_safe_gain_db` uses measured true peak when available, otherwise a finite positive sample
peak, and clamps only upward gain to `-20*log10(peak)`. Existing attenuation remains unchanged.
Without a usable peak it returns the requested gain but callers must expose that clipping safety
is unknown. A sample-peak-only guard cannot prevent intersample clipping; this is why the PoC
reports true peak separately.

## Product recommendation and limits

The current app has no gain state or DSP stage; its diagnostics intentionally distinguish source
metadata, renderer format, route capability, and measured output. Do not apply this utility to
`PlaybackController` or `SpatialAudioProcessor` without an Owner-approved playback design,
per-item transition/reset semantics, user controls, and device validation. A future integration
could parse tags into a diagnostic-only model first, while leaving playback unchanged.

The host probe uses the installed libebur128 ABI only for a synthetic measurement. libebur128 is
not added to Gradle, Android JNI, or the APK. If considered for product use, obtain the exact
upstream version and license notice, add Android builds for all supported ABIs, and re-run the
runtime-license/relink review. This PoC does not claim Android performance or output-path
equivalence.

