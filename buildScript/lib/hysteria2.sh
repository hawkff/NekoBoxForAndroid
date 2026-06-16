#!/bin/bash
# Download the official apernet/hysteria Android client binaries and install them as
# bundled native executables (app/executableSo/<abi>/libhysteria2.so).
#
# Used for Hysteria2 profiles with Gecko obfs, which the native (starifly) sing-box core
# does not support. PluginManager.initNativeInternal resolves
# "hysteria2-plugin" -> libhysteria2.so from nativeLibraryDir.
#
# Usage: ./run lib hysteria2
set -e
set -o pipefail

# Pinned Hysteria release (app/v2.9.2 introduced Gecko obfs).
HYSTERIA_VERSION="${HYSTERIA_VERSION:-v2.9.2}"
BASE="https://github.com/apernet/hysteria/releases/download/app/${HYSTERIA_VERSION}"

OUT="$(pwd)/app/executableSo"

dl() {
  local abi="$1" asset="$2"
  echo ">> downloading libhysteria2.so for $abi ($asset)"
  mkdir -p "$OUT/$abi"
  curl -fL "$BASE/$asset" -o "$OUT/$abi/libhysteria2.so"
  chmod +x "$OUT/$abi/libhysteria2.so"
}

dl "arm64-v8a"   "hysteria-android-arm64"
dl "armeabi-v7a" "hysteria-android-armv7"
dl "x86"         "hysteria-android-386"
dl "x86_64"      "hysteria-android-amd64"

echo ">> installed Hysteria2 binaries:"
ls -la "$OUT"/*/libhysteria2.so
