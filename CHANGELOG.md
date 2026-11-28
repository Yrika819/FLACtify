# Changelog

This changelog records project releases and notable changes. FLACtify 2.5.2 is a
personal-use release; it was not published for general public distribution.

## Unreleased — 3.0.0 candidate (BLOCKED; not released)

- Candidate identity is prepared as `com.flactify`, versionName `3.0.0`, versionCode `18`.
- Candidate source is based on the Owner-evaluated Phase 9B source HEAD
  `e03dcd9cbd5591e61505387afe9364b7df988dc0`; the only product-source change in this
  preparation is the app version metadata.
- Owner-reported device results: Phase 9A 8/8, Phase 9B 8/8, and Phase 9C 21/21 PASS, with
  no reported defect. These are Owner device results, not Cloud CI results.
- Release readiness is blocked by a HIGH-severity play-statistics backup restore mismatch.
  See [`docs/release/release-readiness-v3.0.0.md`](docs/release/release-readiness-v3.0.0.md).
- No v3.0.0 Release APK has been generated. No signing, release tag, GitHub Release, merge,
  or public distribution has occurred.

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
