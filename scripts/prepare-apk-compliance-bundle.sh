#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
TAG=${1:-}
OUT=${2:-}
VERSION=2.5.1
EXPECTED_SOURCES_SHA256=d9c79a145944e6bc37579403843c9da84cd81459e42c9877351802ee284f5858
EXPECTED_POM_SHA256=f2978526656d884f7b91b518c567bcad330eb3541fd9372ebaeb771bed2e5188
EXPECTED_UPSTREAM_LICENSE_SHA256=76ac9d645569ea2be48e5aea7b6336835a3fd9d4ef8284c7045bd8c59f6a7a39
EXPECTED_UPSTREAM_SNAPSHOT_SHA256=f47c10ce8916db315c6e87ac32f2acd285a6a2beba50c833e8b089e7b63b1335
UPSTREAM_SNAPSHOT_URL=https://bitbucket.org/ijabz/jaudiotagger/get/v3.0.1.tar.gz

if [[ "$TAG" != "v$VERSION" || -z "$OUT" ]]; then
  echo "Usage: $0 v2.5.1 <output-directory>" >&2
  exit 2
fi
if [[ -n $(git -C "$ROOT" status --porcelain) ]]; then
  echo "Refusing to package from a dirty worktree" >&2
  exit 1
fi
if ! git -C "$ROOT" rev-parse --verify "refs/tags/$TAG^{commit}" >/dev/null 2>&1; then
  echo "Release tag $TAG does not exist" >&2
  exit 1
fi
if ! git -C "$ROOT" show "$TAG:app/build.gradle.kts" | grep -Fq 'versionName = "2.5.1"'; then
  echo "Tag $TAG does not declare versionName 2.5.1" >&2
  exit 1
fi
for required in gradlew gradle/wrapper/gradle-wrapper.jar gradle/wrapper/gradle-wrapper.properties settings.gradle.kts build.gradle.kts app/build.gradle.kts scripts/build-jaudiotagger.sh; do
  if ! git -C "$ROOT" cat-file -e "$TAG:$required" 2>/dev/null; then
    echo "Release tag is missing required rebuild material: $required" >&2
    exit 1
  fi
done

SOURCE_JAR="$ROOT/third_party/jaudiotagger-3.0.1/jaudiotagger-3.0.1-sources.jar"
POM="$ROOT/third_party/jaudiotagger-3.0.1/jaudiotagger-3.0.1.pom"
UPSTREAM_LICENSE="$ROOT/third_party/jaudiotagger-3.0.1/license.txt"
printf '%s  %s\n' "$EXPECTED_SOURCES_SHA256" "$SOURCE_JAR" | shasum -a 256 -c -
printf '%s  %s\n' "$EXPECTED_POM_SHA256" "$POM" | shasum -a 256 -c -
printf '%s  %s\n' "$EXPECTED_UPSTREAM_LICENSE_SHA256" "$UPSTREAM_LICENSE" | shasum -a 256 -c -

mkdir -p "$OUT"
OUT=$(cd "$OUT" && pwd)
FLAC_SOURCE="FLACtify-v2.5.1-source.tar.gz"
JAUDIOTAGGER_SOURCE="jaudiotagger-3.0.1-complete-source.tar.gz"

git -C "$ROOT" archive --format=tar.gz --prefix=FLACtify-v2.5.1-source/ "$TAG" > "$OUT/$FLAC_SOURCE"

STAGE=$(mktemp -d "${TMPDIR:-/tmp}/flactify-lgpl-bundle.XXXXXX")
trap 'rm -rf "$STAGE"' EXIT
mkdir -p "$STAGE/jaudiotagger-3.0.1/upstream" "$STAGE/jaudiotagger-3.0.1/maven-central"
curl --fail --location --silent --show-error "$UPSTREAM_SNAPSHOT_URL" -o "$STAGE/upstream-v3.0.1.tar.gz"
printf '%s  %s\n' "$EXPECTED_UPSTREAM_SNAPSHOT_SHA256" "$STAGE/upstream-v3.0.1.tar.gz" | shasum -a 256 -c -
mkdir -p "$STAGE/upstream-extract"
tar -xzf "$STAGE/upstream-v3.0.1.tar.gz" -C "$STAGE/upstream-extract"
UPSTREAM_ROOT="$STAGE/upstream-extract/ijabz-jaudiotagger-b885903528c6"
test -f "$UPSTREAM_ROOT/src/org/jaudiotagger/audio/AudioFile.java"
test -f "$UPSTREAM_ROOT/pom.xml"
test -f "$UPSTREAM_ROOT/license.txt"
cp -R "$UPSTREAM_ROOT/src" "$STAGE/jaudiotagger-3.0.1/upstream/"
cp "$UPSTREAM_ROOT/pom.xml" "$STAGE/jaudiotagger-3.0.1/upstream/"
cp "$UPSTREAM_ROOT/README.md" "$STAGE/jaudiotagger-3.0.1/upstream/"
cp "$UPSTREAM_ROOT/CHANGES.txt" "$STAGE/jaudiotagger-3.0.1/upstream/"
cp "$ROOT/third_party/jaudiotagger-3.0.1/jaudiotagger-3.0.1-sources.jar" "$STAGE/jaudiotagger-3.0.1/maven-central/"
cp "$ROOT/third_party/jaudiotagger-3.0.1/jaudiotagger-3.0.1.pom" "$STAGE/jaudiotagger-3.0.1/maven-central/"
cp "$ROOT/third_party/jaudiotagger-3.0.1/license.txt" "$STAGE/jaudiotagger-3.0.1/"
cp "$ROOT/third_party/licenses/LGPL-2.1.txt" "$STAGE/jaudiotagger-3.0.1/"
cp "$ROOT/scripts/build-jaudiotagger.sh" "$STAGE/jaudiotagger-3.0.1/"
cat > "$STAGE/jaudiotagger-3.0.1/BUILDING.md" <<'EOF'
# jaudiotagger 3.0.1 complete corresponding source and rebuild materials

Coordinates: `net.jthink:jaudiotagger:3.0.1`. `upstream/src/` is the full library source tree from canonical upstream tag `v3.0.1`, commit `b885903528c63fa8ecf62ab117f7eebe931b6340`; its archive SHA-256 and the Maven Central source/JAR/POM hashes are in `SHA256SUMS`. The original upstream POM, README, changelog, upstream `license.txt`, and complete LGPL-2.1 text are included. The Maven Central source JAR is supplemental provenance, not the only source material.

Build the complete upstream source tree directly:

```sh
./build-jaudiotagger.sh ./upstream/src ./jaudiotagger.jar
```

The script compiles all production Java source with `javac --release 8`; inspection and compilation found no external production compile dependency or generated-source step. The source tree includes package interface documentation/resources. Use the resulting JAR with FLACtify's `-PflactifyJaudiotaggerJar=/path/to/jaudiotagger.jar` property.
EOF
cp "$ROOT/scripts/build-jaudiotagger.sh" "$STAGE/jaudiotagger-3.0.1/build-jaudiotagger.sh"
printf '%s  %s\n' "$EXPECTED_UPSTREAM_SNAPSHOT_SHA256" 'Bitbucket upstream v3.0.1 snapshot (not included as a separate duplicate)' > "$STAGE/jaudiotagger-3.0.1/UPSTREAM-SNAPSHOT-SHA256.txt"
(
  cd "$STAGE/jaudiotagger-3.0.1"
  shasum -a 256 maven-central/jaudiotagger-3.0.1-sources.jar maven-central/jaudiotagger-3.0.1.pom upstream/pom.xml upstream/README.md upstream/CHANGES.txt license.txt LGPL-2.1.txt UPSTREAM-SNAPSHOT-SHA256.txt > SHA256SUMS
  python3 - "$PWD" "$OUT/$JAUDIOTAGGER_SOURCE" <<'PY'
import gzip
import os
import sys
import tarfile
from pathlib import Path

root = Path(sys.argv[1])
out = Path(sys.argv[2])
with out.open("wb") as raw:
    with gzip.GzipFile(fileobj=raw, mode="wb", mtime=0) as compressed:
        with tarfile.open(fileobj=compressed, mode="w", format=tarfile.PAX_FORMAT) as archive:
            for path in sorted(root.rglob("*")):
                info = archive.gettarinfo(str(path), arcname="./" + path.relative_to(root).as_posix())
                info.uid = info.gid = 0
                info.uname = info.gname = ""
                info.mtime = 0
                if path.is_file():
                    with path.open("rb") as source:
                        archive.addfile(info, source)
                else:
                    archive.addfile(info)
PY
)

tar -tzf "$OUT/$FLAC_SOURCE" > "$STAGE/flactify-source-files.txt"
grep -Fq 'FLACtify-v2.5.1-source/gradlew' "$STAGE/flactify-source-files.txt"
grep -Fq 'FLACtify-v2.5.1-source/app/build.gradle.kts' "$STAGE/flactify-source-files.txt"
grep -Fq 'FLACtify-v2.5.1-source/third_party/jaudiotagger-3.0.1/jaudiotagger-3.0.1-sources.jar' "$STAGE/flactify-source-files.txt"
tar -tzf "$OUT/$JAUDIOTAGGER_SOURCE" > "$STAGE/jaudiotagger-source-files.txt"
grep -Fq './upstream/src/org/jaudiotagger/audio/AudioFile.java' "$STAGE/jaudiotagger-source-files.txt"
grep -Fq './upstream/pom.xml' "$STAGE/jaudiotagger-source-files.txt"
grep -Fq './build-jaudiotagger.sh' "$STAGE/jaudiotagger-source-files.txt"
grep -Fq './maven-central/jaudiotagger-3.0.1-sources.jar' "$STAGE/jaudiotagger-source-files.txt"

cp "$ROOT/docs/LGPL-COMPLIANCE.md" "$OUT/LGPL-COMPLIANCE.md"
cp "$ROOT/THIRD_PARTY_NOTICES.md" "$OUT/THIRD_PARTY_NOTICES.md"
cp "$ROOT/third_party/licenses/LGPL-2.1.txt" "$OUT/LGPL-2.1.txt"
(
  cd "$OUT"
  shasum -a 256 "$FLAC_SOURCE" "$JAUDIOTAGGER_SOURCE" LGPL-COMPLIANCE.md THIRD_PARTY_NOTICES.md LGPL-2.1.txt > SHA256SUMS
)
printf 'Compliance bundle prepared in %s\n' "$OUT"
