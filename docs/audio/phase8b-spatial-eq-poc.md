# Phase 8B spatial/EQ PoC

This Phase 8B review did not identify a reproducible production defect in the current
Media3/SpatialAudioOutput path. The existing implementation already has focused coverage for
arbitrary PCM chunks, partial downstream writes, compatible same-format stream transitions,
sample-clock discontinuities, final-only EOS tail draining, and position correction while the
tail is written. The native `FrameAdapter` also bounds pending output and reports partial input
consumption, which the Kotlin boundary retries without advancing an unaccepted input buffer.

The review therefore leaves the playback service and native spatial path unchanged. No listening
improvement is claimed. `SpatialAudioOutputPcmTest` passed 15/15 on the Cloud JVM run; this does
not execute Steam Audio or an Android AudioTrack.

## Reproducible numerical experiment plan

When a native/AudioTrack capture environment is available, use deterministic stereo fixtures at
44.1, 48, 88.2, 96, 176.4, and 192 kHz:

1. Feed a one-frame impulse and a one-second impulse-plus-silence through the complete native
   engine. Record accepted output frame count, first non-zero frame, final tail frame, sample peak,
   48-tap/four-phase estimated true peak, and finite-value status.
2. Feed a logarithmic 20 Hz–20 kHz sweep at -1 dBFS. Window away startup/tail transients, FFT the
   accepted output, and report magnitude response, sample peak, estimated dBTP, and harmonic
   residual. Do not infer a device response from the native output.
3. Feed a coherent sine at 100 Hz, 1 kHz, 10 kHz, and near-Nyquist, then calculate THD+N from
   the residual after fitting the fundamental and its phase. Use the same duration/window at every
   rate and report the noise floor and harmonic bins separately.
4. Run two synthetic tracks with lengths not divisible by the 512-frame native block: verify no
   HRTF tail is emitted at an internal compatible transition, accepted frame indices remain
   continuous, and the tail appears exactly once after final EOS. A separate sample capture is
   required to establish convolution-state continuity; diagnostics alone are insufficient.

These measurements must remain labelled synthetic/native-boundary evidence. They do not establish
Bluetooth, DAC, AudioTrack, subjective listening, or licensed-music behavior.

## Isolated biquad EQ PoC

`BiquadEqPoc` implements RBJ peaking, low-shelf, and high-shelf coefficient design plus a
transposed direct-form-II stereo state. It is in a separate package, has no playback call sites,
and is default-off by construction. It validates finite parameters and rejects DC/Nyquist edge
frequencies that would make the experiment ambiguous.

The Cloud numerical run and `BiquadEqPocTest` covered:

| Filter | Rate / frequency | Gain | Measured response | Pole radius | Headroom fixture |
| --- | --- | ---: | ---: | ---: | ---: |
| peaking, Q=1 | 48 kHz / 1 kHz | +6 dB | +6.000 dB at center | 0.954817 | n/a |
| low shelf, S=0.7 | 192 kHz / 100 Hz | -12 dB | -6.000 dB at nominal corner | 0.995959 | n/a |
| high shelf, S=0.7 | 192 kHz / 12 kHz | -12 dB | -6.000 dB at nominal corner | 0.781276 | n/a |
| peaking, Q=0.7 | 48 kHz / 1 kHz | +12 dB | +12.000 dB at center | 0.954315 | 0.995279 peak from 0.25-amplitude sine |

All reported poles were strictly inside the unit circle, coefficients remained finite, and the
stereo state produced finite output for 48,000 synthetic frames. The 0.995279 result is not a
limiter guarantee: it is only the response to that specified sine fixture. A future integrated EQ
would need explicit gain/headroom policy, true-peak measurement, bypass/automation semantics, and
device validation before product use.
