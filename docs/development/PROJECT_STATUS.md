# Project Status

Status: public development snapshot

Baseline: `eb2aad233e7dfe7c0fd0e567967ec9d854c1cae0` (`main`, 2026-11-27)

This document is the current public entry point for product scope, delivery state, and
verification limits. It supersedes design-only assumptions in older, locally preserved
planning records. Those records remain archived outside Git and are not a source of
authority for new work.

## Current product baseline

| Item | Value |
| --- | --- |
| Application ID | `com.flactify` (debug suffix `.spatialdev`, isolated closure suffix `.foundationclosure`) |
| Version | `2.5.1` (`versionCode` 16) |
| SDK levels | `compileSdk 36`, `targetSdk 36`, `minSdk 26` |
| Playback engine | Android Media3 `1.11.1` (single engine) |
| UI | Existing FLACtify full-player UI; no mini-player UI yet |
| Audio | Optional experimental Spatial Audio Studio backed by Steam Audio 4.8.1 |
| Distribution | No public APK/AAB; binary-license gate is not cleared |

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

## Now-playing state: designed, not fully implemented

The target `NowPlayingUiState` contract is normative for Phase 6B Gate 3, but it is **not**
a single published state at this baseline. `PlayerViewModel` still exposes several
independent `StateFlow` values (title, artist, artwork, playback, position, duration,
shuffle, repeat, and so on).

Behavior already present and tested at this baseline:

- Media3 is the only playback source; no duplicate player/service is created;
- an item transition clears the previous artwork, lyrics, and audio-route details;
- duration is dropped when the owning item is no longer current;
- position is clamped to non-negative values and seeks are ignored while duration is
  unknown;
- shuffle ordering and duration ownership follow the Phase 6A rules above.

Still required by the contract: a single immutable snapshot, explicit `NoTrack`, stable
track identity, transition/controller generations, typed artwork state, stamped async
metadata, reconnect resynchronization, and the complete regression matrix. See
[`NOWPLAYING_CONTRACT.md`](NOWPLAYING_CONTRACT.md).

## Verification status

| Area | State | Evidence / limit |
| --- | --- | --- |
| Phase 6A merge PR #19 checks | PASS | Android validation, CodeQL Java/Kotlin, CodeQL C/C++, compliance-bundle, LGPL relink, and CodeQL summary all reported SUCCESS on the PR. |
| Baseline commit check-runs | PASS (observed) | Android validation, CodeQL Java/Kotlin, CodeQL C/C++, and compliance-bundle check-runs are SUCCESS on `eb2aad2`. |
| JVM unit tests, lint, Debug, ReleaseValidation/R8, 16 KiB checks | PASS in existing CI | These are CI results, not a fresh local run in the migration workspace. |
| Instrumentation / real-device playback | NOT VERIFIED on cloud | Requires a connected device; many scenarios remain Owner/device-only. |
| Real music library, SAF picker, notification/MediaSession, Bluetooth | NOT VERIFIED here | Synthetic fixtures only; no claim is made for a user library. |
| Production signing and APK/AAB distribution | NOT AVAILABLE | Signing material and binary-license clearance are out of scope. |
| Codex Cloud setup | NOT YET EXECUTED | The setup script and network plan are prepared but not run in Codex Cloud. |

## Phase 6B gates

1. **Gate 3 — NowPlaying contract** (next product implementation): implement and review the
   single snapshot, generation semantics, stale-result protection, and regression tests in
   a separate PR. No mini-player UI in the same change.
2. **Gate 4 — Mini-player MVP**: only after Gate 3 is implemented and reviewed, and the
   readiness conditions in [`MINIPLAYER_SPEC.md`](MINIPLAYER_SPEC.md) are satisfied.
3. **Gate 5 — UX polish and stability**: device and performance evidence before any
   expansion.

Phase 6B product work has **not** started. The current migration work is documentation and
cloud infrastructure only.

## Non-negotiable constraints

- Do not change product behavior in the cloud-migration phase.
- Do not merge to `main` without explicit Owner approval.
- Do not publish APK/AAB artifacts or releases.
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
