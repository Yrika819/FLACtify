# Public APK distribution compliance record

**Purpose:** This document records FLACtify's technical compliance mechanism. It is not a legal opinion or legal certification. Do not treat the APK distribution gate as closed solely because this mechanism or source code exists.

## Candidate and components

- Candidate first clean public binary: FLACtify `2.5.1` (`versionName = "2.5.1"` in `app/build.gradle.kts` at the time this phase began). Intended release tag: `v2.5.1`.
- jaudiotagger: `net.jthink:jaudiotagger:3.0.1`.
- License: GNU Lesser General Public License version 2.1 or (at the recipient's option) any later version (**LGPL-2.1-or-later**).
- Normal FLACtify builds continue to resolve Maven Central's `net.jthink:jaudiotagger:3.0.1`. No jaudiotagger binary JAR is stored in Git.
- Normal build uses the unmodified upstream artifact. The source is not modified by the normal build.

## Selected LGPL mechanism

The selected technical redistribution route is LGPL-2.1 section 6(a)/(d): provide complete corresponding jaudiotagger source, and FLACtify's complete corresponding source/build materials required to rebuild and relink the application with a modified jaudiotagger, at the same GitHub Release/download location as the APK.

The project does **not** rely on replaceable Android shared-library loading or a three-year written offer. `app/build.gradle.kts` accepts `-PflactifyJaudiotaggerJar=/path/to/compatible.jar`; without the property it uses the normal Maven dependency. `scripts/test-jaudiotagger-relink.sh` builds a stock JAR from the complete source, creates a temporary source modification, rebuilds a different JAR, checks normal Gradle resolution remains Maven 3.0.1, verifies the release-validation classpath selects the supplied JAR instead of Maven, and builds the unsigned `releaseValidation` APK. The APK DEX must also contain the deterministic modification marker. The Android toolchain is available locally, but the R8 step did not complete in the attempted run, so the required proof remains unpassed.

There is no FLACtify EULA restricting modification or reverse engineering for debugging modifications made by the recipient to jaudiotagger. FLACtify's Apache-2.0 license applies to FLACtify's own code only.

## jaudiotagger provenance and integrity

Artifacts independently downloaded from Maven Central for `net.jthink:jaudiotagger:3.0.1`:

| Artifact | SHA-256 |
| --- | --- |
| `jaudiotagger-3.0.1.jar` (verification only; not vendored) | `68aa0fe601b05df683a843acf6f8d4793ab476f47da1ac3dab57e0b1886b0aee` |
| `jaudiotagger-3.0.1-sources.jar` | `d9c79a145944e6bc37579403843c9da84cd81459e42c9877351802ee284f5858` |
| `jaudiotagger-3.0.1.pom` | `f2978526656d884f7b91b518c567bcad330eb3541fd9372ebaeb771bed2e5188` |
| Upstream `license.txt` from the `v3.0.1` tag | `76ac9d645569ea2be48e5aea7b6336835a3fd9d4ef8284c7045bd8c59f6a7a39` |
| Complete LGPL-2.1 text (`third_party/licenses/LGPL-2.1.txt`) | See `SHA256SUMS` generated for the release bundle |

The complete corresponding source basis is the canonical upstream source snapshot for tag `v3.0.1`, resolved independently to commit `b885903528c63fa8ecf62ab117f7eebe931b6340`. The exact Bitbucket archive at `https://bitbucket.org/ijabz/jaudiotagger/get/v3.0.1.tar.gz` was downloaded and SHA-256 verified as `f47c10ce8916db315c6e87ac32f2acd285a6a2beba50c833e8b089e7b63b1335`. It includes the complete `src/` tree, package/interface documentation resources, Maven POM (SHA-256 `1839f6cde0bbd718edca1f1b68c0ad0148a3b8a3bf00382df1ed3890ac275014`), README, changelog, and upstream license; no production generated-source step or external compile dependency was found. Building with `scripts/build-jaudiotagger.sh` using `javac --release 8` produced a JAR whose class list matches the Maven Central 3.0.1 binary exactly (stock rebuild hash from one run: `3db681f1e45abaec6bdc5c34001f652a4387014732e5d93a18c96d59fd11b04b`; byte identity is not asserted). In an isolated same-run comparison, a harmless temporary source marker produced a stock JAR hash `4d139a9e8370da1cfb760b4c68cb009876dc83dbd0ece4becef28c962c06804a` and modified JAR hash `b210245c5d50de20b4c6b4341d82b55fdb067c50b9ac08be1c724e38976e86e6`; direct byte comparison confirmed the modified JAR differs. A separately rebuilt modified JAR (`18008280868134b1701889425d7e94fd05c32ddea10725f6fa4029f18893f58b`) was accepted by `:app:verifyJaudiotaggerOverride`. The integrated relink build advanced to R8 minification, but the validation APK did not finish, so no relink APK/DEX marker proof is claimed. The Maven Central source JAR/POM remain supplemental provenance, not the sole corresponding source.

## Rebuild and relink instructions

1. Extract `jaudiotagger-3.0.1-complete-source.tar.gz`.
2. Build a stock JAR from the included upstream source tree with `./build-jaudiotagger.sh ./upstream/src ./jaudiotagger.jar`.
3. For a fresh-extraction relink proof, set `FLACTIFY_JAUDIOTAGGER_SOURCE_ROOT` to the extracted `upstream/src` directory before running `scripts/test-jaudiotagger-relink.sh`; this lets the test use only the distributed jaudiotagger source rather than fetching upstream.
4. Build an unsigned, minified FLACtify validation APK:

   ```sh
   ./gradlew assembleReleaseValidation -PflactifyJaudiotaggerJar=/path/to/modified-jaudiotagger.jar
   ```

5. To produce a production APK with the modified library, use the same property with `assembleRelease` and the production signing identity. Do not distribute a debug-signed APK. Run `apksigner verify` on any signed candidate.
6. The automated positive proof is `scripts/test-jaudiotagger-relink.sh`. It is not satisfied by a normal build that resolves the Maven artifact.

## Toolchain

Use the repository's Gradle Wrapper (Gradle 9.4.1; wrapper distribution checksum is pinned), Android Gradle Plugin 9.2.1, and Kotlin 2.2.10. CI sets up JDK 17, while `gradle/gradle-daemon-jvm.properties` provisions the Gradle daemon on JDK 21; the app compilation toolchain targets Java/Kotlin 11. The jaudiotagger rebuild script invokes the available `javac --release 8` (verified here with JDK 26.0.2.1; it emits Java 8-compatible classes). Android SDK requirements are Platform 36, Build Tools 36.0.0, NDK 28.2.13676358, and CMake 3.31.6. Production signing identity is separately required for a signed public APK and must not be included in source archives or logs.

## Recipient notices and full runtime audit

The inspected debug APK includes jaudiotagger identification/attribution and full LGPL text, Apache-2.0 text and runtime-family notice, plus the Steam Audio license, exact v4.8.1 `THIRDPARTY.md`, CIPIC notice, and HRTF data with their pinned hashes. The Settings Open-source licenses surface includes jaudiotagger/LGPL and Apache-2.0 text. No emulator/device was connected for interactive UI verification.

A complete final runtime dependency graph and final APK contents must be inspected before readiness. The expected inventory is maintained in `docs/RUNTIME_DEPENDENCY_LICENSE_INVENTORY.md`; unresolved items remain release blockers until the release-validation APK and final signed APK are checked. Test-only dependencies are not runtime redistributions and are separately labeled there.

## Exact same-location GitHub Release assets

The public `v2.5.1` Release must offer, from the same release download location:

- `FLACtify-v2.5.1.apk` (production-signed; not debug-signed)
- `FLACtify-v2.5.1-source.tar.gz`
- `jaudiotagger-3.0.1-complete-source.tar.gz`
- `LGPL-COMPLIANCE.md`
- `THIRD_PARTY_NOTICES.md`
- `LGPL-2.1.txt`
- `SHA256SUMS`

Release notes must prominently identify jaudiotagger 3.0.1 under LGPL-2.1-or-later and these corresponding-source/compliance assets. Keep source assets available for at least as long as the APK is available. `scripts/prepare-apk-compliance-bundle.sh v2.5.1 <output-directory>` packages the source and compliance subset from a clean release tag; attach the production APK separately and regenerate `SHA256SUMS` over the final complete asset set before drafting the Release.

## Gate status

**BLOCKED pending evidence.** Android SDK Platform 36, Build Tools 36.0.0, NDK 28.2.13676358, and CMake 3.31.6 were discovered locally and supplied only to individual Gradle processes; no `local.properties` was created. The exact normal runtime graph was captured. `testDebugUnitTest`, full Gradle `test`, `lint`, and `assembleDebug` passed. The debug APK’s required notices/assets and Steam Audio/CIPIC hashes were inspected; debug ZIP alignment and all native ELF 16 KiB alignments passed. Gitleaks, source/config/docs personal-path scanning, credential-filename scanning, shellcheck, and `git diff --check` passed. The modified-source `releaseValidation` build passed the Gradle external-JAR selection task and reached R8 minification, but did not complete within a 20-minute command timeout; therefore no relink APK or DEX marker proof exists. Final signed-APK inspection, complete artifact-by-artifact runtime license audit, fresh-extraction bundle test, CodeQL/Android CI (not run because the branch was not pushed), production signing, and release-asset audit remain outstanding. Do not describe the APK as READY or publish a draft/public Release.
