# Third-party notices

This file identifies third-party material used by FLACtify. FLACtify's own
source is licensed under Apache License 2.0 (see [`LICENSE`](LICENSE)); that
license does not relicense any component listed here.

## Public source repository and APK distribution are separate

**Public source repository:** jaudiotagger is a Gradle-resolved source/binary
dependency and is not vendored in FLACtify's Git tree. Licensing FLACtify's
independent source under Apache-2.0 does not change jaudiotagger's license.
Steam Audio SDK and CIPIC are downloaded/staged by the build and are not stored
as SDK binaries in the Git tree (the CIPIC notice is included in source).

**Public APK distribution:** APKs include jaudiotagger bytecode after Android
build processing, Steam Audio's `libphonon.so`, the Steam Audio license and
third-party notices as assets, and the `cipic_124.sofa` data asset. Binary
redistribution has obligations distinct from source publication. **Public APK
distribution is BLOCKED pending review and satisfaction of the LGPL binary
distribution requirements below and confirmation of all applicable packaged
third-party terms.** Do not publish an APK/AAB or release until that gate is
closed.

## jaudiotagger 3.0.1 — source dependency; Android bytecode in APK

- Component: `net.jthink:jaudiotagger:3.0.1` (Maven coordinates).
- Upstream attribution: Copyright (C) 2015 Paul Taylor, as stated in the
  upstream `license.txt` for the 3.0.1 tag.
- Applicable license: **LGPL-2.1-or-later** (upstream wording: GNU Lesser
  General Public License, version 2.1, or, at your option, any later version).
- Canonical upstream project/tag:
  <https://bitbucket.org/ijabz/jaudiotagger/src/v3.0.1/>; the upstream tag
  resolves to commit `b885903528c63fa8ecf62ab117f7eebe931b6340`.
- Upstream license text:
  <https://bitbucket.org/ijabz/jaudiotagger/src/v3.0.1/license.txt>
- Maven Central artifact/POM:
  <https://repo1.maven.org/maven2/net/jthink/jaudiotagger/3.0.1/jaudiotagger-3.0.1.pom>
  and <https://repo1.maven.org/maven2/net/jthink/jaudiotagger/3.0.1/jaudiotagger-3.0.1.jar>.
  The Gradle-cached 3.0.1 JAR resolved for this build has SHA-256
  `68aa0fe601b05df683a843acf6f8d4793ab476f47da1ac3dab57e0b1886b0aee`; its
  source JAR has SHA-256
  `d9c79a145944e6bc37579403843c9da84cd81459e42c9877351802ee284f5858`.
  The POM SHA-256 is
  `f2978526656d884f7b91b518c567bcad330eb3541fd9372ebaeb771bed2e5188`; the
  upstream `license.txt` SHA-256 is
  `76ac9d645569ea2be48e5aea7b6336835a3fd9d4ef8284c7045bd8c59f6a7a39`.
  The source JAR, POM, and upstream license notice are retained in
  [`third_party/jaudiotagger-3.0.1/`](third_party/jaudiotagger-3.0.1/). The
  canonical upstream v3.0.1 source snapshot is separately verified at commit
  `b885903528c63fa8ecf62ab117f7eebe931b6340`; its pinned archive SHA-256 is
  `f47c10ce8916db315c6e87ac32f2acd285a6a2beba50c833e8b089e7b63b1335`.
- The full LGPL-2.1 text is included at
  [`third_party/licenses/LGPL-2.1.txt`](third_party/licenses/LGPL-2.1.txt).
- FLACtify does not relicense jaudiotagger. The app build consumes the
  separately resolved Maven dependency; no jaudiotagger JAR is vendored in the
  Git tree.

### Selected LGPL distribution mechanism and release gate

FLACtify selects the LGPL-2.1 section 6(a)/(d) route: make jaudiotagger's
complete corresponding machine-readable source and FLACtify's complete
corresponding source/build materials needed to rebuild with a modified
jaudiotagger available from the **same GitHub Release/download location** as the
APK. The project does not rely on Android replaceable shared-library loading or
a three-year written offer. See
[`docs/APK_DISTRIBUTION_COMPLIANCE.md`](docs/APK_DISTRIBUTION_COMPLIANCE.md)
and [`docs/LGPL-COMPLIANCE.md`](docs/LGPL-COMPLIANCE.md) for the technical
mechanism and recipient instructions.

Normal builds continue to resolve `net.jthink:jaudiotagger:3.0.1`. An
opt-in `flactifyJaudiotaggerJar` Gradle property provides the replacement route.
The complete corresponding source package is based on the full `src/` tree and
build metadata from the verified upstream v3.0.1 tag, plus an explicit `javac`
build script; the Maven source JAR and exact POM are supplemental artifact
provenance, not the sole source. The packager verifies the pinned upstream
snapshot hash and includes the source tree in the same-location release bundle.
The test must modify that source tree, rebuild a different JAR, verify the
selected Gradle artifact, and prove the marker is in the releaseValidation DEX;
a normal Maven build is not evidence of relinkability.

The APK includes a jaudiotagger notice and complete LGPL-2.1 text in assets,
and Settings exposes an Open-source licenses screen. No FLACtify EULA is
provided that prohibits modification or reverse engineering for debugging
jaudiotagger modifications. These statements describe the intended technical
mechanism; they are not a legal interpretation or certification that every
redistribution requirement has been met.

**Public APK distribution remains BLOCKED** until the relink test, final APK
and runtime-license inventory, recipient notices, security checks, signing,
16 KiB validation, and same-location source/compliance Release assets have all
passed and been reviewed. See
[`docs/RUNTIME_DEPENDENCY_LICENSE_INVENTORY.md`](docs/RUNTIME_DEPENDENCY_LICENSE_INVENTORY.md);
that inventory is currently not verified.

## Steam Audio 4.8.1 — SDK source/build dependency and binary in APK

- Component: Steam Audio SDK v4.8.1. FLACtify downloads the pinned upstream SDK
  archive at build time and packages the platform-specific
  `libphonon.so` shared library for `armeabi-v7a`, `arm64-v8a`, `x86`, and
  `x86_64` in APK `lib/` entries.
- Applicable license for Steam Audio: Apache License 2.0.
- Upstream release/archive:
  <https://github.com/ValveSoftware/steam-audio/releases/tag/v4.8.1>
  (<https://github.com/ValveSoftware/steam-audio/releases/download/v4.8.1/steamaudio_4.8.1.zip>).
- Pinned SDK archive SHA-256:
  `4a0aa5ec1176f38f0b0993a37c2259d9e86f27e22d5e24f83ec4c3cb9a1d5449`.
- Apache license source:
  <https://github.com/ValveSoftware/steam-audio/blob/v4.8.1/LICENSE.md>.
  The build stages this full upstream text into APK assets as
  `steam-audio-4.8.1-LICENSE.md` (SHA-256
  `cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30`).
- Steam Audio's full upstream
  [`core/THIRDPARTY.md`](https://github.com/ValveSoftware/steam-audio/blob/v4.8.1/core/THIRDPARTY.md)
  (archive entry `steamaudio/THIRDPARTY.md`) is extracted from the pinned SDK
  archive and staged, unshortened, into APK assets as
  `steam-audio-4.8.1-THIRDPARTY.md` (SHA-256
  `a95e143e7d2466e82a28d6680c7287d9fc7d772136c323ab538a6d24472e70f0`). It
  contains the individual third-party attribution/license terms distributed
  with the SDK, including notices for Intel IPP, FFTS, PFFFT, MySOFA, Intel
  Embree, AMD RadeonRays, AMD TrueAudio Next, CIPIC HRTF data, and Google
  Spherical Harmonics. This exact file, not this summary, must accompany
  redistributed SDK binaries.
- The v4.8.1 upstream trademark terms are at
  <https://github.com/ValveSoftware/steam-audio/blob/v4.8.1/TRADEMARK_RIGHTS.md>.
  They limit Valve Marks to reasonable and customary origin descriptions and
  prohibit any implication of affiliation or endorsement. FLACtify uses Steam
  Audio technology. Steam Audio and Valve marks belong to Valve Corporation.
  No affiliation or endorsement is implied. They are mentioned only to
  identify third-party technology, not as FLACtify branding.

Steam Audio's pinned `THIRDPARTY.md` is material to redistributing the SDK
binary. Every notice applicable to the included `libphonon.so` must remain
available to APK recipients. This project preserves the full upstream notice
as an APK asset; a shortened project summary is not a replacement for it.

## CIPIC subject 124 HRTF — data asset in APK

- The APK data asset is `assets/hrtf/cipic_124.sofa`, taken from Steam Audio
  v4.8.1 at `core/data/hrtf/cipic_124.sofa` and copied by the build without
  modification.
- SHA-256:
  `c28ff4a874ac889ec0c5885ca524762a70d56984232ff7aadcd9c15d32e1cfb6`.
- Upstream path:
  <https://github.com/ValveSoftware/steam-audio/blob/v4.8.1/core/data/hrtf/cipic_124.sofa>.
- Applicable attribution and use terms: the CIPIC HRTF Database section
  distributed in Steam Audio v4.8.1 `THIRDPARTY.md`, including Copyright (c)
  2001 The Regents of the University of California and its notice/conditions.
  FLACtify carries a copy in
  [`app/src/main/assets/licenses/CIPIC-124-HRTF-NOTICE.md`](app/src/main/assets/licenses/CIPIC-124-HRTF-NOTICE.md)
  and the full Steam Audio third-party notice in the APK. No public-domain or
  specific CIPIC Release 1.0 lineage claim is made here.

## Android runtime dependency notice

The runtime graph also includes Kotlin, AndroidX, Compose, Media3, Coil,
OkHttp/Okio, Accompanist, Guava, and related transitive libraries. The debug APK
contains `licenses/Apache-2.0.txt` (SHA-256
`cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30`) and
`licenses/RUNTIME-DEPENDENCIES-NOTICE.txt`. The same terms and component
inventory are exposed by Settings → Open-source licenses. The complete resolved
runtime graph is in
[`docs/RELEASE_RUNTIME_DEPENDENCIES.txt`](docs/RELEASE_RUNTIME_DEPENDENCIES.txt);
recipient-facing classification and the remaining final-APK audit are in
[`docs/RUNTIME_DEPENDENCY_LICENSE_INVENTORY.md`](docs/RUNTIME_DEPENDENCY_LICENSE_INVENTORY.md).

### Public Suffix List (MPL-2.0) — redistributed inside OkHttp

OkHttp 4.12.0 ships the resource `okhttp3/internal/publicsuffix/publicsuffixes.gz`,
compiled from [The Public Suffix List](https://publicsuffix.org/list/public_suffix_list.dat).
OkHttp's own packaged notice records that this data "is subject to the terms of
the Mozilla Public License, v. 2.0", and the Public Suffix List project
distributes the list under MPL-2.0.

Accordingly the APK also contains `licenses/MPL-2.0.txt` (SHA-256
`3f3d9e0024b1921b067d6f7f88deb4a60cbe7a78e76c64e3f1d7fc3b779b9d04`) and
`licenses/PUBLIC-SUFFIX-LIST-NOTICE.txt`, which reproduces the attribution and
points to the corresponding source, satisfying the MPL-2.0 section 3.2
conditions that apply when Covered Software is distributed in executable form.
FLACtify does not modify the Public Suffix List data. MPL-2.0 is a file-level
copyleft license and imposes no obligation on FLACtify's own code or on the
other libraries in the APK.

This is the only non-LGPL copyleft material in the distributed APK other than
jaudiotagger. No other resolved runtime module ships an Apache `NOTICE` file, so
the Apache-2.0 section 4(d) notice condition is not triggered for any of them.

Build-only tools and test/debug-scoped dependencies are classified separately
in that inventory and are not intended to be included in Release runtime. The
final `releaseValidation` and signed APK must still be inspected before claiming
that every runtime notice is present or that no additional copyleft obligation
exists.
