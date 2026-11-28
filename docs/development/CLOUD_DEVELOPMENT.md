# Cloud Development

Status: Phase 6B-P0 migration preparation

Baseline: `eb2aad233e7dfe7c0fd0e567967ec9d854c1cae0` (2026-11-27)

This document describes the Codex Cloud build environment and the Owner-approved private
handoff for personal-use Release candidates. It does not authorize product changes or public
binary distribution.

## Scope

In scope:

- Linux toolchain bootstrap for the existing Gradle/Android build;
- pinned JDK, Android SDK, NDK, CMake, and Steam Audio inputs;
- network, cache, and test-boundary guidance;
- a first validation sequence that does not publish artifacts.

Out of scope:

- product behavior changes;
- APK/AAB signing in Cloud or general-public publication;
- emulator/device provisioning;
- adding secrets, signing keys, or personal data to the repository;
- replacing GitHub Actions as the authoritative merge gate.

## Host requirements

| Item | Requirement |
| --- | --- |
| OS | Linux, x86_64 or aarch64. Ubuntu 22.04/24.04 LTS is the expected image. |
| CPU/RAM/disk | Workload guidance only; see the resource policy below. Host-reported values are not an allocation guarantee. |
| Shell | Bash 4+ and standard coreutils. |
| Python | `python3` for the 16 KiB verification script. |
| Git | Required; the setup script verifies the canonical repository role. |

These are engineering recommendations, not a Codex Cloud resource specification. OpenAI's
Plus plan documentation describes Codex usage allowances and credits, but does not promise
a fixed number of CPU cores, RAM, or disk capacity for a cloud task. The standard cloud
environment has no separate VM charge; model use counts toward the plan's Codex allowance.
Check the account usage page and any displayed limits before scheduling expensive work.
Do not treat `nproc`, `free`, or `df` output as guaranteed capacity or use it to justify
parallel build settings. The initial setup can take 10-20 minutes on a fast network; setup
script time limits have not been measured here.

## Toolchain matrix

| Component | Version | Why |
| --- | --- | --- |
| Gradle | 9.4.1 | Wrapper version; SHA-256 pinned in `gradle-wrapper.properties`. |
| Android Gradle Plugin | 9.2.1 | Existing build plugin. |
| Kotlin | 2.2.10 | Existing Kotlin/Compose build. |
| Launcher JDK | Temurin 17.0.21.1+2 | Matches the existing GitHub Actions setup. |
| Daemon JDK | Temurin 21.0.13.1+2 | Required by `gradle/gradle-daemon-jvm.properties`. |
| Kotlin toolchain | Temurin 11.0.33.1+1 | Required by `jvmToolchain(11)` and Java 11 bytecode. |
| Android platform | `android-36` | `compileSdk 36`. |
| Build tools | `36.0.0` | Existing CI and 16 KiB alignment checks. |
| NDK | `28.2.13676358` | Existing CI native build. |
| CMake | `3.31.6` | Existing CI and app CMake version. |
| Steam Audio | 4.8.1 | Existing spatial engine dependency. |

`app/build.gradle.kts` does not set `ndkVersion`; it relies on the AGP default. The existing
CI installs NDK `28.2.13676358`, which matches the current local build environment. If AGP
changes its default, the build may require an explicit NDK pin in a separate reviewed change.

The app uses `compileSdk 36`, `targetSdk 36`, and `minSdk 26` at this baseline. D-007 permits
an SDK update but does not authorize a `targetSdk` change.

### Dependency sources

| Source | Used for |
| --- | --- |
| Google Maven (`dl.google.com`) | AGP, AndroidX, Compose, Media3. |
| Maven Central | Kotlin/coroutines, JUnit, MockK, Coil, OkHttp, `net.jthink:jaudiotagger:3.0.1`. |
| Gradle Plugin Portal | Kotlin and Compose compiler plugins. |
| GitHub release assets | Temurin JDKs and Steam Audio. |
| Android SDK repository | platform, build-tools, NDK, CMake, command-line tools. |

`jaudiotagger` is a normal Maven dependency in the default build. The LGPL relink workflow
can substitute a compatible jar through `-PflactifyJaudiotaggerJar=...`; that is a separate
compliance exercise, not part of this setup. Do not commit a substituted jar or its
build outputs.

## Existing CI contract

`.github/workflows/android.yml` is the reference job. It runs on `ubuntu-latest`, uses
Temurin 17 as the launcher, installs `platforms;android-36`, `build-tools;36.0.0`,
`ndk;28.2.13676358`, and `cmake;3.31.6`, then runs:

- `./gradlew --version` and `./gradlew tasks --all`;
- `testDebugUnitTest`, `test`, and `lint`;
- `assembleDebug` and `assembleReleaseValidation`;
- `zipalign -c -P 16 4` and `scripts/verify-16k-apk.py` on the debug APK.

The job has a 60-minute timeout. CodeQL and the LGPL relink/compliance workflows are
separate GitHub Actions gates; they are not replaced by this setup script or by a local
cloud run.

## Setup script

`scripts/setup-codex-cloud.sh` is the supported bootstrap. It is Linux-only, idempotent, and
does not run a Gradle build by default.

```sh
./scripts/setup-codex-cloud.sh
```

The script:

1. checks that the checkout is the canonical public-development repository;
2. verifies the Gradle wrapper version and its SHA-256 pin;
3. installs or reuses Temurin JDK 11, 17, and 21;
4. bootstraps Android command-line tools if needed and installs platform 36,
   build-tools 36.0.0, NDK 28.2.13676358, and CMake 3.31.6;
5. pre-seeds the SHA-256-pinned Steam Audio zip, license, and CIPIC SOFA file into
   `app/build/downloads`;
6. writes an environment file at the path printed at the end of the run.

Useful options:

| Option | Effect |
| --- | --- |
| `--check` | Verify only; no installs or downloads. |
| `--offline` | Never download; fail if a pinned input is missing. |
| `--sdk-root PATH` | Select the Android SDK root. |
| `--toolchain-root PATH` | Select the JDK install root. |
| `--steam-audio-archive PATH` | Use a local Steam Audio 4.8.1 zip after verification. |
| `--skip-jdk` | Use the existing `JAVA_HOME`; the caller must provide compatible JDKs. |
| `--skip-sdk` | Do not install or verify Android SDK packages. |
| `--skip-steam-audio` | Do not stage Steam Audio inputs. |
| `--warm-gradle` | Run the wrapper version check and `./gradlew help` after setup. Cannot be combined with `--check` or `--offline`. |

After setup, either source the generated environment file in the current shell or configure
the equivalent values in the Codex Cloud environment. The file sets `JAVA_HOME`, `PATH`,
`ANDROID_HOME`, `ANDROID_SDK_ROOT`, and the Gradle Java installation paths.

The script creates an ignored `local.properties` with `sdk.dir` when one is absent. It does
not overwrite a conflicting existing file; pass a matching `--sdk-root` or resolve the
conflict explicitly.

## Steam Audio and HRTF inputs

`app/build.gradle.kts` stages Steam Audio at build time. It downloads:

| Input | Source | SHA-256 |
| --- | --- | --- |
| SDK zip | `steamaudio_4.8.1.zip` release asset | `4a0aa5ec1176f38f0b0993a37c2259d9e86f27e22d5e24f83ec4c3cb9a1d5449` |
| License | upstream `LICENSE.md` | `cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30` |
| CIPIC 124 HRTF | upstream `cipic_124.sofa` | `c28ff4a874ac889ec0c5885ca524762a70d56984232ff7aadcd9c15d32e1cfb6` |

The setup script caches verified copies outside the build directory and stages them into
`app/build/downloads`. A `./gradlew clean` removes the staged files but not the cache; rerun
the setup script to restore them without downloading again.

`FLACTIFY_STEAMAUDIO_SDK_ZIP` and `--steam-audio-archive` accept a local SDK zip. The zip is
verified before use. The license and SOFA file still come from the pinned upstream URLs
unless they are already in the setup cache.

Do not commit the SDK archive, generated `jniLibs`, HRTF binary, or extracted headers.

## Gradle caches

| Cache | Default location | Notes |
| --- | --- | --- |
| Gradle user home | `$HOME/.gradle` | Expected to be reused by Codex Cloud environment caching. |
| Wrapper distribution | `$GRADLE_USER_HOME/wrapper/dists` | SHA-256 verified by the wrapper. |
| Maven modules | `$GRADLE_USER_HOME/caches/modules-2` | Google Maven, Maven Central, plugin portal. |
| Kotlin/transform caches | `$GRADLE_USER_HOME/caches` | Safe to retain. |
| Android SDK | `$ANDROID_HOME` or the selected SDK root | Contains platform, build-tools, NDK, CMake. |
| Steam Audio setup cache | `$XDG_CACHE_HOME/flactify` or `$HOME/.cache/flactify` | Verified SDK/license/SOFA copies. |
| Build outputs | `app/build` | Disposable; `clean` removes staged Steam Audio. |

### Read-only HOME and reusable tasks

Codex Cloud gives each task an isolated working directory. A published environment supplies
its configured variables and setup to new tasks; it does not carry another task's uncommitted
workspace changes. OpenAI documents saved VM state as recoverable for up to 7 days after the
last task resume, so cache persistence beyond that window is not guaranteed.

The published FLACtify environment explicitly places all writable state outside HOME:
`FLACTIFY_TOOLCHAIN_ROOT`, `ANDROID_HOME`/`ANDROID_SDK_ROOT`, `GRADLE_USER_HOME`,
`ANDROID_USER_HOME`, and `FLACTIFY_CACHE_ROOT` point into `/workspace`. Keep these bindings
in the published environment. The setup script uses `FLACTIFY_CACHE_ROOT` and
`FLACTIFY_TOOLCHAIN_ROOT` when present and writes its environment file under the toolchain
root, so it does not need to write under HOME. If these variables are absent, its fallback
cache and toolchain locations use HOME and may fail on a read-only HOME.

The Android SDK must be writable when setup needs to install packages or accept licenses.
This environment points it to a writable `/workspace` path. A read-only SDK is suitable
only for builds that need no SDK mutation; `--check` is verify-only. Do not copy the SDK or
reinstall packages when the configured SDK already contains the required versions.

Before setup on a new task, confirm that the published paths resolve and are writable with
`test -w` (without attempting a write probe), then run `--check`. Keep Gradle, Android, and
Steam Audio caches at their configured paths when those paths are retained by the task
environment. Do not cache signing material or another machine's `local.properties`.

## Network access

Codex Cloud can either enable all public internet access or use the package-manager preset
plus additional domains. A restricted allowlist is recommended. The following destinations
cover the pinned setup and Gradle build:

| Destination | Purpose |
| --- | --- |
| `services.gradle.org`, `downloads.gradle.org` | Gradle distribution and wrapper. |
| `repo.maven.apache.org`, `repo1.maven.org` | Maven Central artifacts. |
| `plugins.gradle.org`, `plugins-artifacts.gradle.org` | Gradle Plugin Portal. |
| `dl.google.com` | Google Maven and Android SDK packages. |
| `github.com`, `release-assets.githubusercontent.com`, `objects.githubusercontent.com` | Steam Audio release asset and JDK releases. |
| `raw.githubusercontent.com` | Steam Audio license and CIPIC SOFA. |
| `api.adoptium.net` | Optional Temurin metadata lookup; the script downloads releases from `github.com`. |
| `api.foojay.io` | Only if Gradle auto-provisions a missing JDK. |
| `codeload.github.com` | Only if a task downloads a repository archive. |

The package-manager preset already includes the Gradle, Maven Central, plugin portal,
GitHub, and `dl.google.com` destinations. Add `raw.githubusercontent.com`,
`api.adoptium.net`, and `objects.githubusercontent.com` explicitly if the preset does not
cover them. Add `api.foojay.io` only if the environment relies on Gradle JDK auto-provisioning.

Last.fm (`ws.audioscrobbler.com`) is a runtime feature, not a build dependency. Do not add a
real Last.fm key to the repository. Any key embedded in an APK is extractable client
configuration, not a secret.

## Mac-specific dependencies

The committed build has no required macOS path. These are local-only and must not be copied
to the cloud:

- `local.properties` (`sdk.dir`); the setup script creates an ignored file when needed;
- `FLACTIFY_STORE_FILE`, `FLACTIFY_STORE_PASSWORD`, `FLACTIFY_KEY_ALIAS`, and
  `FLACTIFY_KEY_PASSWORD` (production signing);
- a real `LASTFM_API_KEY`;
- local SDK/JDK paths and Android Studio settings;
- existing worktrees, build outputs, and archives.

`gradle/gradle-daemon-jvm.properties` contains vendor URLs for several operating systems.
Gradle selects the Linux entry; this is not a macOS runtime dependency.

## Cloud-runnable validation

These commands do not need a connected Android device:

```sh
./scripts/setup-codex-cloud.sh --check
./gradlew --version
./gradlew tasks --all
./gradlew testDebugUnitTest
./gradlew test
./gradlew lint
./gradlew assembleDebug
./gradlew assembleReleaseValidation
./gradlew compileDebugAndroidTestKotlin
```

`compileDebugAndroidTestKotlin` is an additional compile check for instrumentation
sources; it is not part of the current `android.yml` job. A successful compile does not run
the tests.

16 KiB checks after the debug build:

```sh
apk=app/build/outputs/apk/debug/app-debug.apk
"$ANDROID_HOME/build-tools/36.0.0/zipalign" -c -P 16 4 "$apk"
python3 scripts/verify-16k-apk.py "$apk"
```

`assembleReleaseValidation` is an unsigned minified variant for R8 validation. It is not a
production-signed release and must not be published.

## Device-only validation

The following remain device-only and must not be reported as cloud PASS:

- every `app/src/androidTest` test, including playback, shuffle, auto-advance, spatial
  audio, Media3 boundary, performance, and recovery tests;
- notification and `MediaSession` behavior;
- SAF folder selection and persisted URI access;
- Bluetooth/audio-route changes;
- real-media playback, artwork, lyrics, and tag editing;
- rotation, IME, TalkBack, font scale, and last-item visibility checks;
- frame-time, jank, and sustained-playback measurements;
- installation and 16 KiB behavior on a physical device.

Some instrumentation tests may compile in the cloud but cannot run there. Compilation is not
execution.

## Standard personal Release handoff

Use this flow only for an Owner-authorized personal-use Release. It keeps signing material on
the Mac and retains a Git commit for the Release source.

1. Create a Release candidate branch from the current `main`, set the requested application
   ID, `versionName`, and a `versionCode` greater than the installed version, then run the
   required PR CI. Do not create a public Release or upload an APK to a public location.
2. In Codex Cloud, build the production-ID Release APK unsigned:

   ```sh
   ./gradlew -PflactifyUnsignedRelease=true :app:assembleRelease
   ```

   The explicit property clears only the Release signing configuration for this invocation.
   R8 minification and resource shrinking remain enabled. Do not bind, copy, or print signing
   keys or passwords in Cloud.
3. Record the source commit and verify APK package/version with `aapt dump badging`, ZIP
   alignment with `zipalign -c -P 16 4`, and native ELF alignment with
   `scripts/verify-16k-apk.py`. Record the APK SHA-256 and byte size. Inspect required license
   notices in the APK and retain the applicable LGPL corresponding-source materials.
4. Transfer the unsigned APK only to the existing owner-only Google Drive location. Check
   that sharing is disabled and owner-only access is present both before and after upload.
   Never put a keystore or password in Drive.
5. On the Mac, download the APK, align it with Build Tools 36.0.0, and sign it with the
   existing local production key using `apksigner`. Verify the signed APK certificate,
   application ID, version, alignment, and SHA-256. The signed APK's non-signature ZIP
   payload should match the Cloud-built unsigned artifact.
6. Keep the signed APK private. Install it only after explicit Owner approval and only when
   its signing certificate matches the currently installed app; never uninstall or clear
   app data to work around a signature mismatch.

For Release 2.5.2, the Cloud source build was commit
`26b26fec6776237346b513d36ad32fc1e3ce58a6` (`com.flactify`, versionName `2.5.2`,
versionCode `17`). The Owner-provided signed APK SHA-256 is
`aca14135a9d40a9ac780f24e9792e4e4ac00e7b3a13e6a08e4beb2b7b8d9f3eb`. The signed payload
was verified against the unsigned Cloud artifact, and the Owner reports successful Pixel 7a
in-place update. This was a private personal release, not a public distribution.

## Cloud validation record

Initial Codex Cloud validation was completed on 2026-11-27 from PR #20 head
`1ebf805ea333dece5b41e561e3d267e2c60e4773` (`infra/codex-cloud-readiness`). The task loaded
the published environment configuration. Results:

- `setup-codex-cloud.sh --check`: PASS; verified JDK 11/17/21, Android SDK packages,
  Gradle wrapper 9.4.1 and staged Steam Audio 4.8.1 without installing or downloading.
- `gradlew --version`: PASS; Gradle 9.4.1, launcher JDK 17.0.21.1, daemon requirement JDK 21.
- Published path configuration: all seven required variables reported ready. HOME was
  read-only; toolchain, SDK, Gradle, Android user, and Steam Audio cache roots resolved under
  `/workspace` and were writable. Required SDK package directories and staged Steam Audio
  files were present; the Gradle user home occupied 1.6 GiB.
- The environment reported 3 CPUs, 9.7 GiB RAM, and 60 GiB free disk. These are observations
  only and are not interpreted as Plus plan allocation or build capacity guarantees.
- The 366 unit tests already accepted for this phase were not rerun. No APK/AAB was built
  or published.

The published environment configuration was loaded successfully for this newly provisioned
task, which verifies setup reuse from the published configuration. This does not establish
that VM caches persist indefinitely; OpenAI's documented task isolation and VM recovery
window above apply. The full Android CI is not rerun in this task; use the PR's GitHub Actions
checks for Android and CodeQL status. A missing device run stays UNVERIFIED.

## Troubleshooting

| Symptom | Action |
| --- | --- |
| `SDK location not found` | Set `ANDROID_HOME` or pass `--sdk-root`; the script creates `local.properties`. |
| `No matching toolchains` for Java 11 | Confirm JDK 11 is installed and listed in the generated environment file. |
| Gradle daemon JVM error | Confirm JDK 21 is installed; the daemon criteria requires Java 21. |
| Steam Audio SHA-256 mismatch | Delete the staged/cached copy, re-check the upstream pin, and rerun. Do not bypass verification. |
| Android license failure | Rerun the script as root or run `sdkmanager --licenses` with the selected SDK root. |
| Network 403/timeout | Add the destination to the environment allowlist; test with `curl -I`. |
| CMake 3.31.6 missing | Install `cmake;3.31.6` through the selected SDK manager. |
| NDK mismatch | Install `ndk;28.2.13676358`; do not silently change the build pin. |
| Repository role check fails | Stop. The checkout is not the canonical public-development repository. |

## Security

- The setup script downloads only pinned URLs and verifies SHA-256 for Gradle inputs and
  Steam Audio. The Android command-line tools archive is verified against the SHA-1 published
  in Google's SDK repository because that artifact does not publish a SHA-256 there.
- No signing keys, keystores, tokens, personal paths, or device identifiers are required.
- Use the Codex personal vault only if a future task needs a credential. Never put a secret in
  the repository or a setup script.
- Do not disable TLS verification or add an unverified download fallback.

## Known limitations

At the time of this document, only the verify-only setup check and Gradle version command
have been run in Codex Cloud. Cloud unit tests, lint, R8, and 16 KiB build validation were
not rerun in this task; see the GitHub Actions checks on PR #20 for CI evidence. The active
task loaded the published setup successfully, but cache persistence past the documented VM
recovery window is not guaranteed. All device-only scenarios remain UNVERIFIED.
