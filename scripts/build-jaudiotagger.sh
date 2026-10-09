#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "Usage: $0 <extracted-jaudiotagger-source-dir> <output.jar>" >&2
  exit 2
fi

source_root=$(cd "$1" && pwd)
output_jar=$(mkdir -p "$(dirname "$2")" && cd "$(dirname "$2")" && pwd)/$(basename "$2")
work_dir=$(mktemp -d)
trap 'rm -rf "$work_dir"' EXIT

mkdir -p "$work_dir/classes"
find "$source_root/org/jaudiotagger" -type f -name '*.java' -print | sort > "$work_dir/sources.list"
if [[ ! -s "$work_dir/sources.list" ]]; then
  echo "No jaudiotagger production Java source found under $source_root" >&2
  exit 1
fi

javac --release 8 -encoding UTF-8 -d "$work_dir/classes" "@$work_dir/sources.list"
jar --create --file "$output_jar" -C "$work_dir/classes" .
echo "Built jaudiotagger JAR: $output_jar"
