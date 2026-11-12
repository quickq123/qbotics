#!/usr/bin/env bash
# Build the web panels' JavaScript engine (QuickJS + bridge.c) to engine/js/out/js.wasm, inside
# the toolchain container (../Dockerfile), and copy it into the mod's resources.
# Usage: engine/js/build.sh
set -euo pipefail
cd "$(dirname "$0")"

IMAGE=qbot-engine-toolchain
# quickjs-ng v0.10.1, pinned: bump deliberately and re-run the web panel game tests.
QUICKJS_COMMIT=3c9afc9943323ee9c7dbd123c0cd991448f4b6c2

mkdir -p out
MSYS_NO_PATHCONV=1 docker run --rm \
    -v "$(pwd -W 2>/dev/null || pwd):/js" \
    -v qbot-js-build:/build \
    -e QUICKJS_COMMIT="$QUICKJS_COMMIT" \
    "$IMAGE" bash /js/build-inside.sh

mkdir -p ../../common/src/main/resources/qbotics/web
cp out/js.wasm ../../common/src/main/resources/qbotics/web/js.wasm
ls -la out/js.wasm
