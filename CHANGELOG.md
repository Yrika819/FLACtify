# Changelog

This changelog records project releases and notable changes. FLACtify 2.5.2 is a
personal-use release; it was not published for general public distribution.

## 3.0.0 — 2026-11-28 (personal-use stable release)

- Release identity: `com.flactify`, versionName `3.0.0`, versionCode `18`.
- Integrated through PR #37 using normal merge commit `6272f8357c4709587e3b793faa5aaad7a7b859fb`.
  The merge tree `2ab3d24b55986aba17c8abef2c35cf712437dc89` exactly matches the approved PR tree.
  Product APK source HEAD/tree: `4592291a08a62dd923509d369df3b224c4b5afb3` /
  `a9c9ed0df88f8831f24dd7b79650accd25c64232`.
- Fixed the P0 play-statistics restore mismatch by separating internal `pc/sc/lp` persistence
  from backup `playCount/skipCount/lastPlayed` exchange data. Existing v2.5.2 and backup
  formats remain supported; malformed/missing/conflicting rows are preserved without guessing.
- Owner-reported device evaluations: Phase 9A 8/8, 9B 8/8, 9C 21/21, 10A-R2 9/9, and
  10C 10/10 PASS (56/56 aggregate). Phase 10C confirms the production update from 2.5.2,
  retention of existing data and permissions, and the listed core playback flows. See
  [`docs/release/owner-device-evaluation.md`](docs/release/owner-device-evaluation.md).
- Required PR CI and post-merge Android validation, CodeQL, and fresh-extraction checks passed.
  LGPL relink passed in the final PR CI.
- Gate 3 reconnect paused-retention remains an unresolved conditional risk. SharedPreferences
  instrumentation was compiled but not executed; Android quantitative performance metrics
  remain unmeasured.
- Distribution is limited to Owner-authorized personal use. No GitHub Release or general APK/AAB
  publication was made. The Owner-only Drive contains the signed production APK, but no Mac-side
  measurement report with its SHA-256/certificate fingerprint was available in the repository or
  searched Drive records; those values are intentionally omitted here.

## 2.5.2 — 2026-11-28

- Added content-based Opus/Ogg detection, OpusTags metadata, lyrics and artwork parsing,
  and retention of cached tracks when a provider read fails temporarily.
- Merged the shared NowPlaying state contract and the Opus/Ogg support through PRs #21 and
  #22.
- Release identity: `com.flactify`, versionName `2.5.2`, versionCode `17`.
- Cloud Release source build commit: `26b26fec6776237346b513d36ad32fc1e3ce58a6` on
  `release/2.5.2-candidate`. The final `v2.5.2` tag points to the post-PR main tree; the
  app source and packaged payload match this build commit.
- Owner-provided signed APK SHA-256:
  `aca14135a9d40a9ac780f24e9792e4e4ac00e7b3a13e6a08e4beb2b7b8d9f3eb`.
- The signed APK was verified as `com.flactify` 2.5.2 / code 17. Its 218 non-signature
  payload entries match the unsigned APK built from the source commit above; APK Signing
  Scheme v2 and v3 signatures verify. The Owner reports successful Pixel 7a in-place update
  and functional validation.
- The Owner's Opus test passed 6/6. The PR's 389 unit tests and all required CI workflows
  passed.
- The reconnect paused-retention assertion from Gate 3 remains unresolved. This release does
  not declare full Gate 3 completion.
- Distribution was limited to Owner-authorized personal use. No public APK/AAB was released.

## 2.5.1

See the existing release and compliance records in
[`docs/APK_DISTRIBUTION_COMPLIANCE.md`](docs/APK_DISTRIBUTION_COMPLIANCE.md).
