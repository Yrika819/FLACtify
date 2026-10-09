#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
EXPECTED_UPSTREAM_SHA256=f47c10ce8916db315c6e87ac32f2acd285a6a2beba50c833e8b089e7b63b1335
UPSTREAM_URL=https://bitbucket.org/ijabz/jaudiotagger/get/v3.0.1.tar.gz
WORK=$(mktemp -d "${TMPDIR:-/tmp}/flactify-jaudiotagger-relink.XXXXXX")
trap 'rm -rf "$WORK"' EXIT

command -v java >/dev/null
command -v javac >/dev/null
command -v jar >/dev/null
if [[ -n "${FLACTIFY_JAUDIOTAGGER_SOURCE_ROOT:-}" ]]; then
  SOURCE_ROOT="$FLACTIFY_JAUDIOTAGGER_SOURCE_ROOT"
else
  command -v curl >/dev/null
  curl --fail --location --silent --show-error "$UPSTREAM_URL" -o "$WORK/upstream.tar.gz"
  printf '%s  %s\n' "$EXPECTED_UPSTREAM_SHA256" "$WORK/upstream.tar.gz" | shasum -a 256 -c -
  mkdir -p "$WORK/upstream"
  tar -xzf "$WORK/upstream.tar.gz" -C "$WORK/upstream"
  SOURCE_ROOT="$WORK/upstream/ijabz-jaudiotagger-b885903528c6/src"
fi
test -f "$SOURCE_ROOT/org/jaudiotagger/audio/AudioFile.java"
"$ROOT/gradlew" -p "$ROOT" :app:dependencyInsight \
  --configuration releaseRuntimeClasspath --dependency net.jthink:jaudiotagger \
  > "$WORK/normal-dependency.txt"
grep -Fq 'net.jthink:jaudiotagger:3.0.1' "$WORK/normal-dependency.txt"
printf 'PASS: normal build resolves net.jthink:jaudiotagger:3.0.1\n'
"$ROOT/scripts/build-jaudiotagger.sh" "$SOURCE_ROOT" "$WORK/jaudiotagger-stock-rebuilt.jar"
jar tf "$WORK/jaudiotagger-stock-rebuilt.jar" > "$WORK/stock-contents.txt"
grep -Fxq 'org/jaudiotagger/audio/AudioFile.class' "$WORK/stock-contents.txt"
grep -Fxq 'org/jaudiotagger/tag/Tag.class' "$WORK/stock-contents.txt"
cp -R "$SOURCE_ROOT" "$WORK/modified-source"
python3 - "$WORK/modified-source/org/jaudiotagger/audio/AudioFile.java" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
source = path.read_text(encoding="utf-8")
needle = "public class AudioFile\n{"
replacement = needle + '\n    public static final String FLACTIFY_RELINK_PROOF = "modified-jaudiotagger";'
if source.count(needle) != 1:
    raise SystemExit("Expected a single AudioFile class declaration")
path.write_text(source.replace(needle, replacement, 1), encoding="utf-8")
PY

"$ROOT/scripts/build-jaudiotagger.sh" "$WORK/modified-source" "$WORK/jaudiotagger-modified.jar"
if cmp -s "$WORK/jaudiotagger-stock-rebuilt.jar" "$WORK/jaudiotagger-modified.jar"; then
  echo "Modified jaudiotagger JAR is unexpectedly identical to the stock source rebuild" >&2
  exit 1
fi
printf 'Stock rebuilt JAR SHA-256: '
shasum -a 256 "$WORK/jaudiotagger-stock-rebuilt.jar"
printf 'Modified JAR SHA-256: '
shasum -a 256 "$WORK/jaudiotagger-modified.jar"

"$ROOT/gradlew" -p "$ROOT" :app:verifyJaudiotaggerOverride :app:assembleReleaseValidation \
  "-PflactifyJaudiotaggerJar=$WORK/jaudiotagger-modified.jar"
APK=$(find "$ROOT/app/build/outputs/apk/releaseValidation" -type f -name '*.apk' -print -quit)
if [[ -z "$APK" ]]; then
  echo "releaseValidation APK was not produced" >&2
  exit 1
fi
printf 'Override-built APK: %s\n' "$APK"
printf 'APK SHA-256: '
shasum -a 256 "$APK"
python3 "$ROOT/scripts/verify-16k-apk.py" "$APK"
if ! "$ANDROID_HOME/cmdline-tools/latest/bin/apkanalyzer" dex code --class org.jaudiotagger.audio.AudioFile "$APK" | grep -Fq 'modified-jaudiotagger'; then
  echo "The modified jaudiotagger marker is not present in the APK DEX" >&2
  exit 1
fi
printf 'PASS: modified jaudiotagger marker is present in APK DEX\n'
