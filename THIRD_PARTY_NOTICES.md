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
- The full LGPL-2.1 text is included at
  [`third_party/licenses/LGPL-2.1.txt`](third_party/licenses/LGPL-2.1.txt).
- FLACtify does not relicense jaudiotagger. The app build consumes the
  separately resolved Maven dependency; no jaudiotagger JAR is vendored in the
  Git tree.

### LGPL gate before any public APK distribution

The exact legal treatment of the Android APK must be reviewed for this concrete
packaging. The app resolves jaudiotagger as a JAR and Android build tooling
packages its bytecode into the APK's DEX output; it is not merely a source-tree
reference. LGPL-2.1 section 6 for a work that uses a library requires, among
other things, prominent notice that the library is used, a copy of the license,
terms permitting modification for the recipient's own use and reverse
engineering for debugging those modifications, and one of the section 6
compliance alternatives. Those alternatives include supplying the library's
complete corresponding machine-readable source and (for an executable) the
relinkable object code/source for the work that uses it; a suitable shared
library mechanism that allows a user-installed modified library; or a valid
written offer (at least three years) to supply the required materials. Section
6 also addresses equivalent source access and verification when distribution
is offered from a download location. The complete applicable requirements must
be assessed against the final APK, build/relinkability, user terms, notices and
distribution method by the distributor; this notice is not a legal
interpretation that those requirements have been met.

No APK compliance package, relinkable application materials, suitable
replaceable shared-library arrangement, written offer, or final recipient
notice has been approved in this phase. Therefore: **Public source repository
may proceed; public APK distribution remains BLOCKED pending LGPL APK
distribution compliance.**

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

## Build-time tooling and other dependencies

Other Gradle dependencies are resolved as build dependencies; this document
focuses on the components identified for actual material redistribution or
material incorporation above. Their respective upstream license metadata and
notices govern those components and are not changed by FLACtify's project
license. Review the complete dependency inventory before any APK release.
