#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repo_root=$(cd -- "$script_dir/.." && pwd)
cd "$repo_root"

scripts/verify-repo-role.sh
git config core.hooksPath .githooks
git config remote.pushDefault origin
git config branch.main.pushRemote origin
scripts/verify-repo-role.sh
