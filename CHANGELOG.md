# Changelog

This changelog records project releases and notable changes. FLACtify 2.5.2 is a
personal-use release; it was not published for general public distribution.

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
