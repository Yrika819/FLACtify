# Public source provenance review (Phase 5.2)

This is a bounded, reasonable review of the audited source tree and its
provenance for preparing the source repository. It is not a claim that no
copied code can exist anywhere in the separate private historical archive.

## Code origin

Reviewed the current tracked application Java/Kotlin source, native C/C++ and
CMake files, Gradle build scripts/configuration, Media3 integration, and
Spatial Audio/Steam Audio integration. Searched source and long comments for
copyright/license headers and source-origin signals including `Copyright`,
`Licensed under`, `copied from`, `adapted from`, `based on`, `Stack Overflow`,
GitHub source URLs, and common permissive/copyleft license names. The only
materially relevant source URLs/license declarations found are the deliberate
Steam Audio build inputs and package META-INF exclusions; ordinary APIs,
protocol/file-format references, and library integration are not copied source
attributions.

The provenance review considered the major app, Media3, Steam Audio, and
Spatial Audio integration areas. The integration code is authored for this
project and invokes third-party APIs; no substantial source copied or adapted
from an example, tutorial, Stack Overflow answer, or repository with an
incompatible license was found in this review. This is a reasonable review
conclusion, not a mathematical proof about the separate private history.

Conclusion: **no substantial third-party source requiring a different project
license was found in the reviewed tree/history.** Apache-2.0 can apply to
FLACtify's own source as an independent work. It does not relicense jaudiotagger,
Steam Audio, Steam Audio's bundled third-party components, CIPIC data, or other
external dependencies. See top-level `LICENSE` and `THIRD_PARTY_NOTICES.md`.

## App icons and image resources

The provenance of the former launcher resources could not be established,
so they have been replaced rather than assumed safe. The current icon is a simple original
geometric waveform/frame mark drawn by
[`scripts/generate-app-icons.py`](../scripts/generate-app-icons.py) with Pillow;
no third-party artwork, commercial logo, or Material asset is used. All current
tracked launcher images are generated from that script. Reproduce with:

```sh
python3 scripts/generate-app-icons.py
```

The generator uses Pillow's drawing and resampling operations. The generated
artwork is created specifically for FLACtify and contains no third-party
artwork or recording.
