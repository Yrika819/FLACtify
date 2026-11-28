# Owner Decisions

Status: active decision register

Release code source: `26b26fec6776237346b513d36ad32fc1e3ce58a6` (2026-11-28)

This register records product and delivery decisions that remain binding. Historical
proposals are kept only where they explain a current constraint. Decisions marked
**SUPERSEDED** must not be revived without a new Owner decision.

## Active decisions

| ID | Decision | Status | Consequence for current work |
| --- | --- | --- | --- |
| D-003 | Keep the five primary player controls: shuffle, previous, play/pause, next, repeat. | ACTIVE | Do not remove or merge them while implementing shared state or a mini player. |
| D-004 | Do not add a large fixed auxiliary toolbar at the bottom of the player. | ACTIVE | Keep the existing full-player composition. |
| D-005 | Keep FLACtify's existing lyrics presentation. | ACTIVE | Lyrics work must not replace the established display method. |
| D-006 | Tune fine visual placement and effects on a real device during implementation. | ACTIVE | Mockups and design notes are not visual acceptance. |
| D-007 | Updating the Android SDK is permitted, but it is not an instruction to change `targetSdk` immediately. | ACTIVE | Current baseline remains `compileSdk 36`, `targetSdk 36`, `minSdk 26`. Any SDK-level change is a separate reviewed change. |
| D-008 | Keep the detailed local roadmap/design archive outside Git and preserve it. | ACTIVE | Public documents are sanitized summaries. Do not copy the local archive or its absolute paths into the repository. |
| D-009 | Liquid Glass / LiquidGrass is cancelled. | ACTIVE | Do not add glass shaders, refraction, or glass-quality settings. |
| D-010 | Apple Music-style immersive cover-art expansion is cancelled. | ACTIVE | Keep the existing FLACtify full-player appearance. Old research remains historical only. |
| D-011 | An always-available in-app mini player is the top product priority after the shared playback contract. | ACTIVE | Shared contract is merged. Owner authorized Phase 6B-2 to resume after Release 2.5.2; carry the unresolved Gate 3 reconnect retention risk. |
| D-012 | Stop new Glass/immersive-art research. | ACTIVE | Preserve research material; do not spend implementation time on it. |
| D-013 | Preserve existing playback, lyrics, audio-quality diagnostics, staged SDK migration, and Git-external archive practice. | ACTIVE | These are constraints, not optional cleanup targets. |

## Superseded decisions

| ID | Old decision | Superseded by | Reason |
| --- | --- | --- | --- |
| D-001 | Use the Apple Music-like player concept as the visual base. | D-010 | The Owner cancelled immersive cover-art expansion and retained the existing FLACtify full player. |
| D-002 | Large cover art with a deliberately softened boundary between art and background. | D-010 | The immersive/art-extension direction was cancelled. |
| P-001 | Use Haze 2.0.1 as the first glass candidate. | D-009 | Liquid Glass was cancelled. |
| P-005 | Add light/balanced/high glass-quality settings. | D-009 | No glass quality tiers are planned. |

## Dated decisions and operational clarifications

### 2026-10-20 — visual direction reset

- Liquid Glass and immersive cover-art expansion were cancelled (D-009, D-010).
- The existing FLACtify full-player UI is the reference appearance.
- The in-app mini player became the highest-priority new feature (D-011), to be designed
  only after the shared now-playing state is sound.

### 2026-10-24 — SAF and test-data boundary

- The Owner confirmed that the library folder was selected through the Storage Access
  Framework (SAF). The earlier "unknown media source" concern is explained by normal SAF
  access, not by an undeclared broad-media permission.
- The large Media Provenance Investigation is cancelled.
- Device validation must use synthetic fixtures only. Existing application data must not be
  deleted or overwritten. Personal media, personal URIs, artwork, and UI dumps must not be
  collected.
- Mini-player implementation remains **NO-GO** until the readiness conditions in
  [`MINIPLAYER_SPEC.md`](MINIPLAYER_SPEC.md) are met.

### 2026-11-27 — Phase 6A baseline and cloud migration

- Phase 6A is formally integrated at `eb2aad2`; Phase 6B product implementation is not
  started.
- Development is to move toward Codex Cloud to reduce local Mac CPU, memory, and storage
  load. Cloud migration is limited to documentation, setup automation, and read-only
  compatibility checks until the Owner approves the migration PR.
- The migration branch is `infra/codex-cloud-readiness`; it must not merge product code or
  publish artifacts.
- Device-only validation remains a separate gate. A cloud run cannot satisfy it.

### 2026-11-28 — FLACtify 2.5.2 personal release and Phase 6B-2 readiness

- Owner accepted Release 2.5.2 (`com.flactify`, versionCode 17) after existing-key signing,
  Pixel 7a in-place update, and Owner device validation. This is a personal-use release;
  no general-public APK/AAB release is authorized.
- The signed APK SHA-256 is
  `aca14135a9d40a9ac780f24e9792e4e4ac00e7b3a13e6a08e4beb2b7b8d9f3eb`. It was matched to
  the Owner-uploaded APK. Its 218 non-signature payload entries match the Cloud-built
  unsigned artifact recorded for source commit `26b26fec6776237346b513d36ad32fc1e3ce58a6`.
- Gate 3's reconnect paused-retention assertion remains unresolved. No full Gate 3 PASS is
  claimed, and its device test cycle remains stopped.
- Owner authorizes Phase 6B-2 mini-player development to resume after this release closure.
  This does not close the reconnect finding; carry it as a known risk in subsequent work.
- The standard personal-release workflow is documented in
  [`CLOUD_DEVELOPMENT.md`](CLOUD_DEVELOPMENT.md): Cloud unsigned Release build, owner-only
  Drive handoff, then Mac-side alignment/signing with the existing key.

## Standing delivery rules

1. `main` is merge-by-approval only. Branch protection is not bypassed.
2. Product changes require their own reviewed PR. Documentation and infrastructure changes
   do not authorize product behavior changes.
3. APK/AAB publication remains blocked until the binary-license gate is cleared.
4. Do not commit secrets, signing material, personal paths, device serials, or private
   archive content.
5. Preserve existing worktrees, uncommitted changes, and local backups.
6. Record unverified items as UNVERIFIED. Do not convert missing evidence into PASS.

## Change protocol

To change an active decision, add a dated entry with:

- the decision ID;
- the old and new decision;
- the reason;
- the Owner approval reference;
- the affected PR or evidence.

Do not silently rewrite an active decision. Do not promote a proposal to ACTIVE without
explicit Owner approval.
