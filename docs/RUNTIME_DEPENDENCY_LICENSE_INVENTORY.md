# Runtime dependency and bundled-material license inventory

This is the recipient-facing technical inventory for the resolved `releaseRuntimeClasspath`. It is not a legal opinion or legal certification. The exact Gradle resolution output from this checkout is preserved in [`RELEASE_RUNTIME_DEPENDENCIES.txt`](RELEASE_RUNTIME_DEPENDENCIES.txt), which resolves the normal build to `net.jthink:jaudiotagger:3.0.1`.

This inventory is a **classification of every resolved runtime module** against its own declared license metadata, cross-checked against the actual artifact contents. It is deliberately kept separate from **release evidence gates**: the classification below is complete, while the relink proof and the final signed-APK inspection are execution steps that gate publication rather than open license questions.

## How the classification was performed

1. Parsed `RELEASE_RUNTIME_DEPENDENCIES.txt`, following every `-> version` resolution so that only **finally selected** versions are counted. This yields **114 resolved runtime modules**.
2. Located the POM for each of the 114 modules and read its `<licenses>` element. Where the element was absent, the parent POM chain was resolved against Maven Central.
3. Opened each resolved runtime JAR/AAR (excluding `-sources` and `-javadoc`) and searched for `META-INF/NOTICE*` and any embedded `LICENSE`/`COPYING`/`NOTICE` file, to test the Apache-2.0 section 4(d) notice obligation against real artifact content rather than POM metadata.
4. Counted classes per artifact to separate **materially redistributed** code from variant aliases, BOM-only modules, and empty compatibility shims.
5. Inspected the Steam Audio SDK archive and classified each bundled third-party component from the exact upstream `THIRDPARTY.md`.

## Result

| Classification | Modules | Detail |
| --- | --- | --- |
| Apache-2.0 (declared in the module's own POM) | 110 | 8 name it "The Apache License, Version 2.0", 102 "The Apache Software License, Version 2.0" |
| Apache-2.0 (inherited from `guava-parent`) | 3 | `com.google.guava:guava:33.3.1-android`, `com.google.guava:failureaccess:1.0.2`, `com.google.guava:listenablefuture:9999.0-empty-to-avoid-conflict-with-guava` |
| LGPL-2.1-or-later | 1 | `net.jthink:jaudiotagger:3.0.1` |
| MPL-2.0 (embedded data component of an Apache-2.0 module) | 1 data file | Public Suffix List data inside `com.squareup.okhttp3:okhttp:4.12.0` |
| GPL / AGPL / any other copyleft | 0 | — |
| Unclassified / unknown license | 0 | — |

**UNRESOLVED RUNTIME LICENSE BLOCKERS = 0.**

Two of those results required going past the POM `<licenses>` field, and both are called out below because a POM-only audit would have gotten them wrong in opposite directions: one embedded MPL-2.0 data component would have been missed entirely, and one `failureaccess` javadoc artifact would have been misreported as GPL.

### Notable findings from the audit

- **OkHttp embeds MPL-2.0 Public Suffix List data.** `okhttp-4.12.0.jar` ships `okhttp3/internal/publicsuffix/publicsuffixes.gz`, compiled from [The Public Suffix List](https://publicsuffix.org/list/public_suffix_list.dat), together with `okhttp3/internal/publicsuffix/NOTICE` stating that the data "is subject to the terms of the Mozilla Public License, v. 2.0". That data is redistributed in the APK, so the MPL-2.0 section 3.2 conditions for executable-form distribution apply: inform recipients that it is governed by MPL-2.0, give them a copy of the license, and point to the corresponding source. **Remediation in place:** the APK carries `licenses/MPL-2.0.txt` (SHA-256 `3f3d9e00...`) and `licenses/PUBLIC-SUFFIX-LIST-NOTICE.txt` with the attribution and the corresponding-source pointer. FLACtify does not modify the list. MPL-2.0 is file-level copyleft, so it places no obligation on FLACtify's own code or on any other library in the APK. Treating OkHttp as plain "Apache-2.0" without this would have been inaccurate.
- **`failureaccess-1.0.2-javadoc.jar` contains `legal/LICENSE` (GPLv2) and `legal/ADDITIONAL_LICENSE_INFO` (Oracle Classpath Exception clarifications).** These are historical Guava-repository files that appear **only in the javadoc artifact**, which is never a runtime dependency and is not redistributed. The redistributed `failureaccess-1.0.2.jar` contains 2 classes and no license file, and inherits Apache-2.0 from `guava-parent`. A scan that included documentation artifacts would wrongly flag a GPL copyleft dependency here; this audit excludes them by design.
- **No artifact ships an Apache `NOTICE` file.** Zero of the 114 resolved modules contain a `META-INF/NOTICE`, so the Apache-2.0 section 4(d) "if the Work includes a NOTICE file" condition is never triggered. Section 4(a) is satisfied by shipping the full Apache-2.0 text as `assets/licenses/Apache-2.0.txt`.
- **`guava`, `failureaccess`, and `listenablefuture` POMs carry no `<licenses>` element.** Their license was resolved from the `guava-parent` POMs on Maven Central (`33.3.1-android` and `26.0-android`), both of which declare Apache License 2.0. This is recorded because POM-field absence alone would otherwise be an unverified classification.
- **`listenablefuture:9999.0-empty-to-avoid-conflict-with-guava` is an empty placeholder** with zero classes. It is a dependency-resolution sentinel only and is not materially redistributed.

## Runtime redistribution components

| Component / resolved version | License / required notice | Source and recipient provision | Compliance mechanism |
| --- | --- | --- | --- |
| Kotlin runtime `2.2.10` plus packaged Kotlin transitives (`kotlin-stdlib-jdk7:1.9.0`, `kotlin-stdlib-jdk8:1.9.0`, `org.jetbrains:annotations:23.0.0`, `kotlin-stdlib-common:2.2.10`) | Apache-2.0 | `assets/licenses/Apache-2.0.txt`; families listed in `assets/licenses/RUNTIME-DEPENDENCIES-NOTICE.txt` and in Settings | Apache-2.0 section 4(a) |
| AndroidX Core `1.13.1`, Activity `1.8.2`, Lifecycle `2.8.7`, AppCompat resources `1.6.1`, DocumentFile `1.0.1`, SavedState `1.2.1`, Arch Core `2.2.0`, Concurrency, Emoji2, ExifInterface, ProfileInstaller, Startup, Tracing, VectorDrawable, Media `1.7.0` and transitives | Apache-2.0 | Same Apache text and runtime notice in the APK; exact resolved graph attached to the source bundle | Apache-2.0 section 4(a) |
| Compose UI/Foundation/Runtime/Material/Material3 (BOM `2024.12.01`, resolved `1.7.6`; Material3 `1.3.1`) | Apache-2.0 | Same Apache text and runtime notice | Apache-2.0 section 4(a) |
| Media3 `1.11.1` (ExoPlayer, Session, Common, Container, Database, DataSource, DataSource-OkHttp, Decoder, Extractor) | Apache-2.0 | Same Apache text and runtime notice | Apache-2.0 section 4(a) |
| Coil `2.6.0` (`coil`, `coil-base`, `coil-compose`, `coil-compose-base`) | Apache-2.0 | Same Apache text and runtime notice | Apache-2.0 section 4(a) |
| OkHttp `4.12.0` and Okio `3.8.0` (`okio-jvm`) | OkHttp/Okio: Apache-2.0; **embedded Public Suffix List data: MPL-2.0** | `assets/licenses/Apache-2.0.txt`, plus `assets/licenses/MPL-2.0.txt` and `assets/licenses/PUBLIC-SUFFIX-LIST-NOTICE.txt` | Apache-2.0 section 4(a) for the libraries; MPL-2.0 section 3.2 for the embedded PSL data (license copy, attribution, corresponding-source pointer) |
| Accompanist `accompanist-drawablepainter:0.32.0`, Guava `33.3.1-android`, FailureAccess `1.0.2`, ListenableFuture placeholder | Apache-2.0 (see findings above) | Same Apache text and runtime notice | Apache-2.0 section 4(a) |
| `net.jthink:jaudiotagger:3.0.1` | **LGPL-2.1-or-later**; Copyright (C) 2015 Paul Taylor | `assets/licenses/LGPL-2.1.txt`, `assets/licenses/jaudiotagger-3.0.1-NOTICE.txt`, Settings license viewer, pinned complete upstream source package, and an opt-in `-PflactifyJaudiotaggerJar` rebuild path | LGPL-2.1 section 6(a)/(d): complete corresponding source plus the FLACtify materials to rebuild and relink, offered at the same download location as the APK |
| Steam Audio SDK `4.8.1` (`libphonon.so` for armeabi-v7a, arm64-v8a, x86, x86_64) | Apache-2.0 | `assets/licenses/steam-audio-4.8.1-LICENSE.md` (SHA-256 `cfc7749b...`, identical to the packaged Apache-2.0 text) | Apache-2.0 section 4(a); native library carries no NOTICE |
| Steam Audio bundled third-party: Intel IPP | Intel Simplified Software License — binary-only, no modification, attribution required | Exact unshortened upstream `assets/licenses/steam-audio-4.8.1-THIRDPARTY.md` | Binary redistribution only; upstream copyright notice and terms reproduced verbatim; no source obligation |
| Steam Audio bundled third-party: FFTS | BSD-3-Clause (Copyright (c) 2012, 2013 Anthony M. Blake) | Same upstream third-party notice | Binary redistribution must reproduce the copyright notice; reproduced verbatim |
| Steam Audio bundled third-party: PFFFT | BSD-3-Clause (Mambro, Ayguen, Pommier / Univ. Corp. for Atmospheric Research) | Same upstream third-party notice | Same |
| Steam Audio bundled third-party: MySOFA | BSD-3-Clause (Copyright (c) 2016-2017 Symonics GmbH, Christian Hoene) | Same upstream third-party notice | Same |
| Steam Audio bundled third-party: Intel Embree | Apache-2.0 | Same upstream third-party notice | Apache-2.0 section 4(a) |
| Steam Audio bundled third-party: AMD RadeonRays | MIT (Copyright (c) 2016 AMD) | Same upstream third-party notice | MIT notice reproduced verbatim |
| Steam Audio bundled third-party: AMD TrueAudio Next | MIT (Copyright (c) 2019 AMD) | Same upstream third-party notice | MIT notice reproduced verbatim |
| Steam Audio bundled third-party: Google Spherical Harmonics | Apache-2.0 | Same upstream third-party notice | Apache-2.0 section 4(a) |
| CIPIC subject 124 HRTF `cipic_124.sofa` | CIPIC terms; Copyright (c) 2001 The Regents of the University of California | `assets/licenses/CIPIC-124-HRTF-NOTICE.md` plus the upstream third-party notice; exact HRTF data asset (SHA-256 `c28ff4a8...`) | Attribution reproduced; **no public-domain claim is made** |

No Steam Audio bundled component is GPL or LGPL. The only copyleft material in the distributed APK is jaudiotagger (LGPL-2.1-or-later) and the Public Suffix List data embedded in OkHttp (MPL-2.0); both are handled above.

### Non-redistributed modules

Of the 114 resolved modules, roughly 35 contribute no runtime code and are not materially redistributed: 3 are BOM/constraint-only (`androidx.compose:compose-bom`, `org.jetbrains.kotlinx:kotlinx-coroutines-bom`, `org.jetbrains.kotlin:kotlin-stdlib-common`), most of the rest are Kotlin Multiplatform **variant aliases** whose actual artifact is the `-android`/`-jvm` sibling (for example `androidx.compose.ui:ui:1.7.6` resolves to `ui-android:1.7.6`), 4 are empty compatibility shims (`collection-ktx:1.4.4`, `lifecycle-common-java8:2.8.7`, `lifecycle-runtime-ktx-android:2.8.7`, `lifecycle-viewmodel-ktx-android:2.8.7`), and 1 is the empty ListenableFuture placeholder. These still appear in the resolved graph because Gradle resolves constraints, but they ship no bytecode.

## Build-only and test-only dependencies (excluded from runtime distribution)

Gradle, the Android Gradle Plugin, the Kotlin Gradle plugin, CMake, the NDK, SDK tooling, and the Steam Audio download/staging logic are build-time tools and are not Android runtime redistribution.

Confirmed test/debug-scoped and **not** Release runtime dependencies: JUnit `4.13.2`, kotlinx-coroutines-test `1.7.3`, MockK `1.13.8` and its agent, AndroidX Arch Core testing `2.2.0`, AndroidX Test JUnit `1.1.5`, Espresso `3.5.1`, the Compose UI test artifacts, and the debug-only Compose tooling/test manifest. None appear in `releaseRuntimeClasspath` in `RELEASE_RUNTIME_DEPENDENCIES.txt`.

## Release evidence gates (separate from license classification)

The license classification above is complete and has **zero unresolved blockers**. Publication is nevertheless gated on these execution steps, none of which is a license question:

1. **Modified-library relink proof.** `.github/workflows/lgpl-relink-validation.yml` must go GREEN: modified jaudiotagger selected, minified `releaseValidation` APK produced, and jaudiotagger incorporation confirmed in DEX. This supersedes the earlier local R8 timeout, which was a machine-memory limit, not a relink failure.
2. **Final signed Release APK inspection.** Reconcile packaged `lib/` and `assets/` against this inventory and confirm no test-only dependency is present. Requires `apksigner verify`, ZIP alignment, and native ELF 16 KiB alignment on the exact signed artifact.
3. **Same-location source bundle.** `scripts/prepare-apk-compliance-bundle.sh` must run from a clean release tag, and a fresh-extraction rebuild/relink must reproduce the result.
4. **Production signing.** Performed outside CI with external signing material; private keys and passwords are never committed or uploaded.

The unsigned debug APK was previously built and inspected and contains the jaudiotagger notice and full LGPL text, Apache-2.0 text and runtime-family notice, the Steam Audio license and exact v4.8.1 `THIRDPARTY.md`, CIPIC attribution, and the SHA-256-pinned subject 124 HRTF. That inspection does not substitute for gates 1 and 2.