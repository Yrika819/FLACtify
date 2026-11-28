# Mini-Player Specification

Status: **MVP IMPLEMENTED and integrated through PR #24; Owner-reported device acceptance 12/12**

Shared-state source: `f70eb8d1bcf61c724b248d17f02fb60153dd561b` (PR #21 merge)

This document records the MVP contract and follow-up boundaries for the in-app mini player.
PR #24 merged on 2026-11-28 at `429b9ed14d5d4dfd10cbec72f035aa648196f50d`; its tested head
was `7e23d7899480292418cc657ff1b7594ac70d39b7`. The Owner reported 12/12 manual device checks
passed. This is Owner-reported device validation, separate from Cloud unit/CI results.
Qualitative responsiveness feedback is not a quantitative performance measurement; frame
time, jank, and memory data remain unmeasured. Gate 3's reconnect paused-retention assertion
remains an unresolved risk and its device test cycle was not restarted.

## Preconditions

All of the following are required before mini-player implementation:

1. `NowPlayingUiState` is implemented and reviewed per
   [`NOWPLAYING_CONTRACT.md`](NOWPLAYING_CONTRACT.md).
2. The full player still renders from the same Media3-backed state and keeps its existing
   appearance, lyrics, quality display, and playback behavior.
3. Navigation state (screen, search query, sort, scroll position) is already preserved on
   the routes where the mini player will appear.
4. A synthetic-fixture device test plan is approved. No personal media, URI, artwork, or UI
   dump is collected.
5. The change is split into reviewable PRs; the contract and mini-player UI are not mixed
   in one unreviewable change.

The MVP is implemented behind the internal `MiniPlayerFeature` switch. Its initial feature
scope and constraints below remain the baseline for future Gate 5 proposals.

## Product intent

The mini player is an in-app, persistent playback surface. It lets the listener keep
context while browsing and return to the full player without losing the underlying screen
state.

It is not an OS overlay, a floating system window, a widget, a lock-screen replacement, or a
second playback session.

## Placement and visibility

| Context | Behavior |
| --- | --- |
| Library, search, album, artist, playlist, settings | Mini player is available above the bottom navigation/inset area. |
| Full player | Mini player is hidden; the full player is the playback surface. |
| No current track | Mini player is not shown. |
| Track exists but paused | Mini player remains visible. |
| Track exists but buffering/error | Show the current item and a clear state; do not fabricate playback. |

The mini player must not cover the last list item, the navigation bar, or the IME. Its
height and insets participate in the host layout rather than overlapping content.

## Minimum content

- album artwork thumbnail;
- track title;
- artist name;
- play/pause control;
- a clear tap target that opens the full player.

Next/previous are optional only if the 48 dp tap-target and layout requirements can be met.
Do not add a control by shrinking an existing target below the accessibility minimum.

Long Japanese titles must ellipsize gracefully without pushing controls off-screen. Artwork
absence must use an explicit placeholder, never the previous track's image.

## Interaction

1. Tapping the non-control area opens the existing full player.
2. Back from the full player returns to the exact previous screen, including search query,
   sort, and scroll position.
3. Play/pause reflects Media3 state and does not create a second player.
4. Notification and hardware controls continue to update the same state.
5. The mini player does not intercept system back or gesture navigation.

## Layout and accessibility

- Respect status/navigation bar and IME insets on all supported orientations.
- Maintain at least 48 dp touch targets for controls.
- Provide TalkBack labels for artwork, title, artist, and controls.
- Support font scaling without clipping the title or controls.
- Support portrait and landscape; do not rely on a fixed screen height.
- Keep contrast and focus order usable in both light and dark themes.
- Do not hide the mini player behind a keyboard when a search field is focused.

## Explicit exclusions

- Haze, Liquid Glass, LiquidGrass, refraction, or shader effects.
- Immersive/full-bleed cover-art expansion.
- OS overlay windows or floating bubbles.
- A second `MediaSession` or playback service.
- Swipe-to-morph or shape-morph animations in the MVP.
- A persistent progress line or waveform in the MVP; evaluate only after performance
  evidence.
- Redesigning the full player or its lyrics/quality presentation.

## Performance requirements

- No app-wide recomposition on position ticks. Position is collected only by the local
  progress/seek surface.
- The mini player must not add a 250 ms state collection to root navigation, library, or
  settings.
- No bitmap decode on the main thread for artwork; reuse the existing image pipeline.
- No duplicate Media3 controller subscriptions per screen.

## Test gates

### Cloud-runnable (JVM)

- visibility rules for no-track, paused, playing, and error states;
- navigation restoration contract for search/sort/scroll;
- shared-state projection and artwork fallback;
- stale metadata and transition-generation behavior;
- no duplicate controller creation.

### Device-only (must not be claimed as cloud PASS)

- Library to mini player to full player and back;
- notification and MediaSession controls;
- paused state, buffering, ended, and error states;
- screen rotation and both orientations;
- IME open/closed and last list item visibility;
- TalkBack traversal and labels;
- long Japanese title and large font scale;
- Bluetooth/audio-route changes;
- frame-time and jank observation;
- synthetic-fixture playback only.

## Delivery sequence

1. Gate 3 contract implementation and review (merged; reconnect paused-retention risk remains
   open and is not being retested in this phase).
2. Mini-player MVP behind an internal flag, with state and navigation tests (merged in PR #24).
3. Owner device validation on synthetic fixtures (Owner reported 12/12 checks passed).
4. Gate 5 proposals: collect quantitative performance evidence and review playback flows,
   then assess visual polish, cross-screen consistency, optional controls, and transitions.
   This list is investigation only; no Gate 5 implementation is approved here.

Any step that cannot be validated on a device is reported as UNVERIFIED, not PASS.
