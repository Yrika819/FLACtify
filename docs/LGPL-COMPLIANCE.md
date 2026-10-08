# jaudiotagger LGPL distribution materials

The current FLACtify debug APK contains `net.jthink:jaudiotagger:3.0.1`, licensed under LGPL-2.1-or-later, with Copyright (C) 2015 Paul Taylor attribution. The normal build uses the unmodified Maven Central artifact. Do not infer public APK release readiness from this debug APK or this document.

FLACtify's selected technical distribution mechanism is LGPL-2.1 section 6(a)/(d): make the complete corresponding jaudiotagger source and FLACtify's corresponding source/build materials required to rebuild and relink with a modified jaudiotagger available at the same GitHub Release/download location as the APK. The project does not rely on Android replaceable shared-library loading or a three-year written offer.

The complete jaudiotagger source package is based on the verified upstream `v3.0.1` source snapshot at commit `b885903528c63fa8ecf62ab117f7eebe931b6340`; its pinned archive SHA-256 is recorded in `docs/APK_DISTRIBUTION_COMPLIANCE.md`. It includes the upstream library source tree, POM/build metadata and license, Maven artifacts as supplemental provenance, the full LGPL text, and `build-jaudiotagger.sh`. Build from the extracted source package with:

```sh
./build-jaudiotagger.sh ./upstream/src ./jaudiotagger.jar
```

For an isolated relink test using only the distributed source, point the environment variable at the extracted source tree and run the FLACtify test script:

```sh
FLACTIFY_JAUDIOTAGGER_SOURCE_ROOT=/path/to/extracted/upstream/src \
  scripts/test-jaudiotagger-relink.sh
```

The test creates its temporary modification and modified JAR outside the repository, checks the normal dependency resolution, verifies the external Gradle JAR selection, builds the unsigned `releaseValidation` APK, and checks for a modification marker in DEX. **That integrated proof is now GREEN on GitHub Actions** (`.github/workflows/lgpl-relink-validation.yml`, pull request #5): the modified JAR is the selected artifact, the minified 36,507,034-byte `releaseValidation` APK is produced with R8 and resource shrinking enabled, all 606 `org.jaudiotagger` classes are present in DEX, and the modification marker is found. The earlier local stall during R8 was a limit of an 8 GiB developer machine, not a relink failure. Recorded values are in [`APK_DISTRIBUTION_COMPLIANCE.md`](APK_DISTRIBUTION_COMPLIANCE.md).

The Settings → Open-source licenses surface identifies jaudiotagger and exposes the complete LGPL-2.1 text. No FLACtify EULA is provided that prohibits modification or reverse engineering for debugging modifications to jaudiotagger. FLACtify's Apache-2.0 license applies only to FLACtify's own code and does not relicense jaudiotagger.

This file records the project's technical compliance mechanism; it is not a legal opinion, legal certification, or a representation that a particular distribution has completed every release validation gate. Public APK distribution remains **BLOCKED** until the final signed-APK inspection, the frozen-tag source/compliance bundle, and the draft Release asset audit have all passed.
