# NowPlayingUiState Contract

Status: **normative contract; implemented in Phase 6B/Phase 7G**

Baseline: `eb2aad233e7dfe7c0fd0e567967ec9d854c1cae0` (2026-11-27)

This contract defines the single read-only now-playing snapshot that the existing full
player and mini player consume. The baseline note above is historical: `NowPlayingUiState`
and its Media3-backed `PlayerViewModel` flow are present in the Phase 7G candidate. Gate 3's
reconnect paused-retention assertion remains an unresolved risk; do not report Gate 3 fully
passed or restart its device cycle without a separate Owner decision.

## Scope and authority

- Media3 remains the sole playback engine and the authoritative source for playback state,
  queue transitions, shuffle/repeat, duration, seekability, and controller reconnection.
- No second `ExoPlayer`, `MediaSession`, playback service, or competing state machine is
  introduced.
- The coordinator is owned by the existing `PlayerViewModel` / Media3 controller boundary
  and exposes one immutable `StateFlow<NowPlayingUiState>`.
- The existing full player must keep its current appearance. Moving it onto the new
  snapshot is an internal state change, not a visual redesign.
- Mini-player UI is out of scope for Gate 3.

## Conceptual model

Names and package placement may follow project style. The semantics below are normative.

```kotlin
data class NowPlayingUiState(
    val current: CurrentTrack?,
    val transitionGeneration: Long,
    val playbackState: PlaybackState,
    val isPlaying: Boolean,
    val position: PositionState,
    val shuffleEnabled: Boolean,
    val repeatMode: RepeatMode,
    val session: SessionConnectionState,
    val controllerGeneration: Long
)

data class CurrentTrack(
    val identity: TrackIdentity,
    val title: String?,
    val artist: String?,
    val album: String?,
    val artwork: ArtworkState
)

data class TrackIdentity(
    val mediaId: String,
    val uri: Uri
)

data class PositionState(
    val positionMs: Long,
    val durationMs: Long?,
    val isSeekable: Boolean
)

enum class PlaybackState { IDLE, BUFFERING, READY, ENDED, ERROR }

sealed interface ArtworkState {
    data object Loading : ArtworkState
    data class Available(val artworkUri: Uri, val version: Long) : ArtworkState
    data object Unavailable : ArtworkState
}

enum class SessionConnectionState { CONNECTING, CONNECTED, RECONNECTING, DISCONNECTED }
```

`RepeatMode` follows Media3's `Player.REPEAT_MODE_*` semantics. `controllerGeneration`
identifies the MediaController instance; `transitionGeneration` identifies the accepted
media-item transition.

## Normative invariants

1. **Identity is not display text.** A track is identified by Media3 `mediaId` plus its
   source URI. Equal title/artist/album with a different URI is a different track. Preserve
   the source URI and define a stable comparison key explicitly.
2. **Every accepted transition advances a monotonic generation.** Increment on every
   media-item transition, including A to B to A. A generation is never derived only from a
   URI, because returning to A must invalidate results produced for the earlier A.
3. **Asynchronous results are stamped.** Every artwork, lyrics, tag, or audio-info lookup
   captures `(TrackIdentity, transitionGeneration)`. Apply the completion only if both still
   match. Discard stale completions with no partial writes.
4. **Track-bound data is cleared synchronously on transition.** Publish the new identity and
   clear or invalidate the previous title-dependent artwork, lyrics, and audio details
   before starting async extraction. Artwork starts as `Loading` or `Unavailable`; it must
   never reuse the previous track's art.
5. **Pause retains the current item.** `isPlaying == false` does not imply `current == null`.
   Paused state retains identity, metadata, artwork, and position.
6. **No current item is explicit.** `current == null` means `NoTrack`. Do not infer
   emptiness from an empty title, `isPlaying`, duration, or a placeholder string.
7. **Media3 is authoritative.** Playback state, transitions, shuffle/repeat, duration,
   seekability, and reconnect snapshots come from the connected Media3 controller/session.
   Do not derive a competing source of truth.
8. **Duration is owned by the current item.** `durationMs == null` means unresolved. Zero is
   a resolved zero length, not unknown. Drop the resolved duration when the owning item is
   no longer current or the queue is cleared/idle. Never expose a negative sentinel.
9. **Position is clamped and locally observed.** `positionMs` is non-negative. Seek and
   discontinuity events publish immediately, including while paused. Periodic position
   updates run only while playing and only where needed.
10. **Avoid app-wide recomposition.** Position is collected only in a small progress/seek
    composable. Root navigation, library, and settings must not collect a 250 ms position
    stream. Other fields update at event frequency.
11. **Session reconnection invalidates old callbacks.** A connection change advances
    `controllerGeneration`. Callbacks from an older controller are ignored. On reconnect,
    read one authoritative Media3 snapshot and atomically resynchronize identity,
    generation, playback state, position, duration, shuffle, and repeat.
12. **The snapshot is internally consistent.** Do not publish a state where a new track is
    paired with the old track's artwork, lyrics, duration, or generation. Build and publish
    transitions atomically on the coordinator's serialized event loop.
13. **Shuffle rounds are service-authoritative.** A shuffle round starts at the current queue
    item and includes every item exactly once. A request is bound to the queue fingerprint,
    item count, and start index observed when it was issued. A request that no longer matches
    the current queue or item is rejected as stale; it must not reorder a later queue.
14. **No display-only placeholder is a track.** Placeholder strings such as "not playing"
    belong to UI rendering. They must not become identity, equality, or state values.

## Transition protocol

On an accepted media-item transition, in one serialized step:

1. Resolve `TrackIdentity` from the Media3 item (`mediaId` plus URI).
2. Increment `transitionGeneration`.
3. Set `current` with title/artist/album as available and artwork `Loading` (or
   `Unavailable` when known absent).
4. Clear lyrics, audio info, route-dependent details, and any previous duration.
5. Publish the snapshot.
6. Start async extraction with the captured identity and generation.
7. Apply each completion only if both stamps still match.

On `NoTrack` or queue clear: set `current = null`, advance the generation, clear
track-bound data, and reset duration. Do not leave stale duration or artwork.

## Playback and position rules

- `playbackState` and `isPlaying` come from Media3. Do not synthesize a playing state from
  a UI toggle.
- `isSeekable` is explicit. A seek request against an unresolved or non-seekable duration is
  ignored or deferred by policy; it must not clamp against a negative or guessed value.
- A transient unresolved duration may retain the last resolved value **only within the same
  item**. On item transition, idle, or queue removal, the value is dropped.
- Paused seek publishes a new position immediately; it does not wait for playback to resume.
- Position polling is stopped when paused, idle, ended, or disconnected.

## Shuffle and repeat rules

- `shuffleEnabled` and `repeatMode` mirror Media3.
- Enabling shuffle rebuilds the order around the current item, not around index zero.
- With repeat OFF, the round visits every remaining item exactly once and stops.
- With repeat ALL, the round wraps into the next round.
- Repeat ONE is unaffected.
- An unchanged active order that already starts at the current item is not regenerated.
- A delayed or stale request must not change the order of a different queue.

## Consumer rules

- The full player and mini player render the same `NowPlayingUiState`; they do not own
  separate copies of playback truth.
- Composables collect only the projections they display. Do not collect the entire state in
  the root navigation host.
- The mini player is hidden when `current == null` and visible when a track exists, including
  while paused.
- Artwork and lyrics consumers must tolerate `Loading`, `Unavailable`, and rapid transitions.

## Required regression matrix

| Scenario | Expected result |
| --- | --- |
| A to B to A | Each transition increments the generation; old A results never apply to new A. |
| Same title/artist/album, different URI | Treated as a different track. |
| Delayed artwork/lyrics/tag results in reverse order | Stale completions are discarded; no partial writes. |
| Pause | Current identity, metadata, artwork, and position remain. |
| No track / empty queue | `current == null`; no stale art or duration. |
| Notification or MediaSession control | State matches Media3; no second engine. |
| Paused seek | Immediate clamped position; duration policy respected. |
| Unknown duration | `durationMs == null`; seek not applied against a guess. |
| Zero duration | Resolved zero, not unknown. |
| Controller reconnect | Old callbacks ignored; snapshot resynchronized atomically. |
| No artwork | Explicit `Unavailable`; no previous art. |
| Tag or metadata update | Stamped reload; stale result cannot overwrite. |
| Shuffle request after queue change | Rejected as stale; later queue unchanged. |
| Queue clear while shuffle request is pending | Rejected as stale. |
| Repeat OFF round | Every remaining item once, then stop. |
| Repeat ALL round | Wraps into the next round. |

## Implementation gates

1. Add the snapshot and coordinator without changing the full player's appearance.
2. Add unit tests for the full regression matrix, including A to B to A and out-of-order
   async completions.
3. Compile and run the JVM test suite in Codex Cloud.
4. Run device-only playback, notification, reconnect, and paused-seek checks on a compatible
   device when available. These are not cloud PASS items.
5. Review the contract implementation separately from any mini-player UI.

## Non-goals

- Liquid Glass, immersive artwork, Haze, or shader effects.
- A second playback engine or a parallel MediaSession.
- OS-level overlay windows.
- Mini-player visual implementation in the same change as the contract.
- Changing the existing lyrics, audio-quality, or full-player presentation.
