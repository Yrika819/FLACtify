#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
out_bin="$root_dir/.synthetic_ebur128"
trap 'rm -f "$out_bin"' EXIT

if [[ -e /lib/x86_64-linux-gnu/libebur128.so.1 ]]; then
  cc -std=c11 -Wall -Wextra -Werror "$root_dir/synthetic_ebur128.c" \
    -o "$out_bin" -lm -Wl,-l:libebur128.so.1
elif [[ -e /usr/lib/x86_64-linux-gnu/libebur128.so.1 ]]; then
  cc -std=c11 -Wall -Wextra -Werror "$root_dir/synthetic_ebur128.c" \
    -o "$out_bin" -lm -Wl,-l:libebur128.so.1
else
  echo "libebur128 SONAME not installed; synthetic probe not run" >&2
  exit 2
fi

"$out_bin"

