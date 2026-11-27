# Gate 3 device check preparation

Status: prepared after the Phase 6B-1 cloud checks; device execution remains pending.

## Test boundary

- Run only on the FoundationClosure debug variant and synthetic fixtures.
- Use the Mac as the controlled transfer/build host and the Pixel 7a as the Android device.
- Do not use personal music, commercial artwork, production credentials, production signing
  material, or a public distribution channel.
- Do not attach APK/AAB files to Git, pull requests, releases, or public workflow artifacts.
- Keep the APK and fixture bundle in the task-scoped private workspace. Transfer it to the
  Mac through the authenticated private Codex task file handoff, verify the recorded SHA-256,
  then install only on the Pixel 7a. Delete the temporary copy after the check.
- The FoundationClosure APK is a test build only. It is not a release or a substitute for the
  existing LGPL/license gate.

## Synthetic fixture set

Prepare three short FLAC files with different generated pure tones. Embed generated geometric
PNG covers in A and B, leave the third track without artwork, and embed short synthetic timed
lyrics in A and B. Use distinct titles A, B, and No Artwork; exercise A → B → A by returning to
the same A file/URI. The fixtures must contain no copied or licensed content.

Place the files in one test folder on the Pixel and grant the app access through its normal
folder picker. Use the following device checks:

| Check | Action | Record |
| --- | --- | --- |
| Automatic next | Play A, allow end-of-track transition to B | title, cover, lyric line, and position all belong to B |
| Manual next/previous | Change A → B and back | NowPlaying and MediaSession agree after each transition |
| Rapid A → B → A | Trigger both changes rapidly, then wait for metadata/artwork loads | first A's delayed result never replaces final A's data |
| Paused seek | Pause, seek to a middle point, resume | position changes while paused; no jump to a guessed duration |
| Notification / MediaSession | Change track, pause, resume, seek, next, and previous from notification/system controls | full player and shared state reflect the service state |
| Controller reconnect | Stop/restart the test playback service or use the agreed reconnect trigger while playing | old controller events are ignored; new snapshot has correct track, position, duration, and seekability |
| Full-player preservation | Compare the current full-player screen before and after this change | layout and existing controls remain intact |
| Artwork and lyrics | Observe A/B transitions, including one no-art fixture | art/lyrics never show data from the prior track; missing art has the existing fallback |

Record device model, Android version, app build SHA, APK SHA-256, each check result, and any
logs/screenshots that contain no personal media data. A failed or unrun device check stays
pending; cloud tests do not count as device evidence.

## Cloud build and handoff

Build `assembleFoundationClosure` and the unit-test suite in Codex Cloud with the retained SDK,
Gradle, Steam Audio, and dependency caches. Keep the debug-key-signed test APK out of
the repository and Actions artifacts. Before handoff, calculate SHA-256 and size, transfer the
APK plus synthetic fixtures only through the authenticated private task file handoff, and verify
the digest again on the Mac before using `adb install`. Do not copy signing keys or secrets.

If a private task file handoff is unavailable, stop before transferring the APK; do not replace
it with a public repository, release, or workflow artifact. The cloud build output and its digest
remain available for a later approved private transfer.
