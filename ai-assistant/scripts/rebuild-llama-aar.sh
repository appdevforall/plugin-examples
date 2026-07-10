#!/usr/bin/env bash
#
# Regenerate the prebuilt llama.cpp AAR consumed by ai-core-plugin.
#
# You only need this after bumping the llama.cpp submodule (i.e. when your fork
# is updated). A normal plugin build does NOT use this script — it consumes the
# committed AAR directly.
#
# Requirements: the llama.cpp submodule and an Android NDK/CMake toolchain
# (ANDROID_HOME / sdk.dir configured, NDK installed).

set -euo pipefail

# Run from the ai-assistant/ project root regardless of where it's invoked.
cd "$(dirname "$0")/.."

AAR_DST="ai-core-plugin/libs/v8/llama-v8-release.aar"
AAR_SRC="llama-impl/build/outputs/aar/llama-impl-release.aar"
API_DST="ai-core-plugin/libs/llama-api.jar"
API_SRC="llama-api/build/libs/llama-api.jar"

echo "==> Initializing the llama.cpp submodule (source for the native build)"
git submodule update --init --recursive

echo "==> Building :llama-impl (native lib) and :llama-api (interface jar)"
./gradlew :llama-impl:assembleRelease :llama-api:jar

echo "==> Copying artifacts into ai-core-plugin/libs"
cp "$AAR_SRC" "$AAR_DST"
cp "$API_SRC" "$API_DST"

REV="$(git -C subprojects/llama.cpp rev-parse --short HEAD 2>/dev/null || echo unknown)"
echo "==> Done. Regenerated from llama.cpp @ $REV"
echo "    Review and commit: $AAR_DST, $API_DST"
