#!/usr/bin/env bash
set -euo pipefail

fail() {
  printf '%s\n' \
    'REPOSITORY SAFETY CHECK FAILED' \
    'Refusing mutation because this checkout is not the canonical public-development repository.' >&2
  exit 1
}

repo_root=$(git rev-parse --show-toplevel 2>/dev/null) || fail
cd "$repo_root"

[[ -f .repo-role ]] || fail
grep -Fxq 'PUBLIC_DEVELOPMENT' .repo-role || fail
grep -Fxq 'canonical=Yrika819/FLACtify' .repo-role || fail
grep -Fxq 'archive_history_must_not_be_imported=true' .repo-role || fail

git remote get-url origin >/dev/null 2>&1 || fail

is_canonical_url() {
  local url=$1
  [[ "$url" != *FLACtify-archive* ]] || return 1
  [[ "$url" =~ ^https://github\.com/Yrika819/FLACtify(\.git)?/?$ ]] || \
    [[ "$url" =~ ^git@github\.com:Yrika819/FLACtify(\.git)?$ ]] || \
    [[ "$url" =~ ^ssh://git@github\.com/Yrika819/FLACtify(\.git)?/?$ ]]
}

origin_fetch_urls=$(git remote get-url --all origin 2>/dev/null) || fail
origin_push_urls=$(git remote get-url --push --all origin 2>/dev/null) || fail
origin_url_count=0
for origin_urls in "$origin_fetch_urls" "$origin_push_urls"; do
  while IFS= read -r url; do
    [[ -n "$url" ]] || continue
    origin_url_count=$((origin_url_count + 1))
    is_canonical_url "$url" || fail
  done <<< "$origin_urls"
done
(( origin_url_count > 0 )) || fail

while IFS= read -r remote; do
  [[ -n "$remote" ]] || continue
  remote_fetch_urls=$(git remote get-url --all "$remote" 2>/dev/null) || fail
  remote_push_urls=$(git remote get-url --push --all "$remote" 2>/dev/null) || fail
  for remote_urls in "$remote_fetch_urls" "$remote_push_urls"; do
    while IFS= read -r url; do
      [[ -n "$url" ]] || continue
      [[ "$url" != *FLACtify-archive* ]] || fail
    done <<< "$remote_urls"
  done
done < <(git remote)

printf '%s\n' 'Repository safety check passed: canonical public-development checkout.'
