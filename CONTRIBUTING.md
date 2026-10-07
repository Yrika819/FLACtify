# Contributing

Thanks for helping improve FLACtify. Keep pull requests focused and explain the user-visible behavior or bug being addressed.

## Setup and checks

Install a compatible JDK, Android SDK Platform 36, Android NDK, and CMake 3.31.6; the build downloads pinned Steam Audio inputs and therefore needs network access. Then run relevant checks from the repository root:

```sh
./gradlew testDebugUnitTest
./gradlew lint
./gradlew assembleDebug
```

Add or update tests for behavior changes. Device-specific playback changes should include instrumentation coverage where practical. Never claim checks passed unless they were run.

## Fixtures and credentials

Use synthetic test fixtures only. Do not add copyrighted recordings, commercial test media, lyrics, artwork, or identifying metadata. The fixture generator and provenance are documented at `app/src/test/resources/audio/README.md`.

Do not commit API keys, passwords, signing material, keystores, `local.properties`, or other credentials. Configure personal development values locally using ignored files or environment variables.

## Pull requests

Keep each PR scoped, describe testing performed and any limitations, and preserve third-party notices and license obligations. Do not attach or publish APK/AAB binaries; public binary distribution is blocked pending separate LGPL compliance review.
