# Project Status

Status: FLACtify 2.5.2 personal-use release complete; public development continues

Release code source: `26b26fec6776237346b513d36ad32fc1e3ce58a6` (`release/2.5.2-candidate`, 2026-11-28)

The finalization PR merges this source and its release records onto `main`. The `v2.5.2`
tag targets the PR's main merge commit; app source and resources match the release code
source above.

This document is the current public entry point for product scope, delivery state, and
verification limits. It supersedes design-only assumptions in older, locally preserved
planning records. Those records remain archived outside Git and are not a source of
authority for new work.

## Current product baseline

| Item | Value |
| --- | --- |
| Application ID | `com.flactify` (debug suffix `.spatialdev`, isolated closure suffix `.foundationclosure`) |
| Version | `2.5.2` (`versionCode` 17) |
| SDK levels | `compileSdk 36`, `targetSdk 36`, `minSdk 26` |
| Playback engine | Android Media3 `1.11.1` (single engine) |
| UI | Existing FLACtify full-player UI; no mini-player UI yet |
| Audio | Optional experimental Spatial Audio Studio backed by Steam Audio 4.8.1 |
| Distribution | Personal-use Release completed; no public APK/AAB; public binary-license gate remains closed |

## Phase 6A result (merged)

Phase 6A was merged through PR #19, `Phase 6A playback foundation (shuffle rounds,
duration retention, isolated device build)`. The merge commit is the baseline above.

Delivered and covered by the merged test changes:

- service-authoritative shuffle rounds that start at the current queue item;
- rejection of stale shuffle requests after a queue or current-item change;
- shuffle-round restoration after process restart;
- resolved duration retained only while its media item remains current;
- deterministic duration and position behavior for unknown duration, pause, idle, and
  item transitions;
- synthetic-audio auto-advance and shuffle-order device tests;
- an isolated `foundationClosure` build/test selector for device validation.

The full player remains the existing implementation. Phase 6A did not introduce a
mini player, a second playback engine, a DSP redesign, or a persistence-format migration.

## Phase 6B-1: shared now-playing state

The `NowPlayingUiState` shared snapshot and Media3 reconnect synchronization were merged
through PR #21. The Owner accepted the manual device suite, while one reconnect paused-state
retention assertion remains unresolved. Gate 3 is **not** reported as a complete PASS. Keep
that risk visible in follow-up work; do not restart its device cycle without a new Owner
decision.

## Opus/Ogg support and Release 2.5.2

Content-based audio identification, OpusTags metadata/lyrics/artwork parsing, and cached
track retention on transient provider reads were merged through PR #22. The Owner's Opus
device check passed 6/6. The signed 2.5.2 APK is for personal use only; the public binary
distribution gate remains closed.

## Verification status

| Area | State | Evidence / limit |
| --- | --- | --- |
| PR #22 required CI | PASS | Android validation, CodeQL, LGPL relink, and fresh-extraction compliance all succeeded at head `8e0e597a`; 389 unit tests passed. |
| Release 2.5.2 identity | PASS | `com.flactify`, versionCode 17 / versionName 2.5.2. Signed APK SHA-256 is recorded in `CHANGELOG.md`. |
| Signed APK and source match | PASS | Owner-provided APK hash verified; 218 non-signature ZIP entries match the Cloud-built unsigned APK from `26b26fe`; three v1 `META-INF` signing entries were added and APK Signing Scheme v2/v3 verify. |
| Pixel 7a update and Owner validation | PASS (Owner-reported) | Existing-key update succeeded; no extra device test was run for this closure. |
| 16 KiB alignment / runtime notices | PASS | Cloud unsigned artifact passed ZIP and ELF alignment checks; packaged LGPL-2.1, Steam Audio, and CIPIC notices are present. Signing did not change payload entries. |
| Gate 3 reconnect paused retention | OPEN RISK | A device assertion remains unresolved. Gate 3 is not called fully successful. |
| Public APK/AAB distribution | BLOCKED | The personal release was delivered privately. No general-public binary release was made. |

## Phase 6B gates

1. **Gate 3 — NowPlaying contract:** implementation is merged. Preserve the reconnect
   paused-retention finding as an open risk; do not claim full Gate 3 closure.
2. **Phase 6B-2 — Mini-player MVP:** the Owner authorized development to resume after the
   2.5.2 release closure. The mini-player remains unimplemented and must follow
   [`MINIPLAYER_SPEC.md`](MINIPLAYER_SPEC.md). Carry the Gate 3 risk explicitly into its PR.
3. **Gate 5 — UX polish and stability:** continue to require device and performance evidence.

## Non-negotiable constraints

- Do not change product behavior in the cloud-migration phase.
- Merge to `main` only through a reviewed PR and normal branch protections.
- Do not publish APK/AAB artifacts or releases to the general public without a separate
  Owner decision and cleared binary-license gate.
- Keep personal-use signed binaries in owner-only storage; never commit them or upload signing
  material to Codex Cloud.
- Do not add signing keys, keystores, real Last.fm credentials, device serials, or personal
  paths to the repository.
- Do not import private archive history or add the archive as a remote.
- Device-only results must never be reported as cloud PASS.

## Documentation map

| Document | Purpose |
| --- | --- |
| [`OWNER_DECISIONS.md`](OWNER_DECISIONS.md) | Active and superseded Owner decisions |
| [`NOWPLAYING_CONTRACT.md`](NOWPLAYING_CONTRACT.md) | Normative shared playback-state contract |
| [`MINIPLAYER_SPEC.md`](MINIPLAYER_SPEC.md) | Mini-player scope, preconditions, and test gates |
| [`CLOUD_DEVELOPMENT.md`](CLOUD_DEVELOPMENT.md) | Codex Cloud setup, network, caches, and test split |

For repository identity and mutation safety, follow [`../../AGENTS.md`](../../AGENTS.md).
