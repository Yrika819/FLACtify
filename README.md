# FLACtify

FLACtify is an Android music player focused on local FLAC libraries. It supports SAF-based library access, playback through Android Media3, track metadata and tag editing, playback controls, and Last.fm-powered recommendations when configured.

## Current status

Spatial Audio **Studio** is an experimental playback mode. It processes supported decoded stereo PCM through a native engine backed by Steam Audio; device routes and formats can result in an explicit bypass. It is not a claim of universal spatialization or validated production audio quality. See the technical notes under [`docs/audio/`](docs/audio/) and third-party terms in [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

**Public APK distribution is not currently provided.** APK publication remains blocked pending completion of LGPL binary-distribution compliance; see [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

## Requirements

- JDK 17 or another version supported by the pinned Android Gradle Plugin (the app's Java/Kotlin bytecode target is Java 11)
- Android SDK Platform 36 and Android build tools
- Android NDK and CMake 3.31.6
- Network access during the build to retrieve the SHA-256-pinned Steam Audio 4.8.1 SDK, license, and CIPIC asset
- Gradle Wrapper (Gradle 9.4.1; checksum pinned in the wrapper properties)

The build stages Steam Audio at build time; do not commit downloaded SDK archives or generated binaries. Android app library access uses the Storage Access Framework (SAF), so the user selects a library location through Android's document picker rather than granting broad filesystem access.

## Build and test

From the repository root:

```sh
./gradlew testDebugUnitTest
./gradlew lint
./gradlew assembleDebug
```

The native build requires the Android SDK, NDK, and CMake above. Instrumentation tests require a compatible connected Android device or emulator. Test audio is synthetic; see [`app/src/test/resources/audio/README.md`](app/src/test/resources/audio/README.md) and `scripts/generate-test-audio.sh`.

### Existing validation evidence

GitHub Actions successfully validated the current public `main` revision (`1bcc377a0edd3b4735932aa9a4f2494aa387b169`) in [run 37655097194](https://github.com/Yrika819/FLACtify/actions/runs/37655097194). The run passed JVM unit tests, the full Gradle test lifecycle, Android lint, the Debug build, the unsigned minified Release/R8 validation variant, and both APK ZIP and native ELF 16 KiB alignment checks.

The Release/R8 result is from the unsigned `releaseValidation` variant; production-signed APK validation is not claimed. This validation does not make APK distribution available. Public APK distribution remains blocked pending LGPL binary-distribution compliance.

## Last.fm

Last.fm recommendations are optional. Set `LASTFM_API_KEY` in `local.properties` or in the build environment; if omitted, the app builds without the integration key and recommendations report that the key is not configured. Do not commit real credentials. Any key included in an Android APK is extractable client configuration, **not a secret**.

## Release signing

Production release signing is configured using local-only values, either in ignored `local.properties` or environment variables:

```properties
FLACTIFY_STORE_FILE=/path/to/release.keystore
FLACTIFY_STORE_PASSWORD=replace-locally
FLACTIFY_KEY_ALIAS=replace-locally
FLACTIFY_KEY_PASSWORD=replace-locally
```

Equivalent environment variables are `FLACTIFY_STORE_FILE`, `FLACTIFY_STORE_PASSWORD`, `FLACTIFY_KEY_ALIAS`, and `FLACTIFY_KEY_PASSWORD`. Never put real values or keystore files in source control. CI does not need a production signing key.

## Licensing and notices

FLACtify's own source is licensed under Apache-2.0. Third-party dependencies and bundled build inputs retain their own terms; consult [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md), [`third_party/licenses/LGPL-2.1.txt`](third_party/licenses/LGPL-2.1.txt), and [`docs/PUBLIC_SOURCE_PROVENANCE_AUDIT.md`](docs/PUBLIC_SOURCE_PROVENANCE_AUDIT.md).
