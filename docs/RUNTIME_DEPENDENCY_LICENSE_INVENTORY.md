# Runtime dependency and bundled-material license inventory

This is the recipient-facing technical inventory for the currently resolved `releaseRuntimeClasspath`; it is not a legal opinion or legal certification. The exact Gradle resolution output from this checkout is preserved in [`RELEASE_RUNTIME_DEPENDENCIES.txt`](RELEASE_RUNTIME_DEPENDENCIES.txt). That report resolves the normal build to `net.jthink:jaudiotagger:3.0.1`.

The unsigned debug APK was built and inspected directly. It contains the jaudiotagger notice and full LGPL text, Apache-2.0 text and runtime-family notice, the Steam Audio license and exact v4.8.1 `THIRDPARTY.md`, CIPIC attribution, and the SHA-256-pinned subject 124 HRTF. Those checks do not substitute for inspection of the `releaseValidation` and final signed Release APKs.

## Runtime redistribution components

| Component / resolved version | License / required notice | Source and recipient provision | Current evidence/status |
| --- | --- | --- | --- |
| Kotlin runtime `2.2.10` and packaged Kotlin transitives | Apache-2.0 | Full Apache text in APK asset `licenses/Apache-2.0.txt`; component families listed in `licenses/RUNTIME-DEPENDENCIES-NOTICE.txt` and Settings | Resolved graph recorded; Apache text and notice hash verified in debug APK; verify final release APK |
| AndroidX Core, Activity, Lifecycle, DocumentFile, SavedState, AppCompat support and transitives (versions in resolved graph) | Apache-2.0 | Same Apache license and runtime-family notice in APK; exact resolved graph attached to source | Resolved graph recorded; Apache text and notice hash verified in debug APK; verify final release APK |
| Compose UI/Foundation/Runtime/Material/Material3 (BOM `2024.12.01`, resolved artifacts primarily `1.7.6`; Material3 `1.3.1`) | Apache-2.0 | Same Apache license and runtime-family notice in APK | Resolved graph recorded; Apache text and notice hash verified in debug APK; verify final release APK |
| Media3 ExoPlayer, Session, Common, Container, Extractor, Decoder and DataSource-OkHttp `1.11.1` | Apache-2.0 | Same Apache license and runtime-family notice in APK | Resolved graph recorded; Apache text and notice hash verified in debug APK; verify final release APK |
| Coil Compose, Compose Base, Coil Base `2.6.0` | Apache-2.0 | Same Apache license and runtime-family notice in APK | Resolved graph recorded; Apache text and notice hash verified in debug APK; verify final release APK |
| OkHttp `4.12.0`, Okio `3.8.0` (with graph constraints also listing `3.6.0`) | Apache-2.0 | Same Apache license and runtime-family notice in APK | Resolved graph recorded; Apache text and notice hash verified in debug APK; verify final release APK |
| Accompanist DrawablePainter `0.32.0`, Guava `33.3.1-android`, FailureAccess `1.0.2`, JetBrains annotations and other transitive nodes | Apache-2.0 per resolved artifact metadata | Same Apache license and runtime-family notice in APK; verify any artifact-specific NOTICE metadata before release | Resolved graph recorded; Apache text present in debug APK; artifact-specific NOTICE and final Release APK audit remain open |
| `net.jthink:jaudiotagger:3.0.1` | LGPL-2.1-or-later; Paul Taylor attribution | Full `licenses/LGPL-2.1.txt`, `licenses/jaudiotagger-3.0.1-NOTICE.txt`, Settings license viewer, pinned complete upstream source package and modified-JAR override procedure | Present in debug APK; complete upstream source compiles with Java 8 target; releaseValidation relink/APK proof timed out during R8 and remains unresolved |
| Steam Audio SDK `4.8.1` (`libphonon.so`, packaged ABIs) | Apache-2.0 plus bundled third-party terms | Full `licenses/steam-audio-4.8.1-LICENSE.md` and the exact upstream `licenses/steam-audio-4.8.1-THIRDPARTY.md` | Debug APK inspected; both hashes match pinned inputs; final Release APK still needs inspection |
| Steam Audio bundled third-party components | Per-component terms in the exact upstream `THIRDPARTY.md` (including Intel IPP, FFTS, PFFFT, MySOFA, Embree, RadeonRays, TrueAudio Next and Google Spherical Harmonics) | Exact unshortened upstream notice is in the APK and same-release source/notices package | Debug APK hash verified; confirm final package retains it |
| CIPIC subject 124 HRTF `cipic_124.sofa` | CIPIC attribution/terms reproduced in the Steam Audio third-party notice and project notice | `licenses/CIPIC-124-HRTF-NOTICE.md`, full Steam Audio third-party notice, and exact HRTF data asset | Debug APK asset/notice inspected and hash verified; no public-domain claim |

The direct dependency declarations are in `app/build.gradle.kts`; exact resolved coordinates and transitive versions are in `RELEASE_RUNTIME_DEPENDENCIES.txt`. License statements above describe the expected upstream terms for these families and still require comparison against each artifact's actual license/NOTICE files; a Maven POM field alone is not treated as sufficient evidence for bundled content.

## Build-only and test-only dependencies

Gradle/Android Gradle Plugin, Kotlin Gradle plugin, CMake/NDK, SDK tooling, and Steam Audio staging/download logic are build-time tools and are not Android runtime redistribution dependencies. JUnit 4.13.2, kotlinx-coroutines-test 1.7.3, MockK 1.13.8 and its agent, AndroidX Arch Core testing 2.2.0, AndroidX Test JUnit 1.1.5, Espresso 3.5.1, Compose UI test artifacts, and debug-only Compose tooling/test manifest are test/debug-scoped, not Release runtime dependencies. Confirmed from the Gradle configuration scopes; final Release APK check is still required.

## Gate completion evidence required

Before reporting `UNRESOLVED RUNTIME LICENSE BLOCKERS = 0`:

1. Review the complete captured resolved runtime graph and each actual resolved artifact's license and any artifact-specific NOTICE file, not only POM metadata.
2. Inspect a successful `releaseValidation` APK and the final signed Release APK (DEX, `lib/`, assets, and metadata), reconcile each material packaged component, and verify no test-only dependency is included.
3. Confirm Apache/BSD/MIT and other required notices are retained in the actual APK and release assets.
4. Revalidate the Steam Audio, third-party notice, CIPIC notice and HRTF hashes in the APK.
5. Record resolution of any GPL/AGPL or other copyleft dependency and verify jaudiotagger modified-library relinking into the APK.

**Status: NOT VERIFIED.** The exact Gradle runtime graph and an unsigned debug APK were inspected, but the releaseValidation R8 build did not finish and no final signed APK is available. No claim of zero unresolved runtime license blockers is made.
