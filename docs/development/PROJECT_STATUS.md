# Project Status

Status: Phase 8 autonomous development in progress from the accepted Phase 7G candidate

Active development baseline: `phase7g/integration` at
`b69d1e605b0021cf816cc9330210a8fb73c4382d` (tree
`2b9e3f50456a6b0e56b1a533cbd05f9dcc386bf6`). Phase 7G Draft PR #31 is open and unmerged.
Phase 8 work is isolated on `phase8/autonomous-development`; `main`, v2.5.2, and PR #29–#31
remain unchanged. See the Phase 8 record near the end of this document.

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
| UI | Existing FLACtify full-player UI plus Phase 6B-2 mini-player MVP (PR #24) |
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

## Phase 6B-2: mini-player MVP (merged)

PR #24 was merged on 2026-11-28 at `429b9ed14d5d4dfd10cbec72f035aa648196f50d`.
Its tested PR head, `7e23d7899480292418cc657ff1b7594ac70d39b7`, matched the Owner's
FoundationClosure device-validation target. The Owner reported 12/12 manual checks passed.
This aggregate Owner result is device evidence, not a Cloud test result. The Owner's
qualitative experience of responsiveness is likewise distinct from quantitative frame-time,
jank, or memory measurements; no such measurements are recorded here.

The mini-player uses the shared `NowPlayingUiState`, remains behind its internal feature
switch, and is hidden when no track is current or the full player is open. The Gate 3
reconnect paused-retention assertion remains an unresolved risk; its device cycle was not
restarted as part of Phase 6B-2.

## Verification status

| Area | State | Evidence / limit |
| --- | --- | --- |
| PR #22 required CI | PASS | Android validation, CodeQL, LGPL relink, and fresh-extraction compliance all succeeded at head `8e0e597a`; 389 unit tests passed. |
| PR #24 required CI | PASS | All six check-runs succeeded at tested head `7e23d7899480292418cc657ff1b7594ac70d39b7`. |
| Phase 6B-2 Cloud unit tests | PASS | 393 unit tests passed in the PR validation run. |
| Phase 6B-2 Owner device validation | PASS (Owner-reported) | Owner reported 12/12 manual checks passed on the matching FoundationClosure target. |
| Phase 6B-2 quantitative performance | NOT MEASURED | No frame-time, jank, or memory measurements are represented by the Owner's aggregate manual result. |
| Release 2.5.2 identity | PASS | `com.flactify`, versionCode 17 / versionName 2.5.2. Signed APK SHA-256 is recorded in `CHANGELOG.md`. |
| Signed APK and source match | PASS | Owner-provided APK hash verified; 218 non-signature ZIP entries match the Cloud-built unsigned APK from `26b26fe`; three v1 `META-INF` signing entries were added and APK Signing Scheme v2/v3 verify. |
| Pixel 7a update and Owner validation | PASS (Owner-reported) | Existing-key update succeeded; no extra device test was run for this closure. |
| 16 KiB alignment / runtime notices | PASS | Cloud unsigned artifact passed ZIP and ELF alignment checks; packaged LGPL-2.1, Steam Audio, and CIPIC notices are present. Signing did not change payload entries. |
| Gate 3 reconnect paused retention | OPEN RISK | A device assertion remains unresolved. Gate 3 is not called fully successful. |
| Public APK/AAB distribution | BLOCKED | The personal release was delivered privately. No general-public binary release was made. |

## Phase 6B gates

1. **Gate 3 — NowPlaying contract:** implementation is merged. Preserve the reconnect
   paused-retention finding as an open risk; do not claim full Gate 3 closure.
2. **Phase 6B-2 — Mini-player MVP:** merged through PR #24 and accepted after Owner-reported
   12/12 manual device checks. See [`MINIPLAYER_SPEC.md`](MINIPLAYER_SPEC.md). Keep the Gate 3
   risk open; the manual acceptance does not close that separate finding.
3. **Gate 5 — UX polish and stability:** Phase 8 is authorized to improve the existing UI
    and playback flow. Device validation remains a separate Owner gate.


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

## Phase 7G and Phase 8

On 2026-11-28 the Owner accepted the Phase 7G product candidate and authorized Phase 8
autonomous development from the exact Phase 7G HEAD above. The cumulative Phase 7G candidate
is recorded in Draft PR #31; it has not been merged. At its recorded HEAD, 474 JVM tests,
Android validation, CodeQL (Java/Kotlin and C/C++), LGPL relink, and fresh-extraction
Compliance passed. FoundationClosure was built for Owner validation; no Phase 7G device run
was performed.

Phase 8 may investigate and implement safe ReplayGain/DSP, Spatial/gapless, large-library and
SAF, data-protection/interoperability, UI/UX, OSS, and performance improvements under the
dated decision in `OWNER_DECISIONS.md`. Keep high-risk DSP and storage work isolated until
numeric safety, reversibility, licensing, and regression evidence are established. A
Cloud-only result never establishes listening quality, device performance, or the unresolved
Gate 3 paused-retention behavior.

### Phase 8 implementation checkpoint (2026-11-28)

The dedicated branch `phase8/autonomous-development` was created from the authorized
Phase 7G base. At this checkpoint the cumulative candidate has product and internal changes
on top of `b69d1e6`; it is not merged. PRs #29, #30, and #31 and the stable 2.5.2 release
remain untouched.

| Work | State | Evidence / remaining limit |
| --- | --- | --- |
| Large audio scan fallback | IMPLEMENTED | Seekable SAF descriptor metadata path avoids whole-file copies above the retained 1 GiB staging limit; synthetic scanner metadata tests pass. Embedded artwork is omitted on the oversized fallback. |
| Tag edit staging safety | IMPLEMENTED | Bounded 64 KiB copy loops, 1 GiB source/output ceiling, free-space preflight, cancellation checks, and existing recovery/readback preserved; `TagEditorTest` passes. |
| Opus multi-value preservation | CORE_ONLY | Multi-value tags and untouched lyrics/ReplayGain/artwork preservation are covered by synthetic tests; no personal media used. |
| Backup recovery | IMPLEMENTED | A durable pending-restore journal is exposed in Settings with retry/discard actions, bounded preview counts, exception feedback, single-flight controls, and immediate favorites/playlists/statistics refresh after successful recovery. |
| M3U relative SAF resolver | CORE_ONLY | Safe resolver handles Unicode, percent-encoding, separators, traversal rejection, and ambiguity; current import UI still resolves exact known URIs only. |
| Player and lyrics layout | IMPLEMENTED | Navigation-bar inset and weighted lyrics list changes; related UI tests pass. |
| ReplayGain playback correction | POC / BLOCKED | Numeric PCM prototype passed 13 tests; safe Media3 item-transition synchronization and direct/offload/spatial bypass are unresolved, so no correction is applied to playback. |
| Parametric EQ | POC | Default-disconnected RBJ biquad engine passed stability/headroom tests. No listening-quality claim. |
| Performance | POC / UNVERIFIED on device | Synthetic JVM tests at 1k/10k/50k report host timings only; no device PSS, jank, SAF-provider latency, or frame-time measurement. |
| UI screenshot validation | POC | Existing isolated Compose smoke render passed, but it does not draw product screens; product screenshot verification remains unavailable without dependency changes. |

At candidate revision `fe4c003254d82010987182854b6fa8c6cd1523b2`, Cloud verification passed:
483 JVM tests, full `test` lifecycle, lint, Debug APK, ReleaseValidation/R8, FoundationClosure
APK, FoundationClosure instrumentation compilation, 16 KiB APK ZIP/native ELF checks, and
LGPL relink proof. The debug APK is 52.66 MiB and unsigned ReleaseValidation APK is 35 MiB.
A controlled same-variant debug build comparison measured 54,793,946 bytes at Phase 7G
baseline `b69d1e6` and 55,213,658 bytes at the Phase 8 candidate (+419,712 bytes / 409.9
KiB). This is debug APK size only, not a production APK delta. GitHub CodeQL Java/Kotlin and
C/C++, Fresh-extraction compliance, and LGPL relink passed on earlier candidate revisions.
The backup recovery Settings UI and single-flight controls were subsequently completed. One
later JVM run exposed an existing test-order failure: `PlayerViewModelDirectorySwitchTest`
created its `UnconfinedTestDispatcher` before `Dispatchers.Main` was configured. The test now
uses an explicit scheduler; final verification is running on the exact candidate source.
Owner device checks remain unperformed.

The candidate is intended for a Draft PR against `main`; no merge, production signing,
production release, public distribution, or device operation is authorized.

## Documentation map

| Document | Purpose |
| --- | --- |
| [`OWNER_DECISIONS.md`](OWNER_DECISIONS.md) | Active and superseded Owner decisions |
| [`NOWPLAYING_CONTRACT.md`](NOWPLAYING_CONTRACT.md) | Normative shared playback-state contract |
| [`MINIPLAYER_SPEC.md`](MINIPLAYER_SPEC.md) | Mini-player scope, preconditions, and test gates |
| [`CLOUD_DEVELOPMENT.md`](CLOUD_DEVELOPMENT.md) | Codex Cloud setup, network, caches, and test split |

For repository identity and mutation safety, follow [`../../AGENTS.md`](../../AGENTS.md).
