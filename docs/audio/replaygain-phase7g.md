# ReplayGain Phase 7G boundary

FLACtify now reads ReplayGain 1.0 fields during the existing library metadata scan for the
supported library containers (`flac`, `wav`, `ogg`, `opus`, `m4a`, and `mp3`). Opus comments use
the existing bounded `OpusTags` reader; other supported containers use the already-redistributed
jaudiotagger 3.0.1 dependency. Parsed values are retained in the library cache and exposed in
the current playback diagnostics.

`R128_TRACK_GAIN` remains separate from ReplayGain. It is a signed Opus Q7.8 value in 1/256 dB
units relative to the R128 -23 LUFS target; it is never parsed as a ReplayGain dB string.

The settings are persisted but default to disabled. Track/Album selection, preamp (-12..+12 dB),
and peak protection are calculated and shown as diagnostics. Peak protection clamps requested
boost to `-20*log10(peak)` when a finite positive sample/metadata peak exists; attenuation is not
changed. The status explicitly says diagnostic-only, R128-only, no tag, or unsupported format.

Playback application is intentionally blocked in this phase. The current service owns an existing
Media3 `ExoPlayer`, custom Spatial `AudioOutputProvider`, and direct-capability probe. The only
general volume control is the player/output scalar, which cannot safely provide positive gain,
cannot guarantee true-peak protection, and has no authoritative per-item ReplayGain metadata
handoff across controller/service transitions. Applying it would also need defined reset and
fade semantics when Spatial mode replaces the player. No source audio, queue ordering, direct
path, or Spatial PCM processor is changed.

No new runtime dependency was added. The existing jaudiotagger path remains covered by the
repository's LGPL-2.1-or-later notices. The isolated host PoC under
`tools/replaygain-poc/` retains the synthetic libebur128 measurement and MIT license record;
libebur128 is not packaged or linked into Android.

