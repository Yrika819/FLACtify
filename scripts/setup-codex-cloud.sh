#!/usr/bin/env bash
# FLACtify Codex Cloud environment setup.
#
# Installs only the toolchains needed by the existing CI and the app build:
# Temurin JDK 11, 17, and 21; Android SDK platform/build-tools/NDK/CMake;
# and the SHA-256-pinned Steam Audio 4.8.1 build inputs.
#
# The script is Linux-only, idempotent, and performs no Gradle build unless
# --warm-gradle is requested. It never handles signing keys or personal data.
set -euo pipefail
umask 022

GRADLE_VERSION="9.4.1"
GRADLE_WRAPPER_SHA256="2ab2958f2a1e51120c326cad6f385153bb11ee93b3c216c5fccebfdfbb7ec6cb"
ANDROID_PLATFORM="android-36"
ANDROID_BUILD_TOOLS="36.0.0"
ANDROID_NDK="28.2.13676358"
ANDROID_CMAKE="3.31.6"
ANDROID_CMDLINE_TOOLS_BUILD="16111833"
ANDROID_CMDLINE_TOOLS_SHA1="e025545c62a8e64c7559119566a569fb1dec5f60"
STEAM_AUDIO_VERSION="4.8.1"
STEAM_AUDIO_ZIP_SHA256="4a0aa5ec1176f38f0b0993a37c2259d9e86f27e22d5e24f83ec4c3cb9a1d5449"
STEAM_AUDIO_LICENSE_SHA256="cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30"
STEAM_AUDIO_SOFA_SHA256="c28ff4a874ac889ec0c5885ca524762a70d56984232ff7aadcd9c15d32e1cfb6"
STEAM_AUDIO_ZIP_URL="https://github.com/ValveSoftware/steam-audio/releases/download/v${STEAM_AUDIO_VERSION}/steamaudio_${STEAM_AUDIO_VERSION}.zip"
STEAM_AUDIO_LICENSE_URL="https://raw.githubusercontent.com/ValveSoftware/steam-audio/v${STEAM_AUDIO_VERSION}/LICENSE.md"
STEAM_AUDIO_SOFA_URL="https://raw.githubusercontent.com/ValveSoftware/steam-audio/v${STEAM_AUDIO_VERSION}/core/data/hrtf/cipic_124.sofa"

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"

check_only=false
offline=false
warm_gradle=false
skip_jdk=false
skip_sdk=false
skip_steam_audio=false
sdk_root_arg=""
toolchain_root_arg=""
steam_archive_arg=""

arch=""
sdk_root=""
toolchain_root=""
cache_root=""
sdkmanager=""
jdk_path_11=""
jdk_path_17=""
jdk_path_21=""

usage() {
  cat <<EOF
Usage: scripts/setup-codex-cloud.sh [options]

Linux setup for the FLACtify Codex Cloud environment.

Options:
  --check                     Verify the environment without installing or downloading.
  --sdk-root PATH             Android SDK root. Defaults to ANDROID_HOME, ANDROID_SDK_ROOT,
                              local.properties sdk.dir, or HOME/Android/Sdk.
  --toolchain-root PATH       JDK install root. Defaults to the system JDK directory
                              when run as root, otherwise HOME/.local/share/flactify/toolchains.
  --steam-audio-archive PATH  Use a local Steam Audio 4.8.1 zip after SHA-256 verification.
  --skip-jdk                  Use the existing JAVA_HOME instead of installing JDKs.
  --skip-sdk                  Do not install or verify Android SDK packages.
  --skip-steam-audio          Do not stage the Steam Audio build inputs.
  --warm-gradle               Run the Gradle wrapper version check and tasks after setup.
  --offline                   Never download; fail if a pinned input is missing.
  --help                      Show this help.

Environment:
  ANDROID_HOME / ANDROID_SDK_ROOT   Android SDK root.
  FLACTIFY_STEAMAUDIO_SDK_ZIP       Local Steam Audio 4.8.1 zip.
  FLACTIFY_TOOLCHAIN_ROOT           JDK install root.
  FLACTIFY_CACHE_ROOT               Cache root (default: XDG_CACHE_HOME/flactify).
EOF
}

info() { printf "%s\n" "$*"; }
fail() { printf "ERROR: %s\n" "$*" >&2; exit 1; }

need_cmd() {
  command -v "$1" >/dev/null 2>&1 || fail "Missing required command: $1"
}

parse_args() {
  while (($#)); do
    case "$1" in
      --check) check_only=true; shift ;;
      --offline) offline=true; shift ;;
      --warm-gradle) warm_gradle=true; shift ;;
      --skip-jdk) skip_jdk=true; shift ;;
      --skip-sdk) skip_sdk=true; shift ;;
      --skip-steam-audio) skip_steam_audio=true; shift ;;
      --sdk-root) [[ $# -ge 2 ]] || fail "--sdk-root requires a path"; sdk_root_arg="$2"; shift 2 ;;
      --toolchain-root) [[ $# -ge 2 ]] || fail "--toolchain-root requires a path"; toolchain_root_arg="$2"; shift 2 ;;
      --steam-audio-archive) [[ $# -ge 2 ]] || fail "--steam-audio-archive requires a path"; steam_archive_arg="$2"; shift 2 ;;
      --help|-h) usage; exit 0 ;;
      *) fail "Unknown argument: $1" ;;
    esac
  done
}

resolve_arch() {
  case "$(uname -m)" in
    x86_64|amd64) arch="x86_64" ;;
    aarch64|arm64) arch="aarch64" ;;
    *) fail "Unsupported Linux architecture: $(uname -m)" ;;
  esac
}

ensure_linux() {
  [[ "$(uname -s)" == "Linux" ]] || fail "This script supports Linux only."
}

ensure_base_commands() {
  local missing=()
  local cmd
  for cmd in curl tar sha256sum sha1sum unzip git; do
    if ! command -v "$cmd" >/dev/null 2>&1; then
      missing+=("$cmd")
    fi
  done
  if ((${#missing[@]} == 0)); then
    return
  fi
  if [[ "$check_only" == true || "$offline" == true ]]; then
    fail "Missing required commands: ${missing[*]}"
  fi
  [[ $EUID -eq 0 ]] || fail "Missing required commands (${missing[*]}); run as root or install them first."
  command -v apt-get >/dev/null 2>&1 || fail "Missing required commands (${missing[*]}); apt-get is unavailable."
  local pkgs=("ca-certificates")
  for cmd in "${missing[@]}"; do
    case "$cmd" in
      curl) pkgs+=("curl") ;;
      tar) pkgs+=("tar") ;;
      sha256sum|sha1sum) pkgs+=("coreutils") ;;
      unzip) pkgs+=("unzip") ;;
      git) fail "git is required; install git before running this script." ;;
    esac
  done
  info "Installing minimal base packages: ${pkgs[*]}"
  apt-get update -qq
  DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends "${pkgs[@]}"
}

verify_wrapper() {
  local props="$REPO_ROOT/gradle/wrapper/gradle-wrapper.properties"
  [[ -f "$props" ]] || fail "Missing Gradle wrapper properties."
  grep -Fq "distributionSha256Sum=$GRADLE_WRAPPER_SHA256" "$props" || fail "Gradle wrapper SHA-256 pin changed or missing."
  grep -Fq "gradle-$GRADLE_VERSION-bin.zip" "$props" || fail "Gradle wrapper version is not $GRADLE_VERSION."
  [[ -x "$REPO_ROOT/gradlew" ]] || fail "gradlew is not executable."
}

sha256_of() {
  local value
  value="$(sha256sum "$1")"
  printf "%s" "${value%% *}"
}

sha1_of() {
  local value
  value="$(sha1sum "$1")"
  printf "%s" "${value%% *}"
}

verify_sha256() {
  [[ -f "$1" ]] || return 1
  [[ "$(sha256_of "$1")" == "$2" ]]
}

verify_sha1() {
  [[ -f "$1" ]] || return 1
  [[ "$(sha1_of "$1")" == "$2" ]]
}

download_sha256() {
  local url="$1"
  local dest="$2"
  local expected="$3"
  local tmp
  if verify_sha256 "$dest" "$expected"; then
    return
  fi
  if [[ "$check_only" == true || "$offline" == true ]]; then
    fail "Missing pinned input: $dest"
  fi
  mkdir -p "$(dirname -- "$dest")"
  tmp="${dest}.part.$$"
  rm -f "$tmp"
  info "Downloading $(basename -- "$dest")"
  if ! curl --fail --location --silent --show-error --retry 3 --retry-delay 2 --connect-timeout 30 --max-time 1200 --output "$tmp" "$url"; then
    rm -f "$tmp"
    fail "Download failed: $url"
  fi
  if ! verify_sha256 "$tmp" "$expected"; then
    rm -f "$tmp"
    fail "SHA-256 mismatch: $dest"
  fi
  mv "$tmp" "$dest"
}

download_sha1() {
  local url="$1"
  local dest="$2"
  local expected="$3"
  local tmp
  if verify_sha1 "$dest" "$expected"; then
    return
  fi
  if [[ "$check_only" == true || "$offline" == true ]]; then
    fail "Missing pinned input: $dest"
  fi
  mkdir -p "$(dirname -- "$dest")"
  tmp="${dest}.part.$$"
  rm -f "$tmp"
  info "Downloading $(basename -- "$dest")"
  if ! curl --fail --location --silent --show-error --retry 3 --retry-delay 2 --connect-timeout 30 --max-time 1200 --output "$tmp" "$url"; then
    rm -f "$tmp"
    fail "Download failed: $url"
  fi
  if ! verify_sha1 "$tmp" "$expected"; then
    rm -f "$tmp"
    fail "SHA-1 mismatch: $dest"
  fi
  mv "$tmp" "$dest"
}

jdk_urls() {
  case "$arch" in
    x86_64)
      jdk11_url="https://github.com/adoptium/temurin11-binaries/releases/download/jdk-11.0.33.1%2B1/OpenJDK11U-jdk_x64_linux_hotspot_11.0.33.1_1.tar.gz"
      jdk11_sha="3119b6dd17543e8e61fd0186f9325414bf2601270bf1edf8a314b9a51c5fb89d"
      jdk17_url="https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.21.1%2B2/OpenJDK17U-jdk_x64_linux_hotspot_17.0.21.1_2.tar.gz"
      jdk17_sha="0b0ac5eab6db6abaec68c9913bcddb31321e5da411c45073d520d9c125e6f7b4"
      jdk21_url="https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13.1%2B2/OpenJDK21U-jdk_x64_linux_hotspot_21.0.13.1_2.tar.gz"
      jdk21_sha="bb8a93f9ae6e7a693b2b721e85dbeff26ae1a77447985a8d7afcdd9c7cf6222e"
      ;;
    aarch64)
      jdk11_url="https://github.com/adoptium/temurin11-binaries/releases/download/jdk-11.0.33.1%2B1/OpenJDK11U-jdk_aarch64_linux_hotspot_11.0.33.1_1.tar.gz"
      jdk11_sha="275b8e9f4c8892d6cbe1a2009e39124d46f8a22944c9b5722c81edd327947692"
      jdk17_url="https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.21.1%2B2/OpenJDK17U-jdk_aarch64_linux_hotspot_17.0.21.1_2.tar.gz"
      jdk17_sha="1d808f4b558e9de63d8ba3ead21ffc8b9de204894eff3c19d52c1480aa17cc58"
      jdk21_url="https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13.1%2B2/OpenJDK21U-jdk_aarch64_linux_hotspot_21.0.13.1_2.tar.gz"
      jdk21_sha="8c72816a6b308842d5ed1f13e5826b69c567da4784f7238d7e800efa71fe54b1"
      ;;
  esac
}

java_major() {
  "$1/bin/java" -version 2>&1 | sed -n "s/.*version \"\([0-9][0-9]*\).*/\1/p"
}

java_full_version() {
  "$1/bin/java" -version 2>&1 | sed -n "s/.*version \"\([^\"]*\)\".*/\1/p"
}

install_jdk() {
  local major="$1"
  local url="$2"
  local sha="$3"
  local expected="$4"
  local dest="$toolchain_root/flactify-temurin-$major"
  local archive
  local tmp
  archive="$cache_root/downloads/$(basename -- "$url")"
  local top
  local old
  if [[ -x "$dest/bin/java" ]] && [[ "$(java_major "$dest")" == "$major" ]] && [[ "$(java_full_version "$dest")" == "$expected"* ]]; then
    info "JDK $major already installed: $dest"
  else
    if [[ "$check_only" == true ]]; then
      fail "JDK $major is not installed at $dest"
    fi
    download_sha256 "$url" "$archive" "$sha"
    mkdir -p "$toolchain_root"
    tmp="$(mktemp -d "$toolchain_root/.jdk-$major.XXXXXX")"
    tar -xzf "$archive" -C "$tmp"
    top="$(find "$tmp" -mindepth 1 -maxdepth 1 -type d -print -quit)"
    [[ -n "$top" && -x "$top/bin/java" ]] || fail "JDK $major archive has an unexpected layout."
    [[ "$(java_major "$top")" == "$major" ]] || fail "JDK $major archive reports a different major version."
    [[ "$(java_full_version "$top")" == "$expected"* ]] || fail "JDK $major archive reports an unexpected patch version."
    if [[ -e "$dest" ]]; then
      old="${dest}.old.$$"
      mv "$dest" "$old"
      mv "$top" "$dest"
      rm -rf "$old"
    else
      mv "$top" "$dest"
    fi
    rm -rf "$tmp"
    chmod -R a+rX "$dest"
    info "Installed JDK $major: $dest"
  fi
  case "$major" in
    11) jdk_path_11="$dest" ;;
    17) jdk_path_17="$dest" ;;
    21) jdk_path_21="$dest" ;;
  esac
}

prepare_jdks() {
  if [[ "$skip_jdk" == true ]]; then
    [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]] || fail "--skip-jdk requires JAVA_HOME with a JDK."
    jdk_path_17="$JAVA_HOME"
    info "Using existing JAVA_HOME: $jdk_path_17"
    return
  fi
  jdk_urls
  install_jdk 11 "$jdk11_url" "$jdk11_sha" "11.0.33"
  install_jdk 17 "$jdk17_url" "$jdk17_sha" "17.0.21"
  install_jdk 21 "$jdk21_url" "$jdk21_sha" "21.0.13"
}

resolve_sdk_root() {
  if [[ -n "$sdk_root_arg" ]]; then
    sdk_root="$sdk_root_arg"
  elif [[ -n "${ANDROID_HOME:-}" ]]; then
    sdk_root="$ANDROID_HOME"
  elif [[ -n "${ANDROID_SDK_ROOT:-}" ]]; then
    sdk_root="$ANDROID_SDK_ROOT"
  elif [[ -f "$REPO_ROOT/local.properties" ]]; then
    sdk_root="$(sed -n "s/^sdk\.dir=//p" "$REPO_ROOT/local.properties" | tail -n 1)"
  fi
  if [[ -z "$sdk_root" ]]; then
    sdk_root="$HOME/Android/Sdk"
  fi
  sdk_root="${sdk_root%/}"
}

prepare_local_properties() {
  local props="$REPO_ROOT/local.properties"
  local existing
  if [[ -f "$props" ]]; then
    existing="$(sed -n "s/^sdk\.dir=//p" "$props" | tail -n 1)"
    if [[ -n "$existing" && "$existing" != "$sdk_root" ]]; then
      fail "local.properties points to $existing, but the selected SDK root is $sdk_root. Remove it or pass a matching --sdk-root."
    fi
    if [[ -n "$existing" ]]; then
      return
    fi
    if [[ "$check_only" == true ]]; then
      info "local.properties has no sdk.dir; Gradle will use ANDROID_HOME."
      return
    fi
    printf "\nsdk.dir=%s\n" "$sdk_root" >> "$props"
    return
  fi
  if [[ "$check_only" == true ]]; then
    info "local.properties is absent; Gradle will use ANDROID_HOME."
    return
  fi
  printf "sdk.dir=%s\n" "$sdk_root" > "$props"
}

bootstrap_cmdline_tools() {
  local zip_name="commandlinetools-linux-${ANDROID_CMDLINE_TOOLS_BUILD}_latest.zip"
  local zip_url="https://dl.google.com/android/repository/$zip_name"
  local cache_file="$cache_root/downloads/$zip_name"
  local tmp
  download_sha1 "$zip_url" "$cache_file" "$ANDROID_CMDLINE_TOOLS_SHA1"
  tmp="$(mktemp -d "$sdk_root/.cmdline-tools.XXXXXX")"
  unzip -q "$cache_file" -d "$tmp"
  [[ -x "$tmp/cmdline-tools/bin/sdkmanager" ]] || fail "Android command-line tools archive has an unexpected layout."
  rm -rf "$sdk_root/cmdline-tools/latest"
  mkdir -p "$sdk_root/cmdline-tools"
  mv "$tmp/cmdline-tools" "$sdk_root/cmdline-tools/latest"
  rm -rf "$tmp"
}

find_sdkmanager() {
  if [[ -x "$sdk_root/cmdline-tools/latest/bin/sdkmanager" ]]; then
    sdkmanager="$sdk_root/cmdline-tools/latest/bin/sdkmanager"
    return
  fi
  if [[ -x "$sdk_root/cmdline-tools/bin/sdkmanager" ]]; then
    sdkmanager="$sdk_root/cmdline-tools/bin/sdkmanager"
    return
  fi
  if [[ "$check_only" == true ]]; then
    fail "sdkmanager is not installed under $sdk_root"
  fi
  bootstrap_cmdline_tools
  sdkmanager="$sdk_root/cmdline-tools/latest/bin/sdkmanager"
}

install_sdk() {
  find_sdkmanager
  info "Accepting Android SDK licenses"
  set +o pipefail
  yes | JAVA_HOME="$jdk_path_17" "$sdkmanager" --sdk_root="$sdk_root" --licenses >/dev/null 2>&1 || true
  set -o pipefail
  info "Installing Android SDK packages"
  JAVA_HOME="$jdk_path_17" "$sdkmanager" --sdk_root="$sdk_root" \
    "platforms;$ANDROID_PLATFORM" \
    "build-tools;$ANDROID_BUILD_TOOLS" \
    "ndk;$ANDROID_NDK" \
    "cmake;$ANDROID_CMAKE"
}

verify_sdk() {
  [[ -d "$sdk_root/platforms/$ANDROID_PLATFORM" ]] || fail "Android platform $ANDROID_PLATFORM is missing."
  [[ -d "$sdk_root/build-tools/$ANDROID_BUILD_TOOLS" ]] || fail "Android build-tools $ANDROID_BUILD_TOOLS are missing."
  [[ -d "$sdk_root/ndk/$ANDROID_NDK" ]] || fail "Android NDK $ANDROID_NDK is missing."
  [[ -x "$sdk_root/cmake/$ANDROID_CMAKE/bin/cmake" ]] || fail "Android CMake $ANDROID_CMAKE is missing."
  [[ -x "$sdk_root/cmake/$ANDROID_CMAKE/bin/ninja" ]] || fail "Ninja from Android CMake $ANDROID_CMAKE is missing."
}

prepare_sdk() {
  mkdir -p "$sdk_root"
  prepare_local_properties
  if [[ "$skip_sdk" == true ]]; then
    info "Skipping Android SDK installation by request."
    return
  fi
  if [[ "$check_only" == true || "$offline" == true ]]; then
    find_sdkmanager
  else
    install_sdk
  fi
  verify_sdk
}

steam_audio_sources() {
  local zip_name="steamaudio_${STEAM_AUDIO_VERSION}.zip"
  local license_name="steam-audio-${STEAM_AUDIO_VERSION}-LICENSE.md"
  local sofa_name="steam-audio-${STEAM_AUDIO_VERSION}-cipic_124.sofa"
  local steam_cache="$cache_root/steam-audio/$STEAM_AUDIO_VERSION"
  local dest="$REPO_ROOT/app/build/downloads"
  local source_zip=""
  local tmp
  if [[ -n "$steam_archive_arg" ]]; then
    source_zip="$steam_archive_arg"
  elif [[ -n "${FLACTIFY_STEAMAUDIO_SDK_ZIP:-}" ]]; then
    source_zip="$FLACTIFY_STEAMAUDIO_SDK_ZIP"
  fi
  mkdir -p "$steam_cache" "$dest"
  if [[ -n "$source_zip" ]]; then
    [[ -f "$source_zip" ]] || fail "Steam Audio archive not found: $source_zip"
    verify_sha256 "$source_zip" "$STEAM_AUDIO_ZIP_SHA256" || fail "Steam Audio archive SHA-256 mismatch: $source_zip"
    if ! verify_sha256 "$steam_cache/$zip_name" "$STEAM_AUDIO_ZIP_SHA256"; then
      tmp="$steam_cache/$zip_name.part.$$"
      cp "$source_zip" "$tmp"
      mv "$tmp" "$steam_cache/$zip_name"
    fi
  fi
  download_sha256 "$STEAM_AUDIO_ZIP_URL" "$steam_cache/$zip_name" "$STEAM_AUDIO_ZIP_SHA256"
  download_sha256 "$STEAM_AUDIO_LICENSE_URL" "$steam_cache/$license_name" "$STEAM_AUDIO_LICENSE_SHA256"
  download_sha256 "$STEAM_AUDIO_SOFA_URL" "$steam_cache/$sofa_name" "$STEAM_AUDIO_SOFA_SHA256"
  stage_steam_file "$steam_cache/$zip_name" "$dest/$zip_name" "$STEAM_AUDIO_ZIP_SHA256"
  stage_steam_file "$steam_cache/$license_name" "$dest/$license_name" "$STEAM_AUDIO_LICENSE_SHA256"
  stage_steam_file "$steam_cache/$sofa_name" "$dest/$sofa_name" "$STEAM_AUDIO_SOFA_SHA256"
}

stage_steam_file() {
  local source="$1"
  local dest="$2"
  local expected="$3"
  local tmp
  verify_sha256 "$source" "$expected" || fail "Cached Steam Audio input failed verification: $source"
  if verify_sha256 "$dest" "$expected"; then
    return
  fi
  if [[ "$check_only" == true ]]; then
    fail "Steam Audio input is not staged: $dest"
  fi
  tmp="${dest}.part.$$"
  cp "$source" "$tmp"
  if ! verify_sha256 "$tmp" "$expected"; then
    rm -f "$tmp"
    fail "Staged Steam Audio input failed verification: $dest"
  fi
  mv "$tmp" "$dest"
}

write_env_file() {
  local env_file="$toolchain_root/flactify-env.sh"
  local java_home="$jdk_path_17"
  local paths
  if [[ "$check_only" == true ]]; then
    return
  fi
  if [[ -z "$java_home" ]]; then
    java_home="${JAVA_HOME:-}"
  fi
  paths="$jdk_path_11,$jdk_path_17,$jdk_path_21"
  paths="${paths#,}"
  paths="${paths%,}"
  {
    printf "# Generated by scripts/setup-codex-cloud.sh. Do not commit.\n"
    printf "export FLACTIFY_TOOLCHAIN_ROOT=%q\n" "$toolchain_root"
    printf "export JAVA_HOME=%q\n" "$java_home"
    printf "export PATH=\"%s/bin:\$PATH\"\n" "$java_home"
    printf "export ANDROID_HOME=%q\n" "$sdk_root"
    printf "export ANDROID_SDK_ROOT=%q\n" "$sdk_root"
    printf "export GRADLE_OPTS=\"\${GRADLE_OPTS:-} -Dorg.gradle.java.installations.paths=%s\"\n" "$paths"
  } > "$env_file"
  chmod a+r "$env_file"
  info "Wrote environment file: $env_file"
}

warm_gradle_cache() {
  if [[ "$warm_gradle" != true ]]; then
    return
  fi
  if [[ "$check_only" == true ]]; then
    fail "--warm-gradle cannot be combined with --check."
  fi
  info "Warming the Gradle wrapper and plugin resolution"
  (
    cd "$REPO_ROOT"
    export JAVA_HOME="$jdk_path_17"
    export ANDROID_HOME="$sdk_root"
    export ANDROID_SDK_ROOT="$sdk_root"
    export GRADLE_OPTS="${GRADLE_OPTS:-} -Dorg.gradle.java.installations.paths=$jdk_path_11,$jdk_path_17,$jdk_path_21"
    ./gradlew --version
    if [[ "$offline" == true ]]; then
      ./gradlew help --no-daemon --offline
    else
      ./gradlew help --no-daemon
    fi
  )
}

print_summary() {
  info ""
  if [[ "$check_only" == true ]]; then
    info "FLACtify Codex Cloud environment verification complete."
  else
    info "FLACtify Codex Cloud setup complete."
  fi
  info "  Android SDK: $sdk_root"
  info "  JDK 11:      ${jdk_path_11:-not installed}"
  info "  JDK 17:      ${jdk_path_17:-not installed}"
  info "  JDK 21:      ${jdk_path_21:-not installed}"
  info "  Gradle:      $GRADLE_VERSION (wrapper SHA-256 pinned)"
  if [[ "$skip_steam_audio" != true ]]; then
    info "  Steam Audio: $STEAM_AUDIO_VERSION staged under app/build/downloads"
  fi
  if [[ "$check_only" != true ]]; then
    info ""
    info "For an interactive shell, run:"
    info "  source \"$toolchain_root/flactify-env.sh\""
  fi
}

main() {
  parse_args "$@"
  ensure_linux
  ensure_base_commands
  cd "$REPO_ROOT"
  [[ -f .repo-role ]] || fail "This is not the FLACtify public-development checkout."
  "$REPO_ROOT/scripts/verify-repo-role.sh" >/dev/null || fail "Repository role verification failed."
  verify_wrapper
  resolve_arch
  cache_root="${FLACTIFY_CACHE_ROOT:-${XDG_CACHE_HOME:-$HOME/.cache}/flactify}"
  cache_root="${cache_root%/}"
  if [[ -n "$toolchain_root_arg" ]]; then
    toolchain_root="$toolchain_root_arg"
  elif [[ -n "${FLACTIFY_TOOLCHAIN_ROOT:-}" ]]; then
    toolchain_root="$FLACTIFY_TOOLCHAIN_ROOT"
  elif [[ $EUID -eq 0 && -d /usr/lib/jvm ]]; then
    toolchain_root="/usr/lib/jvm"
  else
    toolchain_root="$HOME/.local/share/flactify/toolchains"
  fi
  toolchain_root="${toolchain_root%/}"
  [[ -n "$toolchain_root" && "$toolchain_root" != "/" ]] || fail "Invalid toolchain root."
  resolve_sdk_root
  [[ -n "$sdk_root" ]] || fail "Android SDK root is empty."
  prepare_jdks
  prepare_sdk
  if [[ "$skip_steam_audio" != true ]]; then
    steam_audio_sources
  fi
  write_env_file
  warm_gradle_cache
  print_summary
}

main "$@"
